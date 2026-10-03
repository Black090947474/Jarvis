package de.jarvis.app.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import de.jarvis.app.logic.TimeLogic
import de.jarvis.app.logic.TimeLogic.Span
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

/** Kalender des Handys: anzeigen, suchen, freie Zeiten, erstellen, verschieben, löschen. */
object CalendarTool : JarvisTool {
    override val name = "calendar"
    override val group = "agenda"
    override val feature = "calendar"
    override val permissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    override val definition: JSONObject = JSONObject("""
{"name":"calendar","description":"Kalender des Nutzers. list: Termine in einem Zeitraum. search: Termine nach Stichwort. free: freie Zeiten finden. create/update/delete: Termin anlegen, ändern/verschieben, löschen (immer erst ohne confirmed aufrufen, dann nach Ja des Nutzers mit confirmed=true). ids kommen aus list/search.",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["list","search","free","create","update","delete"]},
  "range":{"type":"string","description":"heute, morgen, woche, monat oder Datum YYYY-MM-DD"},
  "query":{"type":"string"},
  "id":{"type":"integer"},
  "title":{"type":"string"},
  "start":{"type":"string","description":"YYYY-MM-DDTHH:MM (Ortszeit)"},
  "duration_min":{"type":"integer"},
  "location":{"type":"string"},
  "reminder_min":{"type":"integer","description":"Optional: Erinnerung so viele Minuten vorher"},
  "min_minutes":{"type":"integer","description":"free: Mindestlänge, Standard 60"},
  "from_time":{"type":"string","description":"free: Tagesbeginn HH:MM, Standard 08:00"},
  "to_time":{"type":"string","description":"free: Tagesende HH:MM, Standard 22:00"},
  "confirmed":{"type":"boolean"}},"required":["action"]}}
""".trimIndent())

    data class Event(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean,
                     val location: String, val calendar: String, val recurring: Boolean)

    override fun run(t: ToolContext, a: JSONObject): String {
        if (!t.has(Manifest.permission.READ_CALENDAR))
            return Res.noPermission("deinen Kalender", "Bitte erlaube „Kalender“ im Jarvis-Berechtigungszentrum.")
        return when (a.optString("action")) {
            "list" -> list(t.ctx, a.optString("range", "heute"))
            "search" -> search(t.ctx, a.optString("query"), a.optString("range", "monat"))
            "free" -> free(t, a)
            "create" -> create(t, a)
            "update" -> update(t, a)
            "delete" -> delete(t, a)
            else -> Res.error("Unbekannte Kalender-Aktion.")
        }
    }

    // ---------- Lesen ----------

