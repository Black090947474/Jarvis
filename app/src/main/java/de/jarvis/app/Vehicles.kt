package de.jarvis.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import de.jarvis.app.tools.JarvisTool
import de.jarvis.app.tools.JsonStore
import de.jarvis.app.tools.Res
import de.jarvis.app.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** Auto & Motorrad: Kilometer, Tanken, Kosten, TÜV, Wartung, Rundenzeiten. Alles bleibt auf dem Handy. */
object Vehicles {
    private fun store(ctx: Context) = JsonStore(ctx)
    fun all(ctx: Context) = store(ctx).list("vehicles")
    private fun save(ctx: Context, l: List<JSONObject>) = store(ctx).save("vehicles", l)

    fun find(ctx: Context, name: String?): JSONObject? {
        val l = all(ctx)
        if (name.isNullOrBlank()) return l.firstOrNull { it.optBoolean("main") } ?: l.firstOrNull()
        return l.firstOrNull { it.optString("name").equals(name, true) } ?: l.firstOrNull { it.optString("name").contains(name, true) }
    }

    fun put(ctx: Context, v: JSONObject) = save(ctx, all(ctx).filter { it.optInt("id") != v.optInt("id") } + v)

    fun add(ctx: Context, name: String, type: String): JSONObject {
        val v = JSONObject().put("id", store(ctx).newId()).put("name", name).put("type", type).put("km", 0).put("main", all(ctx).isEmpty())
        put(ctx, v); return v
    }

    fun log(ctx: Context, key: String) = store(ctx).list(key)
    fun addLog(ctx: Context, key: String, o: JSONObject) = store(ctx).save(key, (log(ctx, key) + o).takeLast(2000))

    private fun eur(d: Double) = "%.2f €".format(Locale.GERMANY, d)

    fun status(ctx: Context, v: JSONObject): String {
        val id = v.optInt("id")
        val fuel = log(ctx, "fuel_log").filter { it.optInt("veh") == id }.sortedBy { it.optInt("km") }
        val costs = log(ctx, "vehicle_costs").filter { it.optInt("veh") == id }
        val year = LocalDate.now().year.toString()
        val sb = StringBuilder("${v.optString("name")} (${v.optString("type")}): ${v.optInt("km")} km")
        if (fuel.size >= 2) {
            val dist = fuel.last().optInt("km") - fuel.first().optInt("km")
            val liters = fuel.drop(1).sumOf { it.optDouble("liters") }
            if (dist > 0) sb.append("\nVerbrauch: %.1f l/100 km".format(Locale.GERMANY, liters / dist * 100))
        }
        val spent = fuel.filter { it.optString("date").startsWith(year) }.sumOf { it.optDouble("price") } + costs.filter { it.optString("date").startsWith(year) }.sumOf { it.optDouble("amount") }
        if (spent > 0) sb.append("\nKosten $year: ${eur(spent)}" + (if (v.optInt("km") > 0) "" else ""))
        v.optString("tuev").takeIf { it.isNotBlank() }?.let { sb.append("\nTÜV/HU fällig: $it") }
        if (v.optInt("service_km") > 0) sb.append("\nNächste Wartung bei ${v.optInt("service_km")} km" + (if (v.optInt("km") >= v.optInt("service_km")) " – jetzt fällig!" else " (noch ${v.optInt("service_km") - v.optInt("km")} km)"))
        v.optString("service_date").takeIf { it.isNotBlank() }?.let { sb.append("\nNächste Wartung am $it") }
        return sb.toString()
    }

    /** Täglich (Autopilot-Morgen): TÜV, Wartung. */
    fun dailyCheck(ctx: Context) {
        val now = YearMonth.now()
        for (v in all(ctx)) {
            val name = v.optString("name")
            val tuev = runCatching { val p = v.optString("tuev").split('/', '.', '-'); YearMonth.of(p[1].trim().toInt().let { if (it < 100) 2000 + it else it }, p[0].trim().toInt()) }.getOrNull()
            val keyBase = "veh_${v.optInt("id")}_" + LocalDate.now().let { it.year * 100 + it.monthValue }
            val sp = ctx.getSharedPreferences("autopilot", Context.MODE_PRIVATE)
            if (tuev != null && !tuev.isAfter(now.plusMonths(1)) && !sp.getBoolean(keyBase + "_tuev", false)) {
                sp.edit().putBoolean(keyBase + "_tuev", true).apply()
                AutoPilot.post(ctx, 88_400 + v.optInt("id") % 50, "🚗 $name: TÜV/HU " + (if (tuev.isBefore(now)) "überfällig!" else "bald fällig ($tuev)"),
                    "Termin bei TÜV, DEKRA oder GTÜ machen. Sag danach „TÜV neu bis …“.")
            }
            val sd = runCatching { LocalDate.parse(v.optString("service_date")) }.getOrNull()
            if ((sd != null && !sd.isAfter(LocalDate.now().plusDays(7)) || v.optInt("service_km") in 1..v.optInt("km")) && !sp.getBoolean(keyBase + "_svc", false)) {
                sp.edit().putBoolean(keyBase + "_svc", true).apply()
                AutoPilot.post(ctx, 88_450 + v.optInt("id") % 50, "🔧 $name: Wartung fällig", "Öl, Kette/Bremsen, Reifen – Zeit für den Service.")
            }
        }
    }

