package de.jarvis.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import de.jarvis.app.tools.JsonStore
import de.jarvis.app.tools.Place
import de.jarvis.app.tools.Reminder
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * WENN-DANN-Automationen. Speicher, Beschreibung, Auslöser-Abgleich und Ausführung.
 * Format: {id, name, on, trigger:{type,…}, steps:[{tool,args}], last}
 */
object Automations {
    const val KEY = "automations"

    val TRIGGERS = linkedMapOf(
        "time" to "Uhrzeit erreicht",
        "place" to "Ort erreicht / verlassen",
        "headphones" to "Kopfhörer verbunden",
        "bluetooth" to "Bluetooth-Gerät verbunden",
        "wifi" to "WLAN verbunden",
        "battery" to "Akku unter X %",
        "charging" to "Ladekabel angeschlossen",
        "event" to "Kalendertermin beginnt",
        "notification" to "Benachrichtigung kommt",
        "app" to "App wird geöffnet",
    )

    /** Aktionen, die der Baukasten anbietet (jede ist ein Jarvis-Werkzeug). */
    val ACTIONS = linkedMapOf(
        "notify" to "Nachricht anzeigen",
        "speak" to "Jarvis spricht",
        "reminders" to "Erinnerung erstellen",
        "play_music" to "Musik starten",
        "media_control" to "Musik Pause/Weiter",
        "set_volume" to "Lautstärke ändern",
        "open_app" to "App öffnen",
        "set_timer" to "Timer starten",
        "flashlight" to "Taschenlampe",
        "brightness" to "Helligkeit",
        "briefing" to "Tagesüberblick vorlesen",
        "greet" to "Begrüßung („Willkommen zurück“)",
        "routines" to "Eigenes Kommando ausführen",
    )

    fun all(ctx: Context): List<JSONObject> = JsonStore(ctx).list(KEY)
    fun save(ctx: Context, list: List<JSONObject>) { JsonStore(ctx).save(KEY, list); Scheduler.scheduleAutomations(ctx) }

    fun put(ctx: Context, a: JSONObject): JSONObject {
        if (!a.has("id")) a.put("id", JsonStore(ctx).newId())
        if (!a.has("on")) a.put("on", true)
        // Bestätigungen werden in Automationen nie gespeichert
        a.optJSONArray("steps")?.let { s -> for (i in 0 until s.length()) s.optJSONObject(i)?.optJSONObject("args")?.remove("confirmed") }
        save(ctx, all(ctx).filter { it.optInt("id") != a.optInt("id") } + a)
        return a
    }

    fun remove(ctx: Context, id: Int) = save(ctx, all(ctx).filter { it.optInt("id") != id })

    private val DAY = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

    fun describeTrigger(t: JSONObject?): String {
        t ?: return "?"
        return when (t.optString("type")) {
            "time" -> "um %02d:%02d".format(t.optInt("hour"), t.optInt("minute")) + days(t)
            "place" -> (if (t.optString("when") == "leave") "beim Verlassen von " else "beim Ankommen: ") + t.optString("name", "Zuhause")
            "headphones" -> "Kopfhörer verbunden"
            "bluetooth" -> "Bluetooth " + t.optString("device").ifBlank { "(beliebiges Gerät)" } + " verbunden"
            "wifi" -> "WLAN " + t.optString("ssid").ifBlank { "(beliebig)" } + " verbunden"
            "battery" -> "Akku unter ${t.optInt("below", 20)} %"
            "charging" -> "Ladekabel angeschlossen"
            "event" -> if (t.optInt("minutes_before") > 0) "${t.optInt("minutes_before")} Min. vor einem Termin" else "Kalendertermin beginnt"
            "notification" -> "Benachrichtigung" + t.optString("app").takeIf { it.isNotBlank() }?.let { " von $it" }.orEmpty() +
                t.optString("text").takeIf { it.isNotBlank() }?.let { " mit „$it“" }.orEmpty()
            "app" -> "${t.optString("app")} wird geöffnet"
            else -> t.optString("type")
        }
    }

