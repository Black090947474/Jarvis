package de.jarvis.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Genauere Spracherkennung: nimmt auf, bis du fertig gesprochen hast (Stille-Erkennung),
 * und lässt den Text von Whisper (über Groq, kostenlos mit eigenem Limit) erkennen.
 */
object Whisper {
    private const val RATE = 16_000

    /**
     * Nimmt auf. Gibt PCM (16 Bit, mono, 16 kHz) zurück oder null, wenn niemand gesprochen hat.
     * [level] bekommt die Lautstärke 0..1 (für die Animation), [cancelled] bricht ab.
     */
    @SuppressLint("MissingPermission")
    fun record(level: (Float) -> Unit, cancelled: () -> Boolean, maxMs: Int = 15_000, silenceMs: Int = 1_100, startTimeoutMs: Int = 7_000): ByteArray? {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("Mikrofon belegt") }
        val frame = ShortArray(RATE * 30 / 1000) // 30 ms
        val out = ByteArrayOutputStream()
        val preRoll = ArrayDeque<ByteArray>()
        var noise = 0.0; var noiseFrames = 0
        var speaking = false; var silent = 0; var elapsed = 0; var speechMs = 0
        try {
            rec.startRecording()
            while (!cancelled()) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) continue
                elapsed += 30
                var sum = 0.0
                for (i in 0 until n) sum += frame[i] * frame[i].toDouble()
                val rms = sqrt(sum / n)
                val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> for (i in 0 until n) b.putShort(frame[i]) }.array()
                if (elapsed <= 300 && !speaking) { noise += rms; noiseFrames++ }
                val floor = if (noiseFrames > 0) noise / noiseFrames else 200.0
                val threshold = maxOf(floor * 2.8, 450.0)
                level((rms / 4000.0).toFloat().coerceIn(0f, 1f))
                if (!speaking) {
                    preRoll.addLast(bytes); if (preRoll.size > 12) preRoll.removeFirst()
                    if (rms > threshold && elapsed > 150) { speaking = true; preRoll.forEach { out.write(it) }; preRoll.clear() }
                    else if (elapsed > startTimeoutMs) return null
                } else {
                    out.write(bytes)
                    if (rms > threshold * 0.7) { silent = 0; speechMs += 30 } else silent += 30
                    if (silent >= silenceMs || elapsed >= maxMs) break
                }
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            level(0f)
        }
        if (cancelled() || !speaking || speechMs < 250) return null
        return out.toByteArray()
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }

    /** Typische Whisper-„Halluzinationen“ bei Stille (stammen aus Untertiteln). */
    private val JUNK = listOf("untertitel", "zdf", "abonnier", "copyright", "swr ", "wdr ", "amara.org")

    /** Text erkennen. Wirft eine Exception bei Netz-/Schlüsselproblemen. */
    fun transcribe(apiKey: String, pcm: ByteArray, hint: String = "Hey Jarvis"): String {
        val key = apiKey.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        val boundary = "----jarvis${System.currentTimeMillis()}"
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("model", "whisper-large-v3-turbo")
        field("language", "de")
        field("temperature", "0")
        field("response_format", "json")
        field("prompt", hint)
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sprache.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        body.write(wav(pcm))
        body.write("\r\n--$boundary--\r\n".toByteArray())
        val c = (URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw java.io.IOException("Whisper HTTP $code")
            val text = JSONObject(txt).optString("text").trim()
            return if (JUNK.any { text.lowercase().contains(it) }) "" else text
        } finally { c.disconnect() }
    }
}
