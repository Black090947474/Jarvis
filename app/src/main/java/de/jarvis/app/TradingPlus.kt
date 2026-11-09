package de.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale

/**
 * Alpaca-Demo-Konto (Paper Trading). Die Adresse ist fest auf das DEMO-System eingestellt –
 * mit dieser App kann technisch kein echtes Geld gehandelt werden.
 */
object Alpaca {
    private const val BASE = "https://paper-api.alpaca.markets"   // nur Paper/Demo – niemals api.alpaca.markets (echtes Geld)
    const val SIGNUP = "https://app.alpaca.markets/signup"
    const val KEYS = "https://app.alpaca.markets/paper/dashboard/overview"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    fun keyId(ctx: Context) = sp(ctx).getString("alpaca_id", "").orEmpty()
    fun secret(ctx: Context) = sp(ctx).getString("alpaca_secret", "").orEmpty()
    fun hasKeys(ctx: Context) = keyId(ctx).isNotBlank() && secret(ctx).isNotBlank()
    fun saveKeys(ctx: Context, id: String, sec: String) = sp(ctx).edit().putString("alpaca_id", id.trim()).putString("alpaca_secret", sec.trim()).apply()
    /** Handelt der Bot gerade über Alpaca? */
    fun active(ctx: Context) = hasKeys(ctx) && sp(ctx).getString("trade_broker", "jarvis") == "alpaca"
    fun setActive(ctx: Context, on: Boolean) = sp(ctx).edit().putString("trade_broker", if (on) "alpaca" else "jarvis").apply()

    private fun req(ctx: Context, method: String, path: String, body: JSONObject? = null): String {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("APCA-API-KEY-ID", keyId(ctx)); c.setRequestProperty("APCA-API-SECRET-KEY", secret(ctx))
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toString().toByteArray()) } }
        try {
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw java.io.IOException(when (code) { 401, 403 -> "Schlüssel ungültig"; 422 -> JSONObject(txt.ifBlank { "{}" }).optString("message", "abgelehnt"); else -> "HTTP $code" })
            return txt
        } finally { c.disconnect() }
    }

    /** Jarvis-Kürzel → Alpaca-Kürzel (null = bei Alpaca nicht handelbar, z. B. deutsche Aktien). */
    fun symbol(sym: String): String? = when {
        sym.endsWith(".us") -> sym.removeSuffix(".us").uppercase()
        sym.endsWith("usd") && !sym.contains('.') -> sym.removeSuffix("usd").uppercase() + "/USD"
        else -> null
    }
    /** Alpaca-Kürzel → Jarvis-Kürzel (Krypto „BTCUSD“ → btcusd, Aktie „AAPL“ → aapl.us). */
    fun fromAlpaca(s: String, assetClass: String): String = if (assetClass == "crypto") s.replace("/", "").lowercase() else s.lowercase() + ".us"

    fun account(ctx: Context): JSONObject = JSONObject(req(ctx, "GET", "/v2/account"))
    fun positions(ctx: Context): JSONArray = JSONArray(req(ctx, "GET", "/v2/positions"))

    fun order(ctx: Context, sym: String, usd: Double): JSONObject {
        val a = symbol(sym) ?: throw java.io.IOException("${Market.name(sym)} gibt es bei Alpaca nicht (nur US-Aktien, ETFs und Krypto)")
        val crypto = a.contains('/')
        return JSONObject(req(ctx, "POST", "/v2/orders", JSONObject().put("symbol", a).put("notional", "%.2f".format(Locale.US, usd))
            .put("side", "buy").put("type", "market").put("time_in_force", if (crypto) "gtc" else "day")))
    }

    fun close(ctx: Context, sym: String, part: Double): JSONObject {
        val a = (symbol(sym) ?: throw java.io.IOException("Unbekannt")).replace("/", "")
        val q = if (part < 0.999) "?percentage=" + "%.2f".format(Locale.US, part * 100) else ""
        return JSONObject(req(ctx, "DELETE", "/v2/positions/" + java.net.URLEncoder.encode(a, "UTF-8") + q))
    }
}

/** Bot-Einstellungen: Risiko-Stufe und eigene Beobachtungsliste. */
object BotConfig {
    data class Risk(val label: String, val trail: Double, val perTrade: Double, val maxPos: Int, val minScore: Double, val maxBuysDay: Int)
    val RISKS = linkedMapOf(
        "vorsichtig" to Risk("Vorsichtig", 0.05, 0.08, 6, 3.5, 2),
        "normal" to Risk("Normal", 0.08, 0.15, 5, 3.0, 3),
        "mutig" to Risk("Mutig", 0.12, 0.25, 4, 2.5, 4))

    private fun sp(ctx: Context) = ctx.getSharedPreferences("jarvis_bot", Context.MODE_PRIVATE)
    fun riskKey(ctx: Context) = sp(ctx).getString("risk", "normal").orEmpty().let { if (it in RISKS) it else "normal" }
    fun risk(ctx: Context) = RISKS[riskKey(ctx)]!!
    fun setRisk(ctx: Context, k: String) = sp(ctx).edit().putString("risk", k).apply()

    fun ownList(ctx: Context) = sp(ctx).getString("watch", "").orEmpty()
    fun setOwnList(ctx: Context, s: String) = sp(ctx).edit().putString("watch", s.trim()).apply()

    /** Werte, die der Bot prüft: eigene Liste (falls gesetzt) sonst Standardliste; bei Alpaca nur US & Krypto. */
    fun watch(ctx: Context): List<String> {
        val own = ownList(ctx).split(',', ';', '\n').mapNotNull { Market.symbol(it) }.distinct()
        val base = own.ifEmpty { Market.WATCH.keys.toList() }
        return if (Alpaca.active(ctx)) base.filter { Alpaca.symbol(it) != null } else base
    }

