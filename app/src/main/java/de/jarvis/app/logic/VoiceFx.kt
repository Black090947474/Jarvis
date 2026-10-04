package de.jarvis.app.logic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Jarvis-Klang: bearbeitet eine fertig gesprochene Stimme (16-Bit-PCM, mono) mit Effekten.
 * Reines Kotlin, läuft auf dem Handy ohne Internet und lässt sich ohne Android testen.
 */
object VoiceFx {

    enum class Preset(val label: String, val pitch: Float) {
        AUS("Aus (Originalstimme)", 1.0f),
        BUTLER("Butler – warm, tief, leichter Raumhall", 0.93f),
        KI("KI – metallischer Schimmer, leichte Doppelung", 0.9f),
        HOLOGRAMM("Hologramm – Lautsprecher-Klang mit Echo", 0.88f);

        companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } ?: KI }
    }

    /** WAV-Datei (16 Bit PCM) lesen: (Samples, Abtastrate, Kanäle) oder null bei anderem Format. */
    fun readWav(b: ByteArray): Triple<ShortArray, Int, Int>? {
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (b.size < 44 || String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WAVE") return null
        var pos = 12; var rate = 0; var ch = 1; var bits = 0; var fmt = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4); val len = bb.getInt(pos + 4)
            if (id == "fmt ") { fmt = bb.getShort(pos + 8).toInt(); ch = bb.getShort(pos + 10).toInt(); rate = bb.getInt(pos + 12); bits = bb.getShort(pos + 22).toInt() }
            if (id == "data") {
                if (fmt != 1 || bits != 16 || rate <= 0) return null
                val n = minOf(len, b.size - pos - 8).coerceAtLeast(0) / 2
                val out = ShortArray(n) { bb.getShort(pos + 8 + it * 2) }
                // Stereo → mono
                return if (ch == 2) Triple(ShortArray(n / 2) { ((out[it * 2] + out[it * 2 + 1]) / 2).toShort() }, rate, 1) else Triple(out, rate, ch)
            }
            pos += 8 + len + (len and 1)
            if (len < 0) return null
        }
        return null
    }

    fun writeWav(s: ShortArray, rate: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44 + s.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + s.size * 2); b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray()); b.putInt(16)
        b.putShort(1); b.putShort(1); b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(s.size * 2)
        for (v in s) b.putShort(v)
        return b.array()
    }

    /** Bearbeitet [pcm] und gibt neue Samples zurück (etwas länger wegen Hall/Echo). [strength] 0..1. */
    fun process(pcm: ShortArray, rate: Int, preset: Preset, strength: Float): ShortArray {
        if (preset == Preset.AUS || pcm.isEmpty()) return pcm
        val s = strength.coerceIn(0f, 1f)
        val tail = (rate * when (preset) { Preset.HOLOGRAMM -> 0.6; Preset.KI -> 0.45; else -> 0.35 }).toInt()
        var x = FloatArray(pcm.size + tail) { if (it < pcm.size) pcm[it] / 32768f else 0f }

        when (preset) {
            Preset.BUTLER -> {
                x = highPass(x, rate, 70f)
                x = lowShelf(x, rate, 220f, 1f + 0.5f * s)      // Wärme
                x = reverb(x, rate, room = 0.55f, mix = 0.16f * s)
            }
            Preset.KI -> {
                x = highPass(x, rate, 110f)
                x = chorus(x, rate, delayMs = 11f, depthMs = 1.6f, speedHz = 0.7f, mix = 0.38f * s)
                x = ringMod(x, rate, freq = 38f, mix = 0.10f * s)  // feiner Metall-Schimmer
                x = lowShelf(x, rate, 200f, 1f + 0.3f * s)
                x = reverb(x, rate, room = 0.6f, mix = 0.2f * s)
            }
            Preset.HOLOGRAMM -> {
                x = highPass(x, rate, 260f)
                x = lowPass(x, rate, 5200f)
                x = ringMod(x, rate, freq = 55f, mix = 0.22f * s)
                x = chorus(x, rate, delayMs = 6f, depthMs = 2.5f, speedHz = 0.35f, mix = 0.45f * s)  // Flanger-Charakter
                x = echo(x, rate, delayMs = 95f, feedback = 0.28f * s, mix = 0.3f * s)
                x = reverb(x, rate, room = 0.7f, mix = 0.24f * s)
            }
            else -> {}
        }
        // Lautstärke angleichen (max. −1 dBFS), Stille am Ende abschneiden
        var peak = 0f
        for (v in x) peak = max(peak, abs(v))
        var origPeak = 0
        for (v in pcm) origPeak = max(origPeak, abs(v.toInt()))
        // Gleiche Lautstärke wie das Original, aber nie übersteuern
        val gain = if (peak > 0f) minOf(0.89f / peak, (origPeak / 32768f) / peak * 1.1f).coerceAtLeast(0.0001f) else 1f
        var end = x.size
        while (end > pcm.size && abs(x[end - 1] * gain) < 0.0015f) end--
        return ShortArray(end) { (x[it] * gain * 32767f).coerceIn(-32768f, 32767f).toInt().toShort() }
    }

    private fun highPass(x: FloatArray, rate: Int, fc: Float): FloatArray {
        val rc = 1f / (2f * PI.toFloat() * fc); val dt = 1f / rate; val a = rc / (rc + dt)
        val y = FloatArray(x.size); var prevX = 0f; var prevY = 0f
        for (i in x.indices) { val v = a * (prevY + x[i] - prevX); y[i] = v; prevY = v; prevX = x[i] }
        return y
    }

    private fun lowPass(x: FloatArray, rate: Int, fc: Float): FloatArray {
        val dt = 1f / rate; val rc = 1f / (2f * PI.toFloat() * fc); val a = dt / (rc + dt)
        val y = FloatArray(x.size); var prev = 0f
        for (i in x.indices) { prev += a * (x[i] - prev); y[i] = prev }
        return y
    }

    /** Hebt die Tiefen um [gain] an (1 = unverändert). */
    private fun lowShelf(x: FloatArray, rate: Int, fc: Float, gain: Float): FloatArray {
        val low = lowPass(x, rate, fc)
        return FloatArray(x.size) { x[it] + (gain - 1f) * low[it] }
    }

    private fun chorus(x: FloatArray, rate: Int, delayMs: Float, depthMs: Float, speedHz: Float, mix: Float): FloatArray {
        val y = FloatArray(x.size)
        val base = delayMs * rate / 1000f; val depth = depthMs * rate / 1000f
        for (i in x.indices) {
            val d = base + depth * sin(2 * PI * speedHz * i / rate).toFloat()
            val pos = i - d
            val j = pos.toInt()
            val wet = if (j >= 0 && j + 1 < x.size) { val f = pos - j; x[j] * (1 - f) + x[j + 1] * f } else 0f
            y[i] = x[i] * (1 - mix * 0.5f) + wet * mix
        }
        return y
    }

    private fun ringMod(x: FloatArray, rate: Int, freq: Float, mix: Float): FloatArray =
        FloatArray(x.size) { x[it] * (1 - mix) + x[it] * sin(2 * PI * freq * it / rate).toFloat() * mix }

    private fun echo(x: FloatArray, rate: Int, delayMs: Float, feedback: Float, mix: Float): FloatArray {
        val d = (delayMs * rate / 1000f).toInt().coerceAtLeast(1)
        val buf = x.copyOf()
        for (i in d until buf.size) buf[i] += buf[i - d] * feedback
        return FloatArray(x.size) { x[it] + (buf[it] - x[it]) * mix * 3f }
    }

    /** Einfacher Schroeder-Hall: 4 Kammfilter + 2 Allpässe. */
    private fun reverb(x: FloatArray, rate: Int, room: Float, mix: Float): FloatArray {
        if (mix <= 0f) return x
        val scale = rate / 44100f
        val combs = intArrayOf(1116, 1188, 1277, 1356).map { (it * scale).toInt() }
        val wet = FloatArray(x.size)
        val damp = 0.3f
        for (len in combs) {
            val buf = FloatArray(len); var idx = 0; var store = 0f
            for (i in x.indices) {
                val out = buf[idx]
                store = out * (1 - damp) + store * damp
                buf[idx] = x[i] + store * room
                wet[i] += out
                idx = (idx + 1) % len
            }
        }
        var y = wet
        for (len in intArrayOf(556, 441).map { (it * scale).toInt() }) {
            val buf = FloatArray(len); var idx = 0; val out = FloatArray(y.size)
            for (i in y.indices) {
                val b = buf[idx]; val o = -y[i] + b
                buf[idx] = y[i] + b * 0.5f
                out[i] = o; idx = (idx + 1) % len
            }
            y = out
        }
        return FloatArray(x.size) { x[it] + y[it] * mix * 0.25f }
    }

    @Suppress("unused") private fun db(v: Float) = exp(v / 8.686f)
}
