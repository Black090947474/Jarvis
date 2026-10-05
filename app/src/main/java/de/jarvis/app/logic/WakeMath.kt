package de.jarvis.app.logic

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rechenteil des persönlichen Weckworts „Jarvis“ (ohne Android, damit testbar).
 *
 * Idee: Beim Training sagst du mehrmals „Jarvis“. Aus jeder Aufnahme nehmen wir die
 * Sprach-Merkmale (Embeddings, je 80 ms) rund um das Wortende als Vorlage. Im Betrieb
 * vergleichen wir die letzten Merkmale laufend mit den Vorlagen (Kosinus-Ähnlichkeit,
 * vorher um den „Hintergrund-Mittelwert“ bereinigt). Den Schwellwert legen wir so fest,
 * dass er zwischen deinen „Jarvis“-Aufnahmen und einem normalen Satz liegt.
 */
object WakeMath {
    /** Anzahl aufeinanderfolgender Embeddings pro Vorlage (4 × 80 ms). */
    const val K = 4

    /** Index des letzten lauten Abschnitts (Wortende) oder -1, wenn nichts gesprochen wurde. */
    fun wordEnd(rms: List<Double>, skip: Int): Int {
        val part = rms.drop(skip)
        if (part.isEmpty()) return -1
        val sorted = part.sorted()
        val floor = sorted[(sorted.size * 0.2).toInt().coerceIn(0, sorted.size - 1)]
        val thr = max(floor * 3.0, 450.0)
        var end = -1
        for (i in rms.indices) if (i >= skip && rms[i] > thr) end = i
        return end
    }

    /** Vorlage: K Embeddings ab kurz vor dem Wortende. */
    fun template(embs: List<FloatArray>, rms: List<Double>, skip: Int): List<FloatArray>? {
        val end = wordEnd(rms, skip)
        if (end < 0) return null
        val from = (end - 1).coerceAtLeast(0)
        if (from + K > embs.size) return null
        return embs.subList(from, from + K).map { it.copyOf() }
    }

    fun mean(vs: List<FloatArray>): FloatArray {
        if (vs.isEmpty()) return FloatArray(0)
        val m = FloatArray(vs[0].size)
        for (v in vs) for (i in m.indices) m[i] += v[i]
        for (i in m.indices) m[i] /= vs.size
        return m
    }

    fun cos(a: FloatArray, b: FloatArray, m: FloatArray): Float {
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) {
            val x = a[i] - (if (m.isNotEmpty()) m[i] else 0f)
            val y = b[i] - (if (m.isNotEmpty()) m[i] else 0f)
            ab += x * y; aa += x * x; bb += y * y
        }
        if (aa <= 0 || bb <= 0) return 0f
        return (ab / sqrt(aa * bb)).toFloat()
    }

    /** Ähnlichkeit eines Fensters (K Embeddings) mit einer Vorlage: Mittel der Kosinus-Werte. */
    fun sim(window: List<FloatArray>, tpl: List<FloatArray>, m: FloatArray): Float {
        val n = min(window.size, tpl.size)
        if (n == 0) return 0f
        var s = 0f
        for (i in 0 until n) s += cos(window[window.size - n + i], tpl[tpl.size - n + i], m)
        return s / n
    }

    fun best(window: List<FloatArray>, tpls: List<List<FloatArray>>, m: FloatArray): Float =
        tpls.maxOfOrNull { sim(window, it, m) } ?: 0f

    /** Höchste Ähnlichkeit, die irgendwo in einer Aufnahme ohne „Jarvis“ vorkommt. */
    fun negMax(embs: List<FloatArray>, tpls: List<List<FloatArray>>, m: FloatArray, skip: Int): Float {
        var best = -1f
        for (e in (skip + K) until embs.size + 1) best = max(best, best(embs.subList(e - K, e), tpls, m))
        return best
    }

    /** Jede Vorlage gegen die anderen (leave-one-out): wie sicher erkennt es dich selbst? */
    fun positives(tpls: List<List<FloatArray>>, m: FloatArray): List<Float> =
        tpls.indices.map { i -> best(tpls[i], tpls.filterIndexed { j, _ -> j != i }, m) }

    data class Calib(val threshold: Float, val posMin: Float, val negMax: Float, val good: Boolean)

    /** Schwellwert zwischen deinen Aufnahmen und normalem Sprechen. [bias] 0..1: höher = strenger. */
    fun calibrate(pos: List<Float>, neg: Float, bias: Float = 0.5f): Calib {
        val posMin = pos.minOrNull() ?: 1f
        // Den schwächsten Treffer nicht überbewerten: zweitschlechtester, wenn genug Aufnahmen da sind
        val p = if (pos.size >= 4) pos.sorted()[1] else posMin
        return if (p > neg + 0.04f) {
            Calib((neg + (p - neg) * bias).coerceAtLeast(neg + 0.03f), posMin, neg, true)
        } else Calib(neg + 0.03f, posMin, neg, false)
    }
}
