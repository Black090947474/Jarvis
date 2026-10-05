package de.jarvis.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import de.jarvis.app.Ui.Companion.AMBER
import de.jarvis.app.Ui.Companion.BG
import de.jarvis.app.Ui.Companion.CARD2
import de.jarvis.app.Ui.Companion.GREEN
import de.jarvis.app.Ui.Companion.MUTED
import de.jarvis.app.Ui.Companion.RED
import de.jarvis.app.Ui.Companion.SOFT
import de.jarvis.app.logic.LocalCommands
import de.jarvis.app.logic.TimeLogic
import de.jarvis.app.tools.AgendaStore
import de.jarvis.app.tools.AgendaTool
import de.jarvis.app.tools.ReminderTool
import de.jarvis.app.tools.Res
import de.jarvis.app.tools.TaskTool
import de.jarvis.app.tools.ToolContext
import de.jarvis.app.tools.WeatherTool
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Startbildschirm: Uhrzeit, Wetter, Termine, Erinnerungen, Aufgaben, Nachrichten, Schnellaktionen, Status. */
class DashboardActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var greeting: TextView
    private lateinit var dateText: TextView
    private lateinit var chips: LinearLayout
    private lateinit var weatherBox: LinearLayout
    private lateinit var todayBox: LinearLayout
    private lateinit var weekText: TextView
    private lateinit var msgBox: LinearLayout
    private lateinit var extrasBox: LinearLayout
    private var torchOn = false

    private lateinit var content: android.widget.FrameLayout
    private val pages = mutableMapOf<String, android.view.View>()
    private val navViews = mutableMapOf<String, Pair<IconView, TextView>>()
    private var current = "home"
    private lateinit var systemBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var liveBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this); Ui.load(prefs); ui = Ui(this)
        window.statusBarColor = BG; window.navigationBarColor = BG
        Notifier.channels(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        content = android.widget.FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildNav(), LinearLayout.LayoutParams(-1, -2))
        pages["home"] = buildHome(); pages["tools"] = buildTools(); pages["system"] = buildSystem()
        pages.values.forEach { content.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1)) }
        setContentView(root)
        show(savedInstanceState?.getString("tab") ?: intent.getStringExtra("tab") ?: prefs.startPage)
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("tab", current) }

    override fun onBackPressed() {
        if (current != "home") show("home") else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun show(tab: String) {
        current = tab
        pages.forEach { (k, v) -> v.visibility = if (k == tab) android.view.View.VISIBLE else android.view.View.GONE }
        navViews.forEach { (k, v) ->
            val on = k == tab
            v.second.setTextColor(if (on) RED else MUTED)
            v.first.alpha = if (on) 1f else 0.55f
        }
        if (tab == "system") refreshSystem()
    }

    private fun buildNav(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Ui.CARD); setStroke(ui.px(1), Ui.LINE) }
            setPadding(0, ui.px(8), 0, ui.px(10))
        }
        for ((key, icon, label) in listOf(Triple("home", "home", "Home"), Triple("chat", "chat", "Chat"),
                Triple("tools", "tools", "Tools"), Triple("system", "gear", "System"))) {
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setOnClickListener { if (key == "chat") startActivity(Intent(this@DashboardActivity, ChatActivity::class.java)) else show(key) } }
            val iv = IconView(this, icon, RED)
            item.addView(iv, LinearLayout.LayoutParams(ui.px(24), ui.px(24)))
            val tv = ui.text(label, 11f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, ui.px(3), 0, 0) }
            item.addView(tv)
            navViews[key] = iv to tv
            nav.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return nav
    }

    private fun page(col: LinearLayout) = ScrollView(this).apply { isFillViewport = true; addView(col) }

    private fun title(t: String, sub: String?, gear: Boolean): LinearLayout {
        val head = ui.row().apply { setPadding(0, ui.px(6), 0, 0) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(ui.text(t, 26f, Color.WHITE).apply { letterSpacing = 0.45f; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        sub?.let { left.addView(ui.text(it, 11f, RED).apply { letterSpacing = 0.18f }) }
        head.addView(ui.weighted(left))
        if (gear) head.addView(IconView(this, "gear", SOFT).apply {
            background = ui.round(Ui.CARD2, 14, Ui.LINE); setPadding(ui.px(8), ui.px(8), ui.px(8), ui.px(8))
            setOnClickListener { startActivity(Intent(this@DashboardActivity, MainActivity::class.java)) }
        }, LinearLayout.LayoutParams(ui.px(44), ui.px(44)))
        return head
    }

    // ---------- Home ----------

    private fun buildHome(): ScrollView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(18), ui.px(18), ui.px(18), ui.px(28)) }
        col.addView(title("J A R V I S", "DEIN PERSÖNLICHER KI-ASSISTENT", gear = true))

        val orb = OrbView(this).apply { setOnClickListener { talk() } }
        col.addView(orb, LinearLayout.LayoutParams(-1, ui.px(250)).apply { topMargin = ui.px(8) })

        val greet = ui.card().apply { gravity = Gravity.CENTER_HORIZONTAL }
        greeting = ui.text("", 18f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        dateText = ui.text("", 13f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, ui.px(4), 0, 0) }
        greet.addView(greeting, LinearLayout.LayoutParams(-1, -2)); greet.addView(dateText, LinearLayout.LayoutParams(-1, -2))
        col.addView(greet)

        // Großer Mikrofon-Knopf
        val ask = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, ui.px(18), 0, ui.px(4)); setOnClickListener { talk() } }
        ask.addView(IconView(this, "mic", RED).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Ui.CARD); setStroke(ui.px(2), RED) }
            setPadding(ui.px(22), ui.px(22), ui.px(22), ui.px(22))
            elevation = ui.px(8).toFloat()
        }, LinearLayout.LayoutParams(ui.px(86), ui.px(86)))
        ask.addView(ui.text("FRAG JARVIS", 15f, Color.WHITE, true).apply { letterSpacing = 0.2f; setPadding(0, ui.px(10), 0, 0) })
        ask.addView(ui.text("Tippen oder „Hey Jarvis“ sagen", 12f, RED))
        col.addView(ask, LinearLayout.LayoutParams(-1, -2))

        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        // Live-Status
        liveBox = ui.card().apply { setOnClickListener { show("system") } }
        col.addView(liveBox)

        col.addView(ui.section("Wetter"))
        weatherBox = ui.card(); col.addView(weatherBox)
        col.addView(ui.section("Heute"))
        todayBox = ui.card(); col.addView(todayBox)
        weekText = ui.text("", 13f, MUTED).apply { setPadding(ui.px(4), ui.px(8), 0, 0)
            setOnClickListener { startActivity(Intent(this@DashboardActivity, CalendarActivity::class.java)) } }
        col.addView(weekText)
        col.addView(ui.section("Nachrichten"))
        msgBox = ui.card(); col.addView(msgBox)
        col.addView(ui.section("Schule · Einkauf · Geburtstage"))
        extrasBox = ui.card(); col.addView(extrasBox)
        return page(col)
    }

    // ---------- Tools ----------

    private fun buildTools(): ScrollView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(18), ui.px(18), ui.px(18), ui.px(28)) }
        col.addView(title("J A R V I S", "WERKZEUGE", gear = true))
        val hi = ui.card().apply { background = ui.gradient(Color.argb(90, Color.red(RED), Color.green(RED), Color.blue(RED)), Ui.CARD, 18) }
        hi.addView(ui.text("Was soll ich für dich tun?", 17f, Color.WHITE, true))
        hi.addView(ui.text("Tippe ein Werkzeug an oder sag es einfach.", 13f, SOFT))
        col.addView(hi)
        val grid = GridLayout(this).apply { columnCount = 2 }
        fun tile(icon: String, name: String, sub: String, action: () -> Unit) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Ui.CARD2, Ui.CARD)).apply { cornerRadius = 16 * ui.dp; setStroke(ui.px(1), Ui.LINE) }
                setPadding(ui.px(14), ui.px(14), ui.px(10), ui.px(14))
                setOnClickListener { action() }
            }
            box.addView(IconView(this, icon, RED), LinearLayout.LayoutParams(ui.px(30), ui.px(30)))
            box.addView(ui.text(name, 15f, Color.WHITE, true).apply { setPadding(0, ui.px(10), 0, 0); maxLines = 1 })
            box.addView(ui.text(sub, 11f, RED).apply { maxLines = 1 })
            grid.addView(box, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; setMargins(ui.px(5), ui.px(5), ui.px(5), ui.px(5)) })
        }
        tile("chat", "Chat", "Mit Jarvis schreiben") { startActivity(Intent(this, ChatActivity::class.java)) }
        tile("calendar", "Kalender", "Termine & Planung") { startActivity(Intent(this, CalendarActivity::class.java)) }
        tile("bell", "Erinnerungen", "Aufgaben & Erinnerungen") { remindersDialog() }
        tile("phone", "Telefon", "Anrufe & Anrufliste") { phoneDialog() }
        tile("message", "Nachrichten", "Zusammenfassen") { askJarvis("Fass meine neuen Nachrichten kurz zusammen.") }
        tile("mail", "E-Mails", "Mail-App öffnen") { openMail() }
        tile("music", "Musik", "Steuerung & Erkennen") { musicDialog() }
        tile("device", "Geräte", "Status & Schalter") { show("system") }
        tile("brain", "Wissen", "Fragen & Websuche") { startActivity(Intent(this, ChatActivity::class.java)) }
        tile("camera", "Kamera", "Foto & Video") { openCamera() }
        tile("apps", "Apps", "Öffnen & suchen") { startActivity(Intent(this, AppsActivity::class.java)) }
        tile("school", "Schule", "Stundenplan & Hausaufgaben") { schoolDialog() }
        tile("cart", "Einkauf", "Einkaufsliste") { val l = de.jarvis.app.tools.ShoppingTool.items(this); if (l.isEmpty()) shoppingDialog() else checkShopping(l) }
        tile("notes", "Notizen", "Notieren & finden") { notesDialog() }
        tile("learn", "Lernen", "Vokabeln abfragen") { askJarvis("Frag mich Vokabeln ab.") }
        tile("bolt", "Kommandos", "Eigene Befehle") { routinesDialog() }
        tile("sun", "Briefing", "Dein Tag kurz") { showBriefing() }
        tile("timer", "Timer", "Countdown starten") { timerDialog() }
        tile("flash", "Taschenlampe", "An / aus") { toggleTorch() }
        tile("pin", "Parkplatz", "Merken & finden") { parkingDialog() }
        tile("song", "Lied erkennen", "Was läuft gerade?") { runTool { de.jarvis.app.tools.MusicTool.run(ToolContext(this), JSONObject()) } }
        tile("bolt", "Automationen", "Wenn … dann …") { startActivity(Intent(this, AutomationsActivity::class.java)) }
        tile("brain", "Gedächtnis", "Was Jarvis weiß") { HubActivity.open(this, "memory") }
        tile("chat", "Persönlichkeit", "Charakter & Humor") { HubActivity.open(this, "personality") }
        tile("notes", "Dateien", "Finden & zusammenfassen") { filesDialog() }
        tile("home", "Auto-Modus", "Große Knöpfe") { startActivity(Intent(this, CarActivity::class.java)) }
        tile("device", "Meine Geräte", "Handys & Station") { HubActivity.open(this, "devices") }
        tile("storage", "Aktionsverlauf", "Was Jarvis getan hat") { HubActivity.open(this, "log") }
        tile("battery", "Statistik", "Deine Zahlen") { HubActivity.open(this, "stats") }
        tile("apps", "Plugins", "Module an/aus") { HubActivity.open(this, "plugins") }
        tile("sun", "Personalisieren", "Farben & Layout") { HubActivity.open(this, "personalize") }
        tile("storage", "Sicherung", "Daten sichern") { HubActivity.open(this, "backup") }
        tile("shield", "Notfall", "112 & Notfallkontakt") { HubActivity.open(this, "emergency") }
        tile("shield", "Berechtigungen", "Was Jarvis darf") { startActivity(Intent(this, PermissionsActivity::class.java)) }
        col.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(8) })
        return page(col)
    }

    // ---------- System ----------

    private fun buildSystem(): ScrollView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(18), ui.px(18), ui.px(18), ui.px(28)) }
        col.addView(title("J A R V I S", "COMMAND CENTER", gear = true))
        val status = ui.card(false).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ui.px(20), ui.px(22), ui.px(20), ui.px(22))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Ui.CARD); cornerRadius = 22 * ui.dp; setStroke(ui.px(2), RED) } }
        status.addView(android.view.View(this).apply { background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(GREEN) } }, LinearLayout.LayoutParams(ui.px(22), ui.px(22)))
        val st = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(18), 0, 0, 0) }
        st.addView(ui.text("SYSTEMSTATUS", 12f, SOFT).apply { letterSpacing = 0.15f })
        statusText = ui.text("ONLINE", 26f, GREEN, true).apply { letterSpacing = 0.1f }
        st.addView(statusText)
        status.addView(st)
        col.addView(status)
        systemBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(systemBox)
        return page(col)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (current == "system") refreshSystem()
        dateText.text = SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY).format(Date())
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val hi = when (h) { in 5..10 -> "Guten Morgen"; in 11..17 -> "Hallo"; in 18..22 -> "Guten Abend"; else -> "Gute Nacht" }
        greeting.text = hi + (if (prefs.userName.isNotBlank()) ", ${prefs.userName}" else "") + ".\nWie kann ich dir heute helfen?"
        refreshChips()
        refreshLive()
        refreshWeather()
        refreshAgenda()
        refreshMessages()
        refreshExtras()
        JarvisWidget.updateAll(this)
    }

    private fun online(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun refreshChips() {
        chips.removeAllViews()
        val bm = getSystemService(BatteryManager::class.java)
        val bat = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        chips.addView(ui.chip(if (WakeWordService.isRunning) "● Hört zu" else "○ Wake-Word aus", WakeWordService.isRunning).apply {
            setOnClickListener { startActivity(Intent(this@DashboardActivity, MainActivity::class.java)) }
        })
        val hasKey = prefs.hasAnyKey
        chips.addView(ui.chip(if (hasKey) "KI bereit" else "KI-Schlüssel fehlt", hasKey))
        chips.addView(ui.chip(if (online()) "Online" else "Offline", online()))
        if (bat >= 0) chips.addView(ui.chip("Akku $bat %", bat > 20))
        chips.addView(ui.chip(if (prefs.proactive) "Hinweise an" else "Hinweise aus", prefs.proactive).apply {
            setOnClickListener { startActivity(Intent(this@DashboardActivity, PermissionsActivity::class.java)) }
        })
    }

    // ---------- Wetter ----------

    private fun showWeather() {
        weatherBox.removeAllViews()
        val c = runCatching { JSONObject(prefs.weatherCache) }.getOrNull()
        if (c == null) {
            weatherBox.addView(ui.text(if (online()) "Lade Wetter …" else "Kein Wetter (offline).", 14f, MUTED))
            return
        }
        val r = ui.row()
        r.addView(ui.text("${c.optInt("t")}°", 40f, Color.WHITE).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(14), 0, 0, 0) }
        info.addView(ui.text(c.optString("desc").replaceFirstChar { it.uppercase() }, 16f, Color.WHITE, true))
        val rain = c.optInt("rain", -1)
        info.addView(ui.text("${c.optInt("min")}° / ${c.optInt("max")}°" + (if (rain >= 0) " · Regen $rain %" else "") +
            " · ${c.optString("place")}", 13f, MUTED))
        val age = (System.currentTimeMillis() - prefs.weatherTime) / 60_000
        if (age > 90) info.addView(ui.text("Stand vor ${if (age < 60 * 24) "${age / 60} Std." else "${age / 1440} Tagen"}", 11f, AMBER))
        r.addView(ui.weighted(info))
        weatherBox.addView(r)
        weatherBox.setOnClickListener { askJarvis("Wie wird das Wetter heute und morgen?") }
    }

    private fun refreshWeather() {
        showWeather()
        if (!online() || System.currentTimeMillis() - prefs.weatherTime < 30 * 60_000) return
        thread {
            val r = try { WeatherTool.fetch(applicationContext) } catch (e: Exception) { Res.error(e.message ?: "") }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (Res.isError(r) && prefs.weatherCache.isBlank()) {
                    weatherBox.removeAllViews(); weatherBox.addView(ui.text(Res.spoken(r), 13f, MUTED))
                } else showWeather()
            }
        }
    }

    // ---------- Termine, Erinnerungen, Aufgaben ----------

    private fun refreshAgenda() {
        thread {
            val (from, to) = TimeLogic.range("heute")
            val ag = AgendaTool.collect(applicationContext, from, to)
            val (wf, wt) = TimeLogic.range("woche")
            val week = AgendaTool.summary(AgendaTool.collect(applicationContext, wf, wt), "woche", false)
            runOnUiThread { if (!isDestroyed) showAgenda(ag, week) }
        }
    }

    private fun showAgenda(ag: AgendaTool.Agenda, week: String) {
        todayBox.removeAllViews()
        weekText.text = "Woche: " + week.removePrefix("Diese Woche: ").removePrefix("Diese Woche ")
        if (!ag.calendarAllowed) todayBox.addView(ui.text("Kalender nicht freigegeben – tippen zum Erlauben", 13f, AMBER).apply {
            setOnClickListener { startActivity(Intent(this@DashboardActivity, PermissionsActivity::class.java)) }
            setPadding(0, 0, 0, ui.px(8))
        })
        if (ag.isEmpty()) { todayBox.addView(ui.text("Heute steht nichts an. 🎉", 15f, SOFT)); return }
        val now = System.currentTimeMillis()
        for (e in ag.events) {
            val r = ui.row().apply { setPadding(0, ui.px(5), 0, ui.px(5)) }
            val past = !e.allDay && e.end < now
            r.addView(ui.text(if (e.allDay) "ganzt." else TimeLogic.time(e.begin), 14f, if (past) MUTED else RED, true).apply { width = ui.px(64) })
            r.addView(ui.weighted(ui.text(e.title + (if (e.location.isNotBlank()) "  ·  ${e.location}" else ""), 15f, if (past) MUTED else Color.WHITE).apply { maxLines = 2 }))
            r.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            todayBox.addView(r)
        }
        for (rem in ag.reminders) {
            val r = ui.row().apply { setPadding(0, ui.px(5), 0, ui.px(5)) }
            r.addView(ui.text("🔔 " + TimeLogic.time(rem.next!!), 14f, AMBER, true).apply { width = ui.px(78) })
            r.addView(ui.weighted(ui.text(rem.text, 15f)))
            todayBox.addView(r)
        }
        for (t in ag.overdue + ag.tasks) {
            val r = ui.row().apply { setPadding(0, ui.px(5), 0, ui.px(5)) }
            val overdue = t in ag.overdue
            r.addView(ui.text("☐", 18f, if (overdue) RED else GREEN).apply { width = ui.px(30) })
            r.addView(ui.weighted(ui.text(t.title + (if (overdue) "  (überfällig)" else "") + (if (t.priority == "hoch") "  !" else ""), 15f)))
            r.setOnClickListener { completeTask(t.id) }
            todayBox.addView(r)
        }
    }

    private fun completeTask(id: Int) = thread {
        val msg = TaskTool.complete(applicationContext, id)
        runOnUiThread { if (msg != null) toast(msg); refreshAgenda() }
    }

    // ---------- Nachrichten ----------

    private fun refreshMessages() {
        msgBox.removeAllViews()
        if (!prefs.feature("notifications_read")) { msgBox.addView(ui.text("Nachrichten-Zugriff im Berechtigungszentrum ausgeschaltet.", 13f, MUTED)); return }
        if (!JarvisNotificationListener.isConnected) {
            msgBox.addView(ui.text("Jarvis darf Benachrichtigungen nicht lesen – tippen zum Erlauben.", 13f, AMBER))
            msgBox.setOnClickListener { startActivity(Intent(this, PermissionsActivity::class.java)) }
            return
        }
        val list = JarvisNotificationListener.recent(null, 40)
        if (list.isEmpty()) { msgBox.addView(ui.text("Keine neuen Nachrichten.", 14f, SOFT)); return }
        // Benachrichtigungs-Zentrum: nach Kategorien
        msgBox.addView(ui.text(de.jarvis.app.tools.NotificationHub.summary(), 14f, Color.WHITE, true))
        val g = de.jarvis.app.tools.NotificationHub.grouped()
        for ((cat, msgs) in g.entries.take(4)) {
            val color = when (cat) { "Wichtig" -> Color.rgb(255, 110, 110); "Schule", "Familie" -> AMBER; "Werbung", "System" -> MUTED; else -> SOFT }
            msgBox.addView(ui.text("$cat (${msgs.size}): " + msgs.take(2).joinToString(" · ") { "${it.title}: ${it.text}" }, 13f, color).apply {
                maxLines = 1; setPadding(0, ui.px(5), 0, 0) })
        }
        msgBox.setOnClickListener { askJarvis("Was ist bei meinen Benachrichtigungen wichtig? Fass kurz zusammen.") }
    }

    // ---------- Schule, Einkauf, Geburtstage ----------

    private fun refreshExtras() {
        thread {
            val late = java.time.LocalTime.now().hour >= 15
            val day = java.time.LocalDate.now().plusDays(if (late) 1 else 0).dayOfWeek
            val school = de.jarvis.app.tools.SchoolTool.summary(applicationContext, day)
            val hw = AgendaStore(applicationContext).tasks().filter { !it.done && it.category == de.jarvis.app.tools.SchoolTool.HOMEWORK }
            val shop = de.jarvis.app.tools.ShoppingTool.items(applicationContext)
            val bd = de.jarvis.app.tools.BirthdayTool.upcoming(applicationContext, 7)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                extrasBox.removeAllViews()
                extrasBox.addView(ui.text("🎒 " + (if (late) "Morgen: " else "Heute: ") + (school ?: "kein Stundenplan gespeichert – sag z. B. „Mein Montag: 8 Uhr Mathe, 9 Uhr Deutsch“"), 14f, if (school != null) Color.WHITE else MUTED))
                if (hw.isNotEmpty()) extrasBox.addView(ui.text("📚 Hausaufgaben: " + hw.joinToString(", ") { it.title }, 13f, AMBER).apply { setPadding(0, ui.px(6), 0, 0) })
                extrasBox.addView(ui.text(if (shop.isEmpty()) "🛒 Einkaufsliste leer" else "🛒 " + shop.joinToString(", ") + "  (antippen = abhaken)", 14f,
                    if (shop.isEmpty()) MUTED else Color.WHITE).apply {
                    setPadding(0, ui.px(8), 0, 0)
                    if (shop.isNotEmpty()) setOnClickListener { checkShopping(shop) }
                })
                if (bd.isNotEmpty()) extrasBox.addView(ui.text("🎂 " + bd.joinToString("  ·  ") { de.jarvis.app.tools.BirthdayTool.describe(it) }, 13f, SOFT).apply { setPadding(0, ui.px(8), 0, 0) })
            }
        }
    }

    private fun checkShopping(items: List<String>) {
        val checked = BooleanArray(items.size)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Einkaufsliste – abhaken")
            .setMultiChoiceItems(items.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Erledigt") { _, _ ->
                val done = items.filterIndexed { i, _ -> checked[i] }
                if (done.isNotEmpty()) runTool { de.jarvis.app.tools.ShoppingTool.remove(applicationContext, done) }
            }.setNegativeButton("Abbrechen", null).show()
    }

    private fun shoppingDialog() {
        val what = input("z. B. Milch, Eier und Brot")
        dialog("Auf die Einkaufsliste", listOf(what)) {
            runTool { de.jarvis.app.tools.ShoppingTool.add(applicationContext, what.text.toString().split(Regex(",| und ")).map { it.trim() }) }
        }
    }

    private fun noteDialog() {
        val what = input("Notiz")
        dialog("Neue Notiz", listOf(what)) {
            runTool { de.jarvis.app.tools.NotesTool.run(ToolContext(this), JSONObject().put("action", "add").put("text", what.text.toString())) }
        }
    }

    private fun parkingDialog() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Parkplatz")
            .setItems(arrayOf("Hier geparkt – merken", "Zum Parkplatz navigieren")) { _, i ->
                runTool { de.jarvis.app.tools.PlacesTool.run(ToolContext(this), JSONObject().put("action", if (i == 0) "save" else "go").put("name", "Parkplatz")) }
            }.show()
    }

    private fun showBriefing() {
        toast("Stelle deinen Tag zusammen …")
        thread {
            val text = try { de.jarvis.app.tools.BriefingTool.build(applicationContext) } catch (e: Exception) { "Fehler: ${e.message}" }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Dein Tag")
                    .setMessage(text).setPositiveButton("OK", null)
                    .setNeutralButton("Vorlesen lassen") { _, _ -> startActivity(Intent(this, JarvisActivity::class.java)); toast("Sag „Guten Morgen“") }.show()
                refreshWeather()
            }
        }
    }

    // ---------- Live-Status (Home) ----------

    private fun refreshLive() {
        if (!::liveBox.isInitialized) return
        thread {
            val on = online()
            val bm = getSystemService(BatteryManager::class.java)
            val bat = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val cm = getSystemService(ConnectivityManager::class.java)
            val wifi = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val store = AgendaStore(applicationContext); val now = System.currentTimeMillis()
            val task = store.tasks().filter { !it.done }.sortedWith(compareBy({ it.due ?: Long.MAX_VALUE }, { it.priority != "hoch" })).firstOrNull()
            val rem = store.reminders().filter { it.active && it.next != null && it.next > now }.minByOrNull { it.next!! }
            val ev = de.jarvis.app.tools.CalendarTool.safeEvents(applicationContext, now, now + 2 * 86_400_000L).firstOrNull { !it.allDay && it.end > now }
            val model = ModelStatus.current.ifBlank { if (prefs.nvidiaKey.isNotBlank()) "NVIDIA" else if (prefs.groqKey.isNotBlank()) "Groq" else "–" }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                liveBox.removeAllViews()
                val top = ui.row()
                top.addView(android.view.View(this).apply { background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(if (on) GREEN else AMBER) } }, LinearLayout.LayoutParams(ui.px(12), ui.px(12)))
                top.addView(ui.text(if (on) "  JARVIS ONLINE" else "  OFFLINE-MODUS", 15f, if (on) GREEN else AMBER, true).apply { letterSpacing = 0.12f })
                liveBox.addView(top)
                fun line(k: String, v: String) = liveBox.addView(ui.row().apply { setPadding(0, ui.px(4), 0, 0)
                    addView(ui.text(k, 12f, MUTED).apply { width = ui.px(110) }); addView(ui.weighted(ui.text(v, 13f, Color.WHITE).apply { maxLines = 1 })) })
                line("Mikrofon", if (WakeWordService.isRunning) "hört auf „Hey Jarvis“" else "Wake-Word aus")
                line("KI-Modell", model)
                line("Netz", (if (on) "Internet" else "kein Internet") + " · " + (if (wifi) "WLAN" else "Mobil/aus") + (if (bat >= 0) " · Akku $bat %" else ""))
                line("Aufgabe", task?.title ?: "keine offen")
                line("Erinnerung", rem?.let { TimeLogic.short(it.next!!) + "  " + it.text } ?: "keine")
                line("Termin", ev?.let { TimeLogic.short(it.begin) + "  " + it.title } ?: "keine")
            }
        }
    }

    private fun filesDialog() {
        val f = input("Dateiname oder Stichwort, z. B. Mathe")
        dialog("Datei finden", listOf(f)) {
            runTool { de.jarvis.app.tools.FilesTool.run(ToolContext(this), JSONObject().put("action", "find").put("query", f.text.toString())) }
        }
    }

    // ---------- System-Seite ----------

    private fun refreshSystem() {
        if (!::systemBox.isInitialized) return
        val on = online()
        statusText.text = if (on) "ONLINE" else "OFFLINE"
        statusText.setTextColor(if (on) GREEN else AMBER)
        thread {
            val bm = getSystemService(BatteryManager::class.java)
            val bat = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val charging = bm?.isCharging == true
            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            val net = when {
                caps == null -> "Keins"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WLAN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobil"
                else -> "Verbunden"
            }
            val free = try { android.os.StatFs(android.os.Environment.getDataDirectory().path).availableBytes / 1_000_000_000L } catch (_: Exception) { -1L }
            val loc = de.jarvis.app.tools.Geo.lastKnown(applicationContext)
            val city = loc?.let { l -> try { @Suppress("DEPRECATION")
                android.location.Geocoder(this, Locale.GERMANY).getFromLocation(l.first, l.second, 1)?.firstOrNull()?.locality } catch (_: Exception) { null } }
            val lm = getSystemService(android.location.LocationManager::class.java)
            val locOn = try { if (android.os.Build.VERSION.SDK_INT >= 28) lm?.isLocationEnabled == true else true } catch (_: Exception) { false }
            val bt = try { getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true } catch (_: Exception) { false }
            val media = try {
                val msm = getSystemService(android.media.session.MediaSessionManager::class.java)
                val sessions = msm?.getActiveSessions(android.content.ComponentName(this, JarvisNotificationListener::class.java)).orEmpty()
                sessions.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
            } catch (_: Exception) { null }
            val md = media?.metadata
            val song = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            val artist = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                systemBox.removeAllViews()
                val grid = GridLayout(this).apply { columnCount = 2 }
                fun tile(icon: String, label: String, value: String, ok: Boolean? = null, action: (() -> Unit)? = null) {
                    val box = ui.card(false).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ui.px(12), ui.px(14), ui.px(10), ui.px(14))
                        action?.let { a -> setOnClickListener { a() } } }
                    box.addView(IconView(this, icon, if (ok == false) MUTED else if (ok == true) GREEN else RED), LinearLayout.LayoutParams(ui.px(30), ui.px(30)))
                    val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(10), 0, 0, 0) }
                    t.addView(ui.text(label, 12f, MUTED))
                    t.addView(ui.text(value, 15f, Color.WHITE, true).apply { maxLines = 2 })
                    box.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
                    grid.addView(box, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                        width = 0; setMargins(ui.px(4), ui.px(4), ui.px(4), ui.px(4)) })
                }
                tile("battery", "Akku", if (bat >= 0) "$bat %" + (if (charging) " ⚡" else "") else "?", bat > 20)
                tile("wifi", "Netzwerk", net, caps != null) { startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) }
                tile("storage", "Speicher", if (free >= 0) "$free GB frei" else "?", free > 2)
                tile("pin", "Standort", city ?: if (loc != null) "bekannt" else "unbekannt", locOn) {
                    startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                tile("music", "Aktuelle Musik", song?.let { it + (artist?.let { a -> " – $a" } ?: "") } ?: "Nichts läuft", song != null) { musicDialog() }
                tile("device", "Gerät", android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + android.os.Build.MODEL)
                tile("mic", "Mikrofon / Hey Jarvis", if (WakeWordService.isRunning) "Hört zu" else "Aus", WakeWordService.isRunning) {
                    startActivity(Intent(this, MainActivity::class.java)) }
                tile("camera", "Kamera", "Bereit", true) { openCamera() }
                tile("pin", "Standortdienst", if (locOn) "Ein" else "Aus", locOn) { startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                tile("bluetooth", "Bluetooth", if (bt) "Ein" else "Aus", bt) { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
                tile("brain", "KI", if (prefs.nvidiaKey.isNotBlank()) "NVIDIA" else if (prefs.groqKey.isNotBlank()) "Groq" else if (prefs.hasAnyKey) "bereit" else "Schlüssel fehlt", prefs.hasAnyKey) {
                    startActivity(Intent(this, MainActivity::class.java)) }
                tile("shield", "Berechtigungen", "Verwalten") { startActivity(Intent(this, PermissionsActivity::class.java)) }
                systemBox.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(10) })
                systemBox.addView(ui.text("Schalter öffnen die passende Android-Einstellung – Jarvis ändert nichts heimlich.", 12f, MUTED)
                    .apply { setPadding(ui.px(4), ui.px(10), 0, 0) })
            }
        }
    }

    // ---------- Werkzeug-Dialoge ----------

    private fun list(title: String, items: List<String>, empty: String, onClick: ((Int) -> Unit)? = null, extra: Pair<String, () -> Unit>? = null) {
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title)
        if (items.isEmpty()) b.setMessage(empty) else b.setItems(items.toTypedArray()) { _, i -> onClick?.invoke(i) }
        extra?.let { (label, act) -> b.setPositiveButton(label) { _, _ -> act() } }
        b.setNegativeButton("Schließen", null).show()
    }

    private fun remindersDialog() {
        val store = AgendaStore(this)
        val rem = store.reminders().filter { it.active }.sortedBy { it.next ?: Long.MAX_VALUE }
        val tasks = store.tasks().filter { !it.done }.sortedBy { it.due ?: Long.MAX_VALUE }
        val lines = rem.map { "🔔 " + it.describe().substringAfter(" | ") } + tasks.map { "☐ " + it.title + (it.due?.let { d -> "  · " + TimeLogic.short(d) } ?: "") }
        list("Erinnerungen & Aufgaben", lines, "Nichts geplant.", { i -> if (i >= rem.size) completeTask(tasks[i - rem.size].id) },
            "Neu" to { AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setItems(arrayOf("Neue Erinnerung", "Neue Aufgabe")) { _, w -> if (w == 0) addReminderDialog() else addTaskDialog() }.show() })
    }

    private fun phoneDialog() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Telefon")
            .setItems(arrayOf("Letzte Anrufe", "Telefon-App öffnen", "Kontakte")) { _, i ->
                when (i) {
                    0 -> runTool { de.jarvis.app.tools.CallLogTool.run(ToolContext(this), JSONObject().put("limit", 8)) }
                    1 -> safeStart(Intent(Intent.ACTION_DIAL))
                    else -> safeStart(Intent(Intent.ACTION_VIEW, android.provider.ContactsContract.Contacts.CONTENT_URI))
                }
            }.show()
    }

    private fun openMail() = safeStart(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL))
    private fun openCamera() = safeStart(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
    private fun safeStart(i: Intent) = try { startActivity(i) } catch (_: Exception) { toast("Keine passende App gefunden.") }

    private fun musicDialog() {
        val am = getSystemService(android.media.AudioManager::class.java)
        fun key(k: Int) { am?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, k))
            am?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, k)) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Musik")
            .setItems(arrayOf("⏯  Play / Pause", "⏭  Nächstes Lied", "⏮  Vorheriges Lied", "🎵  Lied erkennen", "Spotify öffnen")) { _, i ->
                when (i) {
                    0 -> key(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    1 -> key(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                    2 -> key(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    3 -> runTool { de.jarvis.app.tools.MusicTool.run(ToolContext(this), JSONObject()) }
                    else -> packageManager.getLaunchIntentForPackage("com.spotify.music")?.let { safeStart(it) } ?: toast("Spotify ist nicht installiert.")
                }
                if (current == "system") main.postDelayed({ refreshSystem() }, 800)
            }.show()
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun schoolDialog() {
        val late = java.time.LocalTime.now().hour >= 15
        val day = java.time.LocalDate.now().plusDays(if (late) 1 else 0).dayOfWeek
        val lessons = de.jarvis.app.tools.SchoolTool.lessons(this, day)
        val hw = AgendaStore(this).tasks().filter { !it.done && it.category == de.jarvis.app.tools.SchoolTool.HOMEWORK }
        val lines = lessons.map { "🕘 $it" } + hw.map { "📚 ${it.title}" + (it.due?.let { d -> " · bis " + TimeLogic.day(d) } ?: "") }
        list((if (late) "Morgen" else "Heute") + " in der Schule", lines,
            "Kein Stundenplan gespeichert.\n\nSchick Jarvis im Chat ein Foto deines Stundenplans und schreib „Speicher meinen Stundenplan“ – oder sag z. B. „Mein Montag: 8 Uhr Mathe, 9:45 Deutsch“.",
            { i -> if (i >= lessons.size) completeTask(hw[i - lessons.size].id) },
            "Foto schicken" to { startActivity(Intent(this, ChatActivity::class.java)) })
    }

    private fun notesDialog() {
        val notes = de.jarvis.app.tools.JsonStore(this).list("notes").reversed()
        list("Notizen", notes.map { TimeLogic.short(it.optLong("at")) + "  " + it.optString("t") }, "Noch keine Notizen.", null, "Neue Notiz" to { noteDialog() })
    }

    private fun routinesDialog() {
        val r = de.jarvis.app.tools.JsonStore(this).list("routines")
        list("Eigene Kommandos", r.map { "⚡ " + it.optString("name") }, "Noch keine eigenen Kommandos.\n\nSag z. B.: „Wenn ich Gaming-Modus sage, mach Nicht stören an, Lautstärke auf 80 und starte Spotify.“",
            { i -> val ctxTools = PhoneTools(this); runTool { ctxTools.execute("routines", JSONObject().put("action", "run").put("name", r[i].optString("name"))) } })
    }

    // ---------- Aktionen ----------

    private fun talk() = startActivity(Intent(this, JarvisActivity::class.java))

    private fun askJarvis(q: String) = startActivity(Intent(this, ChatActivity::class.java).setAction(Intent.ACTION_SEND)
        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, q))

    private fun input(hint: String, number: Boolean = false) = EditText(this).apply {
        this.hint = hint; setTextColor(Color.WHITE); setHintTextColor(MUTED)
        if (number) inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun dialog(title: String, views: List<EditText>, ok: () -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(20), ui.px(8), ui.px(20), 0) }
        views.forEach { box.addView(it) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title).setView(box).setPositiveButton("OK") { _, _ -> ok() }.setNegativeButton("Abbrechen", null).show()
    }

    private fun addTaskDialog() {
        val title = input("Aufgabe, z. B. Mathe lernen")
        val due = input("Fällig (optional): heute, morgen, Fr oder 24.12.")
        val prio = input("Priorität (optional): hoch, mittel, niedrig")
        dialog("Neue Aufgabe", listOf(title, due, prio)) {
            val a = JSONObject().put("action", "add").put("title", title.text.toString())
            parseDue(due.text.toString())?.let { a.put("due", it) }
            prio.text.toString().trim().takeIf { it.isNotBlank() }?.let { a.put("priority", it) }
            runTool { TaskTool.run(ToolContext(this), a) }
        }
    }

    /** "heute", "morgen", Wochentag oder TT.MM. → YYYY-MM-DD */
    private fun parseDue(s: String): String? {
        val t = s.trim().lowercase(Locale.GERMANY)
        if (t.isBlank()) return null
        val today = java.time.LocalDate.now()
        val d = when {
            t == "heute" -> today
            t == "morgen" -> today.plusDays(1)
            t == "übermorgen" -> today.plusDays(2)
            TimeLogic.parseDay(t) != null -> { val dow = TimeLogic.parseDay(t)!!; var x = today.plusDays(1); while (x.dayOfWeek != dow) x = x.plusDays(1); x }
            Regex("""(\d{1,2})\.(\d{1,2})\.?(\d{2,4})?""").matches(t) -> Regex("""(\d{1,2})\.(\d{1,2})\.?(\d{2,4})?""").find(t)!!.groupValues.let {
                val y = it[3].toIntOrNull()?.let { yy -> if (yy < 100) 2000 + yy else yy }
                var x = runCatching { java.time.LocalDate.of(y ?: today.year, it[2].toInt(), it[1].toInt()) }.getOrNull() ?: return null
                if (y == null && x.isBefore(today)) x = x.plusYears(1); x }
            else -> TimeLogic.parseLocal(t)?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
        } ?: return null
        return d.toString()
    }

    private fun addReminderDialog() {
        val text = input("Woran? z. B. Wäsche aufhängen")
        val time = input("Wann? z. B. 18:30, „in 20 Minuten“ oder „morgen 9 Uhr“")
        dialog("Neue Erinnerung", listOf(text, time)) {
            val w = time.text.toString().trim()
            val cmd = if (w.toIntOrNull() != null) null
                else LocalCommands.parse("erinnere mich ${if (w.firstOrNull()?.isDigit() == true && !w.contains("min")) "um $w" else w} an ${text.text}")
            val a = if (cmd != null && cmd.tool == "reminders") JSONObject(cmd.args).put("text", text.text.toString())
                else JSONObject().put("text", text.text.toString()).also { j ->
                    LocalCommands.parseClock(w.lowercase())?.let { (h, m) -> j.put("hour", h).put("minute", m).put("tomorrow", w.lowercase().contains("morgen")) }
                    w.toIntOrNull()?.let { j.remove("hour"); j.put("in_minutes", it) }
                }
            a.put("action", "add")
            runTool { ReminderTool.run(ToolContext(this), a) }
        }
    }

    private fun runTool(block: () -> String) = thread {
        val r = try { block() } catch (e: Exception) { Res.error(e.message ?: "Unbekannter Fehler") }
        runOnUiThread { toast(Res.spoken(r)); refreshAgenda() }
    }

    private fun timerDialog() {
        val min = input("Minuten", number = true)
        dialog("Timer", listOf(min)) {
            val m = min.text.toString().toIntOrNull() ?: return@dialog
            try {
                startActivity(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, m * 60)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                toast("Timer: $m Minuten")
            } catch (_: Exception) { toast("Keine Uhr-App gefunden.") }
        }
    }

    private fun toggleTorch() {
        val cm = getSystemService(CameraManager::class.java)!!
        try {
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return toast("Keine Taschenlampe gefunden.")
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
        } catch (e: Exception) { toast("Taschenlampe geht gerade nicht.") }
    }

    private fun openCalendar() {
        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also { ContentUris.appendId(it, System.currentTimeMillis()) }.build()
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { toast("Keine Kalender-App gefunden.") }
    }

    private fun showTasks() {
        val tasks = AgendaStore(this).tasks().filter { !it.done }.sortedWith(compareBy({ it.priority != "hoch" }, { it.due ?: Long.MAX_VALUE }))
        if (tasks.isEmpty()) { toast("Keine offenen Aufgaben."); return }
        val labels = tasks.map { t ->
            "☐ ${t.title}" + (t.due?.let { "  ·  " + if (t.hasTime) TimeLogic.short(it) else TimeLogic.day(it) } ?: "") +
                (if (t.category.isNotBlank()) "  ·  ${t.category}" else "") + (if (t.priority == "hoch") "  !" else "")
        }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Offene Aufgaben – antippen = erledigt")
            .setItems(labels) { _, i -> completeTask(tasks[i].id) }
            .setNegativeButton("Schließen", null).show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    @Suppress("unused") private val needed = Manifest.permission.READ_CALENDAR
}
