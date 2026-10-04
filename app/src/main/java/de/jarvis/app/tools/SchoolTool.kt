package de.jarvis.app.tools

import android.content.Context
import de.jarvis.app.logic.TimeLogic
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

/** Stundenplan und Hausaufgaben. Hausaufgaben sind Aufgaben mit Kategorie „Hausaufgaben“ (erscheinen auch im Dashboard). */
object SchoolTool : JarvisTool {
    override val name = "school"
    override val group = "agenda"
    override val feature = "tasks"
    override val permissions = emptyList<String>()
    const val HOMEWORK = "Hausaufgaben"

    override val definition: JSONObject = JSONObject("""
{"name":"school","description":"Schule. set_day: Stundenplan für einen Wochentag speichern (lessons = Liste wie '08:00 Mathe R12'). show: Stundenplan für heute/morgen/Wochentag. homework_add: Hausaufgabe (subject, text, due YYYY-MM-DD; ohne due = nächste Stunde in dem Fach) – erinnert am Vortag 17 Uhr. homework_list: offene Hausaufgaben. Erledigt markieren über tasks complete.",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["set_day","show","homework_add","homework_list"]},
  "day":{"type":"string","description":"heute, morgen, mo..so oder Wochentag"},
  "lessons":{"type":"array","items":{"type":"string"}},
  "subject":{"type":"string"},"text":{"type":"string"},"due":{"type":"string"}},"required":["action"]}}
""".trimIndent())

    private val NAMES = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")

    fun dayOf(s: String): DayOfWeek? = when (s.lowercase().trim()) {
        "", "heute" -> LocalDate.now().dayOfWeek
        "morgen" -> LocalDate.now().plusDays(1).dayOfWeek
        "übermorgen" -> LocalDate.now().plusDays(2).dayOfWeek
        else -> TimeLogic.parseDay(s)
    }

    fun lessons(ctx: Context, d: DayOfWeek): List<String> {
        val a = JsonStore(ctx).obj("timetable").optJSONArray(d.name) ?: JSONArray()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }

    /** Kurzer Text für Dashboard/Briefing, null wenn kein Unterricht. */
    fun summary(ctx: Context, d: DayOfWeek): String? {
        val l = lessons(ctx, d)
        if (l.isEmpty()) return null
        val subjects = l.map { it.replace(Regex("^\\d{1,2}[:.]\\d{2}\\s*"), "").substringBefore(" R").trim() }.distinct()
        return "${l.size} Stunden: " + subjects.joinToString(", ") + (l.first().let { first ->
            Regex("^(\\d{1,2}[:.]\\d{2})").find(first)?.let { " (ab ${it.groupValues[1]})" } ?: "" })
    }

    override fun run(t: ToolContext, a: JSONObject): String { return when (a.optString("action")) {
        "set_day" -> {
            val d = dayOf(a.optString("day")) ?: return Res.error("Welcher Wochentag?")
            val arr = a.optJSONArray("lessons") ?: JSONArray()
            val store = JsonStore(t.ctx)
            val tt = store.obj("timetable").put(d.name, arr)
            store.saveObj("timetable", tt)
            Res.ok("Stundenplan ${NAMES[d.value - 1]} gespeichert (${arr.length()} Stunden).")
        }
        "show" -> {
            val d = dayOf(a.optString("day")) ?: return Res.error("Welcher Wochentag?")
            val l = lessons(t.ctx, d)
            if (l.isEmpty()) Res.ok("Für ${NAMES[d.value - 1]} ist kein Stundenplan gespeichert (oder schulfrei).")
            else Res.ok("Stundenplan ${NAMES[d.value - 1]}:\n" + l.joinToString("\n") + homeworkFor(t.ctx, d))
        }
        "homework_add" -> addHomework(t, a)
        "homework_list" -> {
            val hw = AgendaStore(t.ctx).tasks().filter { !it.done && it.category == HOMEWORK }.sortedBy { it.due ?: Long.MAX_VALUE }
            if (hw.isEmpty()) Res.ok("Keine offenen Hausaufgaben.") else Res.ok("Offene Hausaufgaben:\n" + hw.joinToString("\n") { it.describe() })
        }
        else -> Res.error("Unbekannte Aktion.")
    } }

    private fun homeworkFor(ctx: Context, d: DayOfWeek): String {
        val subjects = lessons(ctx, d).map { it.lowercase() }
        val hw = AgendaStore(ctx).tasks().filter { t -> !t.done && t.category == HOMEWORK && subjects.any { s -> s.contains(t.title.substringBefore(":").lowercase()) } }
        return if (hw.isEmpty()) "" else "\nOffene Hausaufgaben dafür: " + hw.joinToString("; ") { it.title }
    }

    /** Nächster Schultag, an dem das Fach im Stundenplan steht. */
    private fun nextLesson(ctx: Context, subject: String): LocalDate? {
        var d = LocalDate.now().plusDays(1)
        repeat(14) {
            if (lessons(ctx, d.dayOfWeek).any { it.contains(subject, true) }) return d
            d = d.plusDays(1)
        }
        return null
    }

    private fun addHomework(t: ToolContext, a: JSONObject): String {
        val subject = a.optString("subject").trim().ifBlank { return Res.error("Für welches Fach?") }
        val text = a.optString("text").trim().ifBlank { return Res.error("Was ist die Hausaufgabe?") }
        val due = a.optString("due").ifBlank { nextLesson(t.ctx, subject)?.toString() ?: LocalDate.now().plusDays(1).toString() }
        val dueDay = runCatching { LocalDate.parse(due.take(10)) }.getOrNull() ?: return Res.error("Datum ungültig (YYYY-MM-DD).")
        val res = TaskTool.run(t, JSONObject().put("action", "add").put("title", "$subject: $text").put("due", dueDay.toString())
            .put("category", HOMEWORK).put("priority", "mittel"))
        if (!res.startsWith("OK")) return res
        // Erinnerung am Vortag um 17 Uhr (falls noch in der Zukunft)
        val remindAt = dueDay.minusDays(1).atTime(17, 0)
        var extra = ""
        if (remindAt.isAfter(java.time.LocalDateTime.now())) {
            val r = ReminderTool.add(t, JSONObject().put("text", "Hausaufgabe $subject: $text").put("at", remindAt.toString().take(16)))
            if (r.startsWith("OK")) extra = " Ich erinnere dich am Vortag um 17 Uhr."
        }
        return Res.ok("Hausaufgabe $subject bis ${TimeLogic.day(dueDay.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())} eingetragen.$extra")
    }
}
