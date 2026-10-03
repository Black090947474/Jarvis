package de.jarvis.app.tools

import android.content.Context
import de.jarvis.app.Scheduler
import de.jarvis.app.logic.TimeLogic
import org.json.JSONObject

/** Aufgaben: Titel, Beschreibung, Priorität, Datum/Uhrzeit, Wiederholung, Status, Kategorie. */
object TaskTool : JarvisTool {
    override val name = "tasks"
    override val group = "agenda"
    override val feature = "tasks"
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"tasks","description":"Aufgabenliste von Jarvis. add: neue Aufgabe. list: offene (filter: offen, erledigt, alle, heute, überfällig, oder Kategorie). complete: als erledigt markieren. update: ändern. delete: löschen (erst ohne confirmed, nach Ja mit confirmed=true). remind=true legt zur Fälligkeit eine Erinnerung an.",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["add","list","complete","update","delete"]},
  "id":{"type":"integer"},
  "title":{"type":"string"},"description":{"type":"string"},
  "priority":{"type":"string","enum":["hoch","mittel","niedrig"]},
  "due":{"type":"string","description":"YYYY-MM-DD oder YYYY-MM-DDTHH:MM"},
  "repeat":{"type":"string","enum":["none","daily","weekdays","weekly","monthly"]},
  "days":{"type":"array","items":{"type":"string"}},
  "category":{"type":"string","description":"z. B. Schule, Haushalt, Sport"},
  "filter":{"type":"string"},
  "remind":{"type":"boolean"},
  "confirmed":{"type":"boolean"}},"required":["action"]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String = when (a.optString("action")) {
        "add" -> add(t, a)
        "list" -> list(t.ctx, a.optString("filter", "offen"))
        "complete" -> complete(t.ctx, a.optInt("id", -1))?.let { Res.ok(it) } ?: Res.error("Aufgabe nicht gefunden. Erst list nutzen.")
        "update" -> update(t, a)
        "delete" -> delete(t, a)
        else -> Res.error("Unbekannte Aktion.")
    }

    private fun parseDue(s: String): Pair<Long, Boolean>? {
        if (s.isBlank()) return null
        val ms = TimeLogic.parseLocal(s) ?: return null
        return ms to s.contains("T")
    }

    private fun add(t: ToolContext, a: JSONObject): String {
        val title = a.optString("title").trim().ifBlank { return Res.error("Wie heißt die Aufgabe?") }
        val due = if (a.has("due")) parseDue(a.optString("due")) ?: return Res.error("Datum ungültig (YYYY-MM-DD oder YYYY-MM-DDTHH:MM).") else null
        val store = AgendaStore(t.ctx)
        var task = TaskItem(store.newId(), title, a.optString("description"), prio(a.optString("priority", "mittel")),
            due?.first, due?.second ?: false, TimeLogic.parseRepeat(a.optString("repeat", "none")), days(a), false, a.optString("category"))
        var extra = ""
        if (a.optBoolean("remind") && task.due != null) {
            val at = if (task.hasTime) task.due!! else task.due!! + 9 * 3_600_000L // ohne Uhrzeit: 9 Uhr
            val r = ReminderTool.add(t, JSONObject().put("text", "Aufgabe: $title").put("at", TimeLogic.iso(at)), task.id)
            if (r.startsWith("OK")) {
                task = task.copy(reminderId = AgendaStore(t.ctx).reminders().lastOrNull { it.taskId == task.id }?.id)
                extra = " Erinnerung gestellt."
            } else extra = " (Erinnerung ging nicht: ${Res.spoken(r)})"
        }
        store.putTask(task)
        return Res.ok("Aufgabe angelegt: ${task.describe()}.$extra")
    }

    private fun prio(s: String) = when (s.lowercase()) { "hoch", "high", "wichtig" -> "hoch"; "niedrig", "low" -> "niedrig"; else -> "mittel" }

    private fun days(a: JSONObject) = a.optJSONArray("days")?.let { arr ->
        (0 until arr.length()).mapNotNull { TimeLogic.parseDay(arr.optString(it)) }.toSet() } ?: emptySet()

    fun list(ctx: Context, filter: String): String {
        val all = AgendaStore(ctx).tasks()
        val now = System.currentTimeMillis()
        val (from, to) = TimeLogic.range("heute")
        val f = filter.lowercase().trim()
        val sel = when (f) {
            "", "offen" -> all.filter { !it.done }
            "erledigt" -> all.filter { it.done }
            "alle" -> all
            "heute" -> all.filter { !it.done && it.due != null && it.due < to }
            "überfällig", "ueberfaellig" -> all.filter { !it.done && it.due != null && it.due < (if (it.hasTime) now else from) }
            else -> all.filter { !it.done && it.category.equals(filter, true) }
        }.sortedWith(compareBy<TaskItem>({ it.done }, { prioRank(it.priority) }, { it.due ?: Long.MAX_VALUE }))
        return if (sel.isEmpty()) Res.ok("Keine Aufgaben ($filter).") else Res.ok("${sel.size} Aufgabe(n) ($filter):\n" + sel.take(30).joinToString("\n") { it.describe() })
    }

    private fun prioRank(p: String) = when (p) { "hoch" -> 0; "mittel" -> 1; else -> 2 }

    /** Erledigt markieren. Wiederkehrende Aufgaben springen auf den nächsten Termin. Gibt den Text zurück oder null. */
    fun complete(ctx: Context, id: Int): String? {
        val store = AgendaStore(ctx)
        val task = store.task(id) ?: return null
        if (task.repeat != TimeLogic.Repeat.NONE && task.due != null) {
            val nx = TimeLogic.nextOccurrence(task.due, task.repeat, task.days, maxOf(task.due, System.currentTimeMillis()))
            if (nx != null) {
                store.putTask(task.copy(due = nx, done = false))
                task.reminderId?.let { rid -> store.reminder(rid)?.let { r ->
                    val off = (r.next ?: r.anchor ?: task.due) - task.due
                    val upd = r.copy(anchor = nx + off, next = nx + off, active = true)
                    store.putReminder(upd); Scheduler.schedule(ctx, upd)
                } }
                return "„${task.title}“ erledigt. Nächstes Mal: ${if (task.hasTime) TimeLogic.short(nx) else TimeLogic.day(nx)}."
            }
        }
        store.putTask(task.copy(done = true))
        task.reminderId?.let { rid -> store.reminder(rid)?.let { Scheduler.cancel(ctx, it); store.putReminder(it.copy(active = false)) } }
        return "„${task.title}“ ist erledigt."
    }

    private fun update(t: ToolContext, a: JSONObject): String {
        val store = AgendaStore(t.ctx)
        var task = store.task(a.optInt("id", -1)) ?: return Res.error("Aufgabe nicht gefunden. Erst list nutzen.")
        if (a.has("title")) task = task.copy(title = a.optString("title"))
        if (a.has("description")) task = task.copy(description = a.optString("description"))
        if (a.has("priority")) task = task.copy(priority = prio(a.optString("priority")))
        if (a.has("category")) task = task.copy(category = a.optString("category"))
        if (a.has("repeat")) task = task.copy(repeat = TimeLogic.parseRepeat(a.optString("repeat")), days = days(a))
        if (a.has("due")) {
            val d = a.optString("due")
            task = if (d.isBlank()) task.copy(due = null, hasTime = false)
            else parseDue(d)?.let { task.copy(due = it.first, hasTime = it.second) } ?: return Res.error("Datum ungültig.")
        }
        store.putTask(task)
        return Res.ok("Aufgabe geändert: ${task.describe()}")
    }

    private fun delete(t: ToolContext, a: JSONObject): String {
        val store = AgendaStore(t.ctx)
        val task = store.task(a.optInt("id", -1)) ?: return Res.error("Aufgabe nicht gefunden.")
        if (!a.optBoolean("confirmed")) return Res.confirm("Aufgabe „${task.title}“ endgültig löschen.")
        task.reminderId?.let { rid -> store.reminder(rid)?.let { Scheduler.cancel(t.ctx, it); store.removeReminder(rid) } }
        store.removeTask(task.id)
        return Res.ok("Aufgabe „${task.title}“ gelöscht.")
    }
}
