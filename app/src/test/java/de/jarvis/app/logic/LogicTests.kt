package de.jarvis.app.logic

import de.jarvis.app.logic.TimeLogic.Span
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Tests für die reine Logik. Laufen ohne Android:
 *   kotlinc logic/TimeLogic.kt logic/LocalCommands.kt LogicTests.kt -include-runtime -d t.jar && java -cp t.jar de.jarvis.app.logic.LogicTestsKt
 */
private val Z = ZoneId.of("Europe/Berlin")
private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, info: () -> String = { "" }) {
    if (cond) { passed++; println("  ✓ $name") } else { failed++; println("  ✗ $name  ${info()}") }
}

private fun at(s: String) = TimeLogic.parseLocal(s, Z)!!

fun main() {
    println("TimeLogic.parseLocal")
    check("ISO mit T", TimeLogic.parseLocal("2026-10-04T16:00", Z) == at("2026-10-04 16:00"))
    check("nur Datum = Mitternacht", TimeLogic.time(at("2026-10-04"), Z) == "00:00")
    check("Unsinn = null", TimeLogic.parseLocal("morgen irgendwann", Z) == null)

    println("TimeLogic.freeSlots")
    val busy = listOf(
        Span(at("2026-10-05T10:00"), at("2026-10-05T11:00"), "Mathe", 1),
        Span(at("2026-10-05T14:30"), at("2026-10-05T15:30"), "Zahnarzt", 2),
    )
    val free = TimeLogic.freeSlots(busy, at("2026-10-05T08:00"), at("2026-10-05T18:00"), 30, 0)
    check("3 freie Fenster", free.size == 3) { free.joinToString { TimeLogic.spanText(it, Z) } }
    check("erstes Fenster 08:00–10:00", free.getOrNull(0)?.let { TimeLogic.time(it.start, Z) == "08:00" && TimeLogic.time(it.end, Z) == "10:00" } == true)
    check("letztes Fenster 15:30–18:00", free.lastOrNull()?.let { TimeLogic.time(it.start, Z) == "15:30" && TimeLogic.time(it.end, Z) == "18:00" } == true)
    val freeBuf = TimeLogic.freeSlots(busy, at("2026-10-05T08:00"), at("2026-10-05T18:00"), 30, 15)
    check("Puffer 15 min verkürzt Fenster", freeBuf[0].end == at("2026-10-05T09:45") && freeBuf[1].start == at("2026-10-05T11:15"))
    val tiny = TimeLogic.freeSlots(busy, at("2026-10-05T08:00"), at("2026-10-05T18:00"), 240, 0)
    check("Mindestlänge 4 h filtert alles kleinere", tiny.size == 0) { tiny.toString() }
    val overlapBusy = busy + Span(at("2026-10-05T10:30"), at("2026-10-05T12:00"), "Überlappend", 3)
    val f2 = TimeLogic.freeSlots(overlapBusy, at("2026-10-05T08:00"), at("2026-10-05T18:00"), 30, 0)
    check("überlappende Termine werden zusammengefasst", f2[1].start == at("2026-10-05T12:00"))

    println("TimeLogic.freeSlotsDays")
    val days = listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6))
    val fd = TimeLogic.freeSlotsDays(busy, days, LocalTime.of(14, 0), LocalTime.of(18, 0), 60, 0, now = at("2026-10-05T07:00"), zone = Z)
    check("freier Nachmittag 6.10. komplett", fd.any { it.start == at("2026-10-06T14:00") && it.end == at("2026-10-06T18:00") })
    val fdNow = TimeLogic.freeSlotsDays(busy, days, LocalTime.of(8, 0), LocalTime.of(18, 0), 30, 0, now = at("2026-10-05T16:00"), zone = Z)
    check("Vergangenes wird ignoriert (ab jetzt)", fdNow.first().start == at("2026-10-05T16:00"))

    println("TimeLogic.conflicts")
    val c = TimeLogic.conflicts(busy, at("2026-10-05T15:00"), at("2026-10-05T16:00"))
    check("Konflikt mit Zahnarzt erkannt", c.size == 1 && c[0].title == "Zahnarzt")
    check("kein Konflikt direkt danach", TimeLogic.conflicts(busy, at("2026-10-05T15:30"), at("2026-10-05T16:00")).isEmpty())
    check("Konflikt durch Puffer", TimeLogic.conflicts(busy, at("2026-10-05T15:40"), at("2026-10-05T16:00"), 15).size == 1)
    check("eigener Termin wird beim Verschieben ignoriert", TimeLogic.conflicts(busy, at("2026-10-05T14:45"), at("2026-10-05T15:45"), 0, ignoreId = 2).isEmpty())
    val pairs = TimeLogic.overlappingPairs(overlapBusy)
    check("Überschneidungs-Paar gefunden", pairs.size == 1 && pairs[0].second.title == "Überlappend")

    println("TimeLogic.nextOccurrence")
    val sun = at("2026-10-04T18:00") // Sonntag
    check("einmalig in Zukunft", TimeLogic.nextOccurrence(sun, TimeLogic.Repeat.NONE, emptySet(), at("2026-10-04T10:00"), Z) == sun)
    check("einmalig vorbei = null", TimeLogic.nextOccurrence(sun, TimeLogic.Repeat.NONE, emptySet(), at("2026-10-04T19:00"), Z) == null)
    check("täglich → nächster Tag", TimeLogic.nextOccurrence(sun, TimeLogic.Repeat.DAILY, emptySet(), sun, Z) == at("2026-10-05T18:00"))
    check("wöchentlich → nächster Sonntag", TimeLogic.nextOccurrence(sun, TimeLogic.Repeat.WEEKLY, emptySet(), sun, Z) == at("2026-10-11T18:00"))
    check("Mo+Mi → Montag", TimeLogic.nextOccurrence(sun, TimeLogic.Repeat.WEEKLY, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), sun, Z) == at("2026-10-05T18:00"))
    check("werktags überspringt Wochenende", TimeLogic.nextOccurrence(at("2026-10-09T07:00"), TimeLogic.Repeat.WEEKDAYS, emptySet(), at("2026-10-09T08:00"), Z) == at("2026-10-12T07:00"))
    check("monatlich am 31. → Monatsende", TimeLogic.nextOccurrence(at("2026-10-31T09:00"), TimeLogic.Repeat.MONTHLY, emptySet(), at("2026-10-31T10:00"), Z) == at("2026-11-30T09:00"))
    check("Zeitumstellung (25.10.) behält 18:00", TimeLogic.time(TimeLogic.nextOccurrence(at("2026-10-24T18:00"), TimeLogic.Repeat.DAILY, emptySet(), at("2026-10-24T19:00"), Z)!!, Z) == "18:00")
    check("parseRepeat deutsch", TimeLogic.parseRepeat("wöchentlich") == TimeLogic.Repeat.WEEKLY && TimeLogic.parseRepeat("werktags") == TimeLogic.Repeat.WEEKDAYS)
    check("parseDay", TimeLogic.parseDay("Sonntag") == DayOfWeek.SUNDAY && TimeLogic.parseDay("mi") == DayOfWeek.WEDNESDAY)

    println("TimeLogic.range")
    val r = TimeLogic.range("morgen", at("2026-10-04T12:00"), Z)
    check("morgen = 5.10. 00:00–24:00", r.first == at("2026-10-05") && r.second == at("2026-10-06"))
    check("woche = 7 Tage", TimeLogic.range("woche", at("2026-10-04T12:00"), Z).let { it.second - it.first == 7L * 86_400_000 })

    println("LocalCommands")
    fun p(s: String) = LocalCommands.parse(s)
    check("Timer 10 Minuten", p("Timer 10 Minuten")?.let { it.tool == "set_timer" && it.args["seconds"] == 600 } == true)
    check("Timer fünf Minuten (Wort)", p("stell einen Timer auf fünf Minuten")?.args?.get("seconds") == 300)
    check("Timer 1 Stunde 30 Minuten", p("timer 1 stunde 30 minuten")?.args?.get("seconds") == 5400)
    check("Wecker um 7", p("Wecker um 7")?.let { it.args["hour"] == 7 && it.args["minute"] == 0 } == true)
    check("Weck mich um 6:30", p("Hey Jarvis weck mich um 6:30")?.let { it.args["hour"] == 6 && it.args["minute"] == 30 } == true)
    check("Wecker halb acht", p("wecker auf halb acht")?.let { it.args["hour"] == 7 && it.args["minute"] == 30 } == true)
    check("Wecker 18 Uhr 15", p("wecker um 18 uhr 15")?.let { it.args["hour"] == 18 && it.args["minute"] == 15 } == true)
    check("Taschenlampe an", p("Taschenlampe an")?.args?.get("on") == true)
    check("Taschenlampe aus", p("mach die taschenlampe aus")?.args?.get("on") == false)
    check("Erinnerung in 20 Minuten", p("erinnere mich in 20 minuten an die Wäsche")?.let {
        it.tool == "reminders" && it.args["in_minutes"] == 20 && it.args["text"] == "die wäsche" } == true)
    check("Erinnerung morgen um 18 Uhr", p("Erinnere mich morgen um 18 Uhr daran, zu lernen")?.let {
        it.args["hour"] == 18 && it.args["tomorrow"] == true && (it.args["text"] as String).contains("lernen") } == true)
    check("Was steht heute an", p("Jarvis, was steht heute an?")?.tool == "agenda")
    check("Was hab ich morgen", p("was hab ich morgen")?.args?.get("range") == "morgen")
    check("Unbekannt = null", p("Erzähl mir einen Witz") == null)
    check("Guten Morgen = Briefing", p("Guten Morgen Jarvis")?.tool == "briefing")
    check("Einkaufsliste add mehrere", p("Setz Milch und Eier auf die Einkaufsliste")?.let {
        it.tool == "shopping" && it.args["items"] == listOf("milch", "eier") } == true) { p("Setz Milch und Eier auf die Einkaufsliste").toString() }
    check("Kurzform Einkaufsliste", p("Brot auf die Einkaufsliste")?.args?.get("items") == listOf("brot"))
    check("Einkaufsliste zeigen", p("Was steht auf der Einkaufsliste?")?.args?.get("action") == "list")
    check("Parkplatz merken", p("Merk dir, wo ich geparkt habe")?.args?.get("action") == "save")
    check("Parkplatz finden", p("Wo hab ich geparkt?")?.args?.get("action") == "go")
    check("Notiz", p("Notiere: Mama anrufen wegen Sonntag")?.let { it.tool == "notes" && (it.args["text"] as String).startsWith("mama") } == true)

    println("\n$passed bestanden, $failed fehlgeschlagen")
    if (failed > 0) kotlin.system.exitProcess(1)
}
