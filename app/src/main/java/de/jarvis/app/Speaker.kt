package de.jarvis.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.PresetReverb
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale

/**
 * Jarvis' Stimme: wählbare Stimme, Tonhöhe und Tempo, optional mit "KI-Hall"
 * (der Text wird erst in eine Datei gesprochen und dann mit Raumhall abgespielt).
 * Alle Rückrufe kommen auf dem Hauptthread.
 */
class Speaker(
    context: Context,
    private val onStart: () -> Unit = {},
    private val onDone: (String?) -> Unit = {},
    private val onReady: (Boolean) -> Unit = {},
) : TextToSpeech.OnInitListener {

    private val ctx = context.applicationContext
    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(ctx, this)
    private var player: MediaPlayer? = null
    private var reverb: PresetReverb? = null
    private val synthIds = mutableSetOf<String>()
    private var counter = 0

    var ready = false
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { main.post { onReady(false) }; return }
        tts.language = Locale.GERMANY
        applySettings()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                if (id != null && id !in synthIds) main.post { onStart() }
            }
            override fun onDone(id: String?) {
                main.post {
                    if (id != null && synthIds.remove(id)) playFile(id) else onDone(id)
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                main.post {
                    // Wenn die Datei-Variante scheitert, normal (ohne Hall) sprechen
                    if (id != null && synthIds.remove(id)) speakDirect(pendingText[id] ?: "", id) else onDone(id)
                }
            }
        })
        ready = true
        main.post { onReady(true) }
    }

    /** Stimme, Tonhöhe und Tempo aus den Einstellungen übernehmen. */
    fun applySettings() {
        tts.setPitch(prefs.voicePitch)
        tts.setSpeechRate(prefs.voiceRate)
        val voices = germanVoices()
        val chosen = voices.firstOrNull { it.name == prefs.voiceName } ?: pickDefault(voices)
        if (chosen != null) tts.voice = chosen
    }

    /** Alle deutschen Stimmen, die offline oder online verfügbar sind. */
    fun germanVoices(): List<Voice> = try {
        tts.voices.orEmpty()
            .filter { it.locale.language == "de" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
    } catch (_: Exception) { emptyList() }

    /**
     * Wählt die natürlichste Stimme: Online-Stimmen (neuronale Google-Stimmen) klingen deutlich
     * menschlicher als die Offline-Stimmen; männlich bevorzugt, falls das Handy das verrät.
     */
    private fun pickDefault(voices: List<Voice>): Voice? {
        fun isMale(v: Voice) = v.name.lowercase().let { (it.contains("male") && !it.contains("female")) || it.contains("de-de-x-deg") }
        return voices.sortedWith(compareByDescending<Voice> { isMale(it) }
            .thenByDescending { it.quality }
            .thenByDescending { it.isNetworkConnectionRequired }).firstOrNull()
    }

    fun preview(voice: Voice?, text: String) {
        if (!ready) return
        if (voice != null) tts.voice = voice
        tts.setPitch(prefs.voicePitch)
        tts.setSpeechRate(prefs.voiceRate)
        speak(text, "preview")
    }

    private val pendingText = HashMap<String, String>()

    fun speak(text: String, id: String) {
        stop()
        if (!ready) { main.post { onDone(id) }; return }
        if (!prefs.voiceEffect) { speakDirect(text, id); return }
        val sid = "$id#${++counter}"
        synthIds += sid
        pendingText[sid] = text
        val file = File(ctx.cacheDir, "jarvis_voice.wav")
        val r = tts.synthesizeToFile(text, null, file, sid)
        if (r != TextToSpeech.SUCCESS) { synthIds -= sid; speakDirect(text, id) }
    }

    private fun speakDirect(text: String, id: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id.substringBefore('#'))
    }

    private fun playFile(sid: String) {
        val id = sid.substringBefore('#')
        val text = pendingText.remove(sid).orEmpty()
        val file = File(ctx.cacheDir, "jarvis_voice.wav")
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            addReverb(mp)
            mp.setOnCompletionListener { releasePlayer(); onDone(id) }
            mp.setOnErrorListener { _, _, _ -> releasePlayer(); onDone(id); true }
            mp.start()
            onStart()
        } catch (_: Exception) {
            releasePlayer()
            speakDirect(text, id)
        }
    }

    /** Leichter Raumhall – klingt nach "KI im Anzug". Geräte ohne Hall-Effekt spielen einfach trocken ab. */
    private fun addReverb(mp: MediaPlayer) {
        try {
            val rv = reverb ?: PresetReverb(1, 0).also { reverb = it }
            rv.preset = PresetReverb.PRESET_MEDIUMROOM
            rv.enabled = true
            mp.attachAuxEffect(rv.id)
            mp.setAuxEffectSendLevel(0.55f)
        } catch (_: Exception) { }
    }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    fun stop() {
        try { tts.stop() } catch (_: Exception) {}
        synthIds.clear()
        try { player?.stop() } catch (_: Exception) {}
        releasePlayer()
    }

    fun shutdown() {
        stop()
        try { reverb?.release() } catch (_: Exception) {}
        reverb = null
        tts.shutdown()
    }
}
