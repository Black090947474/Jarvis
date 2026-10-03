package de.jarvis.app.tools

import android.Manifest
import android.content.Context
import android.os.Build
import de.jarvis.app.Scheduler
import de.jarvis.app.logic.TimeLogic
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Erinnerungen von Jarvis: einmalig, wiederholt, vor Terminen oder beim Ankommen/Verlassen eines Ortes. */
object ReminderTool : JarvisTool {
    override val name = "reminders"
    override val group = "agenda"
    override val feature = "reminders"
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"reminders","description":"Jarvis-Erinnerungen (kommen als Benachrichtigung, auch offline). add: neue Erinnerung – Zeit über at ODER in_minutes ODER hour+minute(+tomorrow) ODER before_event_id+minutes_before ODER place (home/here, when arrive/leave). repeat für Wiederholung. list: alle aktiven. delete: per id. set_home: aktuellen Standort als Zuhause speichern.",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["add","list","delete","set_home"]},
  "text":{"type":"string","description":"Woran erinnert wird"},
  "at":{"type":"string","description":"YYYY-MM-DDTHH:MM Ortszeit"},
  "in_minutes":{"type":"integer"},
  "hour":{"type":"integer"},"minute":{"type":"integer"},"tomorrow":{"type":"boolean"},
  "repeat":{"type":"string","enum":["none","daily","weekdays","weekly","monthly"]},
  "days":{"type":"array","items":{"type":"string"},"description":"Bei weekly: mo,di,mi,do,fr,sa,so"},
  "before_event_id":{"type":"integer","description":"Kalender-id, relativ dazu erinnern"},
  "minutes_before":{"type":"integer"},
  "place":{"type":"string","enum":["home","here"]},
  "when":{"type":"string","enum":["arrive","leave"]},
  "id":{"type":"integer"}},"required":["action"]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String = when (a.optString("action", "add")) {
        "add" -> add(t, a)
        "list" -> list(t.ctx)
        "delete" -> delete(t.ctx, a.optInt("id", -1))
        "set_home" -> setHome(t)
        else -> Res.error("Unbekannte Aktion.")
    }

    fun list(ctx: Context): String {
        val r = AgendaStore(ctx).reminders().filter { it.active }.sortedBy { it.next ?: Long.MAX_VALUE }
        return if (r.isEmpty()) Res.ok("Keine aktiven Erinnerungen.") else Res.ok("${r.size} Erinnerung(en):\n" + r.joinToString("\n") { it.describe() })
    }

    private fun delete(ctx: Context, id: Int): String {
        val store = AgendaStore(ctx)
        val r = store.reminder(id) ?: return Res.error("Erinnerung $id nicht gefunden. Erst list nutzen.")
        Scheduler.cancel(ctx, r)
        store.removeReminder(id)
        return Res.ok("Erinnerung „${r.text}“ gelöscht.")
    }

    private fun setHome(t: ToolContext): String {
        val loc = Geo.lastKnown(t.ctx) ?: return if (!t.has(Manifest.permission.ACCESS_FINE_LOCATION))
            Res.noPermission("den Standort", "Bitte erlaube „Standort“ im Jarvis-Berechtigungszentrum.")
            else Res.error("Kein Standort verfügbar. Ist der Standort eingeschaltet?")
        t.prefs.setHome(loc.first, loc.second)
        return Res.ok("Dein Zuhause ist gespeichert (%.4f, %.4f).".format(loc.first, loc.second))
    }

    /** Legt eine Erinnerung an (auch für Aufgaben und Offline-Befehle). */
    fun add(t: ToolContext, a: JSONObject, taskId: Int? = null): String {
        val text = a.optString("text").trim().ifBlank { return Res.error("Woran soll ich dich erinnern?") }
        val repeat = TimeLogic.parseRepeat(a.optString("repeat", "none"))
        val days = a.optJSONArray("days")?.let { arr -> (0 until arr.length()).mapNotNull { TimeLogic.parseDay(arr.optString(it)) }.toSet() } ?: emptySet()
        val store = AgendaStore(t.ctx)

        // Ortsbasiert
        if (a.has("place")) {
            if (!t.prefs.feature("location")) return Res.disabled("den Standort")
            if (!t.has(Manifest.permission.ACCESS_FINE_LOCATION))
                return Res.noPermission("ortsbasierte Erinnerungen", "Bitte erlaube „Standort“ im Jarvis-Berechtigungszentrum.")
            if (Build.VERSION.SDK_INT >= 29 && !t.has(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                return Res.noPermission("ortsbasierte Erinnerungen",
                    "Android verlangt dafür Standort „Immer erlauben“. Öffne Jarvis → Berechtigungen → Standort im Hintergrund.")
            val (lat, lon, pname) = when (a.optString("place")) {
                "home" -> {
                    if (!t.prefs.homeSet) return Res.error("Ich kenne dein Zuhause noch nicht. Sag „Jarvis, ich bin zu Hause, merk dir das“, dann speichere ich es (reminders set_home).")
                    Triple(t.prefs.homeLat, t.prefs.homeLon, "Zuhause")
                }
                else -> Geo.lastKnown(t.ctx)?.let { Triple(it.first, it.second, "hier") } ?: return Res.error("Kein Standort verfügbar.")
            }
            val onEnter = a.optString("when", "arrive") != "leave"
            val r = Reminder(store.newId(), text, null, null, if (repeat == TimeLogic.Repeat.NONE) repeat else TimeLogic.Repeat.DAILY,
                place = Place(pname, lat, lon, 150f, onEnter), taskId = taskId)
            if (!Scheduler.addGeofence(t.ctx, r)) return Res.error("Die Ortserinnerung konnte nicht eingerichtet werden (Google-Standortdienste nicht verfügbar?).")
            store.putReminder(r)
            return Res.ok("Ich erinnere dich ${if (onEnter) "beim Ankommen" else "beim Verlassen"} ($pname): $text" +
                (if (r.repeat != TimeLogic.Repeat.NONE) " – jedes Mal." else "."))
        }

        // Zeitbasiert
        val z = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val at: Long = when {
            a.has("at") -> TimeLogic.parseLocal(a.optString("at")) ?: return Res.error("Zeitpunkt ungültig (YYYY-MM-DDTHH:MM).")
            a.has("in_minutes") -> now + a.optLong("in_minutes") * 60_000
            a.has("hour") -> {
                var d = ZonedDateTime.now(z).withHour(a.optInt("hour").coerceIn(0, 23)).withMinute(a.optInt("minute", 0).coerceIn(0, 59)).withSecond(0).withNano(0)
                if (a.optBoolean("tomorrow")) d = d.plusDays(1) else if (!d.isAfter(ZonedDateTime.now(z))) d = d.plusDays(1)
                d.toInstant().toEpochMilli()
            }
            a.has("before_event_id") -> {
                val ev = CalendarTool.safeEvents(t.ctx, now, now + 365L * 86_400_000).firstOrNull { it.id == a.optLong("before_event_id") }
                    ?: return Res.error("Termin nicht gefunden.")
                ev.begin - a.optLong("minutes_before", 30) * 60_000
            }
            else -> return Res.error("Wann soll ich erinnern? (Uhrzeit, in X Minuten, vor einem Termin oder an einem Ort)")
        }
        if (at <= now && repeat == TimeLogic.Repeat.NONE) return Res.error("Der Zeitpunkt ${TimeLogic.short(at)} liegt in der Vergangenheit.")
        var first = at
        if (repeat == TimeLogic.Repeat.WEEKLY && days.isNotEmpty() && Instant.ofEpochMilli(at).atZone(z).dayOfWeek !in days)
            first = TimeLogic.nextOccurrence(at, repeat, days, at) ?: at
        val next = if (first > now) first else TimeLogic.nextOccurrence(first, repeat, days, now) ?: return Res.error("Kein zukünftiger Zeitpunkt.")
        val r = Reminder(store.newId(), text, first, next, repeat, days, taskId = taskId)
        store.putReminder(r)
        Scheduler.schedule(t.ctx, r)
        val whenTxt = r.describe().split(" | ").getOrNull(1) ?: TimeLogic.short(next)
        return Res.ok("Erinnerung gestellt: $whenTxt – $text")
    }
}

/** Letzter bekannter Standort (lat, lon) ohne Warten. */
object Geo {
    @Suppress("MissingPermission")
    fun lastKnown(ctx: Context): Pair<Double, Double>? {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return try {
            listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { if (lm.isProviderEnabled(it)) lm.getLastKnownLocation(it) else null }.getOrNull() }
                .maxByOrNull { it.time }?.let { it.latitude to it.longitude }
        } catch (_: Exception) { null }
    }
}
