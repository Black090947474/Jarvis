package de.jarvis.app.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import de.jarvis.app.Prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/** Wetter über Open-Meteo (kostenlos, ohne Schlüssel). */
object WeatherTool : JarvisTool {
    override val name = "weather"
    override val group = "info"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"weather","description":"Aktuelles Wetter und Vorhersage (heute + 2 Tage). Ohne place: am aktuellen Standort bzw. Zuhause.",
 "input_schema":{"type":"object","properties":{"place":{"type":"string","description":"Optional: Ort, z. B. Berlin"}},"required":[]}}
""".trimIndent())

    fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "JarvisApp/1.0")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    fun describe(code: Int): String = when (code) {
        0 -> "klar"; 1 -> "überwiegend klar"; 2 -> "teils bewölkt"; 3 -> "bewölkt"
        45, 48 -> "Nebel"; 51, 53, 55 -> "Nieselregen"; 56, 57 -> "gefrierender Niesel"
        61 -> "leichter Regen"; 63 -> "Regen"; 65 -> "starker Regen"; 66, 67 -> "gefrierender Regen"
        71 -> "leichter Schneefall"; 73 -> "Schneefall"; 75 -> "starker Schneefall"; 77 -> "Schneegriesel"
        80 -> "leichte Schauer"; 81 -> "Schauer"; 82 -> "heftige Schauer"; 85, 86 -> "Schneeschauer"
        95 -> "Gewitter"; 96, 99 -> "Gewitter mit Hagel"; else -> "wechselhaft"
    }

    /** Ort → (lat, lon, Name). */
    private fun resolve(ctx: Context, place: String): Triple<Double, Double, String>? {
        if (place.isNotBlank()) {
            val j = JSONObject(http("https://geocoding-api.open-meteo.com/v1/search?count=1&language=de&name=" + URLEncoder.encode(place, "UTF-8")))
            val r = j.optJSONArray("results")?.optJSONObject(0) ?: return null
            return Triple(r.getDouble("latitude"), r.getDouble("longitude"), r.optString("name", place))
        }
        val p = Prefs(ctx)
        Geo.lastKnown(ctx)?.let { return Triple(it.first, it.second, "hier") }
        if (p.homeSet) return Triple(p.homeLat, p.homeLon, "zu Hause")
        return null
    }

    /** Holt das Wetter. Ergebnis: Text oder FEHLER. Speichert eine Kurzfassung fürs Dashboard. */
    fun fetch(ctx: Context, place: String = ""): String {
        val loc = try { resolve(ctx, place) } catch (e: Exception) { return Res.error("Wetterdienst nicht erreichbar (${e.message}).") }
            ?: return Res.error(if (place.isNotBlank()) "Ort „$place“ nicht gefunden." else
                "Ich kenne deinen Standort nicht. Erlaube Standort oder nenne einen Ort.")
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${loc.first}&longitude=${loc.second}" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=3"
        val j = try { JSONObject(http(url)) } catch (e: Exception) { return Res.error("Wetterdienst nicht erreichbar (${e.message}).") }
        val cur = j.optJSONObject("current") ?: return Res.error("Keine Wetterdaten erhalten.")
        val d = j.optJSONObject("daily")
        val t = cur.optDouble("temperature_2m").roundToInt()
        val feels = cur.optDouble("apparent_temperature").roundToInt()
        val desc = describe(cur.optInt("weather_code"))
        val sb = StringBuilder("Wetter ${loc.third}: $t °C, $desc (gefühlt $feels °C, Wind ${cur.optDouble("wind_speed_10m").roundToInt()} km/h).")
        if (d != null) {
            val names = listOf("Heute", "Morgen", "Übermorgen")
            for (i in 0 until minOf(3, d.optJSONArray("time")?.length() ?: 0)) {
                sb.append("\n").append(names[i]).append(": ")
                    .append(describe(d.getJSONArray("weather_code").optInt(i))).append(", ")
                    .append(d.getJSONArray("temperature_2m_min").optDouble(i).roundToInt()).append(" bis ")
                    .append(d.getJSONArray("temperature_2m_max").optDouble(i).roundToInt()).append(" °C")
                val rain = d.optJSONArray("precipitation_probability_max")?.optInt(i, -1) ?: -1
                if (rain >= 0) sb.append(", Regenrisiko $rain %")
            }
        }
        if (place.isBlank()) {
            val p = Prefs(ctx)
            p.weatherCache = JSONObject().put("t", t).put("desc", desc).put("place", loc.third)
                .put("max", d?.optJSONArray("temperature_2m_max")?.optDouble(0)?.roundToInt() ?: t)
                .put("min", d?.optJSONArray("temperature_2m_min")?.optDouble(0)?.roundToInt() ?: t)
                .put("rain", d?.optJSONArray("precipitation_probability_max")?.optInt(0, -1) ?: -1).toString()
            p.weatherTime = System.currentTimeMillis()
        }
        return Res.ok(sb.toString())
    }

    override fun run(t: ToolContext, a: JSONObject): String = fetch(t.ctx, a.optString("place"))
}

/** „Was ist in meiner Nähe?“ – öffnet die Kartensuche rund um den Standort. */
object NearbyTool : JarvisTool {
    override val name = "nearby"
    override val group = "device"
    override val feature = "location"
    override val permissions = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)

    override val definition: JSONObject = JSONObject("""
{"name":"nearby","description":"Sucht Orte in der Nähe des Nutzers (z. B. Supermarkt, Tankstelle, Apotheke, Döner) und öffnet die Ergebnisse in Google Maps.",
 "input_schema":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String {
        val q = a.optString("query").ifBlank { return Res.error("Wonach soll ich suchen?") }
        val loc = Geo.lastKnown(t.ctx)
        val uri = if (loc != null) "geo:${loc.first},${loc.second}?q=" + Uri.encode(q) else "geo:0,0?q=" + Uri.encode("$q in der Nähe")
        return t.launch(Intent(Intent.ACTION_VIEW, Uri.parse(uri)),
            "Karte mit „$q“ in deiner Nähe ist offen." + (if (loc == null) " (Standort unbekannt – Maps sucht selbst.)" else ""))
    }
}
