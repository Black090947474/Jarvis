package de.jarvis.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import de.jarvis.app.tools.JarvisTool
import de.jarvis.app.tools.JsonStore
import de.jarvis.app.tools.Res
import de.jarvis.app.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Jarvis-Demo-Depot: Spielgeld, echte Kurse (Stooq), einfache Trader-Signale. Nie echtes Geld.
 * Keine Anlageberatung – Signale sind Hinweise, keine Vorhersage.
 */
object Market {
    /** Beobachtungsliste: Stooq-Kürzel → Name. */
    val WATCH = linkedMapOf(
        "aapl.us" to "Apple", "msft.us" to "Microsoft", "nvda.us" to "NVIDIA", "amzn.us" to "Amazon", "googl.us" to "Alphabet (Google)",
        "meta.us" to "Meta", "tsla.us" to "Tesla", "amd.us" to "AMD", "nflx.us" to "Netflix", "pltr.us" to "Palantir",
        "sap.de" to "SAP", "sie.de" to "Siemens", "alv.de" to "Allianz", "rhm.de" to "Rheinmetall", "dte.de" to "Deutsche Telekom",
        "ads.de" to "Adidas", "bmw.de" to "BMW", "mbg.de" to "Mercedes-Benz", "ifx.de" to "Infineon",
        "spy.us" to "S&P-500-ETF (SPY)", "qqq.us" to "Nasdaq-100-ETF (QQQ)", "btcusd" to "Bitcoin", "ethusd" to "Ethereum",
    )

    private val ALIASES = mapOf("apple" to "aapl.us", "microsoft" to "msft.us", "nvidia" to "nvda.us", "amazon" to "amzn.us", "google" to "googl.us",
        "alphabet" to "googl.us", "meta" to "meta.us", "facebook" to "meta.us", "tesla" to "tsla.us", "amd" to "amd.us", "netflix" to "nflx.us",
        "palantir" to "pltr.us", "sap" to "sap.de", "siemens" to "sie.de", "allianz" to "alv.de", "rheinmetall" to "rhm.de", "telekom" to "dte.de",
        "adidas" to "ads.de", "bmw" to "bmw.de", "mercedes" to "mbg.de", "infineon" to "ifx.de", "s&p" to "spy.us", "sp500" to "spy.us",
        "nasdaq" to "qqq.us", "dax" to "^gdaxi", "bitcoin" to "btcusd", "btc" to "btcusd", "ethereum" to "ethusd", "eth" to "ethusd", "volkswagen" to "vow3.de", "vw" to "vow3.de",
        "porsche" to "p911.de", "bayer" to "bayn.de", "basf" to "bas.de", "puma" to "pum.de", "coca" to "ko.us", "disney" to "dis.us", "nike" to "nke.us")

    fun symbol(q: String): String? {
        val t = q.trim().lowercase(Locale.GERMANY)
        if (t.isBlank()) return null
        ALIASES.entries.firstOrNull { t.contains(it.key) }?.let { return it.value }
        if (Regex("^[a-z0-9^]+(\\.[a-z]{2})?$").matches(t)) return if (t.contains('.') || t.endsWith("usd")) t else "$t.us"
        return null
    }

    fun name(sym: String) = WATCH[sym] ?: ALIASES.entries.firstOrNull { it.value == sym }?.key?.replaceFirstChar { it.uppercase() } ?: sym.uppercase()
    fun isUsd(sym: String) = sym.endsWith(".us") || sym.endsWith("usd")

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 12_000; c.setRequestProperty("User-Agent", "JarvisApp/1.0")
        try { if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}"); return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    /** Yahoo-Finance-Kürzel (AAPL, SAP.DE) bzw. Binance-Paar (BTCUSDT) für Krypto. */
    private fun yahoo(sym: String) = if (sym.endsWith(".us")) sym.removeSuffix(".us").uppercase() else sym.uppercase()
    private fun isCrypto(sym: String) = sym == "btcusd" || sym == "ethusd" || (sym.endsWith("usd") && !sym.contains('.'))
    private fun binance(sym: String) = sym.removeSuffix("usd").uppercase() + "USDT"

