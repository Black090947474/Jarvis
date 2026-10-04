package de.jarvis.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Leuchtender HUD-Kern für den Startbildschirm: Ringe, Skala, drehende Bögen, pulsierendes Zentrum. */
class OrbView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    /** 0..1 – Lautstärke beim Zuhören, lässt den Kern stärker pulsieren. */
    var level = 0f

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 12_000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    private fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    override fun onDraw(c: Canvas) {
        val size = min(width, height).toFloat()
        if (size < 24 * dp) return
        val cx = width / 2f; val cy = height / 2f; val r = size / 2f * 0.92f
        val acc = Ui.RED; val acc2 = Ui.ACCENT2
        try {
            // Glühen im Hintergrund
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(cx, cy, r, intArrayOf(alpha(acc, 90), alpha(acc, 20), Color.TRANSPARENT), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p); p.shader = null

            // Äußere Skala (Ticks)
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            for (i in 0 until 72) {
                val a = Math.toRadians(i * 5.0 + t * 360 * 0.1)
                val long = i % 6 == 0
                p.strokeWidth = if (long) 2.4f * dp else 1.2f * dp
                p.color = alpha(acc, if (long) 220 else 110)
                val r1 = r * 0.93f; val r2 = r * (if (long) 0.84f else 0.88f)
                c.drawLine(cx + cos(a).toFloat() * r1, cy + sin(a).toFloat() * r1, cx + cos(a).toFloat() * r2, cy + sin(a).toFloat() * r2, p)
            }
            // Drehende Bögen
            fun arc(rr: Float, start: Float, sweep: Float, w: Float, col: Int) {
                p.strokeWidth = w; p.color = col
                c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), start, sweep, false, p)
            }
            val rot = t * 360f
            arc(r * 0.76f, rot, 70f, 3f * dp, acc2)
            arc(r * 0.76f, rot + 180f, 40f, 3f * dp, alpha(acc2, 180))
            arc(r * 0.66f, -rot * 1.6f, 110f, 1.6f * dp, alpha(acc, 200))
            arc(r * 0.66f, -rot * 1.6f + 200f, 60f, 1.6f * dp, alpha(acc, 140))
            p.strokeWidth = 1f * dp; p.color = alpha(acc, 70)
            c.drawCircle(cx, cy, r * 0.56f, p)
            // Innere Speichen
            for (i in 0 until 36) {
                val a = Math.toRadians(i * 10.0 - t * 360 * 0.25)
                p.strokeWidth = 1.4f * dp; p.color = alpha(acc2, 120)
                val r1 = r * 0.50f; val r2 = r * 0.45f
                c.drawLine(cx + cos(a).toFloat() * r1, cy + sin(a).toFloat() * r1, cx + cos(a).toFloat() * r2, cy + sin(a).toFloat() * r2, p)
            }
            // Pulsierender Kern
            val pulse = 0.5f + 0.5f * sin(t * Math.PI.toFloat() * 2 * 6) * 0.5f + level * 0.6f
            val core = r * (0.26f + 0.04f * pulse)
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(cx, cy, core * 1.6f, intArrayOf(Color.WHITE, acc2, alpha(acc, 160), Color.TRANSPARENT),
                floatArrayOf(0f, 0.25f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, core * 1.6f, p); p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * dp; p.color = alpha(Color.WHITE, 200)
            c.drawCircle(cx, cy, core, p)
        } catch (_: Exception) { p.shader = null }
    }
}

