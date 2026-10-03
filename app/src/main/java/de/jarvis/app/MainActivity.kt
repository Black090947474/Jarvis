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
    private lateinit var groqInput: EditText
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
        crashCard(col)
        col.addView(button("Chat öffnen (Text & Bilder)") {
            save(); startActivity(Intent(this, ChatActivity::class.java))
        })

        // --- Schritt 1: Schlüssel ---
        col.addView(section("1 · Schlüssel"))
        groqInput = input("Groq API-Key (gsk_…)", prefs.groqKey, secret = true)
        col.addView(groqInput)
        col.addView(link("Kostenlos holen: console.groq.com/keys", "https://console.groq.com/keys"))
        geminiInput = input("Gemini API-Key (optional)", prefs.geminiKey, secret = true)
        col.addView(geminiInput)
        col.addView(link("Optional, kostenlos: aistudio.google.com/apikey", "https://aistudio.google.com/apikey"))
        claudeInput = input("Claude API-Key (optional, als Ersatz)", prefs.anthropicKey, secret = true)
        col.addView(claudeInput)
        col.addView(link("Optional, kostet Guthaben: console.anthropic.com", "https://console.anthropic.com/settings/keys"))
        nameInput = input("Dein Name (optional)", prefs.userName)
        col.addView(nameInput)
        col.addView(button("Speichern") { save(); toast("Gespeichert") })

        // --- Schritt 2: Berechtigungen ---
        buildVoiceSection(col)

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
            "„Öffne TikTok“ · „Wie viel Akku hab ich?“ · „Merk dir …“ · " +
            "„Such auf YouTube nach Minecraft“ · „Mach ein Selfie“ · „Lies meine Nachrichten vor“ · " +
            "„Antworte Lisa, dass ich gleich komme“ · „Öffne Insta und like das erste Bild“ · „Mach einen Screenshot“", 14f, Color.rgb(200, 200, 210)))

        buildSecondPhoneSection(col)

        col.addView(section("Gedächtnis"))
        memoryList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(memoryList)

        col.addView(label(
            "Tipp: Nach einem Neustart des Handys einmal diese App öffnen – Android erlaubt " +
            "aus Datenschutzgründen nicht, dass das Mikrofon von selbst wieder angeht.", 13f, MUTED))

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(col) })
    }

    // ---------- Zweites Handy ----------

    private lateinit var pairStatus: TextView

    private fun buildSecondPhoneSection(col: LinearLayout) {
        col.addView(section("Zweites Handy"))
        pairStatus = label("", 14f, Color.rgb(200, 200, 210))
        col.addView(pairStatus)
        updatePairStatus()
        col.addView(button("Station suchen & koppeln") { pairStation() })
        col.addView(button("Dieses Handy als Station starten", filled = false) {
            startActivity(Intent(this, StationActivity::class.java))
        })
        col.addView(label(
            "So geht's: Auf dem zweiten Handy Jarvis installieren und „Dieses Handy als Station starten“ tippen. " +
            "Dann hier auf „Station suchen“ und den Code eingeben, der auf der Station steht. " +
            "Beide Handys müssen im selben WLAN sein. Danach z. B.: „Hey Jarvis, spiel auf dem zweiten Handy Musik.“",
            13f, MUTED))
    }

    private fun updatePairStatus() {
        pairStatus.text = if (prefs.remoteCode.isNotBlank() && prefs.remoteHost.isNotBlank())
            "✓ Gekoppelt mit ${prefs.remoteName.ifBlank { "Station" }} (${prefs.remoteHost})"
        else "Noch kein zweites Handy gekoppelt."
    }

    private fun pairStation() {
        toast("Suche Station im WLAN …")
        Thread {
            val f = StationLink.find(this, 6000)
            runOnUiThread {
                if (f != null) askCode(f.host, f.port, f.name)
                else askIp()
            }
        }.start()
    }

    /** Falls die automatische Suche nichts findet: IP von der Station abtippen. */
    private fun askIp() {
        val ip = EditText(this).apply { hint = "z. B. 192.168.178.34"; inputType = InputType.TYPE_CLASS_PHONE }
        android.app.AlertDialog.Builder(this)
            .setTitle("Keine Station gefunden")
            .setMessage("Ist auf dem zweiten Handy die Station offen und sind beide im selben WLAN? " +
                "Du kannst auch die Zahl hinter dem Code abtippen, die auf der Station steht:")
            .setView(ip)
            .setPositiveButton("Weiter") { _, _ ->
                val h = ip.text.toString().trim()
                if (h.isNotEmpty()) askCode(h, StationLink.PORT, "Station")
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun askCode(host: String, port: Int, name: String) {
        val code = EditText(this).apply { hint = "6-stelliger Code"; inputType = InputType.TYPE_CLASS_NUMBER }
        android.app.AlertDialog.Builder(this)
            .setTitle("Station gefunden: $name")
            .setMessage("Gib den Code ein, der auf der Station steht:")
            .setView(code)
            .setPositiveButton("Koppeln") { _, _ ->
                val c = code.text.toString().trim()
                Thread {
                    val r = try { StationLink.send(host, port, c, "ping", "{}") }
                            catch (e: Exception) { false to "Nicht erreichbar (${e.message})" }
                    runOnUiThread {
                        if (r.first) {
                            prefs.remoteHost = host; prefs.remotePort = port
                            prefs.remoteCode = c; prefs.remoteName = name
                            updatePairStatus()
                            toast("Gekoppelt! Sag z. B. „Mach auf dem zweiten Handy die Taschenlampe an“")
                        } else toast("Koppeln fehlgeschlagen: ${r.second}")
                    }
                }.start()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ---------- Fehlerbericht ----------

    private fun crashCard(col: LinearLayout) {
        val f = JarvisApp.crashFile(this)
        if (!f.exists()) return
        val text = try { f.readText() } catch (_: Exception) { return }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(12), px(14), px(12))
            background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(Color.rgb(60, 18, 22)) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16) }
        }
        box.addView(TextView(this).apply {
            this.text = "Jarvis ist zuletzt abgestürzt. Mach einen Screenshot davon und schick ihn Claude."
            textSize = 14f; setTextColor(Color.WHITE)
        })
        box.addView(TextView(this).apply {
            this.text = text.lines().take(18).joinToString("\n")
            textSize = 10f; setTextColor(Color.rgb(255, 200, 200)); typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) }
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Kopieren", filled = false) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Jarvis-Fehler", text))
            toast("Kopiert")
        }, LinearLayout.LayoutParams(0, px(46), 1f))
        row.addView(android.widget.Space(this), LinearLayout.LayoutParams(px(10), 1))
        row.addView(button("Ausblenden", filled = false) {
            f.delete(); box.visibility = android.view.View.GONE
        }, LinearLayout.LayoutParams(0, px(46), 1f))
        box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
        col.addView(box)
    }

    // ---------- Stimme ----------

    private var speaker: Speaker? = null
    private lateinit var designButton: Button

    private val designs = listOf(
        "PULS" to "Puls – Leuchtkern, Ringe & Schallwelle",
        "NEXUS" to "Nexus – HUD mit Ringen & Reticle",
        "GLUT" to "Glut – rote, atmende Kugel",
        "AURORA" to "Aurora – bunte Farbwolken",
        "LINIE" to "Linie – weißer Ring, minimal",
        "GLAS" to "Glas – Uhrzeit, blaue Kugel, Milchglas")

    private fun designLabel() = "Design: " + (designs.firstOrNull { it.first == prefs.design }?.second ?: designs[0].second)

    private fun chooseDesign() {
        val current = designs.indexOfFirst { it.first == prefs.design }.coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle("Design wählen")
            .setSingleChoiceItems(designs.map { it.second }.toTypedArray(), current) { d, which ->
                prefs.design = designs[which].first
                designButton.text = designLabel()
                d.dismiss()
                toast("Probier es aus: „Jetzt mit Jarvis reden“")
            }
            .show()
    }
    private lateinit var voiceButton: Button

    private fun buildVoiceSection(col: LinearLayout) {
        col.addView(section("Stimme"))
        speaker = Speaker(this, onReady = { updateVoiceButton() })
        voiceButton = button("Stimme: automatisch", filled = false) { chooseVoice() }
        col.addView(voiceButton)

        col.addView(slider("Tonhöhe (links = tiefer)", prefs.voicePitch, 0.5f, 1.5f) { prefs.voicePitch = it })
        col.addView(slider("Tempo", prefs.voiceRate, 0.6f, 1.6f) { prefs.voiceRate = it })

        col.addView(android.widget.Switch(this).apply {
            text = "KI-Hall (klingt mehr nach Jarvis)"
            textSize = 15f; setTextColor(Color.WHITE)
            isChecked = prefs.voiceEffect
            setOnCheckedChangeListener { _, on -> prefs.voiceEffect = on }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) }
        })
        col.addView(button("Probe hören", filled = false) { previewVoice() })

        col.addView(section("Design"))
        designButton = button(designLabel(), filled = false) { chooseDesign() }
        col.addView(designButton)
    }

    private fun voiceLabel(v: android.speech.tts.Voice, i: Int): String {
        val n = v.name.lowercase()
        val g = when {
            n.contains("female") -> " · weiblich"
            n.contains("male") -> " · männlich"
            else -> ""
        }
        return "Stimme ${i + 1}$g · ${if (v.isNetworkConnectionRequired) "online" else "offline"}\n${v.name}"
    }

    private fun updateVoiceButton() {
        val voices = speaker?.germanVoices().orEmpty()
        val i = voices.indexOfFirst { it.name == prefs.voiceName }
        voiceButton.text = if (i >= 0) "Stimme: ${voiceLabel(voices[i], i).substringBefore("\n")}" else "Stimme: automatisch (natürlichste)"
    }

    private fun chooseVoice() {
        val sp = speaker ?: return
        val voices = sp.germanVoices()
        if (voices.isEmpty()) {
            toast("Keine deutschen Stimmen gefunden. Installier die Google-Sprachausgabe.")
            return
        }
        val labels = (listOf("Automatisch (natürlichste)") + voices.mapIndexed { i, v -> voiceLabel(v, i) }).toTypedArray()
        val current = voices.indexOfFirst { it.name == prefs.voiceName } + 1
        android.app.AlertDialog.Builder(this)
            .setTitle("Stimme wählen – antippen zum Anhören")
            .setSingleChoiceItems(labels, current) { _, which ->
                prefs.voiceName = if (which == 0) "" else voices[which - 1].name
                sp.preview(if (which == 0) null else voices[which - 1], "Guten Tag. Ich bin Jarvis. Alle Systeme sind bereit.")
            }
            .setPositiveButton("Fertig") { d, _ -> d.dismiss(); sp.stop(); sp.applySettings(); updateVoiceButton() }
            .show()
    }

    private fun previewVoice() {
        val sp = speaker ?: return
        sp.applySettings()
        sp.speak("Guten Tag. Ich bin Jarvis. Alle Systeme sind bereit.", "preview")
    }

    private fun slider(title: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit): LinearLayout {
        val label = TextView(this).apply { textSize = 14f; setTextColor(MUTED) }
        fun show(v: Float) { label.text = "$title: ${"%.2f".format(v)}" }
        show(value)
        val bar = android.widget.SeekBar(this).apply {
            this.max = 100
            progress = (((value - min) / (max - min)) * 100).toInt()
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * p / 100f
                    show(v); if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) }
            addView(label); addView(bar)
        }
    }

    override fun onDestroy() {
        speaker?.shutdown()
        super.onDestroy()
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
        addCheck("Standort", hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            askPerm(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        val photoPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                        else Manifest.permission.READ_EXTERNAL_STORAGE
        addCheck("Fotos (teilen, zählen)", hasPerm(photoPerm)) { askPerm(photoPerm) }
        addCheck("Nachrichten lesen & antworten", secureListHas("enabled_notification_listeners")) {
            restrictedHint()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        addCheck("Volle Handy-Steuerung (Bedienungshilfe)", secureListHas(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)) {
            restrictedHint()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
        if (prefs.groqKey.isBlank() && prefs.geminiKey.isBlank() && prefs.anthropicKey.isBlank()) { toast("Bitte erst den Groq-Schlüssel eintragen"); return }
        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) { askRuntimePerms(); return }
        WakeWordService.start(this)
        toggle.postDelayed({ refresh() }, 500)
        toast("Sag einfach „Hey Jarvis“")
    }

    private fun save() {
        prefs.groqKey = groqInput.text.toString()
        prefs.geminiKey = geminiInput.text.toString()
        prefs.anthropicKey = claudeInput.text.toString()
        prefs.userName = nameInput.text.toString()
    }

    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Prüft, ob Jarvis in einer System-Liste (Benachrichtigungszugriff, Bedienungshilfen) eingetragen ist. */
    private fun secureListHas(key: String): Boolean =
        (Settings.Secure.getString(contentResolver, key) ?: "").contains(packageName)

    /** Android sperrt diese Rechte bei Apps, die nicht aus dem Play Store kommen – so entsperrt man sie. */
    private fun restrictedHint() {
        Toast.makeText(this,
            "Ausgegraut oder „Eingeschränkte Einstellung“? Dann: Einstellungen → Apps → Jarvis → ⋮ (oben rechts) → " +
            "„Eingeschränkte Einstellungen zulassen“. Danach nochmal hier antippen.", Toast.LENGTH_LONG).show()
    }

    private fun askPerm(vararg perms: String) {
        val p = perms.first()
        if (shouldShowRequestPermissionRationale(p) || !asked.contains(p)) {
            asked += p
            requestPermissions(perms.toList().toTypedArray(), 2)
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