    /** Tagesschlusskurse der letzten ~9 Monate (Cache: 1x pro Tag und Kürzel). Quellen ohne Schlüssel: Yahoo Finance, Binance. */
    fun closes(ctx: Context, sym: String): List<Double> {
        val sp = ctx.getSharedPreferences("jarvis_market", Context.MODE_PRIVATE)
        val key = "h_${sym}_${LocalDate.now()}"
        sp.getString(key, null)?.let { s -> return s.split(',').mapNotNull { it.toDoubleOrNull() } }
        val list: List<Double> = if (isCrypto(sym)) {
            val a = JSONArray(get("https://api.binance.com/api/v3/klines?symbol=${binance(sym)}&interval=1d&limit=200"))
            (0 until a.length()).mapNotNull { a.getJSONArray(it).optString(4).toDoubleOrNull() }
        } else {
            val j = JSONObject(get("https://query1.finance.yahoo.com/v8/finance/chart/" + java.net.URLEncoder.encode(yahoo(sym), "UTF-8") + "?range=9mo&interval=1d"))
            val q = j.getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close")
            (0 until q.length()).mapNotNull { if (q.isNull(it)) null else q.optDouble(it) }
        }
        if (list.size >= 30) sp.edit().apply { sp.all.keys.filter { it.startsWith("h_") && !it.endsWith(LocalDate.now().toString()) }.forEach { remove(it) } }
            .putString(key, list.joinToString(",")).apply()
        return list
    }

    /** Aktueller Kurs (in Originalwährung). */
    fun quote(sym: String): Double? = if (isCrypto(sym)) {
        JSONObject(get("https://api.binance.com/api/v3/ticker/price?symbol=${binance(sym)}")).optString("price").toDoubleOrNull()
    } else {
        val j = JSONObject(get("https://query1.finance.yahoo.com/v8/finance/chart/" + java.net.URLEncoder.encode(yahoo(sym), "UTF-8") + "?range=1d&interval=1d"))
        j.getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta").optDouble("regularMarketPrice").takeIf { !it.isNaN() && it > 0 }
    }

    @Volatile private var eurUsd = 0.0
    private var eurUsdAt = 0L
    /** 1 € = x US-Dollar (EZB-Kurs über frankfurter.app, ohne Schlüssel). */
    fun eurUsd(): Double {
        if (eurUsd > 0 && System.currentTimeMillis() - eurUsdAt < 3_600_000L) return eurUsd
        eurUsd = try { JSONObject(get("https://api.frankfurter.app/latest?from=EUR&to=USD")).getJSONObject("rates").optDouble("USD") } catch (_: Exception) { eurUsd.takeIf { it > 0 } ?: 1.1 }
        eurUsdAt = System.currentTimeMillis(); return eurUsd
    }

    /** Kurs in Euro. */
    fun priceEur(sym: String): Double? = quote(sym)?.let { if (isUsd(sym)) it / eurUsd() else it }

    data class Signal(val sym: String, val name: String, val score: Double, val reasons: List<String>, val rsi: Double, val mom20: Double)

    private fun sma(l: List<Double>, n: Int) = l.takeLast(n).average()
    private fun rsi(l: List<Double>, n: Int = 14): Double {
        val d = l.takeLast(n + 1).zipWithNext { a, b -> b - a }
        val up = d.filter { it > 0 }.sum() / n; val dn = -d.filter { it < 0 }.sum() / n
        return if (dn == 0.0) 100.0 else 100 - 100 / (1 + up / dn)
    }

