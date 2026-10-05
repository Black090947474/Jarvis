package de.jarvis.app.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import de.jarvis.app.JarvisNotificationListener
import de.jarvis.app.Prefs
import de.jarvis.app.logic.TimeLogic
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.MonthDay

/** Eigene Kommandos: mehrere Aktionen unter einem Namen, z. B. „Gaming-Modus“. */
object RoutineTool : JarvisTool {
    override val name = "routines"
    override val group = "core"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"routines","description":"Eigene Kommandos (Routinen). save: unter einem Namen mehrere Werkzeug-Schritte speichern, steps = Liste von {tool, args}, z. B. [{\"tool\":\"set_volume\",\"args\":{\"percent\":80}},{\"tool\":\"play_music\",\"args\":{\"query\":\"Gaming Playlist\"}}]. run: Routine ausführen. test: nur anzeigen, was passieren würde (Testmodus). list: alle. delete: löschen (mit confirmed). Danach reicht es, den Namen zu sagen.",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["save","run","test","list","delete"]},
  "name":{"type":"string"},
  "steps":{"type":"array","items":{"type":"object","properties":{"tool":{"type":"string"},"args":{"type":"object"}}}},
  "confirmed":{"type":"boolean"}},"required":["action"]}}
""".trimIndent())

    /** Diese Werkzeuge dürfen nicht in Routinen (Endlosschleifen, Löschen, Senden ohne Rückfrage). */
    private val FORBIDDEN = setOf("routines", "second_phone", "screen", "build_website")

    fun names(ctx: Context) = JsonStore(ctx).list("routines").map { it.optString("name") }

    fun find(ctx: Context, spoken: String): JSONObject? {
        val s = spoken.lowercase().replace(Regex("[^a-zäöüß0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            .removePrefix("hey ").removePrefix("jarvis ").removePrefix("starte ").removePrefix("start ").removePrefix("aktiviere ").trim()
        return JsonStore(ctx).list("routines").firstOrNull { r ->
            val n = r.optString("name").lowercase().replace(Regex("[^a-zäöüß0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            n.isNotBlank() && (s == n || s == "$n an" || s == "$n starten" || s == "$n aktivieren")
        }
    }

    fun execute(t: ToolContext, r: JSONObject): String {
        val run = t.runner ?: return Res.error("Routinen gehen hier nicht.")
        val steps = r.optJSONArray("steps") ?: JSONArray()
        val results = (0 until minOf(steps.length(), 10)).map { i ->
            val s = steps.getJSONObject(i)
            val tool = s.optString("tool")
            val args = JSONObject(s.optJSONObject("args")?.toString() ?: "{}").apply { remove("confirmed") }
            if (tool in FORBIDDEN) "$tool: übersprungen" else "$tool: " + Res.spoken(run(tool, args)).take(100)
        }
        return Res.ok("Routine „${r.optString("name")}“ ausgeführt:\n" + results.joinToString("\n"))
    }

    override fun run(t: ToolContext, a: JSONObject): String {
        val store = JsonStore(t.ctx)
        val all = store.list("routines")
        val nm = a.optString("name").trim()
        return when (a.optString("action")) {
            "save" -> {
                if (nm.isBlank()) return Res.error("Wie soll das Kommando heißen?")
                val steps = a.optJSONArray("steps") ?: return Res.error("Welche Schritte?")
                val clean = JSONArray()
                for (i in 0 until steps.length()) {
                    val s = steps.optJSONObject(i) ?: continue
                    if (s.optString("tool") in FORBIDDEN) continue
                    s.optJSONObject("args")?.remove("confirmed") // Bestätigungen werden nie gespeichert
                    clean.put(s)
                }
                if (clean.length() == 0) return Res.error("Keine erlaubten Schritte.")
                store.save("routines", all.filter { !it.optString("name").equals(nm, true) } + JSONObject().put("name", nm).put("steps", clean))
                Res.ok("Kommando „$nm“ gespeichert (${clean.length()} Schritte). Sag einfach „$nm“.")
            }
            "test" -> {
                val r = all.firstOrNull { it.optString("name").equals(nm, true) } ?: find(t.ctx, nm) ?: return Res.error("Kommando „$nm“ gibt es nicht.")
                val s = r.optJSONArray("steps") ?: JSONArray()
                Res.ok("[Testmodus – nichts ausgeführt] „${r.optString("name")}“ würde: " + (0 until s.length()).joinToString(", ") {
                    de.jarvis.app.Automations.describeStep(s.getJSONObject(it)) })
            }
            "run" -> execute(t, all.firstOrNull { it.optString("name").equals(nm, true) } ?: find(t.ctx, nm)
                ?: return Res.error("Kommando „$nm“ gibt es nicht."))
            "delete" -> {
                val r = all.firstOrNull { it.optString("name").equals(nm, true) } ?: return Res.error("Kommando nicht gefunden.")
                if (!a.optBoolean("confirmed")) return Res.confirm("Kommando „$nm“ löschen.")
                store.save("routines", all - r); Res.ok("Kommando „$nm“ gelöscht.")
            }
            else -> if (all.isEmpty()) Res.ok("Noch keine eigenen Kommandos. Sag z. B.: „Wenn ich Gaming-Modus sage, mach Nicht stören an und Lautstärke auf 80.“")
                    else Res.ok("Eigene Kommandos:\n" + all.joinToString("\n") { r ->
                        val s = r.optJSONArray("steps") ?: JSONArray()
                        r.optString("name") + ": " + (0 until s.length()).joinToString(", ") { s.getJSONObject(it).optString("tool") } })
        }
    }
}

/** Geburtstage aus den Kontakten. */
object BirthdayTool : JarvisTool {
    override val name = "birthdays"
    override val group = "agenda"
    override val feature = "calls"
    override val permissions = listOf(Manifest.permission.READ_CONTACTS)

    override val definition: JSONObject = JSONObject("""
{"name":"birthdays","description":"Geburtstage aus den Kontakten in den nächsten Tagen (days, Standard 30).",
 "input_schema":{"type":"object","properties":{"days":{"type":"integer"}},"required":[]}}