    private fun days(t: JSONObject): String {
        val d = t.optJSONArray("days") ?: return " (täglich)"
        if (d.length() == 0 || d.length() == 7) return " (täglich)"
        val list = (0 until d.length()).map { d.optInt(it) }.sorted()
        if (list == listOf(1, 2, 3, 4, 5)) return " (werktags)"
        return " (" + list.joinToString(",") { DAY[(it - 1).coerceIn(0, 6)] } + ")"
    }

    fun describeStep(s: JSONObject): String {
        val a = s.optJSONObject("args") ?: JSONObject()
        return when (s.optString("tool")) {
            "notify" -> "Hinweis: „${a.optString("text")}“"
            "speak" -> "sagen: „${a.optString("text")}“"
            "reminders" -> "Erinnerung: ${a.optString("text")}" + (if (a.has("in_minutes")) " in ${a.optInt("in_minutes")} Min." else "")
            "play_music" -> "Musik: ${a.optString("query")}"
            "media_control" -> "Musik ${a.optString("action")}"
            "set_volume" -> "Lautstärke ${a.optInt("percent")} %"
            "open_app" -> "${a.optString("name")} öffnen"
            "set_timer" -> "Timer ${a.optInt("seconds") / 60} Min."
            "flashlight" -> "Taschenlampe ${if (a.optBoolean("on", true)) "an" else "aus"}"
            "brightness" -> "Helligkeit ${a.optInt("percent")} %"
            "briefing" -> "Tagesüberblick vorlesen"
            "greet" -> "Begrüßung sprechen"
            "routines" -> "Kommando „${a.optString("name")}“"
            else -> PhoneTools.labelFor(s.optString("tool"))
        }
    }

    fun describe(a: JSONObject): String {
        val steps = a.optJSONArray("steps") ?: JSONArray()
        return "WENN " + describeTrigger(a.optJSONObject("trigger")) + " → DANN " +
            (0 until steps.length()).joinToString(", ") { describeStep(steps.getJSONObject(it)) }
    }

    // ---------- Auslöser ----------

    /** Ein Ereignis ist eingetreten (z. B. "headphones", "battery" mit level). Passende Automationen ausführen. */
    fun fire(ctx: Context, type: String, data: JSONObject = JSONObject()) {
        val now = System.currentTimeMillis()
        val hits = all(ctx).filter { a ->
            val t = a.optJSONObject("trigger") ?: return@filter false
            a.optBoolean("on", true) && t.optString("type") == type && matches(t, data) && now - a.optLong("last") > 60_000
        }
        if (hits.isEmpty()) return
        val updated = all(ctx).map { a -> if (hits.any { it.optInt("id") == a.optInt("id") }) a.put("last", now) else a }
        JsonStore(ctx).save(KEY, updated)
        hits.forEach { run(ctx, it) }
    }

    private fun matches(t: JSONObject, d: JSONObject): Boolean = when (t.optString("type")) {
        "bluetooth" -> t.optString("device").isBlank() || d.optString("device").contains(t.optString("device"), true)
        "wifi" -> t.optString("ssid").isBlank() || d.optString("ssid").contains(t.optString("ssid"), true)
        "battery" -> d.optInt("level", 100) < t.optInt("below", 20) && d.optInt("prev", 101) >= t.optInt("below", 20)
        "notification" -> (t.optString("app").isBlank() || d.optString("app").contains(t.optString("app"), true)) &&
            (t.optString("text").isBlank() || d.optString("text").contains(t.optString("text"), true))
        "app" -> d.optString("app").equals(t.optString("app"), true) || d.optString("app").contains(t.optString("app"), true)
        "time", "place", "event" -> d.optInt("id", -1) == -1 || true
        else -> true
    }