    fun analyze(ctx: Context, sym: String): Signal? {
        val c = try { closes(ctx, sym) } catch (_: Exception) { return null }
        if (c.size < 55) return null
        val last = c.last(); val s20 = sma(c, 20); val s50 = sma(c, 50); val r = rsi(c)
        val mom20 = (last / c[c.size - 21] - 1) * 100; val mom5 = (last / c[c.size - 6] - 1) * 100
        var score = 0.0; val why = ArrayList<String>()
        if (last > s50) { score += 1; why += "Kurs über dem 50-Tage-Schnitt (Aufwärtstrend)" } else why += "unter dem 50-Tage-Schnitt (schwacher Trend)"
        if (s20 > s50) { score += 1; why += "20-Tage-Schnitt über 50-Tage-Schnitt" }
        when { r > 75 -> { score -= 1; why += "RSI %.0f – überkauft, Rücksetzer möglich".format(r) }
               r in 50.0..70.0 -> { score += 1; why += "RSI %.0f – gesunder Schwung".format(r) }
               r < 30 -> { score += 0.5; why += "RSI %.0f – überverkauft, Gegenbewegung möglich".format(r) }
               else -> why += "RSI %.0f".format(r) }
        if (mom20 > 3) { score += minOf(mom20 / 10, 1.0); why += "+%.1f %% in 20 Tagen".format(Locale.GERMANY, mom20) } else if (mom20 < -5) { score -= 0.5; why += "%.1f %% in 20 Tagen".format(Locale.GERMANY, mom20) }
        if (mom5 > 0) { score += 0.5; why += "letzte Woche im Plus" }
        return Signal(sym, name(sym), score, why, r, mom20)
    }

    /** Alle Werte der Beobachtungsliste parallel prüfen, beste zuerst. */
    fun scan(ctx: Context): List<Signal> {
        val pool = Executors.newFixedThreadPool(5)
        val futures = WATCH.keys.map { s -> pool.submit<Signal?> { analyze(ctx, s) } }
        pool.shutdown(); pool.awaitTermination(60, TimeUnit.SECONDS)
        return futures.mapNotNull { try { it.get(1, TimeUnit.SECONDS) } catch (_: Exception) { null } }.sortedByDescending { it.score }
    }

    fun tradingViewUrl(sym: String): String {
        val tv = when {
            sym.endsWith(".de") -> "XETR:" + sym.removeSuffix(".de").uppercase()
            sym == "btcusd" -> "BITSTAMP:BTCUSD"; sym == "ethusd" -> "BITSTAMP:ETHUSD"
            else -> sym.removeSuffix(".us").uppercase()
        }
        return "https://www.tradingview.com/chart/?symbol=" + Uri.encode(tv)
    }
}

/** Das Spielgeld-Depot (bleibt auf dem Handy). */
object DemoDepot {
    const val START = 10_000.0
    private fun store(ctx: Context) = JsonStore(ctx)
    fun load(ctx: Context): JSONObject = store(ctx).obj("demo_depot").also { if (!it.has("cash")) it.put("cash", START).put("positions", JSONObject()).put("start", START) }
    private fun save(ctx: Context, d: JSONObject) = store(ctx).saveObj("demo_depot", d)
    fun trades(ctx: Context) = store(ctx).list("demo_trades")
    private fun log(ctx: Context, o: JSONObject) = store(ctx).save("demo_trades", (trades(ctx) + o.put("t", System.currentTimeMillis())).takeLast(500))
    fun eur(v: Double) = "%,.2f €".format(Locale.GERMANY, v)

    fun buy(ctx: Context, sym: String, amountEur: Double, stopPct: Double = 0.0, bot: Boolean = false): String {
        val d = load(ctx); val cash = d.optDouble("cash")
        if (amountEur <= 0) return Res.error("Für wie viel Euro?")
        if (amountEur > cash + 0.01) return Res.error("Nicht genug Spielgeld – frei sind ${eur(cash)}.")
        val price = Market.priceEur(sym) ?: return Res.error("Für ${Market.name(sym)} gibt es gerade keinen Kurs.")
        val qty = amountEur / price
        val pos = d.getJSONObject("positions").optJSONObject(sym) ?: JSONObject().put("qty", 0.0).put("cost", 0.0)
        pos.put("qty", pos.optDouble("qty") + qty).put("cost", pos.optDouble("cost") + amountEur)
        if (stopPct > 0) pos.put("stop", price * (1 - stopPct / 100))
        d.getJSONObject("positions").put(sym, pos); d.put("cash", cash - amountEur); save(ctx, d)
        log(ctx, JSONObject().put("side", "KAUF").put("sym", sym).put("qty", qty).put("price", price).put("eur", amountEur).put("bot", bot))
        return Res.ok("Demo-Kauf: ${Market.name(sym)} für ${eur(amountEur)} (%.4f Stück à ${eur(price)}).".format(Locale.GERMANY, qty) +
            (if (stopPct > 0) " Stop-Loss bei -${stopPct.toInt()} %." else "") + " Freies Spielgeld: ${eur(cash - amountEur)}.")
    }

