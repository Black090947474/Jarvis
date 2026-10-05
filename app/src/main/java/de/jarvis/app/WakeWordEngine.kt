package de.jarvis.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.nio.FloatBuffer

/**
 * Offline-Erkennung des Weckworts mit openWakeWord (kostenlos, kein Konto nötig).
 *
 * Ablauf pro 80 ms Audio (1280 Samples bei 16 kHz):
 *   Audio → Mel-Spektrogramm → Sprach-Embedding (96 Werte) → die letzten 16 Embeddings
 *   → "Hey Jarvis"-Modell → Wahrscheinlichkeit 0..1.
 * Zusätzlich (Modus „Nur Jarvis“): Vergleich mit deinen eigenen, im Training aufgenommenen
 * „Jarvis“-Aufnahmen ([PersonalWake]). So reicht auch nur das Wort „Jarvis“.
 */
class WakeWordEngine(
    context: Context,
    private var threshold: Float,
    private val onDetected: () -> Unit,
) {
    private val appCtx = context.applicationContext
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
    private val melModel = load(context, "melspectrogram.onnx")
    private val embModel = load(context, "embedding_model.onnx")
    private val wakeModel = load(context, "hey_jarvis_v0.1.onnx")
    private val wakeInput = wakeModel.inputNames.first()
    private var personal: PersonalWake.Model? = null
    private var onlyJarvis = false

    @Volatile private var running = false
    private var thread: Thread? = null

    private fun load(ctx: Context, name: String): OrtSession =
        env.createSession(ctx.assets.open(name).use { it.readBytes() }, opts)

    /** Erzeugt einen Merkmals-Rechner (eigener Puffer), z. B. fürs Training. */
    fun featurizer() = Featurizer()

    /** Mel + Embedding mit eigenem Zustand. [push] nimmt 80 ms Audio und liefert das neue Embedding (96 Werte). */
    inner class Featurizer {
        private val raw = FloatArray(MEL_IN)
        private val mel = Array(MEL_WINDOW) { FloatArray(MEL_BINS) { 1f } }
        fun push(chunk: ShortArray): FloatArray {
            System.arraycopy(raw, CHUNK, raw, 0, MEL_IN - CHUNK)
            for (i in 0 until CHUNK) raw[MEL_IN - CHUNK + i] = chunk[i].toFloat()
            val melOut = run(melModel, "input", raw, longArrayOf(1, MEL_IN.toLong()))
            val frames = melOut.size / MEL_BINS
            for (f in 0 until frames) {
                System.arraycopy(mel, 1, mel, 0, MEL_WINDOW - 1)
                mel[MEL_WINDOW - 1] = FloatArray(MEL_BINS) { b -> melOut[f * MEL_BINS + b] / 10f + 2f }
            }
            val embIn = FloatArray(MEL_WINDOW * MEL_BINS)
            for (f in 0 until MEL_WINDOW) System.arraycopy(mel[f], 0, embIn, f * MEL_BINS, MEL_BINS)
            return run(embModel, "input_1", embIn, longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1))
        }
    }

    /** Ganze Aufnahme (PCM 16 kHz) in Embeddings + Lautstärke pro 80 ms umrechnen. Vorne wird 1 s Ruhe ergänzt. */
    fun embed(pcm: ShortArray): Pair<List<FloatArray>, List<Double>> {
        val fz = Featurizer()
        val lead = ShortArray(SR) { ((Math.random() - 0.5) * 20).toInt().toShort() }
        val all = lead + pcm
        val embs = ArrayList<FloatArray>(); val rms = ArrayList<Double>()
        var i = 0
        while (i + CHUNK <= all.size) {
            val c = all.copyOfRange(i, i + CHUNK)
            embs.add(fz.push(c))
            var s = 0.0; for (v in c) s += v * v.toDouble(); rms.add(Math.sqrt(s / CHUNK))
            i += CHUNK
        }
        return embs to rms
    }

    /** Einstellungen (Empfindlichkeit, Modus, eigene Aufnahmen) neu laden – z. B. nach dem Training. */
    fun reload() {
        val p = Prefs(appCtx)
        threshold = p.sensitivity
        onlyJarvis = p.wakeMode == "JARVIS"
        personal = if (onlyJarvis) PersonalWake.load(appCtx) else null
    }

    @SuppressLint("MissingPermission") // wird vor dem Start in der App geprüft
    fun start() {
        if (running) return
        reload()
        running = true
        thread = Thread({ loop() }, "jarvis-wakeword").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    fun close() {
        stop()
        melModel.close(); embModel.close(); wakeModel.close(); opts.close()
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK * 2 * 4))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Mikrofon konnte nicht geöffnet werden"); rec.release(); running = false; return
        }

        val chunk = ShortArray(CHUNK)
        val fz = Featurizer()
        val feats = ArrayDeque<FloatArray>()                // letzte 16 Embeddings
        var chunks = 0
        var cooldownUntil = 0L
        // Im Modus „Nur Jarvis“ ohne eigenes Training: das Modell etwas empfindlicher stellen,
        // weil das „Hey“ fehlt (Wert aus Messung mit synthetischen Stimmen).
        val modelThr = if (onlyJarvis && personal == null) (threshold * ONLY_FACTOR).coerceAtLeast(0.08f) else threshold

        try {
            rec.startRecording()
            while (running) {
                var read = 0
                while (read < CHUNK && running) {
                    val n = rec.read(chunk, read, CHUNK - read)
                    if (n <= 0) { Thread.sleep(10); continue }
                    read += n
                }
                if (!running) break

                val emb = fz.push(chunk)
                feats.addLast(emb)
                if (feats.size > FEATURES) feats.removeFirst()
                chunks++

                // Erst nach ca. 2 s Aufwärmzeit bewerten (Puffer müssen echtes Audio enthalten)
                if (chunks < WARMUP || feats.size < FEATURES) continue
                val wwIn = FloatArray(FEATURES * EMB)
                feats.forEachIndexed { i, e -> System.arraycopy(e, 0, wwIn, i * EMB, EMB) }
                val score = run(wakeModel, wakeInput, wwIn, longArrayOf(1, FEATURES.toLong(), EMB.toLong()))[0]
                val mine = personal?.score(feats) ?: 0f

                val now = SystemClock.elapsedRealtime()
                val hit = score >= modelThr || (personal != null && mine >= personal!!.threshold)
                if (hit && now > cooldownUntil) {
                    cooldownUntil = now + 2000
                    Log.i(TAG, "Jarvis erkannt (Modell %.2f, eigen %.2f)".format(score, mine))
                    running = false
                    onDetected()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fehler bei der Erkennung", e)
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            running = false
        }
    }

    private fun run(s: OrtSession, input: String, data: FloatArray, shape: LongArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { t ->
            s.run(mapOf(input to t)).use { r ->
                val fb = (r.get(0) as OnnxTensor).floatBuffer
                return FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
    }

    companion object {
        private const val TAG = "JarvisWake"
        const val SR = 16_000
        const val CHUNK = 1280            // 80 ms
        private const val MEL_IN = CHUNK + 480    // 1760 Samples → 8 Mel-Frames
        private const val MEL_BINS = 32
        private const val MEL_WINDOW = 76
        const val EMB = 96
        private const val FEATURES = 16
        private const val WARMUP = 25
        /** Faktor für den Modell-Schwellwert im Modus „Nur Jarvis“ ohne eigenes Training. */
        const val ONLY_FACTOR = 0.6f
    }
}