""".trimIndent())

    /** (Name, Datum dieses Jahr/nächstes Jahr, Alter oder null) */
    fun upcoming(ctx: Context, days: Int): List<Triple<String, LocalDate, Int?>> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
        val out = mutableListOf<Triple<String, LocalDate, Int?>>()
        val today = LocalDate.now()
        try {
            ctx.contentResolver.query(ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE),
                "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ${ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY}",
                arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE), null)?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    val raw = c.getString(1) ?: continue
                    val m = Regex("""(\d{4}|-)-?-?(\d{1,2})-(\d{1,2})""").find(raw) ?: continue
                    val year = m.groupValues[1].toIntOrNull()
                    val md = runCatching { MonthDay.of(m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull() ?: continue
                    var next = runCatching { md.atYear(today.year) }.getOrNull() ?: continue
                    if (next.isBefore(today)) next = md.atYear(today.year + 1)
                    if (next.isAfter(today.plusDays(days.toLong()))) continue
                    out += Triple(n, next, year?.takeIf { it > 1900 }?.let { next.year - it })
                }
            }
        } catch (_: Exception) { }
        return out.distinctBy { it.first + it.second }.sortedBy { it.second }
    }

    fun describe(b: Triple<String, LocalDate, Int?>): String {
        val d = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), b.second)
        val whenTxt = when (d) { 0L -> "heute"; 1L -> "morgen"; else -> "in $d Tagen (${b.second.dayOfMonth}.${b.second.monthValue}.)" }
        return "${b.first} $whenTxt" + (b.third?.let { " – wird $it" } ?: "")
    }

    override fun run(t: ToolContext, a: JSONObject): String {
        if (!t.has(Manifest.permission.READ_CONTACTS)) return Res.noPermission("deine Kontakte", "Bitte erlaube „Telefon & Anrufliste“ im Berechtigungszentrum.")
        val days = a.optInt("days", 30).coerceIn(1, 366)
        val l = upcoming(t.ctx, days)
        return if (l.isEmpty()) Res.ok("Keine Geburtstage in den nächsten $days Tagen (bei den Kontakten eingetragen).")
        else Res.ok("Geburtstage:\n" + l.joinToString("\n") { describe(it) })
    }
}

/** Orte merken (z. B. wo das Auto/Fahrrad steht) und wieder hinfinden. */
object PlacesTool : JarvisTool {
    override val name = "places"
    override val group = "device"
    override val feature = "location"
    override val permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override val definition: JSONObject = JSONObject("""
{"name":"places","description":"Orte merken. save: aktuellen Standort unter einem Namen speichern (z. B. Parkplatz, Fahrrad). go: zum gespeicherten Ort navigieren (zu Fuß). list: alle Orte.",
 "input_schema":{"type":"object","properties":{"action":{"type":"string","enum":["save","go","list"]},"name":{"type":"string"}},"required":["action"]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String {
        val store = JsonStore(t.ctx)
        val places = store.list("places")
        val nm = a.optString("name").ifBlank { "Parkplatz" }
        return when (a.optString("action")) {
            "save" -> {
                val loc = Geo.lastKnown(t.ctx) ?: return Res.noPermission("deinen Standort", "Bitte Standort einschalten und im Berechtigungszentrum erlauben.")
                store.save("places", places.filter { !it.optString("name").equals(nm, true) } +
                    JSONObject().put("name", nm).put("lat", loc.first).put("lon", loc.second).put("at", System.currentTimeMillis()))
                Res.ok("Gemerkt: $nm ist hier.")
            }
            "go" -> {
                val p = places.firstOrNull { it.optString("name").equals(nm, true) } ?: places.lastOrNull()
                    ?: return Res.error("Ich habe mir noch keinen Ort gemerkt.")
                val ll = "${p.optDouble("lat")},${p.optDouble("lon")}"
                t.launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$ll&mode=w")),
                    "Navigation zu „${p.optString("name")}“ (gemerkt ${TimeLogic.short(p.optLong("at"))}) gestartet.")
            }
            else -> if (places.isEmpty()) Res.ok("Noch keine Orte gemerkt.")
                    else Res.ok(places.joinToString("\n") { "${it.optString("name")} (gemerkt ${TimeLogic.short(it.optLong("at"))})" })
        }
    }
}

