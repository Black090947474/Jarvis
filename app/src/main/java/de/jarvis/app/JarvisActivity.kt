package de.jarvis.app

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.concurrent.thread

/** Der Gesprächsbildschirm: zuhören → Claude fragen → vorlesen → wieder zuhören. */
class JarvisActivity : Activity() {

    private lateinit var core: CoreView
    private lateinit var youText: TextView
    private lateinit var jarvisText: TextView
    private lateinit var hint: TextView

    private var tts: Speaker? = null
    private var recognizer: SpeechRecognizer? = null
    private var claude: BrainRouter? = null
    private var tools: PhoneTools? = null
    private val main = Handler(Looper.getMainLooper())

    private var listening = false
    private var busy = false          // wartet gerade auf Claude
    private var silentTries = 0       // wie oft hintereinander nichts gesagt wurde

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isOpen = true
        showOverLockScreen()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(WakeWordService.NOTIF_WAKE)
        WakeWordService.instance?.pause() // Mikrofon für die Spracherkennung freigeben
        buildUi()

        val prefs = Prefs(this)
        if (prefs.groqKey.isBlank() && prefs.geminiKey.isBlank() && prefs.anthropicKey.isBlank()) {
            jarvisText.text = "Mir fehlt noch ein Schlüssel. Trag den Groq-Schlüssel in der Jarvis-App ein."
        } else {
            val t = PhoneTools(this).also { tools = it }
            val mem = Memory(this)
            // Kostenlose zuerst (Groq, Gemini), Claude springt ein, falls beide nicht können
            val groq = prefs.groqKey.takeIf { it.isNotBlank() }
                ?.let { GroqClient(it, GroqClient.DEFAULT_MODEL, prefs.userName, t, mem) }
            val gemini = prefs.geminiKey.takeIf { it.isNotBlank() }
                ?.let { GeminiClient(it, prefs.geminiModel, prefs.userName, t, mem) }
            val claudeBrain = prefs.anthropicKey.takeIf { it.isNotBlank() }
                ?.let { ClaudeClient(it, prefs.model, prefs.userName, t, mem) }
            claude = BrainRouter(listOfNotNull(groq, gemini, claudeBrain))
        }
        tts = Speaker(this,
            onStart = { core.mode = CoreView.Mode.SPEAKING },
            onDone = { id -> afterSpeaking(id) },
            onReady = { ok -> onTtsReady(ok) })
        // Sofort zuhören: "Jarvis, stell den Wecker auf 7" funktioniert in einem Satz.
        // Kurze Pause, damit der Wake-Word-Dienst das Mikrofon freigeben kann.
        if (claude != null) main.postDelayed({ beep(); listen() }, 200)
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private class Theme(val bg: Int, val bg2: Int, val fg: Int, val muted: Int, val accent: Int, val center: Boolean)

    private fun theme(style: CoreView.Style) = when (style) {
        CoreView.Style.GLUT -> Theme(Color.rgb(8, 6, 7), Color.rgb(8, 6, 7), Color.rgb(244, 238, 234),
            Color.rgb(156, 138, 132), Color.rgb(232, 69, 60), true)
        CoreView.Style.AURORA -> Theme(Color.rgb(7, 10, 20), Color.rgb(7, 10, 20), Color.rgb(234, 240, 255),
            Color.rgb(131, 144, 176), Color.rgb(61, 224, 208), false)
        CoreView.Style.LINIE -> Theme(Color.BLACK, Color.BLACK, Color.WHITE,
            Color.rgb(122, 122, 122), Color.rgb(200, 200, 200), true)
        CoreView.Style.GLAS -> Theme(Color.rgb(30, 36, 48), Color.rgb(11, 13, 18), Color.rgb(241, 243, 247),
            Color.rgb(140, 147, 163), Color.rgb(143, 176, 255), false)
    }

