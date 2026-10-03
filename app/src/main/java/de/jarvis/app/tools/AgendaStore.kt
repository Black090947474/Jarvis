package de.jarvis.app.tools

import android.content.Context
import de.jarvis.app.logic.TimeLogic
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

/** Ort für ortsbasierte Erinnerungen. */
data class Place(val name: String, val lat: Double, val lon: Double, val radius: Float = 150f, val onEnter: Boolean = true) {
    fun json(): JSONObject = JSONObject().put("name", name).put("lat", lat).put("lon", lon).put("radius", radius.toDouble()).put("enter", onEnter)
    companion object {
        fun from(o: JSONObject?) = o?.let { Place(it.optString("name"), it.optDouble("lat"), it.optDouble("lon"), it.optDouble("radius", 150.0).toFloat(), it.optBoolean("enter", true)) }
    }
}

/** Eine Erinnerung (Zeit oder Ort), lokal gespeichert – funktioniert auch ohne Internet. */
data class Reminder(
    val id: Int,
    val text: String,
    val anchor: Long?,           // erster Zeitpunkt (Basis für Wiederholungen)
    val next: Long?,             // nächster fälliger Zeitpunkt (null = ortsbasiert oder erledigt)
    val repeat: TimeLogic.Repeat = TimeLogic.Repeat.NONE,
    val days: Set<DayOfWeek> = emptySet(),
    val place: Place? = null,
    val active: Boolean = true,
    val taskId: Int? = null,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("text", text).put("anchor", anchor ?: -1).put("next", next ?: -1)
        .put("repeat", repeat.name).put("days", JSONArray(days.map { it.value })).put("active", active)
        .put("task", taskId ?: -1).apply { place?.let { put("place", it.json()) } }

    fun describe(): String {
        val whenTxt = when {
            place != null -> (if (place.onEnter) "beim Ankommen: " else "beim Verlassen: ") + place.name
            next != null -> TimeLogic.short(next) + repeatText()
            else -> "erledigt"
        }
        return "id $id | $whenTxt | $text"
    }

    private fun repeatText() = when (repeat) {
        TimeLogic.Repeat.NONE -> ""
        TimeLogic.Repeat.DAILY -> " (täglich)"
        TimeLogic.Repeat.WEEKDAYS -> " (werktags)"
        TimeLogic.Repeat.WEEKLY -> " (wöchentlich" + (if (days.isNotEmpty()) " " + days.sorted().joinToString(",") { dayShort(it) } else "") + ")"
        TimeLogic.Repeat.MONTHLY -> " (monatlich)"
    }

    companion object {
        fun from(o: JSONObject) = Reminder(
            o.optInt("id"), o.optString("text"),
            o.optLong("anchor", -1).takeIf { it > 0 }, o.optLong("next", -1).takeIf { it > 0 },
            runCatching { TimeLogic.Repeat.valueOf(o.optString("repeat", "NONE")) }.getOrDefault(TimeLogic.Repeat.NONE),
            (o.optJSONArray("days") ?: JSONArray()).let { a -> (0 until a.length()).map { DayOfWeek.of(a.getInt(it)) }.toSet() },
            Place.from(o.optJSONObject("place")), o.optBoolean("active", true), o.optInt("task", -1).takeIf { it > 0 })

        fun dayShort(d: DayOfWeek) = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")[d.value - 1]
    }
}

/** Eine Aufgabe im Jarvis-Aufgabensystem. */
data class TaskItem(
    val id: Int,
    val title: String,
    val description: String = "",
    val priority: String = "mittel",   // hoch | mittel | niedrig
    val due: Long? = null,             // Fälligkeit (Datum + Uhrzeit, oder Datum 00:00)
    val hasTime: Boolean = false,
    val repeat: TimeLogic.Repeat = TimeLogic.Repeat.NONE,
    val days: Set<DayOfWeek> = emptySet(),
    val done: Boolean = false,
    val category: String = "",
    val reminderId: Int? = null,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("title", title).put("desc", description).put("prio", priority)
        .put("due", due ?: -1).put("hasTime", hasTime).put("repeat", repeat.name).put("days", JSONArray(days.map { it.value }))
        .put("done", done).put("cat", category).put("rem", reminderId ?: -1)

    fun describe(): String {
        val d = due?.let { if (hasTime) TimeLogic.short(it) else TimeLogic.day(it) } ?: "ohne Datum"
        return "id $id | ${if (done) "erledigt" else "offen"} | Priorität $priority | $d" +
            (if (repeat != TimeLogic.Repeat.NONE) " | wiederholt ${repeat.name.lowercase()}" else "") +
            (if (category.isNotBlank()) " | $category" else "") + " | $title" +
            (if (description.isNotBlank()) " – $description" else "")
    }

    companion object {
        fun from(o: JSONObject) = TaskItem(
            o.optInt("id"), o.optString("title"), o.optString("desc"), o.optString("prio", "mittel"),
            o.optLong("due", -1).takeIf { it > 0 }, o.optBoolean("hasTime"),
            runCatching { TimeLogic.Repeat.valueOf(o.optString("repeat", "NONE")) }.getOrDefault(TimeLogic.Repeat.NONE),
            (o.optJSONArray("days") ?: JSONArray()).let { a -> (0 until a.length()).map { DayOfWeek.of(a.getInt(it)) }.toSet() },
            o.optBoolean("done"), o.optString("cat"), o.optInt("rem", -1).takeIf { it > 0 })
    }
}

/** Speicher für Erinnerungen und Aufgaben (SharedPreferences, JSON). */
class AgendaStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("jarvis_agenda", Context.MODE_PRIVATE)

    @Synchronized fun newId(): Int { val n = sp.getInt("next_id", 1); sp.edit().putInt("next_id", n + 1).commit(); return n }

    // Erinnerungen
    @Synchronized fun reminders(): List<Reminder> = read("reminders").map { Reminder.from(it) }
    @Synchronized fun reminder(id: Int) = reminders().firstOrNull { it.id == id }
    @Synchronized fun putReminder(r: Reminder) = write("reminders", reminders().filter { it.id != r.id } + r) { it.json() }
    @Synchronized fun removeReminder(id: Int) = write("reminders", reminders().filter { it.id != id }) { it.json() }

    // Aufgaben
    @Synchronized fun tasks(): List<TaskItem> = read("tasks").map { TaskItem.from(it) }
    @Synchronized fun task(id: Int) = tasks().firstOrNull { it.id == id }
    @Synchronized fun putTask(t: TaskItem) = write("tasks", tasks().filter { it.id != t.id } + t) { it.json() }
    @Synchronized fun removeTask(id: Int) = write("tasks", tasks().filter { it.id != id }) { it.json() }

    private fun read(key: String): List<JSONObject> {
        val a = try { JSONArray(sp.getString(key, "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun <T> write(key: String, list: List<T>, map: (T) -> JSONObject) {
        val a = JSONArray(); list.forEach { a.put(map(it)) }
        sp.edit().putString(key, a.toString()).apply()
    }
}
