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
import de.jarvis.app.logic.VoiceFx
import java.io.File
import kotlin.concurrent.thread
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
    private val tts = TextToSpeech(ctx, this, prefs.ttsEngine.ifBlank { null })

    /** Installierte Sprach-Engines (Google, Samsung, SherpaTTS …). */
    fun engines(): List<TextToSpeech.EngineInfo> = try { tts.engines.orEmpty() } catch (_: Exception) { emptyList() }
    private var player: MediaPlayer? = null
    private var reverb: PresetReverb? = null
    private val synthIds: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
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
                if (id != null && synthIds.contains(id)) {
                    // Satz fertig gesprochen (als Datei) → Jarvis-Klang im Hintergrund drüberlegen, dann in die Abspiel-Schlange
                    thread {
                        val f = fxChunk(id)
                        main.post { if (synthIds.remove(id)) chunkReady(id, f) }
                    }
                } else main.post { if (langPending) { langPending = false; try { tts.language = Locale.GERMANY; applySettings() } catch (_: Exception) {} }; onDone(id) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                main.post {
                    // Wenn die Datei-Variante scheitert, normal (ohne Hall) sprechen
                    if (id != null && synthIds.remove(id)) chunkReady(id, null) else onDone(id)
                }
            }
        })
        ready = true
        main.post { onReady(true) }
    }

    /** Stimme, Tonhöhe und Tempo aus den Einstellungen übernehmen. */
    fun applySettings() {
        tts.setPitch(prefs.voicePitch * preset().pitch)
        tts.setSpeechRate(prefs.voiceRate)
        val voices = germanVoices()
        val chosen = voices.firstOrNull { it.name == prefs.voiceName } ?: pickDefault(voices)
        if (chosen != null) tts.voice = chosen
    }

    /** Alle deutschen Stimmen, die offline oder online verfügbar sind. */
    fun germanVoices(): List<Voice> = try {
        tts.voices.orEmpty()
            .filter { (it.locale.language in setOf("de", "deu", "ger") || it.locale.isO3Language == "deu") &&
                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .ifEmpty { tts.voices.orEmpty().filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) } }
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
        tts.setPitch(prefs.voicePitch * preset().pitch)
        tts.setSpeechRate(prefs.voiceRate)
        speak(text, "preview")
    }

    private val pendingText = HashMap<String, String>()

    // ---------- Satzweise sprechen: der erste Satz startet, während die nächsten noch berechnet werden ----------
    private var gen = 0
    private var chunkTotal = 0
    private var nextPlay = 0
    private var playing = false
    private var curId = ""
    private val readyFiles = HashMap<Int, File?>()
    private var startedSent = false

    fun speak(text: String, id: String) {
        stop()
        if (!ready) { main.post { onDone(id) }; return }
        tts.setPitch(prefs.voicePitch * preset().pitch)
        if (preset() == VoiceFx.Preset.AUS) { speakDirect(text, id); return }
        val chunks = de.jarvis.app.logic.VoiceFx.sentences(text)
        if (chunks.isEmpty()) { main.post { onDone(id) }; return }
        val g = ++gen
        chunkTotal = chunks.size; nextPlay = 0; playing = false; curId = id; readyFiles.clear(); startedSent = false
        chunks.forEachIndexed { i, c ->
            val sid = "$id#$g#$i"
            synthIds += sid
            pendingText[sid] = c
            val r = tts.synthesizeToFile(c, null, File(ctx.cacheDir, "jv_${g}_$i.wav"), sid)
            if (r != TextToSpeech.SUCCESS) { synthIds -= sid; if (i == 0) { stop(); speakDirect(text, id); return } else readyFiles[i] = null }
        }
    }

    /** Liest die Satz-Datei, legt den Jarvis-Klang darüber. Rückgabe: abspielbare Datei (oder roh, wenn der Klang scheitert). */
    private fun fxChunk(sid: String): File? {
        val p = sid.split('#'); if (p.size < 3) return null
        val raw = File(ctx.cacheDir, "jv_${p[1]}_${p[2]}.wav")
        if (!raw.exists()) return null
        return try {
            val wav = VoiceFx.readWav(raw.readBytes()) ?: return raw
            val out = VoiceFx.process(wav.first, wav.second, preset(), strengthOverride ?: prefs.voiceFxStrength)
            File(ctx.cacheDir, "jvfx_${p[1]}_${p[2]}.wav").also { it.writeBytes(VoiceFx.writeWav(out, wav.second)) }
        } catch (_: Throwable) { raw }
    }

    private fun chunkReady(sid: String, f: File?) {
        val p = sid.split('#'); if (p.size < 3) return
        if (p[1].toIntOrNull() != gen) return
        readyFiles[p[2].toInt()] = f
        pump()
    }

    private fun pump() {
        if (playing) return
        while (nextPlay < chunkTotal && readyFiles.containsKey(nextPlay) && readyFiles[nextPlay] == null) nextPlay++
        if (nextPlay >= chunkTotal) { if (chunkTotal > 0) { chunkTotal = 0; onDone(curId) }; return }
        val f = readyFiles[nextPlay] ?: return
        val g = gen
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(f.absolutePath)
            mp.prepare()
            volume().let { v -> mp.setVolume(v, v) }
            playing = true
            mp.setOnCompletionListener { releasePlayer(); f.delete(); if (g == gen) { playing = false; nextPlay++; pump() } }
            mp.setOnErrorListener { _, _, _ -> releasePlayer(); if (g == gen) { playing = false; nextPlay++; pump() }; true }
            mp.start()
            if (!startedSent) { startedSent = true; onStart() }
        } catch (_: Exception) {
            releasePlayer(); playing = false; nextPlay++; pump()
        }
    }

    private var langPending = false

    /** Text in einer anderen Sprache vorlesen (Übersetzer-Modus), danach wieder die deutsche Stimme. */
    fun speakIn(text: String, locale: Locale, id: String) {
        stop()
        if (!ready) { main.post { onDone(id) }; return }
        try { tts.language = locale } catch (_: Exception) {}
        tts.setPitch(1f); tts.setSpeechRate(prefs.voiceRate)
        langPending = true
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun speakDirect(text: String, id: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume()) }, id.substringBefore('#'))
    }

    /** Lautstärke 0..1: Flüstermodus leise, sonst passend zu deiner Stimme (Auto-Lautstärke). */
    fun volume(): Float = when {
        prefs.whisper -> 0.28f
        prefs.autoVolume && heardLevel >= 0f -> (0.55f + heardLevel * 0.9f).coerceIn(0.55f, 1f)
        else -> 1f
    }

    companion object {
        /** Lauteste Stelle der letzten Spracheingabe (0..1), -1 = unbekannt. Wird beim Zuhören gesetzt. */
        @Volatile @JvmStatic var heardLevel = -1f
        private var peak = 0f
        @JvmStatic fun noteLevel(l: Float) { if (l > peak) peak = l }
        @JvmStatic fun endUtterance() { if (peak > 0f) heardLevel = peak; peak = 0f }
    }

    private fun playFile(sid: String) {
        val id = sid.substringBefore('#')
        val text = pendingText.remove(sid).orEmpty()
        val file = File(ctx.cacheDir, "jarvis_voice_fx.wav")
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            volume().let { v -> mp.setVolume(v, v) }
            mp.setOnCompletionListener { releasePlayer(); onDone(id) }
            mp.setOnErrorListener { _, _, _ -> releasePlayer(); onDone(id); true }
            mp.start()
            onStart()
        } catch (_: Exception) {
            releasePlayer()
            speakDirect(text, id)
        }
    }

    /** Zum Ausprobieren in den Einstellungen: diesen Klang statt des gespeicherten verwenden. */
    var fxOverride: VoiceFx.Preset? = null
    var strengthOverride: Float? = null

    private fun preset() = fxOverride ?: VoiceFx.Preset.of(prefs.voiceFx)

    /** Liest die gesprochene Datei, legt den Jarvis-Klang darüber und speichert sie. false = Format unbekannt. */
    private fun applyFx(): Boolean = try {
        val raw = File(ctx.cacheDir, "jarvis_voice.wav").readBytes()
        val wav = VoiceFx.readWav(raw)
        if (wav == null) false else {
            val out = VoiceFx.process(wav.first, wav.second, preset(), strengthOverride ?: prefs.voiceFxStrength)
            File(ctx.cacheDir, "jarvis_voice_fx.wav").writeBytes(VoiceFx.writeWav(out, wav.second))
            true
        }
    } catch (_: Throwable) { false }

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
        gen++; chunkTotal = 0; playing = false; readyFiles.clear()
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