    fun sell(ctx: Context, sym: String, part: Double = 1.0, bot: Boolean = false, why: String = ""): String {
        val d = load(ctx); val pos = d.getJSONObject("positions").optJSONObject(sym) ?: return Res.error("${Market.name(sym)} ist nicht im Depot.")
        val price = Market.priceEur(sym) ?: return Res.error("Gerade kein Kurs für ${Market.name(sym)}.")
        val f = part.coerceIn(0.01, 1.0); val qty = pos.optDouble("qty") * f; val cost = pos.optDouble("cost") * f
        val value = qty * price; val pl = value - cost
        if (f >= 0.999) d.getJSONObject("positions").remove(sym) else pos.put("qty", pos.optDouble("qty") - qty).put("cost", pos.optDouble("cost") - cost)
        d.put("cash", d.optDouble("cash") + value); save(ctx, d)
        log(ctx, JSONObject().put("side", "VERKAUF").put("sym", sym).put("qty", qty).put("price", price).put("eur", value).put("pl", pl).put("bot", bot).put("why", why))
        return Res.ok("Demo-Verkauf: ${Market.name(sym)} für ${eur(value)} – " + (if (pl >= 0) "Gewinn ${eur(pl)} 🎉" else "Verlust ${eur(-pl)}") + (if (why.isNotBlank()) " ($why)" else "") + ".")
    }

    /** Depot-Übersicht mit aktuellen Kursen. */
    fun summary(ctx: Context): String {
        val d = load(ctx); val pos = d.getJSONObject("positions"); var value = 0.0
        val lines = pos.keys().asSequence().toList().map { s -> val p = pos.getJSONObject(s)
            val price = try { Market.priceEur(s) } catch (_: Exception) { null }
            val v = price?.let { it * p.optDouble("qty") } ?: p.optDouble("cost"); value += v
            val pl = v - p.optDouble("cost")
            "• ${Market.name(s)}: ${eur(v)} (" + (if (pl >= 0) "+" else "") + eur(pl) + ", " + "%+.1f %%".format(Locale.GERMANY, pl / p.optDouble("cost") * 100) + ")" }
        val total = d.optDouble("cash") + value; val start = d.optDouble("start", START)
        return "Demo-Depot: ${eur(total)} (" + "%+.1f %%".format(Locale.GERMANY, (total / start - 1) * 100) + " seit Start)\nFreies Spielgeld: ${eur(d.optDouble("cash"))}" +
            (if (lines.isEmpty()) "\nNoch keine Aktien im Depot." else "\n" + lines.joinToString("\n"))
    }

    fun reset(ctx: Context) { save(ctx, JSONObject().put("cash", START).put("positions", JSONObject()).put("start", START)); store(ctx).save("demo_trades", emptyList()) }

    private val BERLIN = java.time.ZoneId.of("Europe/Berlin")

    /** Ist die Börse für diesen Wert gerade offen? Krypto: immer. Xetra 9–17:30, USA 15:30–22 Uhr (deutsche Zeit), Mo–Fr. */
    fun marketOpen(sym: String, now: java.time.ZonedDateTime = java.time.ZonedDateTime.now(BERLIN)): Boolean {
        if (sym.endsWith("usd") && !sym.contains('.')) return true
        if (now.dayOfWeek.value > 5) return false
        val m = now.hour * 60 + now.minute
        return if (sym.endsWith(".de") || sym.startsWith("^g")) m in 540..1050 else m in 930..1320
    }

