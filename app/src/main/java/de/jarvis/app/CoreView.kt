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

    enum class Style { NEXUS, GLUT, AURORA, LINIE, GLAS }
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
            Style.NEXUS -> drawNexus(canvas, t, cx, cy, size)
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

    // ---------- 0 · Nexus (HUD) ----------

    private fun drawNexus(c: Canvas, t: Float, cx: Float, cy: Float, size: Float) {
        val base = size * 0.38f
        val main = when (mode) {
            Mode.THINKING -> Color.rgb(255, 182, 60)
            Mode.SPEAKING -> Color.rgb(90, 230, 255)
            Mode.IDLE -> Color.rgb(90, 150, 170)
            else -> Color.rgb(34, 224, 224)
        }
        val amber = Color.rgb(255, 182, 60)

        // faint radial grid glow
        fill.shader = RadialGradient(cx, cy, base * 1.25f,
            intArrayOf(withAlpha(main, 36), Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, base * 1.25f, fill); fill.shader = null

        val spin = when (mode) { Mode.THINKING -> 90f; Mode.SPEAKING -> 34f; else -> 16f }
        val ang = t * spin

        // outer tick ring (rotating), with four longer index ticks
        stroke.strokeCap = Paint.Cap.BUTT
        for (i in 0 until 60) {
            val a = Math.toRadians((i * 6 + ang).toDouble())
            val long = i % 15 == 0
            val r0 = base * (if (long) 0.90f else 0.95f)
            val r1 = base * 1.0f
            stroke.color = withAlpha(if (long) amber else main, if (long) 255 else 150)
            stroke.strokeWidth = if (long) 2.5f * dp else 1f * dp
            c.drawLine(cx + (cos(a) * r0).toFloat(), cy + (sin(a) * r0).toFloat(),
                cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat(), stroke)
        }

        // two arc segments on a mid ring, counter-rotating
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = main; stroke.strokeWidth = 2.5f * dp
        val midR = base * 0.80f
        rect.set(cx - midR, cy - midR, cx + midR, cy + midR)
        c.drawArc(rect, -ang * 1.4f, 55f, false, stroke)
        c.drawArc(rect, -ang * 1.4f + 180f, 55f, false, stroke)
        stroke.color = withAlpha(main, 90); stroke.strokeWidth = 1f * dp
        c.drawArc(rect, -ang * 1.4f + 70f, 95f, false, stroke)
        c.drawArc(rect, -ang * 1.4f + 250f, 95f, false, stroke)

        // reticle corner brackets (square frame), gently pulsing
        val fr = base * (0.62f + 0.015f * sin(t * 2f))
        stroke.color = withAlpha(amber, 220); stroke.strokeWidth = 2f * dp
        val leg = fr * 0.32f
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
            val x = cx + sx * fr; val y = cy + sy * fr
            c.drawLine(x, y, x - sx * leg, y, stroke)
            c.drawLine(x, y, x, y - sy * leg, stroke)
        }

        // sweeping radar line when listening / thinking
        if (mode == Mode.LISTENING || mode == Mode.THINKING) {
            val sweep = -t * (if (mode == Mode.THINKING) 220f else 120f)
            val sa = Math.toRadians(sweep.toDouble())
            stroke.strokeWidth = 2f * dp
            stroke.shader = android.graphics.LinearGradient(cx, cy,
                cx + (cos(sa) * fr).toFloat(), cy + (sin(sa) * fr).toFloat(),
                withAlpha(main, 0), withAlpha(main, 210), Shader.TileMode.CLAMP)
            c.drawLine(cx, cy, cx + (cos(sa) * fr).toFloat(), cy + (sin(sa) * fr).toFloat(), stroke)
            stroke.shader = null
        }

        // hexagon core
        val hx = fr * 0.52f * (1f + pulse(t))
        val hex = Path()
        for (i in 0 until 6) {
            val a = Math.toRadians((60 * i - 90 + ang * 0.3f).toDouble())
            val px = cx + (cos(a) * hx).toFloat(); val py = cy + (sin(a) * hx).toFloat()
            if (i == 0) hex.moveTo(px, py) else hex.lineTo(px, py)
        }
        hex.close()
        fill.shader = RadialGradient(cx, cy, hx, intArrayOf(withAlpha(main, 235), withAlpha(main, 40)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(hex, fill); fill.shader = null
        stroke.color = Color.WHITE; stroke.strokeWidth = 1.5f * dp
        c.drawPath(hex, stroke)

        // audio bars radiating when speaking
        if (mode == Mode.SPEAKING || mode == Mode.LISTENING) {
            stroke.color = withAlpha(main, 200); stroke.strokeCap = Paint.Cap.ROUND
            for (i in 0 until 24) {
                val a = Math.toRadians((i * 15).toDouble())
                val lvl = if (mode == Mode.LISTENING) level else abs(sin(t * 7f + i * 0.6f))
                val r0 = hx * 1.35f
                val r1 = r0 + (fr * 0.22f) * (0.25f + 0.75f * lvl)
                stroke.strokeWidth = 2f * dp
                c.drawLine(cx + (cos(a) * r0).toFloat(), cy + (sin(a) * r0).toFloat(),
                    cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat(), stroke)
            }
        }
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
