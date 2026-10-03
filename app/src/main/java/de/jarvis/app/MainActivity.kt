package de.jarvis.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Einrichtungsbildschirm: Schlüssel eintragen, Berechtigungen erteilen, Jarvis ein-/ausschalten. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var geminiInput: EditText
    private lateinit var claudeInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var toggle: Button
    private lateinit var checklist: LinearLayout
    private lateinit var memoryList: LinearLayout

    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(28), px(20), px(40))
        }
        col.addView(TextView(this).apply {
            text = "JARVIS"; textSize = 34f; setTextColor(RED)
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL); letterSpacing = 0.15f
        })
        col.addView(label("Sag „Hey Jarvis“ – auch bei gesperrtem Handy.", 15f, MUTED))

        // --- Schritt 1: Schlüssel ---
        col.addView(section("1 · Schlüssel"))
        geminiInput = input("Gemini API-Key (AIza…)", prefs.geminiKey, secret = true)
        col.addView(geminiInput)
        col.addView(link("Kostenlos holen: aistudio.google.com/apikey", "https://aistudio.google.com/apikey"))
        claudeInput = input("Claude API-Key (optional, als Ersatz)", prefs.anthropicKey, secret = true)
        col.addView(claudeInput)
        col.addView(link("Optional, kostet Guthaben: console.anthropic.com", "https://console.anthropic.com/settings/keys"))
        nameInput = input("Dein Name (optional)", prefs.userName)
        col.addView(nameInput)
        col.addView(button("Speichern") { save(); toast("Gespeichert") })

        // --- Schritt 2: Berechtigungen ---
        col.addView(section("2 · Berechtigungen"))
        checklist = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(checklist)

        // --- Schritt 3: Los ---
        col.addView(section("3 · Los geht's"))
        toggle = button("") { toggleService() }
        col.addView(toggle)
        col.addView(button("Jetzt mit Jarvis reden", filled = false) {
            save()
            startActivity(Intent(this, JarvisActivity::class.java))
        })
        col.addView(section("Das kann Jarvis"))
        col.addView(label(
            "„Weck mich morgen um halb sieben“ · „Timer 10 Minuten“ · „Taschenlampe an“ · " +
            "„Ruf Mama an“ · „Schreib Lisa auf WhatsApp, dass ich später komme“ · „Navigier mich nach Hause“ · " +
            "„Spiel Drake auf Spotify“ · „Nächstes Lied“ · „Lautstärke auf 30 Prozent“ · „Wie wird das Wetter?“ · " +
            "„Öffne TikTok“ · „Wie viel Akku hab ich?“ · „Merk dir …“", 14f, Color.rgb(200, 200, 210)))

        col.addView(section("Gedächtnis"))
        memoryList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(memoryList)

        col.addView(label(
            "Tipp: Nach einem Neustart des Handys einmal diese App öffnen – Android erlaubt " +
            "aus Datenschutzgründen nicht, dass das Mikrofon von selbst wieder angeht.", 13f, MUTED))

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        checklist.removeAllViews()
        addCheck("Mikrofon", hasPerm(Manifest.permission.RECORD_AUDIO)) { askRuntimePerms() }
        if (Build.VERSION.SDK_INT >= 33) {
            addCheck("Benachrichtigungen", hasPerm(Manifest.permission.POST_NOTIFICATIONS)) { askRuntimePerms() }
        }
        addCheck("Über anderen Apps einblenden", Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            addCheck("Vollbild-Benachrichtigungen", nm.canUseFullScreenIntent()) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:$packageName")))
            }
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        addCheck("Akku-Optimierung aus", pm.isIgnoringBatteryOptimizations(packageName)) {
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }
        addCheck("Kontakte (für Anrufe & Nachrichten)", hasPerm(Manifest.permission.READ_CONTACTS)) {
            askPerm(Manifest.permission.READ_CONTACTS)
        }
        addCheck("Direkt anrufen", hasPerm(Manifest.permission.CALL_PHONE)) {
            askPerm(Manifest.permission.CALL_PHONE)
        }
        refreshMemory()

        val running = WakeWordService.isRunning
        toggle.text = if (running) "Jarvis hört zu – ausschalten" else "Jarvis einschalten"
        (toggle.background as GradientDrawable).setColor(if (running) Color.rgb(40, 40, 48) else RED)
    }

    private fun toggleService() {
        save()
        if (WakeWordService.isRunning) {
            WakeWordService.stop(this)
            toggle.postDelayed({ refresh() }, 300)
            return
        }
        if (prefs.geminiKey.isBlank() && prefs.anthropicKey.isBlank()) { toast("Bitte erst den Gemini-Schlüssel eintragen"); return }
        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) { askRuntimePerms(); return }
        WakeWordService.start(this)
        toggle.postDelayed({ refresh() }, 500)
        toast("Sag einfach „Hey Jarvis“")
    }

    private fun save() {
        prefs.geminiKey = geminiInput.text.toString()
        prefs.anthropicKey = claudeInput.text.toString()
        prefs.userName = nameInput.text.toString()
    }

    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun askPerm(p: String) {
        if (shouldShowRequestPermissionRationale(p) || !asked.contains(p)) {
            asked += p
            requestPermissions(arrayOf(p), 2)
        } else {
            // Schon einmal abgelehnt → App-Einstellungen öffnen
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
    private val asked = mutableSetOf<String>()

    private fun refreshMemory() {
        memoryList.removeAllViews()
        val facts = Memory(this).all()
        if (facts.isEmpty()) {
            memoryList.addView(label("Noch leer. Sag z. B.: „Jarvis, merk dir, dass ich um 8 Uhr Schule habe.“", 13f, MUTED))
            return
        }
        facts.forEachIndexed { i, f ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(14), px(10), px(8), px(10))
                background = round(CARD)
            }
            row.addView(TextView(this).apply { text = f; textSize = 14f; setTextColor(Color.WHITE) },
                LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "Löschen"; textSize = 13f; setTextColor(RED); setPadding(px(10), px(6), px(6), px(6))
                setOnClickListener { Memory(this@MainActivity).removeAt(i); refreshMemory() }
            })
            memoryList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
        }
    }

    private fun askRuntimePerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refresh()
        if (code == 1 && !hasPerm(Manifest.permission.RECORD_AUDIO) && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Dauerhaft abgelehnt → App-Einstellungen öffnen
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    // ---------- kleine UI-Helfer ----------

    private fun addCheck(name: String, ok: Boolean, fix: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14), px(12), px(14), px(12))
            background = round(CARD)
            if (!ok) setOnClickListener { fix() }
        }
        row.addView(TextView(this).apply {
            text = if (ok) "✓" else "○"; textSize = 18f
            setTextColor(if (ok) Color.rgb(60, 210, 120) else MUTED)
            width = px(30)
        })
        row.addView(TextView(this).apply { text = name; textSize = 15f; setTextColor(Color.WHITE) },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (!ok) row.addView(TextView(this).apply { text = "Erlauben ›"; textSize = 14f; setTextColor(RED) })
        checklist.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
    }

    private fun section(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(MUTED); letterSpacing = 0.1f
        typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(28) }
    }

    private fun label(t: String, size: Float, color: Int) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) }
    }

    private fun link(t: String, url: String) = label(t, 13f, Color.rgb(120, 170, 255)).apply {
        setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun input(hint: String, value: String, secret: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(value); textSize = 15f
        setTextColor(Color.WHITE); setHintTextColor(MUTED)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or
            (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        background = round(CARD)
        setPadding(px(14), px(12), px(14), px(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) }
    }

    private fun button(t: String, filled: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE)
        background = round(if (filled) RED else CARD)
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, px(52)).apply { topMargin = px(12) }
    }

    private fun round(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 14 * dp }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    companion object {
        private val BG = Color.rgb(8, 8, 12)
        private val CARD = Color.rgb(24, 24, 30)
        private val RED = Color.rgb(255, 45, 60)
        private val MUTED = Color.rgb(140, 140, 150)
    }
}
