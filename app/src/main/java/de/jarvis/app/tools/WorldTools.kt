package de.jarvis.app.tools

import android.app.ActivityManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

private fun get(url: String, timeout: Int = 10_000): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = timeout; c.readTimeout = timeout
    c.setRequestProperty("User-Agent", "JarvisApp/1.0")
    try {
        if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().readText()
    } finally { c.disconnect() }
}

/** Live-Weltassistent: Nachrichten, Börse, Fußball, Sonne, Flugzeuge über dir. */
object LiveTool : JarvisTool {
    override val name = "live_info"
    override val group = "info"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"live_info","description":"Aktuelle Live-Infos. news: Top-Nachrichten (tagesschau). stock: Kurs (query = Firma/Index/Krypto, z. B. Apple, Tesla, DAX, Bitcoin, oder Stooq-Kürzel wie aapl.us). football: Bundesliga-Spieltag mit Ergebnissen (league bl1/bl2) oder table. sun: Sonnenauf- und -untergang heute. planes: Flugzeuge gerade über dem Standort. Für alles andere web nutzen.",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["news","stock","football","sun","planes"]},
  "query":{"type":"string"},"league":{"type":"string"},"table":{"type":"boolean"}},"required":["action"]}}
""".trimIndent())

    private val SYMBOLS = mapOf("apple" to "aapl.us", "tesla" to "tsla.us", "nvidia" to "nvda.us", "microsoft" to "msft.us", "amazon" to "amzn.us",
        "google" to "googl.us", "alphabet" to "googl.us", "meta" to "meta.us", "netflix" to "nflx.us", "amd" to "amd.us", "intel" to "intc.us",
        "dax" to "^dax", "dow" to "^dji", "nasdaq" to "^ndq", "s&p" to "^spx", "sp500" to "^spx", "bitcoin" to "btcusd", "ethereum" to "ethusd",
        "sap" to "sap.de", "siemens" to "sie.de", "volkswagen" to "vow3.de", "vw" to "vow3.de", "bmw" to "bmw.de", "mercedes" to "mbg.de",
        "allianz" to "alv.de", "adidas" to "ads.de", "puma" to "pum.de", "deutsche bank" to "dbk.de", "telekom" to "dte.de", "rheinmetall" to "rhm.de",
        "gold" to "xauusd", "euro" to "eurusd", "dollar" to "eurusd")

    override fun run(t: ToolContext, a: JSONObject): String { return try { when (a.optString("action")) {
        "news" -> {
            val j = JSONObject(get("https://www.tagesschau.de/api2u/homepage/"))
            val n = j.optJSONArray("news") ?: JSONArray()
            val items = (0 until n.length()).map { n.getJSONObject(it) }.filter { it.optString("title").isNotBlank() }.take(7)
            if (items.isEmpty()) Res.error("Gerade keine Nachrichten abrufbar.")
            else Res.ok("Top-Nachrichten (tagesschau):\n" + items.joinToString("\n") { i ->
                "• " + (i.optString("topline").takeIf { it.isNotBlank() }?.let { "$it: " } ?: "") + i.optString("title") })
        }
        "stock" -> {
            val q = a.optString("query").trim()
            if (q.isBlank()) return Res.error("Welche Aktie oder welcher Index?")
            val sym = de.jarvis.app.Market.symbol(q) ?: return Res.error("Für „$q“ habe ich kein Kürzel gefunden. Versuch den Firmennamen.")
            val price = de.jarvis.app.Market.quote(sym) ?: return Res.error("Kein Kurs für „$q“ gefunden.")
            val c = try { de.jarvis.app.Market.closes(t.ctx, sym) } catch (_: Exception) { emptyList() }
            val ch = if (c.size >= 2) (price / c[c.size - 2] - 1) * 100 else null
            Res.ok("${de.jarvis.app.Market.name(sym)}: %.2f %s".format(Locale.GERMANY, price, if (de.jarvis.app.Market.isUsd(sym)) "$" else "€") +
                (ch?.let { " (%+.2f %% zum Vortag)".format(Locale.GERMANY, it) } ?: "") + ". Kein Anlage-Rat – nur zur Info.")
        }
        "football" -> {
            val lg = a.optString("league").ifBlank { "bl1" }
            if (a.optBoolean("table")) {
                val season = java.time.LocalDate.now().let { if (it.monthValue >= 7) it.year else it.year - 1 }
                val arr = JSONArray(get("https://api.openligadb.de/getbltable/$lg/$season"))
                Res.ok("Tabelle:\n" + (0 until minOf(arr.length(), 18)).joinToString("\n") { i -> val o = arr.getJSONObject(i)
                    "${i + 1}. ${o.optString("teamName")} – ${o.optInt("points")} Pkt. (${o.optInt("goals")}:${o.optInt("opponentGoals")})" })
            } else {
                val arr = JSONArray(get("https://api.openligadb.de/getmatchdata/$lg"))
                Res.ok("Aktueller Spieltag:\n" + (0 until arr.length()).joinToString("\n") { i -> val m = arr.getJSONObject(i)
                    val res = m.optJSONArray("matchResults")
                    val end = (0 until (res?.length() ?: 0)).map { res!!.getJSONObject(it) }.maxByOrNull { it.optInt("resultOrderID") }
                    val score = end?.let { "${it.optInt("pointsTeam1")}:${it.optInt("pointsTeam2")}" } ?: m.optString("matchDateTime").replace("T", " ").take(16)
                    "${m.optJSONObject("team1")?.optString("shortName")} – ${m.optJSONObject("team2")?.optString("shortName")}  $score" +
                        (if (end != null && !m.optBoolean("matchIsFinished")) " (läuft)" else "") })
            }
        }
        "sun" -> {
            val loc = Geo.lastKnown(t.ctx) ?: return Res.error("Ich kenne deinen Standort nicht.")
            val j = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=${loc.first}&longitude=${loc.second}&daily=sunrise,sunset,daylight_duration&timezone=auto&forecast_days=1"))
            val d = j.getJSONObject("daily")
            val up = d.getJSONArray("sunrise").getString(0).substringAfter('T'); val down = d.getJSONArray("sunset").getString(0).substringAfter('T')
            val len = d.optJSONArray("daylight_duration")?.optDouble(0)?.let { (it / 3600).toInt().toString() + " Std. " + ((it % 3600) / 60).toInt() + " Min." }
            Res.ok("Sonnenaufgang $up, Sonnenuntergang $down" + (len?.let { " · Tageslicht $it" } ?: "") + ".")
        }
        "planes" -> {
            val loc = Geo.lastKnown(t.ctx) ?: return Res.error("Ich kenne deinen Standort nicht.")
            val d = 0.45
            val j = JSONObject(get("https://opensky-network.org/api/states/all?lamin=${loc.first - d}&lomin=${loc.second - d * 1.5}&lamax=${loc.first + d}&lomax=${loc.second + d * 1.5}", 15_000))
            val s = j.optJSONArray("states") ?: JSONArray()
            val list = (0 until s.length()).map { s.getJSONArray(it) }.filter { !it.optBoolean(8) }   // nicht am Boden
                .map { p -> val dLat = p.optDouble(6) - loc.first; val dLon = (p.optDouble(5) - loc.second) * Math.cos(Math.toRadians(loc.first))
                    Triple(p, Math.sqrt(dLat * dLat + dLon * dLon) * 111.0, p.optDouble(7, 0.0)) }
                .sortedBy { it.second }.take(6)
            if (list.isEmpty()) Res.ok("Gerade fliegt kein Flugzeug in der Nähe (ca. 50 km).")
            else Res.ok("Flugzeuge in der Nähe:\n" + list.joinToString("\n") { (p, km, alt) ->
                "• ${p.optString(1).trim().ifBlank { "?" }} aus ${p.optString(2)} – ${km.toInt()} km entfernt, ${(alt / 1000).let { "%.1f".format(Locale.GERMANY, it) }} km hoch, ${(p.optDouble(9, 0.0) * 3.6).toInt()} km/h" } +
                "\n(Daten: OpenSky Network)")
        }
        else -> Res.error("Unbekannte Aktion.")
    } } catch (e: Exception) { Res.error("Live-Daten gerade nicht erreichbar (${e.message?.take(60)}).") } }
}

/** Handy-Check: Temperatur, Internet-Tempo, WLAN, Speicher, große Dateien, doppelte Bilder, Systeminfo. */
object PhoneCheckTool : JarvisTool {
    override val name = "phone_check"
    override val group = "device"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"phone_check","description":"Handy analysieren. temperature (Akku-/Handytemperatur), speedtest (Internet-Geschwindigkeit + Ping), wifi (WLAN-Qualität), storage (Speicherplatz nach Art), big_files (größte Fotos/Videos), duplicates (doppelte Bilder finden), delete_duplicates (Doppelte löschen – Android fragt nochmal), sysinfo (Systeminformationen).",
 "input_schema":{"type":"object","properties":{"action":{"type":"string","enum":["temperature","speedtest","wifi","storage","big_files","duplicates","delete_duplicates","sysinfo"]}},"required":["action"]}}
""".trimIndent())

    fun temperature(ctx: Context): Float? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (t == Int.MIN_VALUE) null else t / 10f
    }

    private fun mb(b: Long) = if (b >= 1L shl 30) "%.1f GB".format(Locale.GERMANY, b / 1073741824.0) else "${b / 1048576} MB"

    private fun mediaOk(ctx: Context) = Build.VERSION.SDK_INT < 33 && ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == 0 ||
        ctx.checkSelfPermission("android.permission.READ_MEDIA_IMAGES") == 0

    /** (Uri, Größe) aller doppelten Bilder – das jeweils älteste bleibt. */
    fun duplicates(ctx: Context): List<Pair<android.net.Uri, Long>> {
        data class Img(val id: Long, val size: Long, val w: Int, val h: Int, val date: Long)
        val all = ArrayList<Img>()
        ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.DATE_ADDED),
            null, null, null)?.use { c -> while (c.moveToNext()) all += Img(c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), c.getLong(4)) }
        val out = ArrayList<Pair<android.net.Uri, Long>>()
        for (g in all.groupBy { Triple(it.size, it.w, it.h) }.values.filter { it.size > 1 && it.first().size > 0 }) {
            // gleiche Größe → Inhalt (Anfang) vergleichen
            val byHash = g.groupBy { img ->
                try {
                    ctx.contentResolver.openInputStream(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, img.id))?.use { s ->
                        val buf = ByteArray(65536); val n = s.read(buf); MessageDigest.getInstance("MD5").digest(buf.copyOf(maxOf(n, 0))).joinToString("") { "%02x".format(it) } } ?: "x${img.id}"
                } catch (_: Exception) { "x${img.id}" }
            }
            for (same in byHash.values.filter { it.size > 1 }) same.sortedBy { it.date }.drop(1).forEach {
                out += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.id) to it.size }
            if (out.size > 400) break
        }
        return out
    }

    override fun run(t: ToolContext, a: JSONObject): String {
        val ctx = t.ctx
        return try { when (a.optString("action")) {
            "temperature" -> {
                val temp = temperature(ctx) ?: return Res.error("Temperatur nicht lesbar.")
                Res.ok("Akku-Temperatur: %.1f °C – ".format(Locale.GERMANY, temp) + when {
                    temp >= 45 -> "sehr warm! Handy aus der Sonne nehmen, Spiele/Laden pausieren."
                    temp >= 40 -> "ziemlich warm. Kurz Pause gönnen."
                    temp < 5 -> "sehr kalt – Akku hält dann kürzer."
                    else -> "alles im grünen Bereich." })
            }
            "speedtest" -> {
                val p0 = SystemClock.elapsedRealtime(); get("https://speed.cloudflare.com/__down?bytes=0", 8000); val ping = SystemClock.elapsedRealtime() - p0
                val bytes = 15_000_000
                val c = URL("https://speed.cloudflare.com/__down?bytes=$bytes").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 15000
                val t0 = SystemClock.elapsedRealtime(); var n = 0L
                c.inputStream.use { s -> val buf = ByteArray(65536); while (true) { val r = s.read(buf); if (r < 0) break; n += r
                    if (SystemClock.elapsedRealtime() - t0 > 12_000) break } }
                c.disconnect()
                val sec = (SystemClock.elapsedRealtime() - t0) / 1000.0
                val mbit = n * 8 / 1_000_000.0 / sec.coerceAtLeast(0.1)
                Res.ok("Download: %.1f Mbit/s · Ping: %d ms – ".format(Locale.GERMANY, mbit, ping) + when {
                    mbit >= 50 -> "sehr schnell, 4K-Videos laufen problemlos."
                    mbit >= 15 -> "gut für HD-Videos und Spiele."
                    mbit >= 5 -> "reicht für Musik und normale Videos."
                    else -> "eher langsam." } + " (Test über Cloudflare, ca. ${n / 1_000_000} MB verbraucht)")
            }
            "wifi" -> {
                @Suppress("DEPRECATION") val wi = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
                if (wi == null || wi.networkId == -1) return Res.ok("Gerade nicht mit einem WLAN verbunden.")
                val rssi = wi.rssi
                val q = when { rssi >= -55 -> "ausgezeichnet"; rssi >= -67 -> "gut"; rssi >= -75 -> "mittel"; else -> "schwach – näher an den Router" }
                Res.ok("WLAN: Signal $q ($rssi dBm), Verbindung ${wi.linkSpeed} Mbit/s, ${if (wi.frequency > 4900) "5 GHz" else "2,4 GHz"}." +
                    (if (wi.frequency in 1..4899 && rssi > -60) " Tipp: Falls der Router 5 GHz kann, ist das schneller." else ""))
            }
            "storage" -> {
                val st = StatFs(Environment.getDataDirectory().path)
                val total = st.totalBytes; val free = st.availableBytes
                val sb = StringBuilder("Speicher: ${mb(total - free)} von ${mb(total)} belegt, ${mb(free)} frei.")
                if (mediaOk(ctx)) {
                    fun sum(uri: android.net.Uri) = ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { c -> var s = 0L; while (c.moveToNext()) s += c.getLong(0); s } ?: 0L
                    sb.append("\nFotos ${mb(sum(MediaStore.Images.Media.EXTERNAL_CONTENT_URI))}, Videos ${mb(sum(MediaStore.Video.Media.EXTERNAL_CONTENT_URI))}, Musik ${mb(sum(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI))}.")
                }
                if (free < total / 10) sb.append("\nWird knapp! Sag „Finde große Dateien“ oder „Finde doppelte Bilder“.")
                Res.ok(sb.toString())
            }
            "big_files" -> {
                if (!mediaOk(ctx)) return Res.noPermission("deine Fotos und Videos", "Bitte Fotos & Videos erlauben (Berechtigungen).")
                val rows = ArrayList<Triple<String, Long, String>>()
                for ((uri, kind) in listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Video", MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Foto"))
                    ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null,
                        MediaStore.MediaColumns.SIZE + " DESC")?.use { c -> var k = 0; while (c.moveToNext() && k++ < 8) rows += Triple(c.getString(0) ?: "?", c.getLong(1), kind) }
                Res.ok("Größte Dateien:\n" + rows.sortedByDescending { it.second }.take(8).joinToString("\n") { "• ${it.third} ${it.first} – ${mb(it.second)}" } +
                    "\nLöschen kannst du sie in der Galerie (ich lösche nichts selbst).")
            }
            "duplicates" -> {
                if (!mediaOk(ctx)) return Res.noPermission("deine Fotos", "Bitte Fotos erlauben (Berechtigungen).")
                val d = duplicates(ctx)
                if (d.isEmpty()) Res.ok("Keine doppelten Bilder gefunden. 👍")
                else Res.ok("${d.size} doppelte Bilder gefunden (≈ ${mb(d.sumOf { it.second })}). Das Original bleibt jeweils erhalten. Sag „lösch die doppelten Bilder“, dann fragt Android dich nochmal.")
            }
            "delete_duplicates" -> {
                if (Build.VERSION.SDK_INT < 30) return Res.error("Geht erst ab Android 11.")
                val d = duplicates(ctx)
                if (d.isEmpty()) return Res.ok("Keine doppelten Bilder gefunden.")
                val pi = MediaStore.createDeleteRequest(ctx.contentResolver, d.map { it.first })
                t.ui { t.activity.startIntentSenderForResult(pi.intentSender, 4711, null, 0, 0, 0) }
                Res.ok("Android zeigt dir jetzt ${d.size} doppelte Bilder zum Löschen – erst nach deinem Tippen auf „Zulassen“ werden sie gelöscht.")
            }
            "sysinfo" -> {
                val am = ctx.getSystemService(ActivityManager::class.java)
                val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                val up = SystemClock.elapsedRealtime() / 3_600_000
                Res.ok("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (Sicherheits-Patch ${Build.VERSION.SECURITY_PATCH})\n" +
                    "Arbeitsspeicher: ${mb(mi.totalMem - mi.availMem)} von ${mb(mi.totalMem)} belegt · ${Runtime.getRuntime().availableProcessors()} Prozessorkerne\n" +
                    "Läuft seit ${if (up >= 24) "${up / 24} Tagen" else "$up Std."}" + (temperature(ctx)?.let { " · Akku %.1f °C".format(Locale.GERMANY, it) } ?: ""))
            }
            else -> Res.error("Unbekannte Aktion.")
        } } catch (e: Exception) { Res.error("Ging gerade nicht (${e.message?.take(60)}).") }
    }
}