    private lateinit var status: TextView
    private var clock: TextView? = null

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val style = CoreView.styleFrom(Prefs(this).design)
        val th = theme(style)
        val light = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        val thin = android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL)
        val align = if (th.center) Gravity.CENTER_HORIZONTAL else Gravity.START

        window.statusBarColor = th.bg
        window.navigationBarColor = th.bg2

        core = CoreView(this, style)
        status = TextView(this).apply {
            textSize = 12f; letterSpacing = 0.22f; setTextColor(th.accent); gravity = align
        }
        core.onModeChanged = { m -> status.text = statusText(m) }
        status.text = statusText(CoreView.Mode.IDLE)
        youText = TextView(this).apply { setTextColor(th.muted); textSize = 15f; gravity = align }
        jarvisText = TextView(this).apply {
            setTextColor(th.fg); textSize = if (style == CoreView.Style.GLAS) 20f else 23f
            typeface = light; gravity = align; setLineSpacing(0f, 1.25f)
        }
        hint = TextView(this).apply {
            setTextColor(Color.argb(150, Color.red(th.muted), Color.green(th.muted), Color.blue(th.muted)))
            textSize = 12f; gravity = align
            text = "Tippen = nochmal zuhören · „Danke“ = beenden"
        }
        fun brand(spacing: Float, size: Float) = TextView(this).apply {
            text = "JARVIS"; textSize = size; letterSpacing = spacing; typeface = thin
            setTextColor(th.muted); gravity = Gravity.CENTER_HORIZONTAL
        }
        fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(top) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(30), px(52), px(30), px(36))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(th.bg, th.bg2))
            setOnClickListener { onTap() }
        }

        // Kopfzeile
        when (style) {
            CoreView.Style.GLUT -> root.addView(brand(0.42f, 13f), lp())
            CoreView.Style.AURORA -> {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(TextView(this).apply {
                    text = "JARVIS"; textSize = 15f; letterSpacing = 0.3f; setTextColor(th.fg)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }, LinearLayout.LayoutParams(0, -2, 1f))
                clock = TextView(this).apply { textSize = 13f; setTextColor(th.muted) }
                row.addView(clock)
                root.addView(row, lp())
            }
            CoreView.Style.GLAS -> {
                clock = TextView(this).apply {
                    textSize = 46f; typeface = light; setTextColor(th.fg); gravity = Gravity.CENTER_HORIZONTAL
                }
                root.addView(clock, lp())
                root.addView(TextView(this).apply {
                    text = java.text.SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY).format(java.util.Date())
                    textSize = 13f; setTextColor(th.muted); gravity = Gravity.CENTER_HORIZONTAL
                }, lp(2))
            }
            CoreView.Style.LINIE -> {}
        }

        // Kern
        root.addView(core, LinearLayout.LayoutParams(-1, 0, 1f))
        if (style == CoreView.Style.LINIE) root.addView(brand(0.6f, 15f), lp(0))

        // Text-Bereich
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (style == CoreView.Style.GLAS) {
                setPadding(px(22), px(20), px(22), px(18))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 28 * dp
                    setColor(Color.argb(20, 255, 255, 255))
                    setStroke(px(1), Color.argb(32, 255, 255, 255))
                }
            }
        }
        texts.addView(status, lp())
        texts.addView(youText, lp(12))
        texts.addView(jarvisText, lp(10))
        texts.addView(hint, lp(22))
        root.addView(texts, lp(16))

        setContentView(root)
        tickClock()
    }

    private fun tickClock() {
        val c = clock ?: return
        c.text = java.text.SimpleDateFormat("HH:mm", Locale.GERMANY).format(java.util.Date())
        main.postDelayed({ if (!isFinishing) tickClock() }, 15_000)
    }

    private fun statusText(m: CoreView.Mode) = when (m) {
        CoreView.Mode.IDLE -> "BEREIT"
        CoreView.Mode.LISTENING -> "ICH HÖRE ZU"
        CoreView.Mode.THINKING -> "DENKE NACH"
        CoreView.Mode.SPEAKING -> "SPRICHT"
    }

    // ---------- Sprachausgabe ----------

    private fun onTtsReady(ok: Boolean) {
        if (!ok) {
            jarvisText.text = "Die Sprachausgabe funktioniert nicht. Ist eine Text-zu-Sprache-App installiert?"
            listen()
            return
        }
        if (claude == null) speak("Mir fehlt noch der API-Schlüssel.", ID_BYE)
    }

    /** Kurzer Ton als Zeichen "Ich höre" – statt einer Begrüßung, damit man direkt weiterreden kann. */
    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 55)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            main.postDelayed({ tg.release() }, 300)
        } catch (_: Exception) {}
    }

    private fun speak(text: String, id: String) {
        jarvisText.text = text
        if (tts?.ready != true) { afterSpeaking(id); return }
        tts?.speak(text, id)
    }

    private fun afterSpeaking(id: String?) {
        if (isFinishing) return
        when (id) {
            ID_BYE -> finishWhenUnlocked()
            else -> listen()
        }
    }

    /**
     * Beenden – aber erst, wenn das Handy entsperrt ist. Sonst würde Android die
     * Bitte zum Entsperren abbrechen und die gewünschte App (z. B. Maps) nicht öffnen.
     */
    private fun finishWhenUnlocked(waited: Int = 0) {
        if (isFinishing) return
        val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (tools?.leftApp == true && km.isKeyguardLocked && waited < 30_000) {
            core.mode = CoreView.Mode.IDLE
            hint.text = "Entsperr dein Handy, um fortzufahren"
            main.postDelayed({ finishWhenUnlocked(waited + 500) }, 500)
        } else {
            finish()
        }
    }

    // ---------- Spracherkennung ----------

    private fun listen() {
        if (isFinishing || listening || busy) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            jarvisText.text = "Keine Spracherkennung gefunden. Installier bitte die Google-App."
            core.mode = CoreView.Mode.IDLE
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        listening = true
        core.mode = CoreView.Mode.LISTENING
        youText.text = "…"
        recognizer?.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { core.level = (rmsdB + 2f) / 12f }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { core.level = 0f }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partial: Bundle?) {
            partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let { youText.text = "„$it“" }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            if (text.isNullOrEmpty()) onNothingHeard() else onHeard(text)
        }

        override fun onError(error: Int) {
            listening = false
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onNothingHeard()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT ->
                    main.postDelayed({ listen() }, 400)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    core.mode = CoreView.Mode.IDLE
                    jarvisText.text = "Ich darf das Mikrofon nicht benutzen. Bitte erlaub es in der Jarvis-App."
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    speak("Ich erreiche die Spracherkennung nicht. Prüf bitte dein Internet.", ID_BYE)
                else -> onNothingHeard()
            }
        }
    }

    private fun onNothingHeard() {
        silentTries++
        youText.text = ""
        // Nach einer Pause ohne Antwort verabschiedet sich Jarvis leise
        if (silentTries >= 3) { core.mode = CoreView.Mode.IDLE; finish() }
        else listen()
    }

    private fun onHeard(text: String) {
        silentTries = 0
        youText.text = "„$text“"
        // Kurze Abschiedsfloskeln ("Danke", "Tschüss Jarvis", "Stopp") beenden das Gespräch
        val clean = text.lowercase(Locale.GERMANY).replace(Regex("[^a-zäöüß' ]"), "").trim()
        if (clean.split(" ").size <= 3 && BYE_WORDS.any { clean == it || clean.startsWith("$it ") }) {
            speak(listOf("Bis später.", "Gern geschehen.", "Jederzeit.").random(), ID_BYE)
            return
        }
        val client = claude ?: return
        busy = true
        core.mode = CoreView.Mode.THINKING
        jarvisText.text = "…"
        tools?.leftApp = false
        tools?.silentExit = false
        thread {
            val reply = client.ask(text) { step -> main.post { jarvisText.text = step } }
            main.post {
                busy = false
                if (isFinishing) return@post
                if (tools?.silentExit == true && tools?.leftApp == true) {
                    jarvisText.text = reply
                    finishWhenUnlocked()
                    return@post
                }
                // Wurde eine andere App geöffnet (Anruf, Maps, Spotify …), verabschiedet sich Jarvis danach
                speak(reply, if (tools?.leftApp == true) ID_BYE else ID_LISTEN_AFTER)
            }
        }
    }

    private fun onTap() {
        // Tippen unterbricht Jarvis und hört wieder zu
        if (busy) return
        tts?.stop()
        recognizer?.cancel()
        listening = false
        silentTries = 0
        listen()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Erneut "Jarvis" gerufen, während der Bildschirm offen ist
        WakeWordService.instance?.pause()
        onTap()
    }

    override fun onDestroy() {
        isOpen = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        // Etwas warten, bis das Mikrofon wirklich frei ist, dann wieder auf "Jarvis" hören
        Handler(Looper.getMainLooper()).postDelayed({ WakeWordService.instance?.resume() }, 600)
        super.onDestroy()
    }

    companion object {
        private const val ID_LISTEN_AFTER = "listen"
        private const val ID_BYE = "bye"
        private val BYE_WORDS = listOf("danke", "tschüss", "stopp", "stop", "ende", "das wars", "das war's",
            "passt", "nichts", "abbrechen", "bis später")

        @Volatile var isOpen = false
            private set
    }
}