    /** Nächster Bot-Lauf: tagsüber alle 30 Minuten, nachts alle 2 Stunden (nur Krypto handelt dann). */
    fun nextRun(): Long {
        val now = java.time.ZonedDateTime.now(BERLIN)
        val step = if (now.hour in 7..22) 30L else 120L
        return now.plusMinutes(step).withSecond(0).toInstant().toEpochMilli()
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("jarvis_bot", Context.MODE_PRIVATE)
    fun lastRunText(ctx: Context): String = sp(ctx).getString("last", "").orEmpty()

    /**
     * Der automatische Bot (läuft nur, wenn eingeschaltet): prüft offene Positionen (Stop-Loss, -8 %, +15 %, schwaches Signal)
     * und kauft gute Signale – höchstens 3 Käufe pro Tag, max. 5 Positionen, nichts zurückkaufen, was in den letzten 3 Tagen verkauft wurde.
     * Rückgabe: die gemachten Trades (leer = nichts getan).
     */
    fun botRun(ctx: Context): List<String> {
        val out = ArrayList<String>()
        val d = load(ctx); val pos = d.getJSONObject("positions")
        for (s in pos.keys().asSequence().toList()) {
            if (!marketOpen(s)) continue
            val p = pos.getJSONObject(s); val price = try { Market.priceEur(s) } catch (_: Exception) { null } ?: continue
            val chg = price * p.optDouble("qty") / p.optDouble("cost") - 1
            when {
                p.has("stop") && price <= p.optDouble("stop") -> out += Res.spoken(sell(ctx, s, 1.0, true, "Stop-Loss"))
                chg <= -0.08 -> out += Res.spoken(sell(ctx, s, 1.0, true, "-8 % Verlustgrenze"))
                chg >= 0.15 -> out += Res.spoken(sell(ctx, s, 1.0, true, "+15 % Gewinn mitgenommen"))
                Market.analyze(ctx, s)?.let { it.score < 0.5 } == true -> out += Res.spoken(sell(ctx, s, 1.0, true, "Signal schwach geworden"))
            }
        }
        val today = LocalDate.now().toString()
        val buysToday = trades(ctx).count { it.optBoolean("bot") && it.optString("side") == "KAUF" &&
            java.time.Instant.ofEpochMilli(it.optLong("t")).atZone(BERLIN).toLocalDate().toString() == today }
        val recentlySold = trades(ctx).filter { it.optString("side") == "VERKAUF" && System.currentTimeMillis() - it.optLong("t") < 3 * 86_400_000L }.map { it.optString("sym") }.toSet()
        val d2 = load(ctx)
        if (d2.getJSONObject("positions").length() < 5 && buysToday < 3) {
            val best = Market.scan(ctx).firstOrNull { it.score >= 3 && marketOpen(it.sym) && !d2.getJSONObject("positions").has(it.sym) && it.sym !in recentlySold }
            if (best != null) {
                val amt = minOf(d2.optDouble("cash") * 0.15, 2000.0)
                if (amt >= 50) out += Res.spoken(buy(ctx, best.sym, amt, 8.0, true)) + " Grund: " + best.reasons.take(2).joinToString(", ")
            }
        }
        val stamp = java.time.ZonedDateTime.now(BERLIN).format(DateTimeFormatter.ofPattern("dd.MM. HH:mm"))
        sp(ctx).edit().putString("last", "Letzter Lauf: $stamp – " + (if (out.isEmpty()) "nichts gehandelt" else "${out.size} Trade(s)")).apply()
        return out
    }
}

object TradingTool : JarvisTool {
    override val name = "trading"
    override val group = "info"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"trading","description":"Jarvis-Demo-Depot mit Spielgeld (NIE echtes Geld) und echten Kursen. scan: Beobachtungsliste prüfen, welche Werte gerade die besten Trend-/Schwung-Signale haben (keine Garantie!). analyze: einen Wert bewerten (symbol = Name oder Kürzel, z. B. Apple, SAP, Bitcoin). buy: Demo-Kauf (symbol, amount Euro, stop Prozent optional). sell: Demo-Verkauf (symbol, part 0–1, Standard alles). portfolio: Depot mit Gewinn/Verlust. trades: letzte Trades. chart: Chart in TradingView öffnen. bot: automatischen Demo-Bot an/aus (on). reset: Depot zurücksetzen (confirmed). Immer erwähnen: Signale sind keine Vorhersage und keine Anlageberatung.",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["scan","analyze","buy","sell","portfolio","trades","chart","bot","reset"]},
  "symbol":{"type":"string"},"amount":{"type":"number"},"stop":{"type":"number"},"part":{"type":"number"},"on":{"type":"boolean"},"confirmed":{"type":"boolean"}},"required":["action"]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String {
        val ctx = t.ctx
        fun sym() = Market.symbol(a.optString("symbol"))
        return try { when (a.optString("action")) {
            "scan" -> { val s = Market.scan(ctx); if (s.isEmpty()) Res.error("Kurse gerade nicht abrufbar.")
                else Res.ok("Stärkste Signale gerade (Spielgeld, keine Garantie):\n" + s.take(5).joinToString("\n") { "• ${it.name} – Punkte %.1f: ".format(Locale.GERMANY, it.score) + it.reasons.take(3).joinToString(", ") } +
                    "\nSchwächste: " + s.takeLast(3).joinToString(", ") { it.name }) }
            "analyze" -> { val s = sym() ?: return Res.error("Welche Aktie?"); val sig = Market.analyze(ctx, s) ?: return Res.error("Keine Kursdaten für ${a.optString("symbol")}.")
                Res.ok("${sig.name}: " + (if (sig.score >= 3) "starkes Signal" else if (sig.score >= 1.5) "neutral bis positiv" else "eher schwach") +
                    " (Punkte %.1f). ".format(Locale.GERMANY, sig.score) + sig.reasons.joinToString(", ") + ". Keine Garantie – nur Statistik.") }
            "buy" -> { val s = sym() ?: return Res.error("Was soll ich kaufen?"); DemoDepot.buy(ctx, s, a.optDouble("amount", 0.0), a.optDouble("stop", 0.0)) }
            "sell" -> { val s = sym() ?: return Res.error("Was soll ich verkaufen?"); DemoDepot.sell(ctx, s, a.optDouble("part", 1.0)) }
            "portfolio" -> Res.ok(DemoDepot.summary(ctx))
            "trades" -> { val l = DemoDepot.trades(ctx).takeLast(10); if (l.isEmpty()) Res.ok("Noch keine Trades.")
                else Res.ok(l.reversed().joinToString("\n") { o -> "${de.jarvis.app.logic.TimeLogic.short(o.optLong("t"))} ${o.optString("side")} ${Market.name(o.optString("sym"))} ${DemoDepot.eur(o.optDouble("eur"))}" +
                    (if (o.has("pl")) " (" + (if (o.optDouble("pl") >= 0) "+" else "") + DemoDepot.eur(o.optDouble("pl")) + ")" else "") + (if (o.optBoolean("bot")) " 🤖" else "") }) }
            "chart" -> { val s = sym() ?: return Res.error("Welcher Chart?"); t.launch(Intent(Intent.ACTION_VIEW, Uri.parse(Market.tradingViewUrl(s))), "Chart von ${Market.name(s)} in TradingView geöffnet.") }
            "bot" -> { AutoPilot.set(ctx, "trade_bot", a.optBoolean("on", true)); Res.ok(if (a.optBoolean("on", true)) "Demo-Bot an: Er handelt jetzt ganz allein im Hintergrund – alle 30 Minuten, wenn die Börse offen ist (Krypto auch nachts und am Wochenende). Er verkauft bei Stop-Loss, -8 % oder +15 % und kauft gute Signale, alles mit Spielgeld. Ich melde jeden Trade." else "Demo-Bot aus – er kauft und verkauft nichts mehr von selbst.") }
            "reset" -> { if (!a.optBoolean("confirmed")) return Res.confirm("Demo-Depot auf ${DemoDepot.eur(DemoDepot.START)} Spielgeld zurücksetzen (alle Positionen und Trades weg).")
                DemoDepot.reset(ctx); Res.ok("Demo-Depot zurückgesetzt.") }
            else -> Res.error("Unbekannte Aktion.")
        } } catch (e: Exception) { Res.error("Börsendaten gerade nicht erreichbar (${e.message?.take(60)}).") }
    }
}