/** Schlichte Linien-Icons im HUD-Stil (selbst gezeichnet, keine fremden Grafiken). */
class IconView(context: Context, private val icon: String, private val color: Int = Ui.RED) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 0) return
        c.save(); c.translate((width - s) / 2f, (height - s) / 2f); c.scale(s / 24f, s / 24f)
        p.strokeWidth = 1.7f; p.color = color
        p.maskFilter = null
        val path = Path()
        fun rr(l: Float, t: Float, r: Float, b: Float, rad: Float = 3f) = c.drawRoundRect(RectF(l, t, r, b), rad, rad, p)
        when (icon) {
            "chat" -> { rr(3f, 4f, 21f, 16f); path.moveTo(8f, 16f); path.lineTo(7f, 20f); path.lineTo(12f, 16f); c.drawPath(path, p)
                c.drawLine(7f, 9f, 17f, 9f, p); c.drawLine(7f, 12f, 14f, 12f, p) }
            "calendar" -> { rr(3f, 5f, 21f, 21f); c.drawLine(3f, 10f, 21f, 10f, p); c.drawLine(8f, 3f, 8f, 7f, p); c.drawLine(16f, 3f, 16f, 7f, p)
                for (x in listOf(8f, 12f, 16f)) for (y in listOf(14f, 18f)) c.drawPoint(x, y, p) }
            "bell" -> { path.moveTo(6f, 17f); path.lineTo(6f, 11f); path.cubicTo(6f, 4f, 18f, 4f, 18f, 11f); path.lineTo(18f, 17f); path.close()
                c.drawPath(path, p); c.drawLine(4f, 17f, 20f, 17f, p); c.drawArc(RectF(10f, 18f, 14f, 22f), 0f, 180f, false, p) }
            "phone" -> { path.moveTo(5f, 4f); path.lineTo(9f, 4f); path.lineTo(10.5f, 8.5f); path.lineTo(8f, 10f)
                path.cubicTo(9.5f, 13f, 11f, 14.5f, 14f, 16f); path.lineTo(15.5f, 13.5f); path.lineTo(20f, 15f); path.lineTo(20f, 19f)
                path.cubicTo(12f, 20f, 4f, 12f, 5f, 4f); c.drawPath(path, p) }
            "message" -> { rr(3f, 5f, 21f, 17f); path.moveTo(7f, 17f); path.lineTo(7f, 21f); path.lineTo(11f, 17f); c.drawPath(path, p)
                c.drawLine(7f, 9.5f, 17f, 9.5f, p); c.drawLine(7f, 12.5f, 13f, 12.5f, p) }
            "mail" -> { rr(3f, 5f, 21f, 19f, 2f); path.moveTo(3.5f, 6f); path.lineTo(12f, 13f); path.lineTo(20.5f, 6f); c.drawPath(path, p) }
            "music" -> { c.drawLine(9f, 17f, 9f, 5f, p); c.drawLine(19f, 15f, 19f, 3f, p); c.drawLine(9f, 5f, 19f, 3f, p)
                c.drawCircle(7f, 17f, 2.2f, p); c.drawCircle(17f, 15f, 2.2f, p) }
            "device" -> { rr(7f, 2.5f, 17f, 21.5f, 2.5f); c.drawLine(11f, 18.5f, 13f, 18.5f, p) }
            "brain" -> { c.drawCircle(9f, 9f, 4f, p); c.drawCircle(15f, 9f, 4f, p); c.drawCircle(9f, 15f, 4f, p); c.drawCircle(15f, 15f, 4f, p)
                c.drawLine(12f, 5f, 12f, 19f, p) }
            "camera" -> { rr(3f, 7f, 21f, 19f); c.drawCircle(12f, 13f, 3.5f, p); path.moveTo(8f, 7f); path.lineTo(9.5f, 4.5f)
                path.lineTo(14.5f, 4.5f); path.lineTo(16f, 7f); c.drawPath(path, p) }
            "apps" -> { rr(4f, 4f, 10f, 10f, 2f); rr(14f, 4f, 20f, 10f, 2f); rr(4f, 14f, 10f, 20f, 2f); rr(14f, 14f, 20f, 20f, 2f) }
            "notes" -> { rr(5f, 3f, 19f, 21f, 2f); c.drawLine(8f, 8f, 16f, 8f, p); c.drawLine(8f, 12f, 16f, 12f, p); c.drawLine(8f, 16f, 13f, 16f, p) }
            "cart" -> { path.moveTo(3f, 4f); path.lineTo(6f, 4f); path.lineTo(8f, 15f); path.lineTo(18f, 15f); path.lineTo(20f, 7f); path.lineTo(7f, 7f)
                c.drawPath(path, p); c.drawCircle(9f, 19f, 1.5f, p); c.drawCircle(17f, 19f, 1.5f, p) }
            "school" -> { path.moveTo(2f, 9f); path.lineTo(12f, 4f); path.lineTo(22f, 9f); path.lineTo(12f, 14f); path.close(); c.drawPath(path, p)
                c.drawLine(6f, 11f, 6f, 17f, p); path.reset(); path.moveTo(6f, 17f); path.cubicTo(9f, 20f, 15f, 20f, 18f, 17f); path.lineTo(18f, 11f); c.drawPath(path, p) }
            "learn" -> { path.moveTo(3f, 5f); path.lineTo(10f, 5f); path.cubicTo(11f, 5f, 12f, 6f, 12f, 7f); path.lineTo(12f, 20f)
                path.cubicTo(12f, 19f, 11f, 18f, 10f, 18f); path.lineTo(3f, 18f); path.close()
                path.moveTo(21f, 5f); path.lineTo(14f, 5f); path.cubicTo(13f, 5f, 12f, 6f, 12f, 7f); path.moveTo(12f, 20f)
                path.cubicTo(12f, 19f, 13f, 18f, 14f, 18f); path.lineTo(21f, 18f); path.lineTo(21f, 5f); c.drawPath(path, p) }
            "bolt" -> { path.moveTo(13f, 2f); path.lineTo(5f, 13f); path.lineTo(11f, 13f); path.lineTo(10f, 22f); path.lineTo(19f, 10f)
                path.lineTo(13f, 10f); path.close(); c.drawPath(path, p) }
            "shield" -> { path.moveTo(12f, 3f); path.lineTo(20f, 6f); path.lineTo(20f, 12f); path.cubicTo(20f, 17f, 16f, 20f, 12f, 21.5f)
                path.cubicTo(8f, 20f, 4f, 17f, 4f, 12f); path.lineTo(4f, 6f); path.close(); c.drawPath(path, p)
                path.reset(); path.moveTo(8.5f, 12f); path.lineTo(11f, 14.5f); path.lineTo(15.5f, 9.5f); c.drawPath(path, p) }
            "gear" -> { c.drawCircle(12f, 12f, 3f, p); for (i in 0 until 8) { val a = Math.toRadians(i * 45.0)
                c.drawLine(12f + cos(a).toFloat() * 6f, 12f + sin(a).toFloat() * 6f, 12f + cos(a).toFloat() * 9f, 12f + sin(a).toFloat() * 9f, p) }
                c.drawCircle(12f, 12f, 6f, p) }
            "home" -> { path.moveTo(3f, 11f); path.lineTo(12f, 3.5f); path.lineTo(21f, 11f); path.moveTo(5.5f, 9.5f); path.lineTo(5.5f, 20f)
                path.lineTo(18.5f, 20f); path.lineTo(18.5f, 9.5f); c.drawPath(path, p); rr(10f, 14f, 14f, 20f, 1f) }
            "tools" -> { c.drawCircle(7f, 7f, 3f, p); c.drawCircle(17f, 17f, 3f, p); c.drawLine(10f, 7f, 21f, 7f, p); c.drawLine(3f, 17f, 14f, 17f, p)
                c.drawLine(17f, 3f, 17f, 11f, p); c.drawLine(7f, 13f, 7f, 21f, p) }
            "mic" -> { rr(9f, 3f, 15f, 14f, 3f); path.moveTo(5.5f, 11f); path.cubicTo(5.5f, 19f, 18.5f, 19f, 18.5f, 11f); c.drawPath(path, p)
                c.drawLine(12f, 17f, 12f, 21f, p) }
            "pin" -> { path.moveTo(12f, 21f); path.cubicTo(5f, 14f, 5f, 10f, 5f, 9f); path.cubicTo(5f, 5f, 8f, 2.5f, 12f, 2.5f)
                path.cubicTo(16f, 2.5f, 19f, 5f, 19f, 9f); path.cubicTo(19f, 10f, 19f, 14f, 12f, 21f); c.drawPath(path, p); c.drawCircle(12f, 9f, 2.5f, p) }
            "battery" -> { rr(3f, 7f, 19f, 17f, 2f); c.drawLine(21f, 10f, 21f, 14f, p); p.style = Paint.Style.FILL; rr(5f, 9f, 13f, 15f, 1f); p.style = Paint.Style.STROKE }
            "wifi" -> { c.drawArc(RectF(2f, 5f, 22f, 25f), 225f, 90f, false, p); c.drawArc(RectF(5.5f, 8.5f, 18.5f, 21.5f), 225f, 90f, false, p)
                c.drawArc(RectF(9f, 12f, 15f, 18f), 225f, 90f, false, p); c.drawPoint(12f, 18.5f, p) }
            "storage" -> { rr(4f, 4f, 20f, 20f, 3f); c.drawLine(8f, 15f, 16f, 15f, p); c.drawLine(8f, 9f, 11f, 9f, p) }
            "bluetooth" -> { path.moveTo(7f, 7f); path.lineTo(17f, 16f); path.lineTo(12f, 20.5f); path.lineTo(12f, 3.5f); path.lineTo(17f, 8f)
                path.lineTo(7f, 17f); c.drawPath(path, p) }
            "timer" -> { c.drawCircle(12f, 13f, 8f, p); c.drawLine(12f, 13f, 12f, 8.5f, p); c.drawLine(10f, 2.5f, 14f, 2.5f, p) }
            "flash" -> { path.moveTo(8f, 3f); path.lineTo(16f, 3f); path.lineTo(16f, 8f); path.lineTo(13.5f, 12f); path.lineTo(13.5f, 21f)
                path.lineTo(10.5f, 21f); path.lineTo(10.5f, 12f); path.lineTo(8f, 8f); path.close(); c.drawPath(path, p) }
            "sun" -> { c.drawCircle(12f, 12f, 4f, p); for (i in 0 until 8) { val a = Math.toRadians(i * 45.0)
                c.drawLine(12f + cos(a).toFloat() * 6.5f, 12f + sin(a).toFloat() * 6.5f, 12f + cos(a).toFloat() * 9f, 12f + sin(a).toFloat() * 9f, p) } }
            "song" -> { c.drawCircle(12f, 12f, 9f, p); c.drawCircle(12f, 12f, 3f, p); c.drawArc(RectF(6f, 6f, 18f, 18f), 200f, 60f, false, p) }
            else -> c.drawCircle(12f, 12f, 8f, p)
        }
        c.restore()
    }

    @Suppress("unused") private val glow = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
}