/** „Was läuft gerade?“ – Lied erkennen über Google oder Shazam. */
object MusicTool : JarvisTool {
    override val name = "recognize_song"
    override val group = "core"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"recognize_song","description":"Erkennt das Lied, das gerade im Raum läuft (öffnet die Liederkennung von Google oder Shazam). Danach sagen, dass das Ergebnis auf dem Bildschirm erscheint.",
 "input_schema":{"type":"object","properties":{},"required":[]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String {
        val pm = t.ctx.packageManager
        val google = Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH")
        if (google.resolveActivity(pm) != null) return t.launch(google, "Ich höre zu – das Lied erscheint gleich auf dem Bildschirm.")
        pm.getLaunchIntentForPackage("com.shazam.android")?.let {
            return t.launch(it, "Shazam ist offen – tippe auf den großen Shazam-Knopf.")
        }
        t.launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.shazam.android")), "")
        return Res.error("Auf dem Handy ist keine Liederkennung. Ich habe Shazam im Play Store geöffnet.")
    }
}

/** Tagesüberblick: Wetter, Termine, Aufgaben, Schule, Geburtstage, Nachrichten, Einkaufsliste. */
object BriefingTool : JarvisTool {
    override val name = "briefing"
    override val group = "core"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"briefing","description":"Tagesüberblick ('Guten Morgen', 'Was gibt's heute?', 'Briefing'): Wetter, Termine, Erinnerungen, Aufgaben, Stundenplan, Geburtstage, neue Nachrichten. day: heute oder morgen.",
 "input_schema":{"type":"object","properties":{"day":{"type":"string","enum":["heute","morgen"]}},"required":[]}}
""".trimIndent())

    /** Liefert einen gut vorlesbaren Text (auch ohne KI nutzbar). */
    fun build(ctx: Context, day: String = "heute"): String {
        val p = Prefs(ctx)
        val parts = mutableListOf<String>()
        val h = java.time.LocalTime.now().hour
        if (day == "heute") parts += when (h) { in 4..10 -> "Guten Morgen"; in 11..17 -> "Hallo"; else -> "Guten Abend" } +
            (if (p.userName.isNotBlank()) ", ${p.userName}" else "") + ". Heute ist ${TimeLogic.day(System.currentTimeMillis())}."
        // Wetter (kurz)
        try {
            val w = WeatherTool.fetch(ctx)
            if (!Res.isError(w)) {
                val c = JSONObject(p.weatherCache)
                parts += "Draußen sind es ${c.optInt("t")} Grad, ${c.optString("desc")}, heute ${c.optInt("min")} bis ${c.optInt("max")} Grad" +
                    (c.optInt("rain", -1).takeIf { it >= 40 }?.let { ", Regenrisiko $it Prozent – nimm einen Schirm mit" } ?: "") + "."
            }
        } catch (_: Exception) { }
        val (from, to) = TimeLogic.range(day)
        val ag = AgendaTool.collect(ctx, from, to)
        val now = System.currentTimeMillis()
        val ev = ag.events.filter { it.allDay || it.end > now }
        if (ev.isNotEmpty()) parts += "Termine: " + ev.take(4).joinToString(", ") {
            (if (it.allDay) "" else "um ${TimeLogic.time(it.begin)} ") + it.title } + (if (ev.size > 4) " und ${ev.size - 4} weitere" else "") + "."
        else parts += "Keine Termine ${if (day == "heute") "heute" else "morgen"}."
        val dow = LocalDate.now().plusDays(if (day == "morgen") 1 else 0).dayOfWeek
        SchoolTool.summary(ctx, dow)?.let { parts += "Schule: $it." }
        val hw = AgendaStore(ctx).tasks().filter { !it.done && it.category == SchoolTool.HOMEWORK && it.due != null && it.due < to + 86_400_000L }
        if (hw.isNotEmpty()) parts += "Hausaufgaben fällig: " + hw.joinToString(", ") { it.title } + "."
        val tasks = (ag.overdue + ag.tasks).filter { it.category != SchoolTool.HOMEWORK }
        if (tasks.isNotEmpty()) parts += "Aufgaben: " + tasks.take(4).joinToString(", ") { it.title } + "."
        if (ag.reminders.isNotEmpty()) parts += "Erinnerungen: " + ag.reminders.take(3).joinToString(", ") { "${TimeLogic.time(it.next!!)} ${it.text}" } + "."
        val bd = BirthdayTool.upcoming(ctx, if (day == "heute") 0 else 1).filter { it.second == LocalDate.now().plusDays(if (day == "morgen") 1 else 0) }
        if (bd.isNotEmpty()) parts += "Geburtstag hat: " + bd.joinToString(", ") { it.first + (it.third?.let { a -> " ($a)" } ?: "") } + "."
        if (JarvisNotificationListener.isConnected && day == "heute" && JarvisNotificationListener.recent(null, 1).isNotEmpty())
            parts += NotificationHub.summary()
        val shop = ShoppingTool.items(ctx)
        if (shop.isNotEmpty()) parts += "Auf der Einkaufsliste stehen ${shop.size} Sachen."
        return parts.joinToString(" ")
    }

    override fun run(t: ToolContext, a: JSONObject): String = Res.ok(build(t.ctx, a.optString("day", "heute").ifBlank { "heute" }))
}
