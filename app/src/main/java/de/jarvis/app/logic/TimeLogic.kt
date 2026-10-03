package de.jarvis.app.logic

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reine Zeit-Logik ohne Android-Abhängigkeiten (dadurch auf dem PC testbar):
 * freie Zeiten, Überschneidungen, Wiederholungen, Datums-Parsing, Formatierung.
 */
object TimeLogic {

    /** Ein Zeitraum in Millisekunden (Epoch). */
    data class Span(val start: Long, val end: Long, val title: String = "", val id: Long = 0) {
        val minutes: Long get() = (end - start) / 60_000
    }

    // ---------- Parsing ----------

    /** Akzeptiert "2026-10-04T16:00", "2026-10-04 16:00", "2026-10-04" (dann 00:00). */
    fun parseLocal(s: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val t = s.trim().replace(' ', 'T')
        if (t.isEmpty()) return null
        return try {
            val ldt = if (t.length <= 10) LocalDate.parse(t).atStartOfDay()
                      else LocalDateTime.parse(t.take(16))
            ldt.atZone(zone).toInstant().toEpochMilli()
        } catch (_: Exception) { null }
    }

    fun dayBounds(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> =
        date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** "heute", "morgen", "woche", "monat" oder ein Datum → Zeitraum. */
    fun range(name: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (name.lowercase(Locale.GERMANY).trim()) {
            "", "heute", "today" -> dayBounds(today, zone)
            "morgen", "tomorrow" -> dayBounds(today.plusDays(1), zone)
            "übermorgen" -> dayBounds(today.plusDays(2), zone)
            "woche", "week", "diese woche" -> today.atStartOfDay(zone).toInstant().toEpochMilli() to
                today.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            "monat", "month" -> today.atStartOfDay(zone).toInstant().toEpochMilli() to
                today.plusDays(31).atStartOfDay(zone).toInstant().toEpochMilli()
            else -> parseLocal(name, zone)?.let { val d = Instant.ofEpochMilli(it).atZone(zone).toLocalDate(); dayBounds(d, zone) }
                ?: dayBounds(today, zone)
        }
    }

    // ---------- Freie Zeiten & Konflikte ----------

    /**
     * Freie Zeitfenster zwischen [from, to], wobei Termine um [bufferMin] Minuten Puffer
     * erweitert werden. Nur Fenster mit mindestens [minMinutes] Länge.
     */
    fun freeSlots(busy: List<Span>, from: Long, to: Long, minMinutes: Long = 30, bufferMin: Long = 0): List<Span> {
        val buf = bufferMin * 60_000
        val blocks = busy.map { Span(it.start - buf, it.end + buf) }
            .filter { it.end > from && it.start < to }
            .sortedBy { it.start }
        val out = mutableListOf<Span>()
        var cursor = from
        for (b in blocks) {
            if (b.start > cursor) out += Span(cursor, minOf(b.start, to))
            cursor = maxOf(cursor, b.end)
            if (cursor >= to) break
        }
        if (cursor < to) out += Span(cursor, to)
        return out.filter { it.minutes >= minMinutes }
    }

    /** Freie Zeiten für mehrere Tage, jeweils im Tagesfenster [dayStart]–[dayEnd]. */
    fun freeSlotsDays(
        busy: List<Span>, days: List<LocalDate>, dayStart: LocalTime, dayEnd: LocalTime,
        minMinutes: Long, bufferMin: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(),
    ): List<Span> = days.flatMap { d ->
        val s = maxOf(d.atTime(dayStart).atZone(zone).toInstant().toEpochMilli(), now)
        val e = d.atTime(dayEnd).atZone(zone).toInstant().toEpochMilli()
        if (e <= s) emptyList() else freeSlots(busy, s, e, minMinutes, bufferMin)
    }

    /** Termine, die sich mit [start]–[end] (inkl. Puffer) überschneiden. */
    fun conflicts(busy: List<Span>, start: Long, end: Long, bufferMin: Long = 0, ignoreId: Long = -1): List<Span> {
        val buf = bufferMin * 60_000
        return busy.filter { it.id != ignoreId && it.start < end + buf && it.end > start - buf }
    }

    /** Alle Paare von Terminen, die sich überschneiden (für Konflikt-Warnungen). */
    fun overlappingPairs(events: List<Span>): List<Pair<Span, Span>> {
        val s = events.sortedBy { it.start }
        val out = mutableListOf<Pair<Span, Span>>()
        for (i in s.indices) for (j in i + 1 until s.size) {
            if (s[j].start >= s[i].end) break
            out += s[i] to s[j]
        }
        return out
    }

    // ---------- Wiederholungen ----------

    enum class Repeat { NONE, DAILY, WEEKDAYS, WEEKLY, MONTHLY }

    fun parseRepeat(s: String?): Repeat = when (s?.lowercase(Locale.GERMANY)?.trim()) {
        "daily", "täglich", "jeden tag" -> Repeat.DAILY
        "weekdays", "werktags", "wochentags" -> Repeat.WEEKDAYS
        "weekly", "wöchentlich", "jede woche" -> Repeat.WEEKLY
        "monthly", "monatlich", "jeden monat" -> Repeat.MONTHLY
        else -> Repeat.NONE
    }

    fun parseDay(s: String): DayOfWeek? = when (s.lowercase(Locale.GERMANY).take(2)) {
        "mo" -> DayOfWeek.MONDAY; "di", "tu" -> DayOfWeek.TUESDAY; "mi", "we" -> DayOfWeek.WEDNESDAY
        "do", "th" -> DayOfWeek.THURSDAY; "fr" -> DayOfWeek.FRIDAY; "sa" -> DayOfWeek.SATURDAY
        "so", "su" -> DayOfWeek.SUNDAY; else -> null
    }

    /**
     * Nächster Zeitpunkt nach [after] für eine Erinnerung, die zuerst bei [first] fällig war.
     * Bei WEEKLY mit [days] werden alle genannten Wochentage berücksichtigt.
     */
    fun nextOccurrence(first: Long, repeat: Repeat, days: Set<DayOfWeek>, after: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (repeat == Repeat.NONE) return if (first > after) first else null
        val base = Instant.ofEpochMilli(first).atZone(zone)
        val time = base.toLocalTime()
        var d: LocalDate = maxOf(base.toLocalDate(), Instant.ofEpochMilli(after).atZone(zone).toLocalDate())
        repeat(400) {
            val ok = when (repeat) {
                Repeat.DAILY -> true
                Repeat.WEEKDAYS -> d.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                Repeat.WEEKLY -> (if (days.isEmpty()) setOf(base.dayOfWeek) else days).contains(d.dayOfWeek)
                Repeat.MONTHLY -> d.dayOfMonth == minOf(base.dayOfMonth, d.lengthOfMonth())
                Repeat.NONE -> false
            }
            if (ok) {
                val t = ZonedDateTime.of(d, time, zone).toInstant().toEpochMilli()
                if (t > after) return t
            }
            d = d.plusDays(1)
        }
        return null
    }

    // ---------- Formatierung (deutsch, gut vorlesbar) ----------

    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)
    private val DAY = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMANY)
    private val SHORT = DateTimeFormatter.ofPattern("EE d.M. HH:mm", Locale.GERMANY)

    fun time(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = TIME.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun day(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = DAY.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun short(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = SHORT.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun iso(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").format(Instant.ofEpochMilli(ms).atZone(zone))

    fun duration(min: Long): String {
        val h = min / 60; val m = min % 60
        return when { h == 0L -> "$m Min."; m == 0L -> "$h Std."; else -> "$h Std. $m Min." }
    }

    fun spanText(s: Span, zone: ZoneId = ZoneId.systemDefault()): String =
        "${short(s.start, zone)}–${time(s.end, zone)} (${duration(Duration.ofMillis(s.end - s.start).toMinutes())})"
}