    /** Auto-Bluetooth verbunden → Wetter vor der Fahrt. */
    fun beforeDrive(ctx: Context) {
        val loc = de.jarvis.app.tools.Geo.lastKnown(ctx) ?: return
        val j = try { JSONObject(de.jarvis.app.tools.WeatherTool.http("https://api.open-meteo.com/v1/forecast?latitude=${loc.first}&longitude=${loc.second}&current=temperature_2m,weather_code,wind_gusts_10m&timezone=auto")) } catch (_: Exception) { return }
        val c = j.optJSONObject("current") ?: return
        val t = c.optDouble("temperature_2m"); val code = c.optInt("weather_code"); val gust = c.optDouble("wind_gusts_10m")
        val warn = mutableListOf<String>()
        if (t <= 3) warn += "Nur %.0f °C – Glättegefahr, besonders auf Brücken.".format(t)
        if (code in 71..77 || code in 85..86) warn += "Es schneit."
        if (code in 95..99) warn += "Gewitter."
        if (code in 61..67 || code in 80..82) warn += "Regen – längerer Bremsweg."
        if (code in 45..48) warn += "Nebel – Licht an."
        if (gust >= 60) warn += "Starke Böen (${gust.toInt()} km/h)."
        if (warn.isNotEmpty()) AutoPilot.post(ctx, 88_480, "🚗 Vorsicht auf der Fahrt", warn.joinToString(" "))
    }
}

object VehicleTool : JarvisTool {
    override val name = "vehicle"
    override val group = "device"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"vehicle","description":"Auto/Motorrad/Crossbike verwalten. add (name, type auto|motorrad|crossbike|roller). km: Kilometerstand setzen (km). fuel: Tanken eintragen (liters, price Gesamt-€, km optional). cost: Kosten (amount €, what z. B. Reifen, Versicherung). tuev: TÜV/HU fällig (date MM/YYYY). service: nächste Wartung (km oder date YYYY-MM-DD). status: Übersicht (Verbrauch, Kosten, TÜV). laps: gespeicherte Rundenzeiten (Motocross). open: Tacho & Rundenzeit-Bildschirm. name = welches Fahrzeug (optional).",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["add","km","fuel","cost","tuev","service","status","laps","open"]},
  "name":{"type":"string"},"type":{"type":"string"},"km":{"type":"integer"},"liters":{"type":"number"},"price":{"type":"number"},
  "amount":{"type":"number"},"what":{"type":"string"},"date":{"type":"string"}},"required":["action"]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String {
        val ctx = t.ctx
        val act = a.optString("action")
        if (act == "add") {
            val n = a.optString("name").trim().ifBlank { return Res.error("Wie heißt das Fahrzeug?") }
            Vehicles.add(ctx, n, a.optString("type").ifBlank { "auto" }); return Res.ok("$n angelegt. Sag z. B. „Kilometerstand 12.500“ oder „TÜV bis 05/2027“.")
        }
        if (act == "open") return t.launch(android.content.Intent(ctx, VehicleActivity::class.java), "Tacho & Rundenzeit geöffnet.")
        if (act == "laps") {
            val s = Vehicles.log(ctx, "lap_sessions").takeLast(5)
            if (s.isEmpty()) return Res.ok("Noch keine Rundenzeiten gespeichert. Öffne „Fahrzeug“ → Rundenzeit.")
            return Res.ok(s.reversed().joinToString("\n") { o -> val l = o.optJSONArray("laps") ?: JSONArray()
                val best = (0 until l.length()).minOfOrNull { l.getLong(it) } ?: 0
                "${o.optString("date")} ${o.optString("track")}: ${l.length()} Runden, beste ${VehicleActivity.fmt(best)}" })
        }
        val v = Vehicles.find(ctx, a.optString("name")) ?: return Res.error("Noch kein Fahrzeug angelegt. Sag z. B. „Leg mein Motorrad KTM an“.")
        val today = LocalDate.now().toString()
        return when (act) {
            "km" -> { val km = a.optInt("km", -1).takeIf { it >= 0 } ?: return Res.error("Welcher Kilometerstand?")
                v.put("km", km); Vehicles.put(ctx, v)
                val lastFuel = Vehicles.log(ctx, "fuel_log").filter { it.optInt("veh") == v.optInt("id") }.maxByOrNull { it.optInt("km") }
                val range = v.optInt("range", if (v.optString("type") == "auto") 550 else 220)
                val hint = if (lastFuel != null && km - lastFuel.optInt("km") > range * 0.8) " Seit dem letzten Tanken ${km - lastFuel.optInt("km")} km – bald tanken!" else ""
                Res.ok("Kilometerstand ${v.optString("name")}: $km km.$hint") }
            "fuel" -> { val l = a.optDouble("liters", 0.0); if (l <= 0) return Res.error("Wie viele Liter?")
                val km = a.optInt("km", v.optInt("km")); if (km > v.optInt("km")) { v.put("km", km); Vehicles.put(ctx, v) }
                Vehicles.addLog(ctx, "fuel_log", JSONObject().put("veh", v.optInt("id")).put("date", today).put("liters", l).put("price", a.optDouble("price", 0.0)).put("km", km))
                Res.ok("Getankt: %.1f l".format(Locale.GERMANY, l) + (if (a.has("price")) " für %.2f €".format(Locale.GERMANY, a.optDouble("price")) else "") + ".\n" + Vehicles.status(ctx, v)) }
            "cost" -> { val amt = a.optDouble("amount", 0.0); if (amt <= 0) return Res.error("Wie viel hat es gekostet?")
                Vehicles.addLog(ctx, "vehicle_costs", JSONObject().put("veh", v.optInt("id")).put("date", today).put("amount", amt).put("what", a.optString("what")))
                Res.ok("Eingetragen: ${a.optString("what").ifBlank { "Kosten" }} %.2f €.".format(Locale.GERMANY, amt)) }
            "tuev" -> { val d = a.optString("date").trim().ifBlank { return Res.error("Bis wann (MM/JJJJ)?") }
                v.put("tuev", d); Vehicles.put(ctx, v); Res.ok("TÜV/HU ${v.optString("name")}: fällig $d. Ich erinnere dich einen Monat vorher.") }
            "service" -> { if (a.has("km")) v.put("service_km", a.optInt("km")); if (a.optString("date").isNotBlank()) v.put("service_date", a.optString("date"))
                Vehicles.put(ctx, v); Res.ok("Nächste Wartung gespeichert. Ich sage Bescheid.") }
            else -> Res.ok(Vehicles.status(ctx, v))
        }
    }
}