    /** Führt eine Automation aus. Reine Hinweise laufen direkt, alles andere über den unsichtbaren Ausführer. */
    fun run(ctx: Context, a: JSONObject) {
        val steps = a.optJSONArray("steps") ?: return
        val onlyNotify = (0 until steps.length()).all { steps.getJSONObject(it).optString("tool") == "notify" }
        if (onlyNotify) {
            for (i in 0 until steps.length()) {
                val t = steps.getJSONObject(i).optJSONObject("args")?.optString("text").orEmpty()
                Notifier.post(ctx, Notifier.CH_REMINDER, 50_000 + a.optInt("id") * 10 + i, a.optString("name", "Jarvis"), t)
            }
            ActionLog.add(ctx, "automation", "Automation „${a.optString("name")}“", JSONObject(), "OK", null)
            return
        }
        val i = Intent(ctx, AutomationRunActivity::class.java).putExtra("automation", a.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val canStart = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(ctx)
        if (canStart) try { ctx.startActivity(i); return } catch (_: Exception) {}
        // Android verbietet das Starten aus dem Hintergrund → Benachrichtigung zum Antippen
        Notifier.post(ctx, Notifier.CH_REMINDER, 50_000 + a.optInt("id"), "Automation „${a.optString("name")}“",
            describe(a) + "\nTippen zum Ausführen. (Tipp: „Über anderen Apps einblenden“ erlauben, dann läuft es automatisch.)",
            open = PendingIntent.getActivity(ctx, 50_000 + a.optInt("id"), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    fun runById(ctx: Context, id: Int) {
        val a = all(ctx).firstOrNull { it.optInt("id") == id && it.optBoolean("on", true) } ?: return
        if (System.currentTimeMillis() - a.optLong("last") < 60_000) return
        JsonStore(ctx).save(KEY, all(ctx).map { if (it.optInt("id") == id) it.put("last", System.currentTimeMillis()) else it })
        run(ctx, a)
    }

    /** Bluetooth-Gerät verbunden (Auto, Kopfhörer, Lautsprecher …). */
    @Suppress("MissingPermission")
    fun onBluetooth(ctx: Context, i: Intent) {
        val dev: android.bluetooth.BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
            i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
        val name = try { dev?.name.orEmpty() } catch (_: SecurityException) { "" }
        val audio = try { dev?.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO } catch (_: SecurityException) { false }
        fire(ctx, "bluetooth", JSONObject().put("device", name))
        if (audio) fire(ctx, "headphones", JSONObject().put("device", name))
        // Auto-Modus: als Auto markiertes Gerät
        val car = Prefs(ctx).carDevice
        if (car.isNotBlank() && name.contains(car, true)) {
            try { ctx.startActivity(Intent(ctx, CarActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    // ---------- Planung (Uhrzeit, Ort) ----------

    fun nextTime(t: JSONObject, after: Long = System.currentTimeMillis()): Long? {
        val z = ZoneId.systemDefault()
        val d = t.optJSONArray("days")?.let { a -> (0 until a.length()).map { DayOfWeek.of(a.optInt(it).coerceIn(1, 7)) }.toSet() }.orEmpty()
        var c = ZonedDateTime.now(z).withHour(t.optInt("hour").coerceIn(0, 23)).withMinute(t.optInt("minute").coerceIn(0, 59)).withSecond(0).withNano(0)
        repeat(8) {
            if (c.toInstant().toEpochMilli() > after && (d.isEmpty() || c.dayOfWeek in d)) return c.toInstant().toEpochMilli()
            c = c.plusDays(1)
        }
        return null
    }

    /** Ort einer Orts-Automation als Place (für Geofence). */
    fun place(ctx: Context, t: JSONObject): Place? {
        val p = Prefs(ctx)
        val (lat, lon) = when {
            t.has("lat") -> t.optDouble("lat") to t.optDouble("lon")
            p.homeSet -> p.homeLat to p.homeLon
            else -> return null
        }
        return Place(t.optString("name", "Zuhause"), lat, lon, 150f, t.optString("when") != "leave")
    }

    /** Pseudo-Erinnerung für den Geofence-Mechanismus (id-Bereich ab 900 000). */
    fun fenceReminder(ctx: Context, a: JSONObject): Reminder? {
        val pl = place(ctx, a.optJSONObject("trigger") ?: return null) ?: return null
        return Reminder(900_000 + a.optInt("id"), "auto", null, null, place = pl)
    }
}
