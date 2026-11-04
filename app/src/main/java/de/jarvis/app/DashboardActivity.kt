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
    private lateinit var historyBox: LinearLayout
    private var hud: HudCoreView? = null
    private lateinit var greetName: TextView
    private lateinit var nextBox: LinearLayout
    private var batGauge: GaugeView? = null
    private var batText: TextView? = null
    private var hudStatus: TextView? = null
    private var timeline: TimelineView? = null
    private var nowText: TextView? = null
    private companion object { var lastUpdateLook = 0L }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!prefs.v20Migrated) { prefs.design = "HOLO"; if (prefs.uiTheme != "HELL") prefs.uiTheme = "BLAU"; prefs.v20Migrated = true }
        thread { ProShell.keepAccessibility(applicationContext) }
        if (prefs.bubble && BubbleService.instance == null) BubbleService.start(this)
        if (!prefs.wizardShown) { prefs.wizardShown = true; startActivity(Intent(this, PermissionsActivity::class.java).putExtra("wizard", true)) }
        Ui.load(prefs); ui = Ui(this)
        window.statusBarColor = BG; window.navigationBarColor = BG; Ui.lightBars(window)
        Notifier.channels(this)

        // Seiten füllen den ganzen Bildschirm, die Leiste schwebt darüber
        val root = android.widget.FrameLayout(this).apply { background = ui.aurora() }
        content = android.widget.FrameLayout(this)
        root.addView(content, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(buildNav(), android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(ui.px(16), 0, ui.px(16), ui.px(14)) })
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
            v.second.setTextColor(if (on) Ui.FG else MUTED)
            v.first.alpha = if (on) 1f else 0.45f
        }
        if (tab == "system") refreshSystem()
    }

    private fun buildNav(): android.view.View {
        val wrap = android.widget.FrameLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(0, ui.px(26), 0, 0) }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 24 * ui.dp
                setColor(if (Ui.light) Color.WHITE else Color.argb(245, Color.red(Ui.CARD), Color.green(Ui.CARD), Color.blue(Ui.CARD))); setStroke(ui.px(1), Color.argb(22, 255, 255, 255)) }
            setPadding(ui.px(4), ui.px(8), ui.px(4), ui.px(8))
            elevation = ui.px(10).toFloat()
        }
        fun item(key: String, icon: String, label: String) {
            val it = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setOnClickListener { if (key == "chat") startActivity(Intent(this@DashboardActivity, ChatActivity::class.java)) else show(key) } }
            val iv = IconView(this, icon, Ui.FG)
            it.addView(iv, LinearLayout.LayoutParams(ui.px(22), ui.px(22)))
            val tv = ui.text(label, 10.5f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, ui.px(3), 0, 0) }
            it.addView(tv)
            navViews[key] = iv to tv
            nav.addView(it, LinearLayout.LayoutParams(0, -2, 1f))
        }
        item("home", "home", "Home"); item("chat", "chat", "Chat")
        nav.addView(android.view.View(this), LinearLayout.LayoutParams(ui.px(70), 1))   // Platz für den Mikrofon-Knopf
        item("tools", "tools", "Tools"); item("system", "gear", "System")
        wrap.addView(nav, android.widget.FrameLayout.LayoutParams(-1, ui.px(66)))
        // Großer Mikrofon-Knopf in der Mitte
        wrap.addView(IconView(this, "mic", Color.rgb(3, 16, 32)).apply {
            background = ui.gradient(RED, Ui.ACCENT2, 30); setPadding(ui.px(17), ui.px(17), ui.px(17), ui.px(17))
            elevation = ui.px(14).toFloat(); setOnClickListener { talk() }
        }, android.widget.FrameLayout.LayoutParams(ui.px(60), ui.px(60), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = -ui.px(22) })
        return wrap
    }

    private fun page(col: LinearLayout) = ScrollView(this).apply { isFillViewport = true; clipToPadding = false
        setPadding(0, 0, 0, ui.px(100)); addView(col) }   // unten Platz für die schwebende Leiste

    private fun title(t: String, sub: String?, gear: Boolean): LinearLayout {
        val head = ui.row().apply { setPadding(0, ui.px(6), 0, 0) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // clean: klarer Seitentitel („Werkzeuge“, „System“) statt gesperrter Schrift
        val main = sub?.lowercase()?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } ?: t
        left.addView(ui.text(main, 28f, Ui.FG, true))
        left.addView(ui.text("Jarvis", 13f, MUTED))
        head.addView(ui.weighted(left))
        if (gear) head.addView(IconView(this, "gear", SOFT).apply {
            background = ui.round(Color.argb(14, 255, 255, 255), 22, Color.argb(22, 255, 255, 255)); setPadding(ui.px(10), ui.px(10), ui.px(10), ui.px(10))
            setOnClickListener { startActivity(Intent(this@DashboardActivity, MainActivity::class.java)) }
        }, LinearLayout.LayoutParams(ui.px(40), ui.px(40)))
        return head
    }

    // ---------- Home ----------

    private var headView: HeadTextureView? = null

    private fun buildHome(): ScrollView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(20), ui.px(18), ui.px(20), ui.px(28)) }
        // Kopfzeile: Datum · Glocke · Einstellungen
        val head = ui.row()
        dateText = ui.text("", 13.5f, MUTED)
        head.addView(ui.weighted(dateText))
        fun roundIcon(icon: String, act: () -> Unit) = IconView(this, icon, Ui.SOFT).apply {
            background = ui.round(Color.argb(14, 255, 255, 255), 22, Color.argb(22, 255, 255, 255))
            setPadding(ui.px(10), ui.px(10), ui.px(10), ui.px(10)); setOnClickListener { act() } }
        head.addView(roundIcon("bell") { askJarvis("Fass meine neuen Nachrichten kurz zusammen.", true) }, LinearLayout.LayoutParams(ui.px(40), ui.px(40)))
        head.addView(roundIcon("gear") { startActivity(Intent(this@DashboardActivity, MainActivity::class.java)) },
            LinearLayout.LayoutParams(ui.px(40), ui.px(40)).apply { leftMargin = ui.px(8) })
        col.addView(head)

        // Der echte 3D-Kopf (live) – antippen = mit Jarvis reden
        val hero = android.widget.FrameLayout(this)
        val hv = HeadTextureView(this).apply { onTap = { talk() } }
        headView = hv
        hero.addView(hv, android.widget.FrameLayout.LayoutParams(-1, -1))
        hudStatus = ui.text("● Bereit – sag „Jarvis“", 12f, GREEN).apply {
            background = ui.round(Color.argb(20, 0, 229, 255), 16, Color.argb(56, 0, 229, 255))
            setPadding(ui.px(12), ui.px(6), ui.px(12), ui.px(6))
            setOnClickListener { if (!prefs.hasAnyKey) startActivity(Intent(this@DashboardActivity, ProvidersActivity::class.java)) else talk() } }
        hero.addView(hudStatus, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.px(6) })
        col.addView(hero, LinearLayout.LayoutParams(-1, ui.px(290)).apply { topMargin = ui.px(4) })

        // Begrüßung (eine Zeile, zentriert)
        val greetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        greeting = ui.text("", 25f, Ui.FG).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        greetName = ui.text("", 25f, Ui.FG, true).apply { setPadding(ui.px(7), 0, 0, 0) }
        greetRow.addView(greeting); greetRow.addView(greetName)
        col.addView(greetRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(10) })

        // „Frag Jarvis etwas …“ – Eingabe-Pille mit Mikrofon
        val ask = ui.row().apply {
            background = ui.round(Color.argb(16, 255, 255, 255), 28, Color.argb(26, 255, 255, 255))
            setPadding(ui.px(20), ui.px(6), ui.px(6), ui.px(6))
            setOnClickListener { startActivity(Intent(this@DashboardActivity, ChatActivity::class.java)) } }
        ask.addView(ui.weighted(ui.text("Frag Jarvis etwas …", 15f, MUTED)))
        ask.addView(IconView(this, "mic", Color.rgb(3, 16, 32)).apply { background = ui.gradient(RED, Ui.ACCENT2, 22)
            setPadding(ui.px(11), ui.px(11), ui.px(11), ui.px(11)); setOnClickListener { talk() } }, LinearLayout.LayoutParams(ui.px(44), ui.px(44)))
        col.addView(ask, LinearLayout.LayoutParams(-1, ui.px(56)).apply { topMargin = ui.px(18) })

        // Drei Kacheln: Wetter · Als Nächstes · Akku
        val tiles = ui.row().apply { gravity = Gravity.TOP }
        fun tile() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = ui.glass(18); setPadding(ui.px(12), ui.px(12), ui.px(10), ui.px(12)) }
        weatherBox = tile()
        nextBox = tile().apply { setOnClickListener { startActivity(Intent(this@DashboardActivity, CalendarActivity::class.java)) } }
        val batBox = tile().apply { setOnClickListener { show("system") } }
        batGauge = null
        batBox.addView(ui.text("Akku", 11.5f, MUTED))
        batText = ui.text("", 19f, Ui.FG, true).apply { setPadding(0, ui.px(6), 0, 0) }
        batBox.addView(batText)
        tiles.addView(weatherBox, LinearLayout.LayoutParams(0, ui.px(100), 1f))
        tiles.addView(nextBox, LinearLayout.LayoutParams(0, ui.px(100), 1f).apply { leftMargin = ui.px(10) })
        tiles.addView(batBox, LinearLayout.LayoutParams(0, ui.px(100), 1f).apply { leftMargin = ui.px(10) })
        col.addView(tiles, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(14) })

        msgBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = ui.glass(20); setPadding(ui.px(16), ui.px(14), ui.px(16), ui.px(14)) }
        col.addView(msgBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(10) })

        // „Für dich“ – Vorschläge
        col.addView(ui.row().apply { setPadding(ui.px(4), ui.px(20), ui.px(4), ui.px(8))
            addView(ui.weighted(ui.text("Für dich", 13.5f, MUTED, true)))
            addView(ui.text("Alle Tools", 12f, MUTED).apply { setOnClickListener { show("tools") } }) })
        val forYou = LinearLayout(this)
        fun fy(emoji: String, t: String, sub: String, act: () -> Unit) = forYou.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = ui.glass(18); setPadding(ui.px(14), ui.px(14), ui.px(14), ui.px(14)); setOnClickListener { act() }
            val icon = when (emoji) { "🎒" -> "school"; "⏱" -> "timer"; "📷" -> "camera"; "🦖" -> "brain"; "🎮" -> "chat"; else -> "apps" }
            addView(IconView(this@DashboardActivity, icon, RED).apply { background = ui.round(Color.argb(22, 0, 229, 255), 12)
                setPadding(ui.px(8), ui.px(8), ui.px(8), ui.px(8)) }, LinearLayout.LayoutParams(ui.px(36), ui.px(36)))
            addView(ui.text(t, 14f, Ui.FG, true).apply { setPadding(0, ui.px(10), 0, 0); maxLines = 1 })
            addView(ui.text(sub, 11.5f, MUTED).apply { maxLines = 1 })
        }, LinearLayout.LayoutParams(ui.px(150), -2).apply { rightMargin = ui.px(10) })
        fy("🎒", "Packliste", "Was muss mit?") { thread {
            val r = try { de.jarvis.app.tools.SchoolTool.pack(this, if (java.time.LocalTime.now().hour >= 12) "morgen" else "heute") } catch (e: Exception) { Res.error(e.message ?: "Fehler") }
            runOnUiThread { android.app.AlertDialog.Builder(this).setTitle("🎒 Packliste").setMessage(Res.spoken(r)).setPositiveButton("OK", null).show() } } }
        fy("⏱", "Fokus 25 min", "Lernen ohne Ablenkung") { startActivity(Intent(this, FocusActivity::class.java)) }
        fy("📷", "Was ist das?", "Kamera-Scan") { startActivity(Intent(this, ScanActivity::class.java)) }
        fy("🦖", "Hologramm", "Dinos & Planeten") { voice("Erklär mir den T-Rex in drei Sätzen.") }
        fy("🎮", "Wer bin ich?", "Ratespiel") { voice("Lass uns „Wer bin ich?“ spielen. Du denkst dir eine berühmte Person oder Figur aus, ich stelle Ja-Nein-Fragen.") }
        col.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(forYou) })

        // Verlauf: letzte Fragen
        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(historyBox)
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        liveBox = ui.card().apply { setOnClickListener { show("system") } }
        col.addView(liveBox)

        col.addView(ui.section("Heute"))
        todayBox = ui.card()
        timeline = TimelineView(this)
        col.addView(ui.card().apply { addView(timeline) })
        col.addView(todayBox)
        weekText = ui.text("", 13f, MUTED).apply { setPadding(ui.px(4), ui.px(8), 0, 0)
            setOnClickListener { startActivity(Intent(this@DashboardActivity, CalendarActivity::class.java)) } }
        col.addView(weekText)
        col.addView(ui.section("Schule · Einkauf · Geburtstage"))
        extrasBox = ui.card(); col.addView(extrasBox)
        col.addView(android.view.View(this), LinearLayout.LayoutParams(-1, ui.px(90)))   // Platz für die schwebende Leiste
        return page(col)
    }

    // ---------- Tools ----------

    private class ToolTile(val icon: String, val name: String, val sub: String, val action: () -> Unit)

    private fun toolCategory(name: String): String = when (name) {
        "Was ist das?", "Schwebender Jarvis", "Hologramme", "Handy finden", "Profi-Modus", "Autopilot", "Sicherheit" -> "⭐ Highlights"
        "Chat", "Telefon", "Nachrichten", "E-Mails", "Übersetzer" -> "💬 Kommunikation"
        "Schule", "Lernen", "Packliste", "Fokus-Timer", "Klassenarbeit-Coach" -> "🎓 Schule & Lernen"
        "Quiz-Abend", "Geschichte", "Witz", "Wer bin ich?", "Rätsel", "Abenteuer" -> "🎮 Spaß"
        "Automationen", "Kommandos", "Gedächtnis", "Persönlichkeit", "KI-Schlüssel", "Update", "Alles freigeben", "Berechtigungen",
        "Weckwort trainieren", "Meine Geräte", "Aktionsverlauf", "Statistik", "Plugins", "Personalisieren", "Sicherung", "Notfall" -> "⚙ Jarvis & System"
        else -> "🏠 Alltag"
    }

    private fun buildTools(): ScrollView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(18), ui.px(18), ui.px(18), ui.px(28)) }
        col.addView(title("J A R V I S", "WERKZEUGE", gear = true))
        val all = mutableListOf<ToolTile>()
        fun tile(icon: String, name: String, sub: String, action: () -> Unit) { all += ToolTile(icon, name, sub, action) }
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
        tile("camera", "Was ist das?", "Kamera-Scan mit KI") { startActivity(Intent(this, ScanActivity::class.java)) }
        tile("mic", "Schwebender Jarvis", if (prefs.bubble) "An – antippen zum Ausschalten" else "Kugel über allen Apps") { toggleBubble() }
        tile("learn", "Klassenarbeit-Coach", "Lernplan bis zur Arbeit") { voice("Ich schreibe bald eine Klassenarbeit. Frag mich nach Fach, Datum und Thema und mach mir dann einen Lernplan.") }
        tile("chat", "Wer bin ich?", "Ratespiel mit Jarvis") { voice("Lass uns „Wer bin ich?“ spielen. Du denkst dir eine berühmte Person oder Figur aus, ich stelle Ja-Nein-Fragen.") }
        tile("brain", "Rätsel", "Knobeln mit Jarvis") { voice("Gib mir ein kniffliges, aber lösbares Rätsel. Verrate die Lösung erst, wenn ich aufgebe.") }
        tile("sun", "Abenteuer", "Du entscheidest die Story") { voice("Starte ein kurzes Abenteuer, in dem ich der Held bin. Gib mir nach jedem Abschnitt zwei oder drei Möglichkeiten.") }
        tile("bolt", "Autopilot", "Was Jarvis von selbst macht") { startActivity(Intent(this, AutoPilotActivity::class.java)) }
        tile("shield", "Sicherheit", "Check, Phishing, Passwort") { startActivity(Intent(this, SecurityActivity::class.java)) }
        tile("pin", "Fahrzeug", "Auto, Motorrad, Tacho, Runden") { startActivity(Intent(this, VehicleActivity::class.java)) }
        tile("phone", "Handy finden", "Klingeln & Blinken") { FindActivity.start(this) }
        tile("brain", "KI-Schlüssel", "Mehr Anbieter = weniger Pausen") { startActivity(Intent(this, ProvidersActivity::class.java)) }
        tile("storage", "Update", "Neue Version installieren") { Updater.check(this, quiet = false) }
        tile("bolt", "Profi-Modus", "Systemschalter direkt (Shizuku)") { startActivity(Intent(this, ProActivity::class.java)) }
        tile("shield", "Alles freigeben", "Assistent für alle Rechte") { startActivity(Intent(this, PermissionsActivity::class.java).putExtra("wizard", true)) }
        tile("brain", "Hologramme", "3D: Dinos, Planeten …") { voice("Erklär mir den T-Rex in drei Sätzen.") }
        tile("timer", "Fokus-Timer", "Pomodoro & Nicht stören") { startActivity(Intent(this, FocusActivity::class.java)) }
        tile("school", "Packliste", "Was muss in die Tasche?") { thread {
            val r = try { de.jarvis.app.tools.SchoolTool.pack(this, if (java.time.LocalTime.now().hour >= 12) "morgen" else "heute") } catch (e: Exception) { Res.error(e.message ?: "Fehler") }
            runOnUiThread { android.app.AlertDialog.Builder(this).setTitle("🎒 Packliste").setMessage(Res.spoken(r)).setPositiveButton("OK", null).show() } } }
        tile("battery", "Taschengeld", "Ausgaben & Sparziel") { askJarvis("Wie viel Taschengeld habe ich diesen Monat ausgegeben und wie steht mein Sparziel?", true) }
        tile("camera", "Fotos finden", "Nach Zeit, Ordner, Ort") { askJarvis("Zeig mir meine Fotos von ") }
        tile("message", "Übersetzer", "Dolmetscher-Modus") { voice("Ich möchte, dass du für mich übersetzt. Frag mich, in welche Sprache.") }
        tile("learn", "Quiz-Abend", "Quizmaster für alle") { voice("Lass uns einen Quiz-Abend machen! Frag, wer mitspielt.") }
        tile("notes", "Geschichte", "Gute-Nacht & Abenteuer") { voice("Erzähl mir eine kurze Geschichte, in der ich der Held bin.") }
        tile("chat", "Witz", "Etwas zum Lachen") { voice("Erzähl mir einen guten Witz.") }
        tile("mic", "Weckwort trainieren", "Nur „Jarvis“ sagen") { startActivity(Intent(this, WakeTrainActivity::class.java)) }
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
        // Suche + Kategorien
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var cat = "Alle"; var query = ""
        val chips = LinearLayout(this)
        fun render() {
            box.removeAllViews()
            val list = all.filter { (cat == "Alle" || toolCategory(it.name) == cat) &&
                (query.isBlank() || (it.name + " " + it.sub).contains(query, true)) }
            if (list.isEmpty()) { box.addView(ui.text("Nichts gefunden – frag Jarvis einfach direkt.", 13f, MUTED).apply { setPadding(ui.px(4), ui.px(16), 0, 0) }); return }
            for ((c, items) in list.groupBy { toolCategory(it.name) }.toSortedMap(compareBy { listOf("⭐ Highlights", "💬 Kommunikation", "🏠 Alltag", "🎓 Schule & Lernen", "🎮 Spaß", "⚙ Jarvis & System").indexOf(it) })) {
                if (cat == "Alle") box.addView(ui.section(c))
                val grid = GridLayout(this).apply { columnCount = 2 }
                for (t in items) {
                    val tb = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; background = ui.glass(16)
                        setPadding(ui.px(14), ui.px(14), ui.px(10), ui.px(14)); setOnClickListener { t.action() }
                    }
                    tb.addView(IconView(this, t.icon, RED).apply {
                        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 10 * ui.dp
                            setColor(Color.argb(28, Color.red(RED), Color.green(RED), Color.blue(RED))) }
                        setPadding(ui.px(6), ui.px(6), ui.px(6), ui.px(6)) }, LinearLayout.LayoutParams(ui.px(34), ui.px(34)))
                    tb.addView(ui.text(t.name, 14.5f, Ui.FG, true).apply { setPadding(0, ui.px(10), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    tb.addView(ui.text(t.sub, 11f, MUTED).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    grid.addView(tb, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                        width = 0; setMargins(ui.px(4), ui.px(4), ui.px(4), ui.px(4)) })
                }
                box.addView(grid)
            }
        }
        val search = ui.row().apply { background = ui.glass(14); setPadding(ui.px(14), ui.px(2), ui.px(10), ui.px(2)) }
        search.addView(ui.text("⌕", 18f, RED).apply { setPadding(0, 0, ui.px(8), 0) })
        search.addView(android.widget.EditText(this).apply {
            hint = "Werkzeug suchen …"; setHintTextColor(MUTED); setTextColor(Ui.FG); textSize = 14f * Ui.fontScale; background = null; isSingleLine = true
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { query = e?.toString().orEmpty().trim(); render() }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(14) })
        val cats = listOf("Alle", "⭐ Highlights", "💬 Kommunikation", "🏠 Alltag", "🎓 Schule & Lernen", "🎮 Spaß", "⚙ Jarvis & System")
        fun paintChips() {
            chips.removeAllViews()
            for (c in cats) chips.addView(ui.text(c, 12.5f, if (c == cat) Color.rgb(0, 16, 24) else Ui.SOFT, c == cat).apply {
                setPadding(ui.px(13), ui.px(8), ui.px(13), ui.px(8))
                background = if (c == cat) ui.gradient(RED, Ui.ACCENT2, 18) else ui.round(Ui.CARD2, 18, Ui.LINE)
                setOnClickListener { cat = c; paintChips(); render() }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ui.px(6) })
        }
        paintChips()
        col.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(10); bottomMargin = ui.px(4) })
        render()
        col.addView(box)
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
        // Bei jedem Öffnen nach Updates schauen (höchstens alle 3 Minuten) – neue Version = Benachrichtigung
        if (intent?.getBooleanExtra("install_update", false) == true) {
            intent.removeExtra("install_update"); lastUpdateLook = System.currentTimeMillis(); Updater.check(this, quiet = false)
        } else if (System.currentTimeMillis() - lastUpdateLook > 3 * 60_000L) {
            lastUpdateLook = System.currentTimeMillis(); prefs.lastUpdateCheck = lastUpdateLook; Updater.check(this, quiet = true)
        }
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i) }

    private fun refresh() {
        if (current == "system") refreshSystem()
        dateText.text = SimpleDateFormat("EEEE, d. MMM", Locale.GERMANY).format(Date())
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val hi = when (h) { in 5..10 -> "Guten Morgen"; in 11..17 -> "Hallo"; in 18..22 -> "Guten Abend"; else -> "Gute Nacht" }
        greeting.text = "$hi,"
        greetName.text = prefs.userName.trim().ifBlank { "Sir" }
        refreshHud()
        refreshHistory()
        refreshChips()
        refreshLive()
        refreshWeather()
        refreshAgenda()
        refreshMessages()
        refreshExtras()
        JarvisWidget.updateAll(this)
    }

    /** Anzeigen am HUD-Kern: Weckwort, KI, Wetter, Akku. */
    private fun refreshHud() {
        val bat = (getSystemService(BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val w = runCatching { JSONObject(prefs.weatherCache) }.getOrNull()
        val ki = when { prefs.nvidiaKey.isNotBlank() -> "NVIDIA"; prefs.groqKey.isNotBlank() -> "GROQ"; prefs.hasAnyKey -> "BEREIT"; else -> "KEIN SCHLÜSSEL" }
        hud?.callouts = listOf(
            "Wake" to (if (WakeWordService.isRunning) "„Jarvis“" else "aus"),
            "Akku" to (if (bat in 0..100) "$bat %" else "?"),
            "KI-Kern" to ki,
            "Wetter" to (w?.let { "${it.optInt("t")}° " + it.optString("desc").take(10) } ?: "–"))
        hudStatus?.text = if (!prefs.hasAnyKey) "● KI-Schlüssel fehlt – antippen" else if (WakeWordService.isRunning) "● Bereit – sag „Jarvis“" else "● Bereit – Kopf antippen"
        hudStatus?.setTextColor(if (prefs.hasAnyKey) GREEN else AMBER)
        if (bat in 0..100) { batGauge?.value = bat; batText?.text = "$bat %" + (if ((getSystemService(BATTERY_SERVICE) as android.os.BatteryManager).isCharging) " ⚡" else "") }
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
        refreshHud()
        weatherBox.removeAllViews()
        val c = runCatching { JSONObject(prefs.weatherCache) }.getOrNull()
        if (c == null) {
            weatherBox.addView(ui.text(if (online()) "Lade Wetter …" else "Kein Wetter (offline).", 14f, MUTED))
            return
        }
        val rain = c.optInt("rain", -1)
        weatherBox.addView(ui.text("Wetter", 11.5f, MUTED))
        weatherBox.addView(ui.text("${c.optInt("t")}°", 19f, Ui.FG, true).apply { setPadding(0, ui.px(6), 0, 0) })
        weatherBox.addView(ui.text(c.optString("desc").replaceFirstChar { it.uppercase() } + (if (rain >= 30) " · ☂ $rain %" else ""), 11.5f, if (rain >= 50) AMBER else MUTED).apply { maxLines = 2 })
        val age = (System.currentTimeMillis() - prefs.weatherTime) / 60_000
        if (age > 180) weatherBox.addView(ui.text("vor ${if (age < 60 * 24) "${age / 60} Std." else "${age / 1440} Tg."}", 10.5f, AMBER))
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
        timeline?.items = ag.events.filter { !it.allDay }.map { TimelineView.Item(it.begin, it.title) } +
            ag.reminders.mapNotNull { r -> r.next?.let { TimelineView.Item(it, r.text, warn = true) } }
        nowText?.text = "JETZT " + TimeLogic.time(System.currentTimeMillis())
        if (::nextBox.isInitialized) {
            nextBox.removeAllViews()
            val nowMs = System.currentTimeMillis()
            val nx = ag.events.filter { !it.allDay && it.end > nowMs }.minByOrNull { it.begin }
            if (nx == null) { nextBox.addView(ui.text("Als Nächstes", 11.5f, MUTED)); nextBox.addView(ui.text("Frei", 19f, Ui.FG, true).apply { setPadding(0, ui.px(6), 0, 0) }); nextBox.addView(ui.text("Heute nichts mehr", 11.5f, MUTED)) }
            else {
                val mins = (nx.begin - nowMs) / 60_000
                nextBox.addView(ui.text("Als Nächstes", 11.5f, MUTED))
                nextBox.addView(ui.text(if (mins in 1..59) "in $mins min" else if (mins <= 0) "jetzt" else TimeLogic.time(nx.begin), 19f, Ui.FG, true).apply { maxLines = 1; setPadding(0, ui.px(6), 0, 0) })
                nextBox.addView(ui.text(nx.title, 11.5f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }
        }
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
            r.addView(ui.weighted(ui.text(e.title + (if (e.location.isNotBlank()) "  ·  ${e.location}" else ""), 15f, if (past) MUTED else Ui.FG).apply { maxLines = 2 }))
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
        msgBox.addView(ui.text(de.jarvis.app.tools.NotificationHub.summary(), 14f, Ui.FG, true))
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
                extrasBox.addView(ui.text("🎒 " + (if (late) "Morgen: " else "Heute: ") + (school ?: "kein Stundenplan gespeichert – sag z. B. „Mein Montag: 8 Uhr Mathe, 9 Uhr Deutsch“"), 14f, if (school != null) Ui.FG else MUTED))
                if (hw.isNotEmpty()) extrasBox.addView(ui.text("📚 Hausaufgaben: " + hw.joinToString(", ") { it.title }, 13f, AMBER).apply { setPadding(0, ui.px(6), 0, 0) })
                extrasBox.addView(ui.text(if (shop.isEmpty()) "🛒 Einkaufsliste leer" else "🛒 " + shop.joinToString(", ") + "  (antippen = abhaken)", 14f,
                    if (shop.isEmpty()) MUTED else Ui.FG).apply {
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
                    addView(ui.text(k, 12f, MUTED).apply { width = ui.px(110) }); addView(ui.weighted(ui.text(v, 13f, Ui.FG).apply { maxLines = 1 })) })
                line("Mikrofon", if (WakeWordService.isRunning) "hört auf „Jarvis“" else "Wake-Word aus")
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
                // Diagnose oben: Akku-Ring + Balken
                val diag = ui.row()
                diag.addView(ui.card().apply { gravity = Gravity.CENTER_HORIZONTAL
                    addView(GaugeView(this@DashboardActivity).apply { value = bat.coerceAtLeast(0); label = if (charging) "LÄDT %" else "AKKU %" },
                        LinearLayout.LayoutParams(ui.px(120), ui.px(120)))
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.px(8) })
                val bars = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                fun bar(title: String, value: String, frac: Float) = bars.addView(ui.card().apply {
                    setPadding(ui.px(12), ui.px(10), ui.px(12), ui.px(10))
                    addView(ui.row().apply {
                        addView(ui.weighted(ui.text(title, 9.5f, RED).apply { typeface = Typeface.MONOSPACE; letterSpacing = 0.18f }))
                        addView(ui.text(value, 9.5f, MUTED).apply { typeface = Typeface.MONOSPACE }) })
                    addView(android.widget.ProgressBar(this@DashboardActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100; progress = (frac * 100).toInt().coerceIn(0, 100)
                        progressTintList = android.content.res.ColorStateList.valueOf(RED)
                        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.LINE)
                    }, LinearLayout.LayoutParams(-1, ui.px(6)).apply { topMargin = ui.px(6) })
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(if (bars.childCount == 0) 10 else 6) })
                val total = try { android.os.StatFs(android.os.Environment.getDataDirectory().path).totalBytes / 1_000_000_000f } catch (_: Exception) { 0f }
                bar("SPEICHER", if (free >= 0) "$free GB frei" else "?", if (total > 0 && free >= 0) 1f - free / total else 0f)
                val mi = android.app.ActivityManager.MemoryInfo().also { getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(it) }
                bar("RAM", "%.1f / %.0f GB".format(Locale.GERMANY, (mi.totalMem - mi.availMem) / 1e9, mi.totalMem / 1e9), if (mi.totalMem > 0) 1f - mi.availMem.toFloat() / mi.totalMem else 0f)
                bar("NETZ", net, if (caps != null) 0.9f else 0f)
                diag.addView(bars, LinearLayout.LayoutParams(0, -2, 1.1f))
                systemBox.addView(diag)
                val grid = GridLayout(this).apply { columnCount = 2 }
                fun tile(icon: String, label: String, value: String, ok: Boolean? = null, action: (() -> Unit)? = null) {
                    val box = ui.card(false).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ui.px(12), ui.px(14), ui.px(10), ui.px(14))
                        action?.let { a -> setOnClickListener { a() } } }
                    box.addView(IconView(this, icon, if (ok == false) MUTED else if (ok == true) GREEN else RED), LinearLayout.LayoutParams(ui.px(30), ui.px(30)))
                    val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(10), 0, 0, 0) }
                    t.addView(ui.text(label, 12f, MUTED))
                    t.addView(ui.text(value, 15f, Ui.FG, true).apply { maxLines = 2 })
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
                tile("mic", "Mikrofon / Jarvis", if (WakeWordService.isRunning) "Hört zu" else "Aus", WakeWordService.isRunning) {
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

    private fun toggleBubble() {
        if (BubbleService.instance != null || prefs.bubble) { BubbleService.stop(this); toast("Schwebender Jarvis aus."); return }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            toast("Bitte „Über anderen Apps einblenden“ für Jarvis erlauben, dann nochmal tippen.")
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))); return
        }
        BubbleService.start(this); toast("Schwebender Jarvis an – die Kugel liegt am Bildschirmrand.")
    }

    private fun voice(q: String) = startActivity(Intent(this, JarvisActivity::class.java).putExtra("ask", q))

    private fun askJarvis(q: String, send: Boolean = false) = startActivity(Intent(this, ChatActivity::class.java).setAction(Intent.ACTION_SEND)
        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, q).putExtra("send", send))

    private fun refreshHistory() {
        if (!::historyBox.isInitialized) return
        historyBox.removeAllViews()
        val qs = Convo(this).recent(30).filter { it.first == "user" }.map { it.second.removePrefix("[Bild] ") }.distinct().takeLast(4).reversed()
        if (qs.isEmpty()) return
        historyBox.addView(ui.row().apply {
            setPadding(0, ui.px(20), 0, ui.px(2))
            addView(ui.weighted(ui.text("Verlauf", 16f, Ui.FG, true)))
            addView(ui.text("Alle", 13f, MUTED).apply { setOnClickListener { HubActivity.open(this@DashboardActivity, "memory") } })
        })
        for (q in qs) historyBox.addView(ui.card(false).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(ui.px(12), ui.px(10), ui.px(12), ui.px(10))
            addView(IconView(this@DashboardActivity, "chat", RED).apply {
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Ui.CARD2) }
                setPadding(ui.px(7), ui.px(7), ui.px(7), ui.px(7)) }, LinearLayout.LayoutParams(ui.px(34), ui.px(34)))
            addView(ui.text(q, 14f, Ui.FG).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(ui.px(12), 0, ui.px(8), 0) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.text("→", 16f, MUTED))
            setOnClickListener { askJarvis(q) }
        })
    }

    private fun input(hint: String, number: Boolean = false) = EditText(this).apply {
        this.hint = hint; setTextColor(Ui.FG); setHintTextColor(MUTED)
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
