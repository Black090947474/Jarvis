package de.jarvis.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import de.jarvis.app.logic.TimeLogic
import de.jarvis.app.tools.AgendaStore
import de.jarvis.app.tools.CalendarTool
import de.jarvis.app.tools.Place
import de.jarvis.app.tools.Reminder
import de.jarvis.app.tools.SchoolTool

/**
 * Smart Context: verbindet Kalender, Aufgaben und Lernstoff und schlägt Sinnvolles vor.
 * Nur wenn „Proaktive Hinweise“ an sind; ändert nie etwas selbst – nur Vorschläge zum Antippen.
 */
object SmartHints {
    private val EXAM = Regex("(?i)(klassenarbeit|klausur|arbeit|test|prüfung|pruefung|exam|referat|vokabeltest|lzk|abfrage)")

    private fun enabled(p: Prefs) = p.proactive && p.proactivity >= 34

    private fun askJarvis(ctx: Context, rc: Int, question: String): PendingIntent =
        PendingIntent.getActivity(ctx, rc, Intent(ctx, ChatActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, question).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun check(ctx: Context) {
        val p = Prefs(ctx)
        if (!enabled(p)) return
        val now = System.currentTimeMillis()
        // 1) Prüfung in den nächsten 3 Tagen, aber keine Lernzeit eingeplant?
        val events = CalendarTool.safeEvents(ctx, now, now + 3 * 86_400_000L)
        val tasks = AgendaStore(ctx).tasks().filter { !it.done }
        for (e in events.filter { EXAM.containsMatchIn(it.title) }) {
            val key = "exam:${e.id}:${e.begin}"
            if (p.wasNotified(key)) continue
            val subject = e.title.replace(EXAM, "").trim().ifBlank { e.title }
            val planned = events.any { it.begin < e.begin && it.title.contains("lern", true) } ||
                tasks.any { it.title.contains("lern", true) && (it.title.contains(subject, true) || subject.isBlank()) }
            if (planned) continue
            p.markNotified(key)
            Notifier.post(ctx, Notifier.CH_TASKS, 60_000 + (key.hashCode() and 0xfff),
                "„${e.title}“ am ${TimeLogic.day(e.begin)}",
                "Du hast dafür noch keine Lernzeit eingeplant. Soll ich dir Lernblöcke vorschlagen? (Antippen – ich trage nichts ohne dein Ja ein.)",
                open = askJarvis(ctx, 61_000, "Ich habe am ${TimeLogic.day(e.begin)} „${e.title}“. Plane mir bis dahin Lernzeit in meine freien Zeiten und frag mich, bevor du etwas einträgst."))
        }
        // 2) Akku knapp
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        if (level in 1..15 && bm?.isCharging != true) batteryLow(ctx, level)
    }

    fun batteryLow(ctx: Context, level: Int) {
        val p = Prefs(ctx)
        if (!p.proactive) return
        val key = "bat:" + (System.currentTimeMillis() / (3 * 3_600_000L))
        if (p.wasNotified(key)) return
        p.markNotified(key)
        Notifier.post(ctx, Notifier.CH_EVENTS, 62_000, "Akku nur noch $level %", "Zeit fürs Ladekabel.")
    }

    /** Zu Hause angekommen (Geofence). */
    /** Gesprochene Begrüßung beim Heimkommen: Name, Wetter, offene Aufgaben, neue Nachrichten. */
    fun greeting(ctx: Context): String {
        val p = Prefs(ctx)
        val h = java.time.LocalTime.now().hour
        val name = p.userName.trim().ifBlank { null }
        val sb = StringBuilder(if (h < 11) "Guten Morgen" else if (h >= 18) "Willkommen zurück" else "Willkommen zu Hause")
        if (name != null) sb.append(", ").append(name)
        sb.append(".")
        try {
            val c = org.json.JSONObject(p.weatherCache)
            if (c.has("t")) sb.append(" Draußen sind es ${c.optInt("t")} Grad" + (c.optString("desc").takeIf { it.isNotBlank() }?.let { ", $it" } ?: "") + ".")
        } catch (_: Exception) {}
        val (_, tomorrowEnd) = TimeLogic.range("morgen")
        val open = AgendaStore(ctx).tasks().filter { !it.done && (it.due == null || it.due < tomorrowEnd) }
        val hw = open.filter { it.category == SchoolTool.HOMEWORK }
        when {
            hw.isNotEmpty() -> sb.append(" Noch offen: ${hw.size} Hausaufgabe" + (if (hw.size > 1) "n" else "") + ", zum Beispiel ${hw.first().title.substringAfter(": ")}.")
            open.isNotEmpty() -> sb.append(" Du hast noch ${open.size} offene Aufgabe" + (if (open.size > 1) "n" else "") + ".")
            else -> sb.append(" Für heute ist alles erledigt.")
        }
        val n = JarvisNotificationListener.recent(null, 30).size
        if (n > 0) sb.append(" Es gibt $n neue Benachrichtigung" + (if (n > 1) "en" else "") + ".")
        return sb.toString()
    }

    fun arrivedHome(ctx: Context) {
        val p = Prefs(ctx)
        if (p.greetOnArrive) {
            val h = java.time.LocalTime.now().hour
            if (h in 7..21) Automations.run(ctx, org.json.JSONObject().put("name", "Willkommen zu Hause")
                .put("steps", org.json.JSONArray().put(org.json.JSONObject().put("tool", "greet").put("args", org.json.JSONObject()))))
        }
        if (!enabled(p)) return
        val (_, tomorrowEnd) = TimeLogic.range("morgen")
        val open = AgendaStore(ctx).tasks().filter { !it.done && (it.due == null || it.due < tomorrowEnd) }
        val hw = open.filter { it.category == SchoolTool.HOMEWORK }
        if (open.isEmpty()) return
        val text = if (hw.isNotEmpty()) "Noch offen: " + hw.joinToString(", ") { it.title } + ". Soll ich dich in einer Stunde daran erinnern?"
            else "Du hast noch ${open.size} offene Aufgabe(n): " + open.take(3).joinToString(", ") { it.title } + "."
        Notifier.post(ctx, Notifier.CH_TASKS, 63_000, "Willkommen zu Hause", text,
            open = askJarvis(ctx, 63_001, "Erinnere mich in einer Stunde an meine offenen Hausaufgaben."))
    }

    /** Geofence „Zuhause“ für den Willkommens-Hinweis einplanen. */
    fun scheduleHome(ctx: Context) {
        val p = Prefs(ctx)
        val r = Reminder(899_999, "home", null, null, place = Place("Zuhause", p.homeLat, p.homeLon, 150f, true))
        if ((enabled(p) || p.greetOnArrive) && p.homeSet) Scheduler.addGeofence(ctx, r) else Scheduler.cancel(ctx, r)
    }

    @Suppress("unused") private val keep = IntentFilter::class
}