    // ---------- Leistung: Depotwert-Verlauf + Vergleich mit dem S&P 500

    private fun hk(ctx: Context) = if (Alpaca.active(ctx)) "alpaca" else "jarvis"

    /** Heutigen Depotwert (und S&P-500-Kurs) merken – für Kurve und Vergleich. */
    fun record(ctx: Context, total: Double) {
        val spy = try { Market.quote("spy.us") } catch (_: Exception) { null } ?: return
        val k = hk(ctx)
        val eq = JSONObject(sp(ctx).getString("eq_$k", "{}")); val bm = JSONObject(sp(ctx).getString("bm_$k", "{}"))
        val d = LocalDate.now().toString()
        eq.put(d, total); bm.put(d, spy)
        // höchstens ein Jahr behalten
        if (eq.length() > 370) eq.keys().asSequence().toList().sorted().take(eq.length() - 370).forEach { eq.remove(it); bm.remove(it) }
        sp(ctx).edit().putString("eq_$k", eq.toString()).putString("bm_$k", bm.toString()).apply()
    }

    fun clearHistory(ctx: Context) { val k = hk(ctx); sp(ctx).edit().remove("eq_$k").remove("bm_$k").apply() }

    /** (Datum, Depotwert, S&P-Kurs) aufsteigend. */
    fun history(ctx: Context): List<Triple<String, Double, Double>> {
        val k = hk(ctx)
        val eq = JSONObject(sp(ctx).getString("eq_$k", "{}")); val bm = JSONObject(sp(ctx).getString("bm_$k", "{}"))
        return eq.keys().asSequence().toList().sorted().mapNotNull { d -> if (bm.has(d)) Triple(d, eq.getDouble(d), bm.getDouble(d)) else null }
    }

    fun vsMarket(ctx: Context): String? {
        val h = history(ctx); if (h.size < 2) return null
        val me = (h.last().second / h.first().second - 1) * 100; val mk = (h.last().third / h.first().third - 1) * 100
        val diff = me - mk
        return "Seit ${h.first().first.substring(8, 10)}.${h.first().first.substring(5, 7)}.: Depot %+.1f %% · S&P 500 %+.1f %% → ".format(Locale.GERMANY, me, mk) +
            (if (diff >= 0) "der Bot liegt %.1f Punkte VOR dem Markt 💪".format(Locale.GERMANY, diff) else "einfach den S&P 500 kaufen wäre %.1f Punkte besser gewesen".format(Locale.GERMANY, -diff))
    }

    /** Statistik der abgeschlossenen Trades (Verkäufe). */
    fun stats(ctx: Context): String {
        val broker = hk(ctx)
        val sells = DemoDepot.trades(ctx).filter { it.optString("side") == "VERKAUF" && it.has("pl") && it.optString("broker", "jarvis") == broker }
        if (sells.isEmpty()) return "Noch keine abgeschlossenen Trades."
        val wins = sells.filter { it.optDouble("pl") > 0 }; val loss = sells.filter { it.optDouble("pl") <= 0 }
        val cur = if (broker == "alpaca") "$" else "€"
        fun m(v: Double) = "%,.2f %s".format(Locale.GERMANY, v, cur)
        val bot = sells.count { it.optBoolean("bot") }
        return "${sells.size} abgeschlossene Trades ($bot vom Bot) · Trefferquote ${wins.size * 100 / sells.size} %\n" +
            "Ø Gewinn ${if (wins.isEmpty()) "–" else m(wins.map { it.optDouble("pl") }.average())} · Ø Verlust ${if (loss.isEmpty()) "–" else m(loss.map { it.optDouble("pl") }.average())}\n" +
            "Summe realisiert: ${m(sells.sumOf { it.optDouble("pl") })}"
    }
}

/** Kleine Kurve: Depot (grün/cyan) gegen S&P 500 (grau), beide auf 100 normiert. */
class EquityChartView(ctx: Context, private val data: List<Triple<String, Double, Double>>) : View(ctx) {
    private val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.rgb(0, 229, 255) }
    private val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.argb(150, 180, 190, 210) }
    private val grid = Paint().apply { color = Color.argb(30, 255, 255, 255); strokeWidth = 2f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 200, 210, 225); textSize = 28f }

    override fun onDraw(c: Canvas) {
        if (data.size < 2) { c.drawText("Kurve erscheint nach ein paar Tagen.", 20f, height / 2f, txt); return }
        val a = data.map { it.second / data.first().second * 100 }; val b = data.map { it.third / data.first().third * 100 }
        val lo = minOf(a.min(), b.min()) - 1; val hi = maxOf(a.max(), b.max()) + 1
        val pad = 36f; val w = width - pad * 2; val h = height - pad * 2
        fun y(v: Double) = (pad + h - (v - lo) / (hi - lo) * h).toFloat()
        c.drawLine(pad, y(100.0), pad + w, y(100.0), grid)
        fun line(v: List<Double>, p: Paint) { val path = Path(); v.forEachIndexed { i, x -> val px = pad + w * i / (v.size - 1); if (i == 0) path.moveTo(px, y(x)) else path.lineTo(px, y(x)) }; c.drawPath(path, p) }
        line(b, p2); line(a, p1)
        c.drawText("Depot", pad, pad - 6, txt.apply { color = Color.rgb(0, 229, 255) })
        c.drawText("S&P 500", pad + 120, pad - 6, txt.apply { color = Color.argb(170, 180, 190, 210) })
    }
}
