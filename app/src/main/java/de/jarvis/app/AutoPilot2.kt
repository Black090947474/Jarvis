package de.jarvis.app

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import de.jarvis.app.tools.JsonStore
import de.jarvis.app.tools.Place
import de.jarvis.app.tools.Geo
import de.jarvis.app.tools.Reminder
import de.jarvis.app.tools.WeatherTool
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId

/**
 * Autopilot Teil 2: Gewohnheiten, Bus & Bahn, Ankunft melden, Parkplatz, Vokabeln nebenbei,
 * Wetter-Überraschungen, Akku-Notfall. Gesteuert über [AutoPilot] (gleiche Schalter-Liste und Zentrale).
 */
object AutoPilot2 {
    const val J_TRANSIT = "transit"; const val J_VOCAB = "vocab"; const val J_VOCAB_ANSWER = "vocab_answer"
    const val J_SKY = "sky"; const val J_HABIT_YES = "habit_yes"; const val J_HABIT_NO = "habit_no"
    const val KEY_REPLY = "vocab_reply"
    private const val GEO_BASE = 899_500

    val FEATURES = listOf(
        AutoPilot.Feature("habits", "🧠 Gewohnheiten erkennen", "Merkt, wenn du immer dasselbe machst (z. B. Kopfhörer rein → Spotify) und fragt, ob er das automatisch tun soll.", true),
        AutoPilot.Feature("transit", "🚌 Bus & Bahn zur Schule", "An Schultagen morgens: nächste Verbindungen mit Verspätung (Deutsche Bahn / ÖPNV). Haltestellen unten eintragen.", false),
        AutoPilot.Feature("arrival", "📍 Ankunft melden", "Wenn du an einem gemerkten Ort ankommst, fragt Jarvis, ob er z. B. Mama schreiben soll. Gesendet wird erst nach deinem Ja.", false),
        AutoPilot.Feature("parking", "🚗 Parkplatz automatisch", "Trennt sich das Auto-Bluetooth, merkt sich Jarvis den Standort als Parkplatz.", true),
        AutoPilot.Feature("vocab", "📚 Vokabeln nebenbei", "Nachmittags eine Karteikarten-Frage als Benachrichtigung – direkt dort antworten.", false),
        AutoPilot.Feature("sky", "🌨 Wetter-Überraschungen", "Hinweis bei Schnee oder Gewitter morgen und in Sternschnuppen-Nächten.", true),
        AutoPilot.Feature("heat", "🌡 Hitze-Warnung", "Wird der Akku über 43 °C heiß, sagt Jarvis Bescheid.", true),
        AutoPilot.Feature("sos", "🆘 Akku-Notfall-SMS", "Nur wenn du es einschaltest: Bei 5 % Akku bekommt deine Notfall-Person eine SMS mit deinem Standort. Du wirst informiert.", false),
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences("autopilot", Context.MODE_PRIVATE)
    private fun on(ctx: Context, k: String) = AutoPilot.on(ctx, k)
    private fun str(ctx: Context, k: String) = AutoPilot.str(ctx, k, "")

    fun scheduleAll(ctx: Context) {
        AutoPilot.at(ctx, J_TRANSIT, if (on(ctx, "transit")) AutoPilot.nextAt(AutoPilot.wake(ctx).plusMinutes(15)) else null)
        AutoPilot.at(ctx, J_VOCAB, if (on(ctx, "vocab")) AutoPilot.nextAt(AutoPilot.parse(AutoPilot.str(ctx, "vocab_time", "16:30"), LocalTime.of(16, 30))) else null)
        AutoPilot.at(ctx, J_SKY, if (on(ctx, "sky")) AutoPilot.nextAt(LocalTime.of(18, 15)) else null)
        scheduleArrival(ctx)
    }

    fun handle(ctx: Context, job: String, intent: Intent?) {
        when (job) {
            J_TRANSIT -> if (on(ctx, "transit")) transit(ctx, auto = true)
            J_VOCAB -> vocabAsk(ctx)
            J_VOCAB_ANSWER -> vocabAnswer(ctx, intent)
            J_SKY -> sky(ctx)
            J_HABIT_YES -> habitYes(ctx, intent)
            J_HABIT_NO -> { intent?.getStringExtra("key")?.let { sp(ctx).edit().putBoolean("habit_asked_$it", true).apply() }; AutoPilot.cancel(ctx, 88_160) }
        }
    }

    // ---------- 7 Gewohnheiten erkennen

    @Volatile private var lastConnect = 0L
    @Volatile private var lastKind = ""

    /** Kopfhörer / Bluetooth verbunden. */
    fun connected(ctx: Context, kind: String) {
        lastConnect = System.currentTimeMillis(); lastKind = kind
    }

    private val IGNORE = Regex("launcher|systemui|settings|jarvis|inputmethod|keyboard|permissioncontroller|packageinstaller|honeyspace|dialer|incallui", RegexOption.IGNORE_CASE)

    fun appOpened(ctx: Context, pkg: String, label: String) {
        if (!on(ctx, "habits") || lastConnect == 0L) return
        if (System.currentTimeMillis() - lastConnect > 3 * 60_000L) { lastConnect = 0L; return }
        if (IGNORE.containsMatchIn(pkg) || pkg == ctx.packageName) return
        val kind = lastKind; lastConnect = 0L   // nur die erste App nach dem Verbinden zählt
        val key = (kind + "|" + label).lowercase()
        if (sp(ctx).getBoolean("habit_asked_$key", false)) return
        // Schon eine passende Automation vorhanden?
        if (Automations.all(ctx).any { a -> a.optJSONObject("trigger")?.optString("type") == kind &&
                a.optJSONArray("steps")?.toString()?.contains(label, true) == true }) return
        val n = sp(ctx).getInt("habit_$key", 0) + 1
        sp(ctx).edit().putInt("habit_$key", n).apply()
        if (n < 3) return
        val what = if (kind == "headphones") "Kopfhörer verbindest" else "dich mit Bluetooth verbindest"
        fun br(job: String, rc: Int) = PendingIntent.getBroadcast(ctx, rc, Intent(ctx, AutoPilotReceiver::class.java).setAction(AutoPilot.ACTION)
            .putExtra("job", job).putExtra("key", key).putExtra("kind", kind).putExtra("app", label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        AutoPilot.post(ctx, 88_160, "🧠 Gewohnheit erkannt",
            "Du öffnest oft $label, wenn du $what. Soll ich $label dann automatisch öffnen?",
            listOf(AutoPilot.action("Ja, automatisch", br(J_HABIT_YES, 88_261)), AutoPilot.action("Nein danke", br(J_HABIT_NO, 88_262))))
    }

    private fun habitYes(ctx: Context, i: Intent?) {
        val kind = i?.getStringExtra("kind") ?: return
        val app = i.getStringExtra("app") ?: return
        val trig = JSONObject().put("type", kind)
        val name = (if (kind == "headphones") "Kopfhörer" else "Bluetooth") + " → $app"
        Automations.put(ctx, JSONObject().put("name", name).put("trigger", trig)
            .put("steps", JSONArray().put(JSONObject().put("tool", "open_app").put("args", JSONObject().put("name", app)))))
        i.getStringExtra("key")?.let { sp(ctx).edit().putBoolean("habit_asked_$it", true).apply() }
        AutoPilot.post(ctx, 88_160, "✓ Automation angelegt", "„$name“. Ändern oder löschen kannst du sie unter Werkzeuge → Automationen.")
    }

    // ---------- 9 Bus & Bahn

    private const val TR = "https://v6.db.transport.rest"

    private fun station(q: String): Pair<String, String>? {
        if (q.isBlank()) return null
        val a = JSONArray(WeatherTool.http("$TR/locations?results=1&addresses=false&poi=false&query=" + URLEncoder.encode(q, "UTF-8")))
        val o = a.optJSONObject(0) ?: return null
        return o.optString("id") to o.optString("name")
    }

    /** Text mit den nächsten Verbindungen (oder Fehler). */
    fun transit(ctx: Context, auto: Boolean = false): String {
        val fromQ = str(ctx, "tr_from"); val toQ = str(ctx, "tr_to")
        if (fromQ.isBlank() || toQ.isBlank()) return "Trag in der Autopilot-Zentrale deine Start- und Ziel-Haltestelle ein."
        val today = LocalDate.now().dayOfWeek
        val first = AutoPilot.firstLesson(ctx, today)
        if (auto && first == null) return ""   // kein Schultag
        val txt = try {
            val f = station(fromQ) ?: return "Haltestelle „$fromQ“ nicht gefunden."
            val t = station(toQ) ?: return "Haltestelle „$toQ“ nicht gefunden."
            val z = ZoneId.systemDefault()
            val whenParam = if (first != null && LocalTime.now().isBefore(first)) {
                "&arrival=" + URLEncoder.encode(LocalDate.now().atTime(first.minusMinutes(5)).atZone(z).toOffsetDateTime().toString(), "UTF-8")
            } else ""
            val j = JSONObject(WeatherTool.http("$TR/journeys?results=3&stopovers=false&remarks=false&from=${f.first}&to=${t.first}$whenParam"))
            val js = j.optJSONArray("journeys") ?: JSONArray()
            if (js.length() == 0) return "Keine Verbindung gefunden."
            val lines = (0 until js.length()).mapNotNull { k ->
                val legs = js.getJSONObject(k).optJSONArray("legs") ?: return@mapNotNull null
                val leg = (0 until legs.length()).map { legs.getJSONObject(it) }.firstOrNull { !it.optBoolean("walking") } ?: legs.getJSONObject(0)
                val last = legs.getJSONObject(legs.length() - 1)
                val dep = leg.optString("departure").ifBlank { leg.optString("plannedDeparture") }
                val arr = last.optString("arrival").ifBlank { last.optString("plannedArrival") }
                val delay = if (leg.isNull("departureDelay")) 0 else leg.optInt("departureDelay") / 60
                val line = leg.optJSONObject("line")?.optString("name").orEmpty()
                val cancelled = leg.optBoolean("cancelled")
                "• ${hhmm(dep)} $line → an ${hhmm(arr)}" + (if (cancelled) " ❌ fällt aus" else if (delay > 0) " (+$delay Min.)" else " ✓ pünktlich") +
                    (leg.optString("departurePlatform").takeIf { it.isNotBlank() && it != "null" }?.let { " · Gleis/Steig $it" } ?: "")
            }
            "${f.second} → ${t.second}:\n" + lines.joinToString("\n")
        } catch (e: Exception) { if (auto) return "" else return "Fahrplan gerade nicht erreichbar (${e.message})." }
        if (auto) AutoPilot.post(ctx, 88_170, "🚌 Dein Schulweg", txt,
            listOf(AutoPilot.action("Aktualisieren", PendingIntent.getBroadcast(ctx, 88_270, Intent(ctx, AutoPilotReceiver::class.java)
                .setAction(AutoPilot.ACTION).putExtra("job", J_TRANSIT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))))
        return txt
    }

    private fun hhmm(iso: String) = Regex("T(\\d{2}:\\d{2})").find(iso)?.groupValues?.get(1) ?: "?"

    // ---------- 10 Ankunft melden

    private fun arrivalPlaces(ctx: Context): List<Place> {
        val wanted = str(ctx, "arr_places").split(',', ';').map { it.trim() }.filter { it.isNotBlank() }
        if (wanted.isEmpty()) return emptyList()
        val saved = JsonStore(ctx).list("places")
        val p = Prefs(ctx)
        return wanted.mapNotNull { w ->
            if (w.equals("zuhause", true) || w.equals("zu hause", true)) { if (p.homeSet) Place("Zuhause", p.homeLat, p.homeLon, 150f, true) else null }
            else saved.firstOrNull { it.optString("name").equals(w, true) }?.let { Place(it.optString("name"), it.optDouble("lat"), it.optDouble("lon"), 150f, true) }
        }.take(20)
    }

    fun scheduleArrival(ctx: Context) {
        val places = if (on(ctx, "arrival")) arrivalPlaces(ctx) else emptyList()
        for (i in 0 until 20) {
            val r = Reminder(GEO_BASE + i, "arrival", null, null, place = places.getOrNull(i) ?: Place("-", 0.0, 0.0, 150f, true))
            try { if (i < places.size) Scheduler.addGeofence(ctx, r) else Scheduler.cancel(ctx, r) } catch (_: Exception) {}
        }
        sp(ctx).edit().putString("arr_names", places.joinToString("|") { it.name }).apply()
    }

    fun isArrivalId(id: Int) = id in GEO_BASE until GEO_BASE + 20

    fun arrived(ctx: Context, id: Int) {
        if (!on(ctx, "arrival")) return
        val name = sp(ctx).getString("arr_names", "")!!.split("|").getOrNull(id - GEO_BASE)?.takeIf { it.isNotBlank() } ?: return
        val k = "arr_${name}_" + LocalDate.now()
        if (sp(ctx).getBoolean(k, false)) return
        sp(ctx).edit().putBoolean(k, true).apply()
        val who = str(ctx, "arr_contact").ifBlank { "Mama" }
        val app = str(ctx, "arr_app").ifBlank { "WhatsApp" }
        val text = if (name == "Zuhause") "Bin gut zu Hause angekommen 🙂" else "Bin gut bei $name angekommen 🙂"
        AutoPilot.post(ctx, 88_180, "📍 Angekommen: $name", "Soll ich $who schreiben: „$text“?",
            listOf(AutoPilot.action("$who schreiben", AutoPilot.chatIntent(ctx, 88_280,
                "Schick $who per $app die Nachricht: „$text“ (frag mich vorher noch einmal kurz)."))))
    }

    // ---------- 11 Parkplatz automatisch

    fun btDisconnected(ctx: Context, name: String) {
        val car = Prefs(ctx).carDevice
        if (!on(ctx, "parking") || car.isBlank() || name.isBlank() || !name.contains(car, true)) return
        val loc = Geo.lastKnown(ctx) ?: return
        val store = JsonStore(ctx)
        store.save("places", store.list("places").filter { !it.optString("name").equals("Parkplatz", true) } +
            JSONObject().put("name", "Parkplatz").put("lat", loc.first).put("lon", loc.second).put("at", System.currentTimeMillis()))
        AutoPilot.post(ctx, 88_190, "🚗 Parkplatz gemerkt", "Auto-Bluetooth getrennt – ich habe mir den Standort gemerkt. Sag später „Jarvis, wo steht mein Auto?“.",
            listOf(AutoPilot.action("Hinführen", AutoPilot.askIntent(ctx, 88_290, "Navigiere mich zum Parkplatz"))))
    }

    // ---------- 15 Vokabeln nebenbei

    private val INTERVAL_MIN = longArrayOf(0, 10, 24 * 60, 3 * 24 * 60, 7 * 24 * 60, 14 * 24 * 60)

    private fun pickCard(ctx: Context): Triple<String, String, JSONObject>? {
        val store = JsonStore(ctx)
        val index = store.obj("decks")
        val now = System.currentTimeMillis()
        val all = index.keys().asSequence().flatMap { k -> store.list(k).map { Triple(k, index.optString(k), it) } }.toList()
        if (all.isEmpty()) return null
        val due = all.filter { it.third.optLong("due", 0) <= now }
        return (due.ifEmpty { all }).minByOrNull { it.third.optInt("box", 1) * 10 - it.third.optInt("bad") * 3 + Math.random() * 8 }
    }

    fun vocabAsk(ctx: Context) {
        val c = pickCard(ctx) ?: run {
            AutoPilot.post(ctx, 88_200, "📚 Vokabeln nebenbei", "Du hast noch keine Karteikarten. Sag z. B. „Jarvis, speichere Englisch-Vokabeln: dog = Hund, cat = Katze“.")
            return
        }
        val (key, deck, card) = c
        val reply = RemoteInput.Builder(KEY_REPLY).setLabel("Deine Antwort").build()
        val pi = PendingIntent.getBroadcast(ctx, 88_300, Intent(ctx, AutoPilotReceiver::class.java).setAction(AutoPilot.ACTION)
            .putExtra("job", J_VOCAB_ANSWER).putExtra("deck", key).putExtra("id", card.optInt("id")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val act = Notification.Action.Builder(null, "Antworten", pi).addRemoteInput(reply).build()
        val skip = PendingIntent.getBroadcast(ctx, 88_301, Intent(ctx, AutoPilotReceiver::class.java).setAction(AutoPilot.ACTION)
            .putExtra("job", J_VOCAB).putExtra("manual", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        AutoPilot.post(ctx, 88_200, "📚 $deck", "Was heißt: ${card.optString("q")}?", listOf(act, AutoPilot.action("Andere Karte", skip)))
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("^(to|the|a|an|der|die|das|ein|eine)\\s+"), "")
        .replace(Regex("[^a-z0-9äöüßéèàç ]"), "").trim()

    private fun vocabAnswer(ctx: Context, i: Intent?) {
        i ?: return
        val answer = RemoteInput.getResultsFromIntent(i)?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
        val key = i.getStringExtra("deck") ?: return
        val id = i.getIntExtra("id", -1)
        val store = JsonStore(ctx)
        val cards = store.list(key)
        val c = cards.firstOrNull { it.optInt("id") == id } ?: return
        val sol = c.optString("a")
        val options = sol.split(',', ';', '/').map { norm(it) }.filter { it.isNotBlank() }
        val a = norm(answer)
        val ok = a.isNotBlank() && (options.any { it == a } || (a.length >= 4 && options.any { o -> lev(o, a) <= 1 }))
        val box = c.optInt("box", 1).coerceIn(1, 5)
        val nb = if (ok) (box + 1).coerceAtMost(5) else 1
        c.put(if (ok) "ok" else "bad", c.optInt(if (ok) "ok" else "bad") + 1).put("box", nb)
            .put("due", System.currentTimeMillis() + INTERVAL_MIN[nb] * 60_000L)
        store.save(key, cards)
        val stats = store.obj("learn_stats"); val day = LocalDate.now().toString()
        store.saveObj("learn_stats", stats.put(day, stats.optInt(day) + 1).put("total", stats.optInt("total") + 1))
        val next = PendingIntent.getBroadcast(ctx, 88_301, Intent(ctx, AutoPilotReceiver::class.java).setAction(AutoPilot.ACTION)
            .putExtra("job", J_VOCAB).putExtra("manual", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        AutoPilot.post(ctx, 88_200, if (ok) "✅ Richtig!" else "❌ Fast",
            (if (ok) "„${c.optString("q")}“ = $sol. Karte rückt in Fach $nb." else "„${c.optString("q")}“ heißt: $sol" + (if (answer.isNotBlank()) " (du: $answer)" else "") + ". Kommt bald wieder dran."),
            listOf(AutoPilot.action("Noch eine", next)))
    }

    private fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) { val t = d[j]; d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1); prev = t }
        }
        return d[b.length]
    }

    // ---------- 19 Wetter-Überraschungen

    /** Sternschnuppen-Maxima (Monat, Tag, Name). */
    private val METEORS = listOf(Triple(1, 3, "Quadrantiden"), Triple(4, 22, "Lyriden"), Triple(5, 6, "Eta-Aquariiden"),
        Triple(8, 12, "Perseiden"), Triple(10, 21, "Orioniden"), Triple(11, 17, "Leoniden"), Triple(12, 14, "Geminiden"))

    private fun sky(ctx: Context) {
        val loc = Geo.lastKnown(ctx) ?: Prefs(ctx).let { if (it.homeSet) it.homeLat to it.homeLon else null } ?: return
        val j = try { JSONObject(WeatherTool.http("https://api.open-meteo.com/v1/forecast?latitude=${loc.first}&longitude=${loc.second}" +
            "&daily=weather_code,snowfall_sum,wind_gusts_10m_max&hourly=cloud_cover&timezone=auto&forecast_days=2")) } catch (_: Exception) { return }
        val d = j.optJSONObject("daily") ?: return
        val code = d.optJSONArray("weather_code")?.optInt(1) ?: -1
        val snow = d.optJSONArray("snowfall_sum")?.optDouble(1) ?: 0.0
        val gust = d.optJSONArray("wind_gusts_10m_max")?.optDouble(1) ?: 0.0
        val msgs = mutableListOf<String>()
        if (code in listOf(71, 73, 75, 77, 85, 86) || snow >= 0.5) msgs += "❄️ Morgen soll es schneien" + (if (snow >= 0.5) " (ca. ${"%.0f".format(snow)} cm)" else "") + "! Warm anziehen – und vielleicht früher los."
        if (code in 95..99) msgs += "⛈ Morgen sind Gewitter angesagt. Bei Blitz nicht unter Bäume stellen, lieber drinnen bleiben."
        if (gust >= 75) msgs += "🌬 Morgen Sturmböen bis ${gust.toInt()} km/h – Vorsicht draußen."
        // Sternschnuppen heute Nacht (Maximum ±1 Tag) bei wenig Wolken
        val today = MonthDay.now()
        METEORS.firstOrNull { (m, dd, _) -> val md = MonthDay.of(m, dd); kotlin.math.abs(md.atYear(2025).dayOfYear - today.atYear(2025).dayOfYear) <= 1 }?.let { (_, _, name) ->
            val cc = j.optJSONObject("hourly")?.optJSONArray("cloud_cover")
            val night = (22..27).mapNotNull { h -> cc?.optInt(h) }   // 22–3 Uhr
            val clear = night.isNotEmpty() && night.average() < 45
            msgs += "🌠 Heute Nacht: ${name}-Sternschnuppen!" + (if (clear) " Der Himmel soll ziemlich klar sein – nach 22 Uhr mal rausschauen, weg von Lichtern." else " Leider eher bewölkt – vielleicht hast du trotzdem Glück.")
        }
        if (msgs.isEmpty()) return
        AutoPilot.post(ctx, 88_210, "🌦 Wetter-Überraschung", msgs.joinToString("\n"),
            listOf(AutoPilot.action("Erklär mir das", AutoPilot.askIntent(ctx, 88_310, if (msgs.any { it.startsWith("🌠") }) "Wie entstehen Sternschnuppen?" else "Wie wird morgen das Wetter?"))))
    }

    // ---------- Hitze-Warnung

    fun heat(ctx: Context, temp: Float) {
        if (temp < 43f || !on(ctx, "heat")) return
        val k = "heat_" + (System.currentTimeMillis() / (2 * 3_600_000L))
        if (sp(ctx).getBoolean(k, false)) return
        sp(ctx).edit().putBoolean(k, true).apply()
        AutoPilot.post(ctx, 88_230, "🌡 Handy wird heiß: %.0f °C".format(temp),
            "Nimm es aus der Sonne, pausiere Spiele oder Laden und nimm eine dicke Hülle kurz ab.")
    }

    // ---------- 20 Akku-Notfall

    fun battery(ctx: Context, level: Int, prev: Int) {
        if (!on(ctx, "sos") || level > 5 || prev <= 5) return
        val bm = ctx.getSystemService(BatteryManager::class.java)
        if (bm?.isCharging == true) return
        val num = str(ctx, "sos_number").filter { it.isDigit() || it == '+' }
        if (num.length < 6) return
        if (ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val k = "sos_" + LocalDate.now()
        if (sp(ctx).getBoolean(k, false)) return
        sp(ctx).edit().putBoolean(k, true).apply()
        val name = Prefs(ctx).userName.ifBlank { "Dein Kontakt" }
        val loc = Geo.lastKnown(ctx)
        val text = "Automatische Nachricht von Jarvis: Der Handy-Akku von $name ist fast leer ($level %)." +
            (loc?.let { " Letzter Standort: https://maps.google.com/?q=%.5f,%.5f".format(java.util.Locale.US, it.first, it.second) } ?: " Standort unbekannt.")
        try {
            val sms = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.telephony.SmsManager::class.java)
                      else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
            sms.sendMultipartTextMessage(num, null, sms.divideMessage(text), null, null)
            ActionLog.add(ctx, "sos", "Akku-Notfall-SMS an ${str(ctx, "sos_name").ifBlank { num }}", JSONObject(), "OK", null)
            AutoPilot.post(ctx, 88_220, "🆘 Notfall-SMS gesendet", "Akku $level % – ${str(ctx, "sos_name").ifBlank { num }} hat deinen Standort bekommen (so wie du es eingestellt hast).")
        } catch (_: Exception) {}
    }
}