/** Bildschirm „Demo-Trading“. */
class TradingActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.load(Prefs(this)); ui = Ui(this)
        window.statusBarColor = Ui.BG; window.navigationBarColor = Ui.BG; Ui.lightBars(window)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(20), ui.px(22), ui.px(20), ui.px(40)) }
        setContentView(ScrollView(this).apply { background = ui.aurora(); addView(col) })
    }

    override fun onResume() { super.onResume(); build() }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun build() {
        col.removeAllViews()
        col.addView(ui.row().apply {
            addView(ui.text("‹", 30f).apply { setPadding(0, 0, ui.px(16), 0); setOnClickListener { finish() } })
            addView(LinearLayout(this@TradingActivity).apply { orientation = LinearLayout.VERTICAL
                addView(ui.text("Demo-Trading", 28f, Ui.FG, true)); addView(ui.text("Spielgeld · echte Kurse · keine Anlageberatung", 13f, Ui.MUTED)) }) })
        // Großer Schalter: Bot an/aus
        val botOn = AutoPilot.on(this, "trade_bot")
        col.addView(ui.card(false).apply {
            background = ui.round(if (botOn) Color.argb(40, 50, 220, 130) else Ui.CARD, 20, if (botOn) Color.argb(120, 50, 220, 130) else Color.argb(24, 255, 255, 255))
            addView(ui.weighted(LinearLayout(this@TradingActivity).apply { orientation = LinearLayout.VERTICAL
                addView(ui.text(if (botOn) "🤖 Bot läuft" else "🤖 Bot ist aus", 18f, if (botOn) Ui.GREEN else Ui.FG, true))
                addView(ui.text(if (botOn) "Kauft und verkauft allein im Hintergrund (alle 30 Min., wenn die Börse offen ist)." else "Einschalten, dann handelt er ganz allein – nur mit Spielgeld.", 12.5f, Ui.SOFT))
                DemoDepot.lastRunText(this@TradingActivity).takeIf { it.isNotBlank() && botOn }?.let { addView(ui.text(it, 11.5f, Ui.MUTED)) } }))
            addView(android.widget.Switch(this@TradingActivity).apply { isChecked = botOn
                setOnCheckedChangeListener { _, v -> AutoPilot.set(this@TradingActivity, "trade_bot", v)
                    toast(if (v) "Bot an – er handelt jetzt allein. Jeder Trade kommt als Benachrichtigung." else "Bot aus."); build() } })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(16) })
        val depot = ui.text("Lade Depot …", 15f, Ui.FG)
        col.addView(ui.card().apply { addView(depot) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(16) })
        thread { val s = try { DemoDepot.summary(this) } catch (e: Exception) { "Kurse gerade nicht abrufbar." }; runOnUiThread { depot.text = s } }
        val posRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(posRow)
        val pos = DemoDepot.load(this).getJSONObject("positions")
        for (s in pos.keys().asSequence().toList()) posRow.addView(ui.row().apply {
            addView(ui.weighted(ui.text(Market.name(s), 14.5f, Ui.FG, true)))
            addView(ui.text("Chart", 13f, Ui.RED).apply { setPadding(ui.px(10), ui.px(10), ui.px(10), ui.px(10)); setOnClickListener { chart(s) } })
            addView(ui.text("Verkaufen", 13f, Color.rgb(255, 110, 110)).apply { setPadding(ui.px(10), ui.px(10), 0, ui.px(10))
                setOnClickListener { thread { val r = DemoDepot.sell(this@TradingActivity, s); runOnUiThread { toast(Res.spoken(r)); build() } } } })
        })

        col.addView(ui.section("Signale"))
        val sigBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.button("Jetzt Markt scannen") {
            sigBox.removeAllViews(); sigBox.addView(ui.text("Prüfe ${Market.WATCH.size} Werte … (ca. 10–30 Sekunden)", 13f, Ui.MUTED))
            thread {
                val list = try { Market.scan(this) } catch (_: Exception) { emptyList() }
                runOnUiThread {
                    sigBox.removeAllViews()
                    if (list.isEmpty()) { sigBox.addView(ui.text("Kurse gerade nicht abrufbar.", 13f, Ui.AMBER)); return@runOnUiThread }
                    for (sig in list.take(8)) sigBox.addView(ui.card().apply {
                        val c = if (sig.score >= 3) Ui.GREEN else if (sig.score >= 1.5) Ui.AMBER else Ui.MUTED
                        addView(ui.text("${sig.name}  ·  %.1f Punkte".format(Locale.GERMANY, sig.score), 15f, c, true))
                        addView(ui.text(sig.reasons.take(3).joinToString(" · "), 12.5f, Ui.SOFT))
                        addView(ui.row().apply {
                            addView(ui.weighted(ui.text("Demo kaufen", 13.5f, Ui.RED, true).apply { setPadding(0, ui.px(8), 0, 0); setOnClickListener { buyDialog(sig.sym) } }))
                            addView(ui.text("Chart in TradingView", 13.5f, Ui.MUTED).apply { setPadding(0, ui.px(8), 0, 0); setOnClickListener { chart(sig.sym) } }) })
                    })
                }
            }
        })
        col.addView(sigBox)

        col.addView(ui.section("Bot-Regeln"))
        col.addView(ui.text("Verkauft bei Stop-Loss, −8 % Verlust, +15 % Gewinn oder wenn das Signal schwach wird. Kauft höchstens 3× am Tag, je max. 15 % des Spielgelds, max. 5 Aktien gleichzeitig, und nur, wenn die Börse offen ist. Ist der Bot aus, passiert nichts automatisch.", 12.5f, Ui.SOFT).apply { setPadding(ui.px(4), 0, 0, 0) })
        col.addView(ui.button("Bot jetzt einmal laufen lassen", false) { toast("Bot läuft …")
            thread { val r = DemoDepot.botRun(this); runOnUiThread { android.app.AlertDialog.Builder(this).setTitle("🤖 Demo-Bot")
                .setMessage(if (r.isEmpty()) "Gerade nichts gehandelt – kein überzeugendes Signal oder Börse zu." else r.joinToString("\n\n")).setPositiveButton("OK") { _, _ -> build() }.show() } } })
        col.addView(ui.button("Depot zurücksetzen", false) {
            android.app.AlertDialog.Builder(this).setTitle("Zurücksetzen?").setMessage("Wieder ${DemoDepot.eur(DemoDepot.START)} Spielgeld, alle Trades weg.")
                .setPositiveButton("Zurücksetzen") { _, _ -> DemoDepot.reset(this); build() }.setNegativeButton("Abbrechen", null).show() })
        col.addView(ui.text("Nur Spielgeld – Jarvis handelt nie mit echtem Geld. Die Signale sind einfache Statistik (Trend, RSI, Schwung) und keine Vorhersage oder Anlageberatung. Kurse: Yahoo Finance & Binance, teils verzögert.", 11.5f, Ui.MUTED).apply { setPadding(0, ui.px(14), 0, 0) })
    }

    private fun chart(sym: String) = try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Market.tradingViewUrl(sym)))) } catch (_: Exception) {}

    private fun buyDialog(sym: String) {
        val e = EditText(this).apply { hint = "Betrag in € (Spielgeld)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText("500")
            setTextColor(Ui.FG); setHintTextColor(Ui.MUTED) }
        android.app.AlertDialog.Builder(this).setTitle("Demo-Kauf: ${Market.name(sym)}")
            .setView(LinearLayout(this).apply { setPadding(ui.px(20), ui.px(8), ui.px(20), 0); addView(e, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Kaufen") { _, _ -> val amt = e.text.toString().replace(',', '.').toDoubleOrNull() ?: return@setPositiveButton
                thread { val r = DemoDepot.buy(this, sym, amt, 8.0); runOnUiThread { toast(Res.spoken(r)); build() } } }
            .setNegativeButton("Abbrechen", null).show()
    }
}