/** Sicherheit per Sprache: Check, Phishing-Prüfung, WLAN-Geräte; Passwort nur im eigenen Bildschirm (geht nie an die KI). */
object SecurityTool : JarvisTool {
    override val name = "security"
    override val group = "device"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"security","description":"Sicherheitszentrale. check: Sicherheits-Check des Handys (Punkte + Hinweise). phishing: text (Nachricht oder Link) auf Betrug prüfen – auch bei 'ist dieser Link sicher?' oder 'ist das Fake?'. lan: Geräte im WLAN scannen (neue/unbekannte melden). password: öffnet den Passwort-Check (das Passwort NIE im Chat erfragen!). open: Sicherheitszentrale öffnen.",
 "input_schema":{"type":"object","properties":{"action":{"type":"string","enum":["check","phishing","lan","password","open"]},"text":{"type":"string"}},"required":["action"]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String = when (a.optString("action")) {
        "check" -> Res.ok(de.jarvis.app.Security.report(t.ctx))
        "phishing" -> Res.ok(de.jarvis.app.Security.phishingText(a.optString("text")))
        "lan" -> Res.ok(de.jarvis.app.Security.lanDevices(t.ctx))
        "password" -> t.launch(Intent(t.ctx, de.jarvis.app.SecurityActivity::class.java).putExtra("password", true), "Passwort-Check geöffnet – tipp es dort ein, es bleibt auf dem Handy.")
        else -> t.launch(Intent(t.ctx, de.jarvis.app.SecurityActivity::class.java), "Sicherheitszentrale geöffnet.")
    }
}