    fun events(ctx: Context, from: Long, to: Long): List<Event> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from); ContentUris.appendId(it, to)
        }.build()
        val proj = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.RRULE)
        val out = mutableListOf<Event>()
        try {
            ctx.contentResolver.query(uri, proj, "${CalendarContract.Instances.VISIBLE} = 1", null,
                "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val allDay = c.getInt(4) == 1
                    var b = c.getLong(2); var e = c.getLong(3)
                    if (allDay) { // Ganztägige Termine stehen in UTC – auf lokale Tage umrechnen
                        val z = ZoneId.systemDefault()
                        b = Instant.ofEpochMilli(b).atZone(ZoneId.of("UTC")).toLocalDate().atStartOfDay(z).toInstant().toEpochMilli()
                        e = Instant.ofEpochMilli(e).atZone(ZoneId.of("UTC")).toLocalDate().atStartOfDay(z).toInstant().toEpochMilli()
                    }
                    out += Event(c.getLong(0), c.getString(1) ?: "(ohne Titel)", b, e, allDay,
                        c.getString(5).orEmpty(), c.getString(6).orEmpty(), !c.getString(7).isNullOrBlank())
                }
            }
        } catch (_: SecurityException) { }
        return out
    }

    fun line(e: Event): String = if (e.allDay) "id ${e.id} | ganztägig ${TimeLogic.day(e.begin)} | ${e.title}"
        else "id ${e.id} | ${TimeLogic.short(e.begin)}–${TimeLogic.time(e.end)} | ${e.title}" +
            (if (e.location.isNotBlank()) " | Ort: ${e.location}" else "") + (if (e.recurring) " | Serientermin" else "")

    private fun list(ctx: Context, range: String): String {
        val (from, to) = TimeLogic.range(range)
        val ev = events(ctx, from, to)
        if (ev.isEmpty()) return Res.ok("Keine Termine ($range).")
        val busy = ev.filter { !it.allDay }.map { Span(it.begin, it.end, it.title, it.id) }
        val clashes = TimeLogic.overlappingPairs(busy)
        return Res.ok("${ev.size} Termin(e) ($range):\n" + ev.joinToString("\n") { line(it) } +
            (if (clashes.isNotEmpty()) "\nÜberschneidungen: " + clashes.joinToString("; ") { "${it.first.title} ↔ ${it.second.title}" } else ""))
    }

    private fun search(ctx: Context, q: String, range: String): String {
        if (q.isBlank()) return Res.error("Kein Suchbegriff.")
        val now = System.currentTimeMillis()
        val (_, to) = TimeLogic.range(range)
        val hits = events(ctx, now - 30L * 86_400_000, maxOf(to, now + 60L * 86_400_000))
            .filter { it.title.contains(q, true) || it.location.contains(q, true) }
        return if (hits.isEmpty()) Res.ok("Keinen Termin zu „$q“ gefunden.")
        else Res.ok(hits.take(15).joinToString("\n") { line(it) })
    }

    private fun free(t: ToolContext, a: JSONObject): String {
        val range = a.optString("range", "heute")
        val (from, to) = TimeLogic.range(range)
        val z = ZoneId.systemDefault()
        val days = generateSequence(Instant.ofEpochMilli(from).atZone(z).toLocalDate()) { it.plusDays(1) }
            .takeWhile { it.atStartOfDay(z).toInstant().toEpochMilli() < to }.toList()
        val startT = runCatching { LocalTime.parse(a.optString("from_time", "08:00")) }.getOrDefault(LocalTime.of(8, 0))
        val endT = runCatching { LocalTime.parse(a.optString("to_time", "22:00")) }.getOrDefault(LocalTime.of(22, 0))
        val busy = events(t.ctx, from, to).filter { !it.allDay }.map { Span(it.begin, it.end, it.title, it.id) }
        val slots = TimeLogic.freeSlotsDays(busy, days, startT, endT, a.optLong("min_minutes", 60), t.prefs.calendarBuffer.toLong())
        if (slots.isEmpty()) return Res.ok("Keine freien Zeiten ($range, ${startT}–${endT}).")
        return Res.ok("Freie Zeiten ($range, Puffer ${t.prefs.calendarBuffer} Min.):\n" + slots.take(20).joinToString("\n") { TimeLogic.spanText(it) })
    }

    // ---------- Schreiben (nur mit Bestätigung) ----------

    private fun writeCalendarId(ctx: Context): Long? {
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.ACCOUNT_TYPE)
        val sel = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${CalendarContract.Calendars.VISIBLE} = 1"
        val cands = mutableListOf<Triple<Long, Boolean, String>>()
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, null, null)?.use { c ->
            while (c.moveToNext()) cands += Triple(c.getLong(0), c.getInt(1) == 1, c.getString(2).orEmpty())
        }
        return (cands.firstOrNull { it.second } ?: cands.firstOrNull { it.third == "com.google" } ?: cands.firstOrNull())?.first
    }

    private fun clashText(ctx: Context, start: Long, end: Long, ignore: Long = -1): String {
        val busy = events(ctx, start - 86_400_000, end + 86_400_000).filter { !it.allDay }.map { Span(it.begin, it.end, it.title, it.id) }
        val c = TimeLogic.conflicts(busy, start, end, 0, ignore)
        return if (c.isEmpty()) "" else " Achtung, Überschneidung mit: " + c.joinToString(", ") { "${it.title} (${TimeLogic.time(it.start)}–${TimeLogic.time(it.end)})" } + "."
    }

    private fun create(t: ToolContext, a: JSONObject): String {
        if (!t.has(Manifest.permission.WRITE_CALENDAR))
            return Res.noPermission("das Eintragen von Terminen", "Bitte erlaube „Kalender“ im Jarvis-Berechtigungszentrum.")
        val title = a.optString("title").ifBlank { return Res.error("Kein Titel.") }
        val start = TimeLogic.parseLocal(a.optString("start")) ?: return Res.error("Startzeit fehlt oder ist ungültig (YYYY-MM-DDTHH:MM).")
        val end = start + a.optLong("duration_min", 60) * 60_000
        val loc = a.optString("location")
        val desc = "„$title“ am ${TimeLogic.day(start)} von ${TimeLogic.time(start)} bis ${TimeLogic.time(end)}" +
            (if (loc.isNotBlank()) " in $loc" else "") + " eintragen."
        if (!a.optBoolean("confirmed")) return Res.confirm(desc + clashText(t.ctx, start, end))
        val cal = writeCalendarId(t.ctx) ?: return Res.error("Kein beschreibbarer Kalender gefunden.")
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, cal); put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start); put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (loc.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, loc)
        }
        val uri = t.ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return Res.error("Der Kalender hat den Termin nicht angenommen.")
        val id = ContentUris.parseId(uri)
        if (a.has("reminder_min")) {
            t.ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, id); put(CalendarContract.Reminders.MINUTES, a.optInt("reminder_min"))
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        }
        return Res.ok("Termin eingetragen (id $id): $title, ${TimeLogic.day(start)} ${TimeLogic.time(start)}–${TimeLogic.time(end)}.")
    }

    private fun findEvent(ctx: Context, id: Long): Event? {
        val now = System.currentTimeMillis()
        return events(ctx, now - 365L * 86_400_000, now + 365L * 86_400_000).firstOrNull { it.id == id && it.end >= now }
            ?: events(ctx, now - 365L * 86_400_000, now + 365L * 86_400_000).firstOrNull { it.id == id }
    }

    private fun update(t: ToolContext, a: JSONObject): String {
        if (!t.has(Manifest.permission.WRITE_CALENDAR))
            return Res.noPermission("das Ändern von Terminen", "Bitte erlaube „Kalender“ im Jarvis-Berechtigungszentrum.")
        val e = findEvent(t.ctx, a.optLong("id", -1)) ?: return Res.error("Termin mit dieser id nicht gefunden. Erst list oder search nutzen.")
        if (e.recurring) return Res.error("„${e.title}“ ist ein Serientermin. Den ändere ich nicht automatisch – bitte in der Kalender-App bearbeiten.")
        val start = if (a.has("start")) TimeLogic.parseLocal(a.optString("start")) ?: return Res.error("Neue Startzeit ungültig.") else e.begin
        val dur = if (a.has("duration_min")) a.optLong("duration_min") * 60_000 else e.end - e.begin
        val end = start + dur
        val title = a.optString("title").ifBlank { e.title }
        val loc = if (a.has("location")) a.optString("location") else e.location
        val desc = "„${e.title}“ (${TimeLogic.short(e.begin)}) ändern zu: „$title“, ${TimeLogic.day(start)} ${TimeLogic.time(start)}–${TimeLogic.time(end)}" +
            (if (loc.isNotBlank()) ", Ort $loc" else "") + "."
        if (!a.optBoolean("confirmed")) return Res.confirm(desc + clashText(t.ctx, start, end, e.id))
        val v = ContentValues().apply {
            put(CalendarContract.Events.TITLE, title); put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end); put(CalendarContract.Events.EVENT_LOCATION, loc)
        }
        val n = t.ctx.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id), v, null, null)
        return if (n > 0) Res.ok("Termin geändert: $title, ${TimeLogic.day(start)} ${TimeLogic.time(start)}–${TimeLogic.time(end)}.")
        else Res.error("Der Kalender hat die Änderung nicht angenommen.")
    }

    private fun delete(t: ToolContext, a: JSONObject): String {
        if (!t.has(Manifest.permission.WRITE_CALENDAR))
            return Res.noPermission("das Löschen von Terminen", "Bitte erlaube „Kalender“ im Jarvis-Berechtigungszentrum.")
        val e = findEvent(t.ctx, a.optLong("id", -1)) ?: return Res.error("Termin mit dieser id nicht gefunden.")
        if (e.recurring) return Res.error("„${e.title}“ ist ein Serientermin. Löschen würde die ganze Serie entfernen – bitte in der Kalender-App machen.")
        if (!a.optBoolean("confirmed")) return Res.confirm("Termin „${e.title}“ am ${TimeLogic.day(e.begin)} um ${TimeLogic.time(e.begin)} endgültig löschen.")
        val n = t.ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id), null, null)
        return if (n > 0) Res.ok("Termin „${e.title}“ gelöscht.") else Res.error("Löschen hat nicht geklappt.")
    }

    /** Für Agenda/Dashboard: Termine heute ohne Exception. */
    fun safeEvents(ctx: Context, from: Long, to: Long): List<Event> =
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            events(ctx, from, to) else emptyList()

    @Suppress("unused") private fun today(): LocalDate = LocalDate.now()
}
