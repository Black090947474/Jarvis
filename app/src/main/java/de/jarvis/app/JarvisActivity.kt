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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.concurrent.thread

/** Der Gesprächsbildschirm: zuhören → Claude fragen → vorlesen → wieder zuhören. */
class JarvisActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var core: CoreView
    private lateinit var youText: TextView
    private lateinit var jarvisText: TextView
    private lateinit var hint: TextView

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var claude: ClaudeClient? = null
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
        if (prefs.anthropicKey.isBlank()) {
            jarvisText.text = "Mir fehlt noch der Claude-API-Schlüssel. Trag ihn in der Jarvis-App ein."
        } else {
            tools = PhoneTools(this)
            claude = ClaudeClient(prefs.anthropicKey, prefs.model, prefs.userName, tools!!, Memory(this))
        }
        tts = TextToSpeech(this, this)
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
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        core = CoreView(this)
        youText = TextView(this).apply {
            setTextColor(Color.rgb(170, 170, 180)); textSize = 16f; gravity = Gravity.CENTER
        }
        jarvisText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 19f; gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }
        hint = TextView(this).apply {
            setTextColor(Color.rgb(110, 110, 120)); textSize = 13f; gravity = Gravity.CENTER
            text = "Tippen = nochmal zuhören · Zurück = beenden"
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), (32 * dp).toInt())
            addView(youText)
            addView(jarvisText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * dp).toInt() })
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (24 * dp).toInt() })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(5, 5, 9))
            addView(core, FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.55f).toInt(),
                Gravity.TOP))
            addView(texts, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            setOnClickListener { onTap() }
        }
        setContentView(root)
    }

    // ---------- Sprachausgabe ----------

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            jarvisText.text = "Die Sprachausgabe funktioniert nicht. Ist eine Text-zu-Sprache-App installiert?"
            listen()
            return
        }
        tts?.language = Locale.GERMANY
        tts?.setSpeechRate(1.05f)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { main.post { core.mode = CoreView.Mode.SPEAKING } }
            override fun onDone(id: String?) { main.post { afterSpeaking(id) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { afterSpeaking(id) } }
        })
        ttsReady = true
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
        if (!ttsReady) { afterSpeaking(id); return }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
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
        tts?.stop()
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