/** Tacho (GPS) und Rundenzeit-Stoppuhr für Motocross & Co. */
class VehicleActivity : Activity(), LocationListener {
    private lateinit var ui: Ui
    private val h = Handler(Looper.getMainLooper())
    private lateinit var speed: TextView; private lateinit var speedSub: TextView
    private lateinit var lapTime: TextView; private lateinit var lapList: LinearLayout; private lateinit var lapBtn: TextView
    private var maxKmh = 0f; private var trip = 0f; private var last: Location? = null
    private var lapStart = 0L; private val laps = ArrayList<Long>(); private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.load(Prefs(this)); ui = Ui(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Ui.BG; window.navigationBarColor = Ui.BG; Ui.lightBars(window)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(20), ui.px(22), ui.px(20), ui.px(40)) }
        col.addView(ui.row().apply {
            addView(ui.text("‹", 30f).apply { setPadding(0, 0, ui.px(16), 0); setOnClickListener { finish() } })
            addView(LinearLayout(this@VehicleActivity).apply { orientation = LinearLayout.VERTICAL
                addView(ui.text("Fahrzeug", 28f, Ui.FG, true)); addView(ui.text("Tacho · Rundenzeit · Übersicht", 13f, Ui.MUTED)) }) })
        // Tacho
        speed = ui.text("0", 84f, Ui.FG, true).apply { gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
        speedSub = ui.text("km/h · warte auf GPS …", 13f, Ui.MUTED).apply { gravity = Gravity.CENTER }
        col.addView(ui.card().apply { addView(speed); addView(speedSub) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(16) })
        // Rundenzeit
        col.addView(ui.section("Rundenzeit"))
        lapTime = ui.text("0:00.0", 44f, Ui.FG, true).apply { gravity = Gravity.CENTER }
        lapList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.card().apply { addView(lapTime); addView(lapList) })
        lapBtn = ui.button("Start") { lap() }.apply { textSize = 22f; minHeight = ui.px(80) }
        col.addView(lapBtn)
        col.addView(ui.button("Stopp & speichern", false) { stopAndSave() })
        // Übersicht
        col.addView(ui.section("Übersicht"))
        val list = Vehicles.all(this)
        col.addView(ui.card().apply { addView(ui.text(if (list.isEmpty()) "Noch kein Fahrzeug. Sag „Jarvis, leg mein Motorrad an“." else list.joinToString("\n\n") { Vehicles.status(this@VehicleActivity, it) }, 14f, Ui.SOFT)) })
        col.addView(ui.button("Tanken eintragen", false) { fuelDialog() })
        setContentView(ScrollView(this).apply { background = ui.aurora(); addView(col) })
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 31) else startGps()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) { if (g.firstOrNull() == PackageManager.PERMISSION_GRANTED) startGps() }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        try { getSystemService(LocationManager::class.java)?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this) } catch (_: Exception) {}
    }

    override fun onLocationChanged(l: Location) {
        val kmh = if (l.hasSpeed()) l.speed * 3.6f else 0f
        if (kmh > maxKmh) maxKmh = kmh
        last?.let { if (l.accuracy < 25) trip += it.distanceTo(l) }
        last = l
        speed.text = kmh.toInt().toString()
        speedSub.text = "km/h · max ${maxKmh.toInt()} · Strecke %.2f km · GPS ±%d m".format(Locale.GERMANY, trip / 1000f, l.accuracy.toInt())
    }
    @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) { speedSub.text = "GPS ist aus – bitte Standort einschalten." }

    private val tick = object : Runnable { override fun run() {
        if (!running) return
        lapTime.text = fmt(SystemClock.elapsedRealtime() - lapStart); h.postDelayed(this, 100) } }

    private fun lap() {
        val now = SystemClock.elapsedRealtime()
        if (!running) { running = true; lapStart = now; lapBtn.text = "Runde"; h.post(tick); return }
        laps += now - lapStart; lapStart = now
        val best = laps.minOrNull() ?: 0
        lapList.removeAllViews()
        laps.withIndex().reversed().take(8).forEach { (i, ms) ->
            lapList.addView(ui.text("Runde ${i + 1}:  ${fmt(ms)}" + if (ms == best) "  ⭐ beste" else "  (+${fmt(ms - best)})", 14f, if (ms == best) Ui.GREEN else Ui.SOFT)) }
    }

    private fun stopAndSave() {
        running = false; h.removeCallbacks(tick); lapBtn.text = "Start"
        if (laps.isEmpty()) { lapTime.text = "0:00.0"; return }
        val e = EditText(this).apply { hint = "Strecke (z. B. MX-Park)"; setTextColor(Ui.FG); setHintTextColor(Ui.MUTED) }
        android.app.AlertDialog.Builder(this).setTitle("Rundenzeiten speichern").setView(e).setPositiveButton("Speichern") { _, _ ->
            Vehicles.addLog(this, "lap_sessions", JSONObject().put("date", LocalDate.now().toString()).put("track", e.text.toString().trim()).put("laps", JSONArray(laps)))
            Toast.makeText(this, "Gespeichert – beste Runde ${fmt(laps.min())}", Toast.LENGTH_LONG).show(); laps.clear(); lapList.removeAllViews(); lapTime.text = "0:00.0"
        }.setNegativeButton("Verwerfen") { _, _ -> laps.clear(); lapList.removeAllViews(); lapTime.text = "0:00.0" }.show()
    }

    private fun fuelDialog() {
        val v = Vehicles.find(this, null) ?: run { Toast.makeText(this, "Erst ein Fahrzeug anlegen.", Toast.LENGTH_LONG).show(); return }
        fun f(hint: String) = EditText(this).apply { this.hint = hint; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setTextColor(Ui.FG); setHintTextColor(Ui.MUTED) }
        val l = f("Liter"); val p = f("Preis gesamt (€)"); val k = f("Kilometerstand")
        android.app.AlertDialog.Builder(this).setTitle("⛽ Tanken – ${v.optString("name")}")
            .setView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(20), 0, ui.px(20), 0); addView(l); addView(p); addView(k) })
            .setPositiveButton("Speichern") { _, _ ->
                val liters = l.text.toString().replace(',', '.').toDoubleOrNull() ?: return@setPositiveButton
                val km = k.text.toString().toIntOrNull() ?: v.optInt("km")
                if (km > v.optInt("km")) { v.put("km", km); Vehicles.put(this, v) }
                Vehicles.addLog(this, "fuel_log", JSONObject().put("veh", v.optInt("id")).put("date", LocalDate.now().toString()).put("liters", liters)
                    .put("price", p.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0).put("km", km))
                recreate()
            }.setNegativeButton("Abbrechen", null).show()
    }

    override fun onDestroy() { try { getSystemService(LocationManager::class.java)?.removeUpdates(this) } catch (_: Exception) {}; h.removeCallbacksAndMessages(null); super.onDestroy() }

    companion object {
        fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d.%d".format(s / 60, s % 60, (ms % 1000) / 100) }
    }
}
