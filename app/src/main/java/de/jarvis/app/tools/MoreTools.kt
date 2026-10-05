package de.jarvis.app.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import de.jarvis.app.HubActivity
import de.jarvis.app.JarvisNotificationListener
import de.jarvis.app.Prefs
import de.jarvis.app.logic.TimeLogic
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** Fahrzeit und Abfahrtszeit (OpenStreetMap: Auto, Fahrrad, zu Fuß). */
object RouteTool : JarvisTool {
    override val name = "route"
    override val group = "device"
    override val feature = "location"
    override val permissions = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)

    override val definition: JSONObject = JSONObject("""
{"name":"route","description":"Wie lange dauert es zu einem Ziel und wann muss ich losfahren? destination = Adresse, Ort oder gespeicherter Ort (Zuhause, Parkplatz …). mode: car, bike, foot. arrive_at: Ankunftszeit HH:MM oder YYYY-MM-DDTHH:MM → berechnet Abfahrtszeit. start=true startet danach die Navigation. Bus/Bahn: nicht berechenbar, dann Navigation mit mode transit öffnen.",
 "input_schema":{"type":"object","properties":{
  "destination":{"type":"string"},"mode":{"type":"string","enum":["car","bike","foot","transit"]},
  "arrive_at":{"type":"string"},"start":{"type":"boolean"}},"required":["destination"]}}
""".trimIndent())

    private const val UA = "JarvisApp/1.0 (privater Assistent)"

    private fun geocode(q: String): Pair<Double, Double>? {
        val j = JSONArray(WebTool.get("https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=de,at,ch&q=" + URLEncoder.encode(q, "UTF-8"), ua = UA))
        val o = j.optJSONObject(0) ?: return null
        return o.optString("lat").toDouble() to o.optString("lon").toDouble()
    }

    private fun known(ctx: Context, name: String): Pair<Double, Double>? {
        val p = Prefs(ctx)
        if (name.lowercase() in setOf("zuhause", "zu hause", "nach hause", "home", "daheim") && p.homeSet) return p.homeLat to p.homeLon
        return JsonStore(ctx).list("places").firstOrNull { it.optString("name").equals(name, true) }?.let { it.optDouble("lat") to it.optDouble("lon") }
    }

    override fun run(t: ToolContext, a: JSONObject): String {
        val dest = a.optString("destination").trim().ifBlank { return Res.error("Wohin?") }
        val mode = a.optString("mode", "car")
        val navMode = mapOf("car" to "d", "bike" to "b", "foot" to "w", "transit" to "r")[mode] ?: "d"
        if (mode == "transit") return t.launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest) + "&mode=r")),
            "Bus und Bahn kann ich nicht selbst berechnen – ich habe die Verbindung in Maps geöffnet.")
        val from = Geo.lastKnown(t.ctx) ?: return Res.noPermission("deinen Standort", "Bitte Standort einschalten und im Berechtigungszentrum erlauben.")
        val to = try { known(t.ctx, dest) ?: geocode(dest) } catch (e: Exception) { return Res.error("Kartendienst nicht erreichbar (${e.message}).") }
            ?: return Res.error("„$dest“ habe ich auf der Karte nicht gefunden. Nenne mir die Adresse genauer.")
        val profile = mapOf("car" to "routed-car", "bike" to "routed-bike", "foot" to "routed-foot")[mode] ?: "routed-car"
        val j = try {
            JSONObject(WebTool.get("https://routing.openstreetmap.de/$profile/route/v1/driving/${from.second},${from.first};${to.second},${to.first}?overview=false"))
        } catch (e: Exception) { return Res.error("Routenberechnung gerade nicht möglich (${e.message}).") }
        val r = j.optJSONArray("routes")?.optJSONObject(0) ?: return Res.error("Keine Route gefunden.")
        val min = (r.optDouble("duration") / 60).roundToInt()
        val km = r.optDouble("distance") / 1000
        val how = mapOf("car" to "mit dem Auto", "bike" to "mit dem Fahrrad", "foot" to "zu Fuß")[mode]
        var text = "Nach $dest ${how}: etwa ${TimeLogic.duration(min.toLong())} (%.1f km, ohne Stau).".format(km)
        a.optString("arrive_at").takeIf { it.isNotBlank() }?.let { s ->
            val z = ZoneId.systemDefault()
            val arrive = TimeLogic.parseLocal(s) ?: runCatching { LocalDate.now().atTime(LocalTime.parse(s.padStart(5, '0'))).atZone(z).toInstant().toEpochMilli() }.getOrNull()
            if (arrive != null) {
                val buffer = if (mode == "car") Prefs(t.ctx).travelBuffer.coerceAtMost(15) else 5
                val leave = arrive - (min + buffer) * 60_000L
                text += " Für Ankunft um ${TimeLogic.time(arrive)} solltest du um ${TimeLogic.time(leave)} losfahren (inkl. $buffer Min. Puffer)."
            }
        }
        if (a.optBoolean("start")) t.launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${to.first},${to.second}&mode=$navMode")), "")
        return Res.ok(text + " (Daten: OpenStreetMap)")
    }
}

