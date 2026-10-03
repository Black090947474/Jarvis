package de.jarvis.app.tools

import android.content.Context
import de.jarvis.app.logic.TimeLogic
import org.json.JSONObject

/** Überblick: Termine + Erinnerungen + Aufgaben für Tag, Woche oder Monat. */
object AgendaTool : JarvisTool {
    override val name = "agenda"
    override val group = "core"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"agenda","description":"Zusammenfassung von Terminen, Erinnerungen und Aufgaben. Nutze das für 'Was steht heute/morgen/diese Woche an?'.",
 "input_schema":{"type":"object","properties":{
  "range":{"type":"string","description":"heute, morgen, übermorgen, woche, monat oder Datum YYYY-MM-DD"}},"required":[]}}
""".trimIndent())

    class Agenda(
        val events: List<CalendarTool.Event>,
        val reminders: List<Reminder>,
        val tasks: List<TaskItem>,
        val overdue: List<TaskItem>,
        val calendarAllowed: Boolean,
    ) {
        fun isEmpty() = events.isEmpty() && reminders.isEmpty() && tasks.isEmpty() && overdue.isEmpty()
    }

    fun collect(ctx: Context, from: Long, to: Long): Agenda {
        val p = de.jarvis.app.Prefs(ctx)
        val calOk = p.feature("calendar") &&
            ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val ev = if (calOk) CalendarTool.events(ctx, from, to) else emptyList()
        val store = AgendaStore(ctx)
        val rem = store.reminders().filter { it.active && it.next != null && it.next in from until to }.sortedBy { it.next }
        val all = store.tasks().filter { !it.done }
        val tasks = all.filter { it.due != null && it.due in from until to }.sortedBy { it.due }
        val now = System.currentTimeMillis()
        val overdue = all.filter { it.due != null && it.due < minOf(from, now) && !(it.due in from until to) }
        return Agenda(ev, rem, tasks, overdue, calOk)
    }

    /** Erste Zeile ist ein kurzer, sprechbarer Satz. */
    fun summary(ag: Agenda, rangeName: String, detailed: Boolean): String {
        val label = when (rangeName.lowercase()) {
            "heute" -> "Heute"; "morgen" -> "Morgen"; "übermorgen" -> "Übermorgen"
            "woche" -> "Diese Woche"; "monat" -> "In den nächsten 30 Tagen"; else -> "Am $rangeName"
        }
        if (ag.isEmpty()) return "$label steht nichts an." + (if (!ag.calendarAllowed) " (Kalender ist nicht freigegeben.)" else "")
        val parts = mutableListOf<String>()
        if (ag.events.isNotEmpty()) parts += "${ag.events.size} Termin" + (if (ag.events.size > 1) "e" else "")
        if (ag.reminders.isNotEmpty()) parts += "${ag.reminders.size} Erinnerung" + (if (ag.reminders.size > 1) "en" else "")
        if (ag.tasks.isNotEmpty()) parts += "${ag.tasks.size} Aufgabe" + (if (ag.tasks.size > 1) "n" else "")
        val sb = StringBuilder()
        sb.append(if (parts.isEmpty()) "$label keine Termine." else "$label: " + parts.joinToString(", ") + ".")
        ag.events.firstOrNull { !it.allDay && it.end > System.currentTimeMillis() }?.let {
            sb.append(" Als Nächstes: ${it.title} um ${TimeLogic.time(it.begin)}.")
        }
        if (ag.overdue.isNotEmpty()) sb.append(" ${ag.overdue.size} Aufgabe(n) überfällig.")
        if (!detailed) return sb.toString()
        val multiDay = rangeName.lowercase() in setOf("woche", "monat")
        if (ag.events.isNotEmpty()) {
            sb.append("\n\nTermine:")
            ag.events.forEach {
                sb.append("\n• ").append(when {
                    it.allDay -> (if (multiDay) TimeLogic.day(it.begin) + " " else "") + "ganztägig"
                    multiDay -> TimeLogic.short(it.begin)
                    else -> TimeLogic.time(it.begin) + "–" + TimeLogic.time(it.end)
                }).append(" ").append(it.title).append(if (it.location.isNotBlank()) " (${it.location})" else "")
            }
            val clashes = TimeLogic.overlappingPairs(ag.events.filter { !it.allDay }.map { TimeLogic.Span(it.begin, it.end, it.title, it.id) })
            if (clashes.isNotEmpty()) sb.append("\n⚠ Überschneidung: " + clashes.joinToString("; ") { "${it.first.title} ↔ ${it.second.title}" })
        }
        if (ag.reminders.isNotEmpty()) {
            sb.append("\n\nErinnerungen:")
            ag.reminders.forEach { sb.append("\n• ${if (multiDay) TimeLogic.short(it.next!!) else TimeLogic.time(it.next!!)} ${it.text}") }
        }
        if (ag.tasks.isNotEmpty() || ag.overdue.isNotEmpty()) {
            sb.append("\n\nAufgaben:")
            ag.overdue.forEach { sb.append("\n• überfällig: ${it.title}") }
            ag.tasks.forEach {
                val d = it.due!!
                sb.append("\n• ").append(if (it.hasTime) (if (multiDay) TimeLogic.short(d) else TimeLogic.time(d)) + " " else if (multiDay) TimeLogic.day(d) + " " else "")
                    .append(it.title).append(if (it.priority == "hoch") " (wichtig)" else "")
            }
        }
        return sb.toString()
    }

    override fun run(t: ToolContext, a: JSONObject): String {
        val range = a.optString("range", "heute").ifBlank { "heute" }
        val (from, to) = TimeLogic.range(range)
        val ag = collect(t.ctx, from, to)
        var s = summary(ag, range, detailed = true)
        if (!ag.calendarAllowed) s += "\n(Hinweis: Kalender-Zugriff fehlt oder ist ausgeschaltet – nur Jarvis-Erinnerungen/Aufgaben.)"
        return Res.ok(s)
    }
}
