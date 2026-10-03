package de.jarvis.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

/** Der leuchtende, pulsierende "Kern" von Jarvis. */
class CoreView(context: Context) : View(context) {

    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    var mode: Mode = Mode.IDLE
        set(value) { field = value; invalidate() }

    /** Lautstärke vom Mikrofon (0..1), lässt den Kern beim Zuhören mitpulsieren. */
    var level: Float = 0f
        set(value) { field = field * 0.6f + value.coerceIn(0f, 1f) * 0.4f }

    private var t = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t += 0.016f; invalidate() }
    }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.NORMAL)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) } // nötig für den Glow-Effekt

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    private fun color(): Int = when (mode) {
        Mode.IDLE -> Color.rgb(140, 30, 40)
        Mode.LISTENING -> Color.rgb(255, 45, 60)
        Mode.THINKING -> Color.rgb(255, 140, 40)
        Mode.SPEAKING -> Color.rgb(60, 200, 255)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) * 0.30f
        val c = color()

        val pulse = when (mode) {
            Mode.IDLE -> 0.03f * sin(t * 1.5f)
            Mode.LISTENING -> 0.04f * sin(t * 4f) + level * 0.25f
            Mode.THINKING -> 0.02f * sin(t * 8f)
            Mode.SPEAKING -> 0.08f * sin(t * 9f) * sin(t * 2.3f)
        }
        val r = base * (1f + pulse)

        // Äußerer Glow
        glow.color = c; glow.alpha = 120; glow.strokeWidth = 24f
        canvas.drawCircle(cx, cy, r * 1.15f, glow)

        // Ringe
        ring.color = c
        for (i in 0..3) {
            ring.alpha = 220 - i * 50
            ring.strokeWidth = (6 - i).toFloat()
            canvas.drawCircle(cx, cy, r * (1.15f - i * 0.17f), ring)
        }

        // Rotierende Bögen (beim Nachdenken schneller)
        val speed = if (mode == Mode.THINKING) 400f else 60f
        ring.alpha = 255; ring.strokeWidth = 5f
        for (i in 0..2) {
            val rr = r * (1.32f + i * 0.08f)
            arc.set(cx - rr, cy - rr, cx + rr, cy + rr)
            val start = (t * speed * (if (i % 2 == 0) 1 else -1) + i * 120f) % 360f
            canvas.drawArc(arc, start, 50f + i * 15f, false, ring)
        }

        // Leuchtender Kern
        val coreR = r * 0.38f
        fill.shader = RadialGradient(cx, cy, coreR, intArrayOf(Color.WHITE, c, Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, coreR, fill)
    }
}