/** Benachrichtigungs-Zentrum: ordnet neue Benachrichtigungen in Kategorien und erkennt Wichtiges. */
object NotificationHub {
    val CATEGORIES = listOf("Wichtig", "Schule", "Familie", "Freunde", "Arbeit", "Werbung", "System", "Sonstiges")
    private val SCHOOL = Regex("(?i)schule|lehrer|hausaufgabe|klasse|unterricht|iserv|untis|schoolfox|moodle|sdui|elternbrief|stundenplan|klausur")
    private val FAMILY = Regex("(?i)\\b(mama|papa|mutter|vater|mom|dad|oma|opa|bruder|schwester|familie|tante|onkel)\\b")
    private val WORK = Regex("(?i)meeting|chef|arbeit|teams|slack|outlook|termin bestätigt|schicht")
    private val ADS = Regex("(?i)angebot|rabatt|sale|gutschein|deal|gratis|% off|\\d+ ?%|jetzt kaufen|newsletter|nur heute|spare|aktion|black friday")
    private val URGENT = Regex("(?i)dringend|wichtig|notfall|sofort|asap|verpasster anruf|missed call|bitte ruf")
    private val MESSENGER = Regex("(?i)whatsapp|instagram|snapchat|telegram|signal|discord|messages|nachrichten|messenger|threema|tiktok")
    private val SHOP = Regex("(?i)amazon|ebay|temu|shein|zalando|lieferando|wish|aliexpress|otto|kleinanzeigen|lidl|aldi|rewe")
    private val SYSTEM = Regex("^(android|com\\.android|com\\.samsung\\.android\\.(?!messaging)|com\\.google\\.android\\.(gms|apps\\.wellbeing|setupwizard)|com\\.sec\\.)")

    fun category(m: JarvisNotificationListener.Msg): String {
        val all = "${m.title} ${m.text}"
        return when {
            URGENT.containsMatchIn(all) || FAMILY.containsMatchIn(m.title) && URGENT.containsMatchIn(m.text) -> "Wichtig"
            SCHOOL.containsMatchIn(all) || SCHOOL.containsMatchIn(m.app) -> "Schule"
            FAMILY.containsMatchIn(m.title) -> "Familie"
            SHOP.containsMatchIn(m.app) || ADS.containsMatchIn(all) -> "Werbung"
            SYSTEM.containsMatchIn(m.pkg) -> "System"
            WORK.containsMatchIn(all) || WORK.containsMatchIn(m.app) -> "Arbeit"
            MESSENGER.containsMatchIn(m.app) || MESSENGER.containsMatchIn(m.pkg) -> "Freunde"
            else -> "Sonstiges"
        }
    }

    fun grouped(): Map<String, List<JarvisNotificationListener.Msg>> =
        JarvisNotificationListener.recent(null, 40).groupBy { category(it) }.toSortedMap(compareBy { CATEGORIES.indexOf(it) })

    fun summary(): String {
        val g = grouped(); val total = g.values.sumOf { it.size }
        if (total == 0) return "Keine neuen Benachrichtigungen."
        val important = (g["Wichtig"]?.size ?: 0) + (g["Familie"]?.size ?: 0) + (g["Schule"]?.size ?: 0)
        return "Du hast $total Benachrichtigungen" + (if (important > 0) ", $important davon wichtig" else "") + ": " +
            g.entries.joinToString(", ") { "${it.key} ${it.value.size}" } + "."
    }
}

object NotifyHubTool : JarvisTool {
    override val name = "notification_hub"
    override val group = "comm"
    override val feature = "notifications_read"
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"notification_hub","description":"Benachrichtigungen nach Kategorien (Wichtig, Schule, Familie, Freunde, Arbeit, Werbung, System) mit Zusammenfassung, z. B. 'Was ist wichtig?', 'Hab ich was Wichtiges verpasst?'. category optional zum Filtern.",
 "input_schema":{"type":"object","properties":{"category":{"type":"string"}},"required":[]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String {
        if (!JarvisNotificationListener.isConnected)
            return Res.noPermission("deine Benachrichtigungen", "Bitte „Benachrichtigungen lesen“ im Berechtigungszentrum erlauben.")
        val cat = a.optString("category").trim()
        val g = NotificationHub.grouped().filterKeys { cat.isBlank() || it.equals(cat, true) }
        if (g.isEmpty()) return Res.ok(if (cat.isBlank()) "Keine neuen Benachrichtigungen." else "Nichts in „$cat“.")
        return Res.ok(NotificationHub.summary() + "\n" + g.entries.joinToString("\n") { (k, v) ->
            "$k:\n" + v.take(8).joinToString("\n") { "  • ${it.app} | ${it.title}: ${it.text.take(140)}" } })
    }
}

/** „Ich brauche Hilfe“: öffnet den Notfall-Bereich (wählt nichts automatisch). */
object EmergencyTool : JarvisTool {
    override val name = "emergency"
    override val group = "core"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"emergency","description":"Notfall: öffnet sofort den Notfall-Bildschirm (112, 110, Notfallkontakt, Standort senden). Bei 'Ich brauche Hilfe', 'Notfall', Gefahr sofort nutzen und ruhig sagen, dass 112 der Notruf ist.",
 "input_schema":{"type":"object","properties":{},"required":[]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String =
        t.launch(Intent(t.ctx, HubActivity::class.java).putExtra("page", "emergency"),
            "Der Notfall-Bildschirm ist offen. Bei Lebensgefahr sofort 112 wählen – der Knopf ist ganz oben.")
}
