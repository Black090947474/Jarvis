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
    fun scan(ctx: Context, syms: Collection<String> = BotConfig.watch(ctx)): List<Signal> {
        val pool = Executors.newFixedThreadPool(5)
        val futures = syms.map { s -> pool.submit<Signal?> { analyze(ctx, s) } }
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
    private fun log(ctx: Context, o: JSONObject) = store(ctx).save("demo_trades",
        (trades(ctx) + o.put("t", System.currentTimeMillis()).put("broker", if (Alpaca.active(ctx)) "alpaca" else "jarvis")).takeLast(500))
    fun eur(v: Double) = "%,.2f €".format(Locale.GERMANY, v)
    fun usd(v: Double) = "%,.2f $".format(Locale.GERMANY, v)
    /** Betrag in der Währung des aktiven Kontos (Jarvis-Depot €, Alpaca $). */
    fun money(ctx: Context, v: Double) = if (Alpaca.active(ctx)) usd(v) else eur(v)

    /** Einheitliche Sicht auf eine Position (beide Konten). Werte in Kontowährung. */
    data class Pos(val sym: String, val qty: Double, val cost: Double, val value: Double, val price: Double)

    fun positions(ctx: Context): List<Pos> {
        if (Alpaca.active(ctx)) {
            val a = Alpaca.positions(ctx)
            return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                Pos(Alpaca.fromAlpaca(o.optString("symbol"), o.optString("asset_class")), o.optString("qty").toDoubleOrNull() ?: 0.0,
                    o.optString("cost_basis").toDoubleOrNull() ?: 0.0, o.optString("market_value").toDoubleOrNull() ?: 0.0, o.optString("current_price").toDoubleOrNull() ?: 0.0) }
        }
        val pos = load(ctx).getJSONObject("positions")
        return pos.keys().asSequence().toList().mapNotNull { s -> val p = pos.getJSONObject(s)
            val price = try { Market.priceEur(s) } catch (_: Exception) { null } ?: return@mapNotNull Pos(s, p.optDouble("qty"), p.optDouble("cost"), p.optDouble("cost"), 0.0)
            Pos(s, p.optDouble("qty"), p.optDouble("cost"), price * p.optDouble("qty"), price) }
    }

    fun cash(ctx: Context): Double = if (Alpaca.active(ctx)) Alpaca.account(ctx).optString("cash").toDoubleOrNull() ?: 0.0 else load(ctx).optDouble("cash")

    private fun peakKey(sym: String) = "peak_" + sym

    fun buy(ctx: Context, sym: String, amount: Double, stopPct: Double = 0.0, bot: Boolean = false): String {
        if (amount <= 0) return Res.error("Für wie viel ${if (Alpaca.active(ctx)) "Dollar" else "Euro"}?")
        if (Alpaca.active(ctx)) {
            if (Alpaca.symbol(sym) == null) return Res.error("${Market.name(sym)} gibt es im Alpaca-Demo-Konto nicht – dort nur US-Aktien, ETFs und Krypto.")
            return try {
                val cash = cash(ctx); if (amount > cash + 0.01) return Res.error("Nicht genug Demo-Geld – frei sind ${usd(cash)}.")
                Alpaca.order(ctx, sym, amount)
                sp(ctx).edit().remove(peakKey(sym)).apply()
                log(ctx, JSONObject().put("side", "KAUF").put("sym", sym).put("eur", amount).put("bot", bot))
                Res.ok("Alpaca-Demo-Kauf: ${Market.name(sym)} für ${usd(amount)} (Order ist raus" + (if (Alpaca.symbol(sym)!!.contains('/')) ")." else "; außerhalb der US-Handelszeit wird sie zur Börsenöffnung ausgeführt).") +
                    " Du siehst sie in der Alpaca-App.")
            } catch (e: Exception) { Res.error("Alpaca hat abgelehnt: ${e.message}") }
        }
        val d = load(ctx); val cash = d.optDouble("cash")
        if (amount > cash + 0.01) return Res.error("Nicht genug Spielgeld – frei sind ${eur(cash)}.")
        val price = Market.priceEur(sym) ?: return Res.error("Für ${Market.name(sym)} gibt es gerade keinen Kurs.")
        val qty = amount / price
        val pos = d.getJSONObject("positions").optJSONObject(sym) ?: JSONObject().put("qty", 0.0).put("cost", 0.0)
        pos.put("qty", pos.optDouble("qty") + qty).put("cost", pos.optDouble("cost") + amount)
        if (stopPct > 0) pos.put("stop", price * (1 - stopPct / 100))
        d.getJSONObject("positions").put(sym, pos); d.put("cash", cash - amount); save(ctx, d)
        sp(ctx).edit().remove(peakKey(sym)).apply()
        log(ctx, JSONObject().put("side", "KAUF").put("sym", sym).put("qty", qty).put("price", price).put("eur", amount).put("bot", bot))
        return Res.ok("Demo-Kauf: ${Market.name(sym)} für ${eur(amount)} (%.4f Stück à ${eur(price)}).".format(Locale.GERMANY, qty) + " Freies Spielgeld: ${eur(cash - amount)}.")
    }

    fun sell(ctx: Context, sym: String, part: Double = 1.0, bot: Boolean = false, why: String = ""): String {
        val f = part.coerceIn(0.01, 1.0)
        if (Alpaca.active(ctx)) {
            val p = try { positions(ctx).firstOrNull { it.sym == sym } } catch (e: Exception) { return Res.error("Alpaca nicht erreichbar: ${e.message}") }
                ?: return Res.error("${Market.name(sym)} ist nicht im Alpaca-Depot.")
            return try {
                Alpaca.close(ctx, sym, f)
                val value = p.value * f; val pl = (p.value - p.cost) * f
                if (f >= 0.999) sp(ctx).edit().remove(peakKey(sym)).apply()
                log(ctx, JSONObject().put("side", "VERKAUF").put("sym", sym).put("eur", value).put("pl", pl).put("bot", bot).put("why", why))
                Res.ok("Alpaca-Demo-Verkauf: ${Market.name(sym)} für ca. ${usd(value)} – " + (if (pl >= 0) "Gewinn ${usd(pl)} 🎉" else "Verlust ${usd(-pl)}") + (if (why.isNotBlank()) " ($why)" else "") + ".")
            } catch (e: Exception) { Res.error("Alpaca hat abgelehnt: ${e.message}") }
        }
        val d = load(ctx); val pos = d.getJSONObject("positions").optJSONObject(sym) ?: return Res.error("${Market.name(sym)} ist nicht im Depot.")
        val price = Market.priceEur(sym) ?: return Res.error("Gerade kein Kurs für ${Market.name(sym)}.")
        val qty = pos.optDouble("qty") * f; val cost = pos.optDouble("cost") * f
        val value = qty * price; val pl = value - cost
        if (f >= 0.999) { d.getJSONObject("positions").remove(sym); sp(ctx).edit().remove(peakKey(sym)).apply() } else pos.put("qty", pos.optDouble("qty") - qty).put("cost", pos.optDouble("cost") - cost)
        d.put("cash", d.optDouble("cash") + value); save(ctx, d)
        log(ctx, JSONObject().put("side", "VERKAUF").put("sym", sym).put("qty", qty).put("price", price).put("eur", value).put("pl", pl).put("bot", bot).put("why", why))
        return Res.ok("Demo-Verkauf: ${Market.name(sym)} für ${eur(value)} – " + (if (pl >= 0) "Gewinn ${eur(pl)} 🎉" else "Verlust ${eur(-pl)}") + (if (why.isNotBlank()) " ($why)" else "") + ".")
    }

    /** Depot-Übersicht mit aktuellen Kursen (merkt sich nebenbei den Tageswert für die Kurve). */
    fun summary(ctx: Context): String {
        val ps = positions(ctx)
        val lines = ps.map { p -> val pl = p.value - p.cost
            "• ${Market.name(p.sym)}: ${money(ctx, p.value)} (" + (if (pl >= 0) "+" else "") + money(ctx, pl) + ", " + "%+.1f %%".format(Locale.GERMANY, if (p.cost > 0) pl / p.cost * 100 else 0.0) + ")" }
        val total: Double; val cash: Double; val start: Double; val title: String
        if (Alpaca.active(ctx)) {
            val acc = Alpaca.account(ctx)
            total = acc.optString("portfolio_value").toDoubleOrNull() ?: 0.0; cash = acc.optString("cash").toDoubleOrNull() ?: 0.0
            start = BotConfig.history(ctx).firstOrNull()?.second ?: total; title = "Alpaca-Demo-Konto"
        } else {
            val d = load(ctx); cash = d.optDouble("cash"); total = cash + ps.sumOf { it.value }; start = d.optDouble("start", START); title = "Jarvis-Demo-Depot"
        }
        try { BotConfig.record(ctx, total) } catch (_: Exception) {}
        return "$title: ${money(ctx, total)} (" + "%+.1f %%".format(Locale.GERMANY, (total / start - 1) * 100) + " seit Start)\nFreies Demo-Geld: ${money(ctx, cash)}" +
            (if (lines.isEmpty()) "\nNoch keine Aktien im Depot." else "\n" + lines.joinToString("\n")) +
            (BotConfig.vsMarket(ctx)?.let { "\n\n" + it } ?: "")
    }

    fun reset(ctx: Context) {
        save(ctx, JSONObject().put("cash", START).put("positions", JSONObject()).put("start", START))
        store(ctx).save("demo_trades", trades(ctx).filter { it.optString("broker", "jarvis") != "jarvis" })
        BotConfig.clearHistory(ctx)
    }

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
     * Der automatische Bot (läuft nur, wenn eingeschaltet). Verkauft per Trailing-Stop (zieht mit dem Höchstkurs mit,
     * Abstand je nach Risiko-Stufe) oder wenn das Signal kippt. Kauft gute Signale aus der Beobachtungsliste –
     * begrenzt nach Risiko-Stufe, nichts zurückkaufen, was in den letzten 3 Tagen verkauft wurde.
     * Rückgabe: die gemachten Trades (leer = nichts getan).
     */
    fun botRun(ctx: Context): List<String> {
        val out = ArrayList<String>()
        val r = BotConfig.risk(ctx)
        val ps = try { positions(ctx) } catch (_: Exception) { return emptyList() }
        for (p in ps) {
            if (!marketOpen(p.sym) || p.price <= 0) continue
            val peak = maxOf(sp(ctx).getFloat(peakKey(p.sym), 0f).toDouble(), p.price, if (p.qty > 0) p.cost / p.qty else 0.0)
            sp(ctx).edit().putFloat(peakKey(p.sym), peak.toFloat()).apply()
            val stop = peak * (1 - r.trail)
            val gainPct = if (p.cost > 0) (p.value / p.cost - 1) * 100 else 0.0
            when {
                p.price <= stop -> out += Res.spoken(sell(ctx, p.sym, 1.0, true, "Trailing-Stop: −${(r.trail * 100).toInt()} % vom Hoch" + (if (gainPct > 0) ", Gewinn gesichert" else "")))
                Market.analyze(ctx, p.sym)?.let { it.score < 0 } == true -> out += Res.spoken(sell(ctx, p.sym, 1.0, true, "Signal ist gekippt"))
            }
        }
        val today = LocalDate.now().toString()
        val broker = if (Alpaca.active(ctx)) "alpaca" else "jarvis"
        val mine = trades(ctx).filter { it.optString("broker", "jarvis") == broker }
        val buysToday = mine.count { it.optBoolean("bot") && it.optString("side") == "KAUF" &&
            java.time.Instant.ofEpochMilli(it.optLong("t")).atZone(BERLIN).toLocalDate().toString() == today }
        val recentlySold = mine.filter { it.optString("side") == "VERKAUF" && System.currentTimeMillis() - it.optLong("t") < 3 * 86_400_000L }.map { it.optString("sym") }.toSet()
        val held = try { positions(ctx).map { it.sym }.toSet() } catch (_: Exception) { ps.map { it.sym }.toSet() }
        if (held.size < r.maxPos && buysToday < r.maxBuysDay) {
            val best = Market.scan(ctx).firstOrNull { it.score >= r.minScore && marketOpen(it.sym) && it.sym !in held && it.sym !in recentlySold }
            if (best != null) {
                val c = try { cash(ctx) } catch (_: Exception) { 0.0 }
                val total = c + ps.sumOf { it.value }
                val amt = minOf(c * r.perTrade * 1.6, total * r.perTrade).coerceAtMost(c)
                if (amt >= 50) out += Res.spoken(buy(ctx, best.sym, amt, 0.0, true)) + " Grund: " + best.reasons.take(2).joinToString(", ")
            }
        }
        try { BotConfig.record(ctx, (try { cash(ctx) } catch (_: Exception) { 0.0 }) + positions(ctx).sumOf { it.value }) } catch (_: Exception) {}
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
{"name":"trading","description":"Jarvis-Demo-Depot mit Spielgeld (NIE echtes Geld) und echten Kursen. scan: Beobachtungsliste prüfen, welche Werte gerade die besten Trend-/Schwung-Signale haben (keine Garantie!). analyze: einen Wert bewerten (symbol = Name oder Kürzel, z. B. Apple, SAP, Bitcoin). buy: Demo-Kauf (symbol, amount in Kontowährung: Jarvis-Depot €, Alpaca-Demo $). sell: Demo-Verkauf (symbol, part 0–1, Standard alles). portfolio: Depot mit Gewinn/Verlust. trades: letzte Trades. chart: Chart in TradingView öffnen. bot: automatischen Demo-Bot an/aus (on). stats: Leistung (Trefferquote, Vergleich mit S&P 500). risk: Risiko-Stufe (value vorsichtig|normal|mutig). account: Konto wechseln (value jarvis|alpaca). reset: Jarvis-Depot zurücksetzen (confirmed). Immer erwähnen: Signale sind keine Vorhersage und keine Anlageberatung.",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["scan","analyze","buy","sell","portfolio","trades","chart","bot","stats","risk","account","reset"]},
  "symbol":{"type":"string"},"value":{"type":"string"},"amount":{"type":"number"},"stop":{"type":"number"},"part":{"type":"number"},"on":{"type":"boolean"},"confirmed":{"type":"boolean"}},"required":["action"]}}
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
            "bot" -> { AutoPilot.set(ctx, "trade_bot", a.optBoolean("on", true)); Res.ok(if (a.optBoolean("on", true)) "Demo-Bot an: Er handelt jetzt ganz allein im Hintergrund – alle 30 Minuten, wenn die Börse offen ist (Krypto auch nachts und am Wochenende). Er verkauft per Trailing-Stop oder wenn das Signal kippt und kauft gute Signale, alles mit Demo-Geld. Ich melde jeden Trade." else "Demo-Bot aus – er kauft und verkauft nichts mehr von selbst.") }
            "stats" -> Res.ok(BotConfig.stats(ctx) + (BotConfig.vsMarket(ctx)?.let { "\n" + it } ?: "\nVergleich mit dem S&P 500 erscheint nach ein paar Tagen."))
            "risk" -> { val k = a.optString("value").lowercase(); if (k !in BotConfig.RISKS) return Res.error("Vorsichtig, normal oder mutig?")
                BotConfig.setRisk(ctx, k); val r = BotConfig.risk(ctx); Res.ok("Risiko-Stufe ${r.label}: Trailing-Stop ${(r.trail * 100).toInt()} %, je Kauf ca. ${(r.perTrade * 100).toInt()} %, max. ${r.maxPos} Werte.") }
            "account" -> { val alp = a.optString("value").lowercase().contains("alpaca")
                if (alp && !Alpaca.hasKeys(ctx)) return t.launch(Intent(ctx, TradingActivity::class.java), "Für Alpaca brauche ich erst die zwei Demo-Schlüssel – tipp auf der Seite auf „Konto“.")
                Alpaca.setActive(ctx, alp); Res.ok(if (alp) "Der Bot handelt jetzt im Alpaca-Demo-Konto." else "Der Bot handelt jetzt im Jarvis-Demo-Depot.") }
            "reset" -> { if (Alpaca.active(ctx)) return Res.error("Das Alpaca-Demo-Konto setzt du auf der Alpaca-Website zurück.")
                if (!a.optBoolean("confirmed")) return Res.confirm("Demo-Depot auf ${DemoDepot.eur(DemoDepot.START)} Spielgeld zurücksetzen (alle Positionen und Trades weg).")
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
        // Risiko-Stufe
        val rk = BotConfig.riskKey(this)
        col.addView(ui.row().apply {
            for ((k, r) in BotConfig.RISKS) addView(ui.text(r.label, 13.5f, if (k == rk) Color.rgb(3, 16, 32) else Ui.SOFT, k == rk).apply {
                gravity = android.view.Gravity.CENTER; setPadding(ui.px(8), ui.px(9), ui.px(8), ui.px(9))
                background = if (k == rk) ui.gradient(Ui.RED, Ui.ACCENT2, 14) else ui.round(Ui.CARD2, 14, Color.argb(24, 255, 255, 255))
                setOnClickListener { BotConfig.setRisk(this@TradingActivity, k); build() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.px(6) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(10) })

        // Konto: Jarvis-Depot oder Alpaca-Demo
        val alp = Alpaca.active(this)
        col.addView(ui.card().apply {
            addView(ui.text(if (alp) "Konto: Alpaca-Demo (Paper Trading, $)" else "Konto: Jarvis-Demo-Depot (€)", 14.5f, Ui.FG, true))
            addView(ui.text(if (alp) "Trades siehst du auch in der Alpaca-App und – wenn du Alpaca dort verbindest – in TradingView." else "Tippen, um ein Alpaca-Demo-Konto zu verbinden (US-Aktien & Krypto, 100.000 $ Spielgeld).", 12.5f, Ui.SOFT))
            setOnClickListener { alpacaDialog() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(10) })

        val depot = ui.text("Lade Depot …", 15f, Ui.FG)
        col.addView(ui.card().apply { addView(depot) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(10) })
        val posRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(posRow)
        col.addView(ui.section("Leistung"))
        val chartBox = android.widget.FrameLayout(this)
        col.addView(ui.card().apply { addView(chartBox, LinearLayout.LayoutParams(-1, ui.px(170))) })
        val statsText = ui.text("", 13f, Ui.SOFT).apply { setPadding(ui.px(4), ui.px(6), 0, 0) }
        col.addView(statsText)
        thread {
            val s = try { DemoDepot.summary(this) } catch (e: Exception) { "Konto gerade nicht erreichbar (${e.message?.take(60)})." }
            val ps = try { DemoDepot.positions(this) } catch (_: Exception) { emptyList() }
            val hist = BotConfig.history(this); val st = BotConfig.stats(this)
            runOnUiThread {
                depot.text = s
                for (p in ps) posRow.addView(ui.row().apply {
                    addView(ui.weighted(ui.text(Market.name(p.sym), 14.5f, Ui.FG, true)))
                    addView(ui.text("Chart", 13f, Ui.RED).apply { setPadding(ui.px(10), ui.px(10), ui.px(10), ui.px(10)); setOnClickListener { chart(p.sym) } })
                    addView(ui.text("Verkaufen", 13f, Color.rgb(255, 110, 110)).apply { setPadding(ui.px(10), ui.px(10), 0, ui.px(10))
                        setOnClickListener { thread { val r = DemoDepot.sell(this@TradingActivity, p.sym); runOnUiThread { toast(Res.spoken(r)); build() } } } })
                })
                chartBox.addView(EquityChartView(this, hist))
                statsText.text = st
            }
        }

        col.addView(ui.section("Signale"))
        val sigBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.button("Jetzt Markt scannen") {
            val w = BotConfig.watch(this)
            sigBox.removeAllViews(); sigBox.addView(ui.text("Prüfe ${w.size} Werte … (ca. 10–30 Sekunden)", 13f, Ui.MUTED))
            thread {
                val list = try { Market.scan(this, w) } catch (_: Exception) { emptyList() }
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

        col.addView(ui.section("Meine Liste"))
        val wl = EditText(this).apply { setText(BotConfig.ownList(this@TradingActivity)); hint = "Leer = Standardliste. Z. B.: Apple, Tesla, SAP, Bitcoin"
            setTextColor(Ui.FG); setHintTextColor(Ui.MUTED); background = ui.round(Ui.CARD2, 14); setPadding(ui.px(14), ui.px(12), ui.px(14), ui.px(12)) }
        col.addView(wl)
        col.addView(ui.button("Liste speichern", false) {
            BotConfig.setOwnList(this, wl.text.toString())
            val n = BotConfig.watch(this).size
            toast(if (BotConfig.ownList(this).isBlank()) "Standardliste aktiv." else "$n Werte erkannt" + if (Alpaca.active(this)) " (bei Alpaca nur US & Krypto)." else ".") })

        val r = BotConfig.risk(this)
        col.addView(ui.section("Bot-Regeln (${r.label})"))
        col.addView(ui.text("Trailing-Stop: verkauft, wenn der Kurs ${(r.trail * 100).toInt()} % unter seinen Höchststand seit dem Kauf fällt – so werden Gewinne mitgezogen. " +
            "Verkauft auch, wenn das Signal kippt. Kauft höchstens ${r.maxBuysDay}× am Tag, je ca. ${(r.perTrade * 100).toInt()} % des Depots, max. ${r.maxPos} Werte, ab ${"%.1f".format(Locale.GERMANY, r.minScore)} Punkten, nur bei offener Börse. Ist der Bot aus, passiert nichts automatisch.", 12.5f, Ui.SOFT).apply { setPadding(ui.px(4), 0, 0, 0) })
        col.addView(ui.button("Bot jetzt einmal laufen lassen", false) { toast("Bot läuft …")
            thread { val res = DemoDepot.botRun(this); runOnUiThread { android.app.AlertDialog.Builder(this).setTitle("🤖 Demo-Bot")
                .setMessage(if (res.isEmpty()) "Gerade nichts gehandelt – kein überzeugendes Signal oder Börse zu." else res.joinToString("\n\n")).setPositiveButton("OK") { _, _ -> build() }.show() } } })
        if (!alp) col.addView(ui.button("Jarvis-Depot zurücksetzen", false) {
            android.app.AlertDialog.Builder(this).setTitle("Zurücksetzen?").setMessage("Wieder ${DemoDepot.eur(DemoDepot.START)} Spielgeld, alle Trades weg.")
                .setPositiveButton("Zurücksetzen") { _, _ -> DemoDepot.reset(this); build() }.setNegativeButton("Abbrechen", null).show() })
        col.addView(ui.text("Nur Demo-Geld – Jarvis kann technisch nicht mit echtem Geld handeln (Alpaca: fest das Demo-System). Die Signale sind einfache Statistik (Trend, RSI, Schwung) und keine Vorhersage oder Anlageberatung. Kurse: Yahoo Finance & Binance, teils verzögert.", 11.5f, Ui.MUTED).apply { setPadding(0, ui.px(14), 0, 0) })
    }

    private fun alpacaDialog() {
        fun f(hint: String, v: String) = EditText(this).apply { this.hint = hint; setText(v); setTextColor(Ui.FG); setHintTextColor(Ui.MUTED); textSize = 14f }
        val id = f("API Key ID (beginnt mit PK…)", Alpaca.keyId(this)); val sec = f("Secret Key", Alpaca.secret(this))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(20), ui.px(4), ui.px(20), 0)
            addView(ui.text("1. Kostenloses Demo-Konto anlegen (nur E-Mail; bei Brokern meist ab 18 – sonst mit einem Elternteil).\n2. Im Paper-Dashboard „API Keys“ → „Generate“.\n3. Beide Schlüssel hier einfügen.", 13f, Ui.SOFT))
            addView(ui.text("① Konto anlegen", 14f, Ui.RED, true).apply { setPadding(0, ui.px(10), 0, ui.px(4)); setOnClickListener { open(Alpaca.SIGNUP) } })
            addView(ui.text("② Schlüssel holen", 14f, Ui.RED, true).apply { setPadding(0, ui.px(4), 0, ui.px(8)); setOnClickListener { open(Alpaca.KEYS) } })
            addView(id); addView(sec) }
        android.app.AlertDialog.Builder(this).setTitle("Alpaca-Demo-Konto").setView(box)
            .setPositiveButton("Verbinden & nutzen") { _, _ ->
                Alpaca.saveKeys(this, id.text.toString(), sec.text.toString())
                thread {
                    val ok = try { Alpaca.setActive(this, true); Alpaca.account(this); true } catch (e: Exception) { Alpaca.setActive(this, false); runOnUiThread { toast("Verbindung fehlgeschlagen: ${e.message}") }; false }
                    runOnUiThread { if (ok) toast("Alpaca-Demo verbunden – der Bot handelt jetzt dort."); build() }
                }
            }
            .setNeutralButton("Jarvis-Depot nutzen") { _, _ -> Alpaca.setActive(this, false); build() }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun open(url: String) = try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}

    private fun chart(sym: String) = try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Market.tradingViewUrl(sym)))) } catch (_: Exception) {}

    private fun buyDialog(sym: String) {
        val e = EditText(this).apply { hint = if (Alpaca.active(this@TradingActivity)) "Betrag in $ (Demo-Geld)" else "Betrag in € (Spielgeld)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText("500")
            setTextColor(Ui.FG); setHintTextColor(Ui.MUTED) }
        android.app.AlertDialog.Builder(this).setTitle("Demo-Kauf: ${Market.name(sym)}")
            .setView(LinearLayout(this).apply { setPadding(ui.px(20), ui.px(8), ui.px(20), 0); addView(e, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Kaufen") { _, _ -> val amt = e.text.toString().replace(',', '.').toDoubleOrNull() ?: return@setPositiveButton
                thread { val r = DemoDepot.buy(this, sym, amt, 0.0); runOnUiThread { toast(Res.spoken(r)); build() } } }
            .setNegativeButton("Abbrechen", null).show()
    }
}
