package de.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Die animierte Mitte des Jarvis-Bildschirms – in vier Designs. */
class CoreView(context: Context, val style: Style) : View(context) {

    enum class Style { GLUT, AURORA, LINIE, GLAS }
    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    var onModeChanged: ((Mode) -> Unit)? = null

    var mode: Mode = Mode.IDLE
        set(value) {
            if (field != value) { field = value; onModeChanged?.invoke(value) }
            invalidate()
        }

    /** Lautstärke vom Mikrofon (0..1), lässt den Kern beim Zuhören mitgehen. */
    var level: Float = 0f
        set(value) { field = field * 0.6f + value.coerceIn(0f, 1f) * 0.4f }

    private val dp = resources.displayMetrics.density
    private val start = SystemClock.uptimeMillis()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val cx = width / 2f
        val cy = height / 2f
        val size = min(width, height).toFloat()
        when (style) {
            Style.GLUT -> drawGlut(canvas, t, cx, cy, size)
            Style.AURORA -> drawAurora(canvas, t, cx, cy, size)
            Style.LINIE -> drawLinie(canvas, t, cx, cy, size)
            Style.GLAS -> drawGlas(canvas, t, cx, cy, size)
        }
        postInvalidateOnAnimation()
    }

    /** Wie stark der Kern gerade "atmet". */
    private fun pulse(t: Float): Float = when (mode) {
        Mode.IDLE -> 0.03f * sin(t * 1.9f)
        Mode.LISTENING -> 0.03f * sin(t * 2.4f) + level * 0.18f
        Mode.THINKING -> 0.025f * sin(t * 7f)
        Mode.SPEAKING -> 0.07f * abs(sin(t * 6.5f)) * (0.6f + 0.4f * sin(t * 1.7f))
    }

    // ---------- 1 · Glut ----------

    private fun drawGlut(c: Canvas, t: Float, cx: Float, cy: Float, size: Float) {
        val accent = when (mode) {
            Mode.THINKING -> Color.rgb(240, 138, 60)
            Mode.IDLE -> Color.rgb(160, 40, 36)
            else -> Color.rgb(232, 69, 60)
        }
        val r = size * 0.19f * (1f + pulse(t))
        val haloR = r * 2.1f * (1f + 0.08f * sin(t * 1.9f))
        fill.shader = RadialGradient(cx, cy, haloR, intArrayOf(withAlpha(accent, 110), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, haloR, fill)
        fill.shader = RadialGradient(cx - r * 0.24f, cy - r * 0.36f, r * 1.35f,
            intArrayOf(Color.rgb(255, 233, 220), accent, Color.rgb(74, 13, 18)),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
    }

    // ---------- 2 · Aurora ----------

    private fun drawAurora(c: Canvas, t: Float, cx: Float, cy: Float, size: Float) {
        val r = size * 0.27f * (1f + pulse(t) * 0.7f)
        val speed = when (mode) { Mode.THINKING -> 2.6f; Mode.SPEAKING -> 1.6f; else -> 1f }
        val tt = t * speed
        c.save()
        clip.reset(); clip.addCircle(cx, cy, r, Path.Direction.CW)
        c.clipPath(clip)
        blob(c, cx - r * 0.25f + r * 0.22f * sin(tt * 1.05f), cy - r * 0.15f + r * 0.18f * cos(tt * 0.9f),
            r * 0.95f, Color.rgb(61, 224, 208), 230)
        blob(c, cx + r * 0.28f + r * 0.2f * cos(tt * 0.85f), cy + r * 0.05f + r * 0.22f * sin(tt * 1.1f),
            r * 0.95f, Color.rgb(106, 91, 255), 240)
        blob(c, cx + r * 0.05f * sin(tt * 1.3f), cy + r * 0.35f + r * 0.2f * cos(tt * 1.2f),
            r * 0.8f, Color.rgb(255, 111, 174), 170)
        c.restore()
        // feiner Rand
        stroke.color = withAlpha(Color.WHITE, 26); stroke.strokeWidth = 1f * dp
        c.drawCircle(cx, cy, r, stroke)
    }

    private fun blob(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Int) {
        fill.shader = RadialGradient(x, y, r, intArrayOf(withAlpha(color, alpha), withAlpha(color, 0)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, fill)
        fill.shader = null
    }

    // ---------- 3 · Linie ----------

    private val barHeights = floatArrayOf(34f, 54f, 70f, 48f, 30f)

    private fun drawLinie(c: Canvas, t: Float, cx: Float, cy: Float, size: Float) {
        val r = size * 0.25f * (1f + 0.02f * sin(t * 2.4f) + if (mode == Mode.LISTENING) level * 0.06f else 0f)
        stroke.color = Color.WHITE
        stroke.alpha = if (mode == Mode.IDLE) 150 else 235
        stroke.strokeWidth = 1.5f * dp
        c.drawCircle(cx, cy, r, stroke)
        fill.color = Color.WHITE
        val bw = 3f * dp
        val gap = 5f * dp
        val total = barHeights.size * bw + (barHeights.size - 1) * gap
        for (i in barHeights.indices) {
            val f = when (mode) {
                Mode.IDLE -> 0.22f
                Mode.LISTENING -> 0.22f + (level * 0.78f) * (0.6f + 0.4f * abs(sin(t * 9f + i)))
                Mode.THINKING -> 0.22f + 0.3f * (0.5f + 0.5f * sin(t * 6f - i * 0.9f))
                Mode.SPEAKING -> 0.25f + 0.75f * abs(sin(t * 6.5f + i * 0.7f))
            }
            val h = barHeights[i] * dp * f
            val x = cx - total / 2 + i * (bw + gap)
            rect.set(x, cy - h / 2, x + bw, cy + h / 2)
            c.drawRoundRect(rect, bw / 2, bw / 2, fill)
        }
    }

    // ---------- 4 · Glas ----------

    private fun drawGlas(c: Canvas, t: Float, cx: Float, cy0: Float, size: Float) {
        val cy = cy0 + 10f * dp * sin(t * 1.6f)
        val r = size * 0.2f * (1f + pulse(t))
        val glowR = r * 2.2f
        fill.shader = RadialGradient(cx, cy + r * 0.5f, glowR,
            intArrayOf(Color.argb(if (mode == Mode.THINKING) 150 else 110, 77, 124, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + r * 0.5f, glowR, fill)
        fill.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.4f, r * 1.5f,
            intArrayOf(Color.WHITE, Color.rgb(185, 212, 255), Color.rgb(77, 124, 255), Color.rgb(26, 42, 102)),
            floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
    }

    private fun withAlpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        fun styleFrom(name: String): Style = Style.entries.firstOrNull { it.name == name } ?: Style.GLUT
    }
}
