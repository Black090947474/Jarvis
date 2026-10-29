package de.jarvis.app

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

/** HUD-Kern der Startseite: Skalen, drehende Bögen, Radar-Strahl, leuchtender Kern und vier Anzeigen. */
class HudCoreView(context: Context) : View(context) {
    /** Vier Anzeigen: oben links, oben rechts, unten links, unten rechts – jeweils (Titel, Wert). */
    var callouts: List<Pair<String, String>> = emptyList(); set(v) { field = v; invalidate() }
    private val dp = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; letterSpacing = 0.12f }
    private val start = SystemClock.uptimeMillis()
    private val sweepM = Matrix()

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    override fun onDraw(c: Canvas) {
        val t = if (Ui.animations) (SystemClock.uptimeMillis() - start) / 1000f else 0.6f
        val cx = width / 2f; val cy = height / 2f
        val r = min(width * 0.5f, height / 2f) * 0.86f
        val acc = Ui.RED; val acc2 = Ui.ACCENT2
        p.style = Paint.Style.STROKE; p.shader = null; p.maskFilter = null
        // Radar-Strahl
        p.style = Paint.Style.FILL
        val sweep = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, alpha(acc, 70), Color.TRANSPARENT), floatArrayOf(0f, 0.8f, 0.985f, 1f))
        sweepM.setRotate((t * 50f) % 360f, cx, cy); sweep.setLocalMatrix(sweepM)
        p.shader = sweep
        c.drawCircle(cx, cy, r * 0.98f, p); p.shader = null
        // Skala außen
        p.style = Paint.Style.STROKE
        for (i in 0 until 90) {
            val a = Math.toRadians(i * 4.0); val long = i % 6 == 0
            val r1 = r; val r2 = r - (if (long) 9 else 4) * dp
            p.color = if (long) acc else alpha(acc, 100); p.strokeWidth = (if (long) 1.6f else 1f) * dp
            c.drawLine(cx + cos(a).toFloat() * r1, cy + sin(a).toFloat() * r1, cx + cos(a).toFloat() * r2, cy + sin(a).toFloat() * r2, p)
        }
        // Leuchtender Bogen (dreht sich)
        val o1 = RectF(cx - r * 0.88f, cy - r * 0.88f, cx + r * 0.88f, cy + r * 0.88f)
        p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 3f * dp
        p.shader = SweepGradient(cx, cy, intArrayOf(acc2, acc, acc2), null)
        p.maskFilter = BlurMaskFilter(4 * dp, BlurMaskFilter.Blur.SOLID)
        val rot = t * 18f
        c.drawArc(o1, rot - 30f, 140f, false, p); c.drawArc(o1, rot + 150f, 40f, false, p)
        p.shader = null; p.maskFilter = null; p.strokeCap = Paint.Cap.BUTT
        // Punktring
        p.color = alpha(acc, 85); p.strokeWidth = 8 * dp
        p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(2 * dp, 5 * dp), 0f)
        c.drawCircle(cx, cy, r * 0.78f, p); p.pathEffect = null
        // Innerer gestrichelter Ring (gegenläufig)
        val o2 = RectF(cx - r * 0.66f, cy - r * 0.66f, cx + r * 0.66f, cy + r * 0.66f)
        p.color = alpha(acc, 210); p.strokeWidth = 1.4f * dp
        for (k in 0 until 4) c.drawArc(o2, -t * 25f + k * 90f, 58f, false, p)
        // Segmentring
        val o3 = RectF(cx - r * 0.48f, cy - r * 0.48f, cx + r * 0.48f, cy + r * 0.48f)
        p.strokeWidth = 5 * dp
        for (k in 0 until 24) { p.color = if (k % 3 == 0) acc else alpha(acc, 70); c.drawArc(o3, k * 15f + t * 8f, 11f, false, p) }
        // Kern
        val breathe = 1f + 0.05f * sin(t * 1.9f)
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, r * 0.42f * breathe, intArrayOf(Color.WHITE, alpha(acc2, 230), alpha(acc, 120), Color.TRANSPARENT),
            floatArrayOf(0f, 0.22f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 0.42f * breathe, p); p.shader = null
        p.style = Paint.Style.STROKE; p.color = Color.argb(235, 235, 255, 255); p.strokeWidth = 1.5f * dp
        c.drawCircle(cx, cy, r * 0.26f, p)
        val tri = Path(); val tr = r * 0.15f
        for (k in 0 until 3) { val a = Math.toRadians(-90.0 + k * 120); val x = cx + cos(a).toFloat() * tr; val y = cy + sin(a).toFloat() * tr; if (k == 0) tri.moveTo(x, y) else tri.lineTo(x, y) }
        tri.close(); c.drawPath(tri, p)
        // Anzeigen mit Linien
        val pos = listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)
        callouts.take(4).forEachIndexed { i, (title, value) ->
            val (sx, sy) = pos[i]
            val ang = Math.toRadians(if (sx < 0) (if (sy < 0) 215.0 else 145.0) else (if (sy < 0) 325.0 else 35.0))
            val ax = cx + cos(ang).toFloat() * r * 0.8f; val ay = cy + sin(ang).toFloat() * r * 0.8f
            val ex = cx + sx * r * 1.12f; val ey = cy + sy * r * 0.72f
            val endX = if (sx < 0) 14 * dp else width - 14 * dp
            p.color = alpha(acc, 130); p.strokeWidth = 1f * dp
            c.drawLine(ax, ay, ex, ey, p); c.drawLine(ex, ey, endX, ey, p)
            p.style = Paint.Style.FILL; p.color = acc; c.drawCircle(ax, ay, 2.5f * dp, p); p.style = Paint.Style.STROKE
            tp.textSize = 9.5f * dp; tp.textAlign = if (sx < 0) Paint.Align.LEFT else Paint.Align.RIGHT
            tp.color = acc; c.drawText(title.uppercase(), endX, ey - 6 * dp, tp)
            tp.color = Ui.MUTED; c.drawText(value.uppercase(), endX, ey + 13 * dp, tp)
        }
        if (Ui.animations) postInvalidateDelayed(33)
    }
}

/** Sechseckiger Schnellknopf mit Symbol und Beschriftung. */
class HexButton(context: Context, private val icon: String, private val label: String) : View(context) {
    private val dp = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconView = IconView(context, icon, Ui.ACCENT2)
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (78 * dp).toInt())
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val s = 26 * dp; val cy = s + 2 * dp
        val path = Path()
        for (k in 0 until 6) { val a = Math.toRadians(-90.0 + k * 60); val x = cx + cos(a).toFloat() * s; val y = cy + sin(a).toFloat() * s; if (k == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        path.close()
        p.style = Paint.Style.FILL; p.shader = LinearGradient(0f, cy - s, 0f, cy + s, alpha(Ui.RED, 45), alpha(Ui.RED, 8), Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * dp; p.color = alpha(Ui.RED, 120); c.drawPath(path, p)
        val isz = (20 * dp).toInt()
        iconView.layout(0, 0, isz, isz)
        c.save(); c.translate(cx - isz / 2f, cy - isz / 2f); iconView.draw(c); c.restore()
        p.style = Paint.Style.FILL; p.color = Ui.SOFT; p.textSize = 9 * dp * Ui.fontScale; p.typeface = Typeface.MONOSPACE
        p.letterSpacing = 0.14f; p.textAlign = Paint.Align.CENTER
        c.drawText(label.uppercase(), cx, cy + s + 15 * dp, p)
    }
}

/** Zeitleiste „Heute“: Fortschritt bis jetzt und die nächsten Termine als Punkte. */
class TimelineView(context: Context) : View(context) {
    data class Item(val at: Long, val label: String, val warn: Boolean = false)
    var items: List<Item> = emptyList(); set(v) { field = v; invalidate() }
    private val dp = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (54 * dp).toInt())
    override fun onDraw(c: Canvas) {
        val now = System.currentTimeMillis()
        val day = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val from = day + 7 * 3_600_000L; val to = day + 22 * 3_600_000L
        fun x(ts: Long) = 6 * dp + ((ts - from).toFloat() / (to - from)).coerceIn(0f, 1f) * (width - 12 * dp)
        val y = 12 * dp
        p.style = Paint.Style.FILL; p.color = alpha(Ui.RED, 40); c.drawRect(6 * dp, y - 1 * dp, width - 6 * dp, y + 1 * dp, p)
        val nx = x(now)
        p.shader = LinearGradient(0f, 0f, nx, 0f, Ui.ACCENT2, Ui.RED, Shader.TileMode.CLAMP)
        c.drawRect(6 * dp, y - 1 * dp, nx, y + 1 * dp, p); p.shader = null
        p.color = Ui.FG; c.drawRect(nx - 1 * dp, y - 8 * dp, nx + 1 * dp, y + 8 * dp, p)
        p.textSize = 10 * dp * Ui.fontScale; p.typeface = Typeface.MONOSPACE
        var lastRight = -1f
        for (it in items.sortedBy { it.at }.filter { it.at >= now - 1_800_000L }.take(4)) {
            val ix = x(it.at)
            val col = if (it.warn) Ui.AMBER else Ui.RED
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * dp; p.color = col
            if (it.warn) { c.save(); c.rotate(45f, ix, y); c.drawRect(ix - 4 * dp, y - 4 * dp, ix + 4 * dp, y + 4 * dp, p); c.restore() }
            else c.drawCircle(ix, y, 4.5f * dp, p)
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).format(java.util.Date(it.at))
            val lx = maxOf(ix - 4 * dp, lastRight + 6 * dp)
            if (lx > width - 30 * dp) break
            p.style = Paint.Style.FILL; p.color = col; c.drawText(time, lx, y + 22 * dp, p)
            p.color = Ui.SOFT
            val label = it.label.take(12)
            c.drawText(label, lx, y + 36 * dp, p)
            lastRight = lx + maxOf(p.measureText(label), p.measureText(time))
        }
        if (items.isEmpty()) { p.color = Ui.MUTED; c.drawText("Heute keine Termine", 6 * dp, y + 26 * dp, p) }
    }
}

/** Runde Anzeige (z. B. Akku) mit Prozentwert. */
class GaugeView(context: Context) : View(context) {
    var value = 0; set(v) { field = v; invalidate() }
    var label = "AKKU %"
    private val dp = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat(); val cx = width / 2f; val cy = height / 2f; val r = s * 0.4f
        val o = RectF(cx - r, cy - r, cx + r, cy + r)
        p.style = Paint.Style.STROKE; p.strokeWidth = 8 * dp; p.strokeCap = Paint.Cap.ROUND
        p.color = alpha(Ui.RED, 35); c.drawArc(o, 135f, 270f, false, p)
        p.shader = SweepGradient(cx, cy, intArrayOf(Ui.ACCENT2, Ui.RED, Ui.ACCENT2), null)
        p.maskFilter = BlurMaskFilter(3 * dp, BlurMaskFilter.Blur.SOLID)
        c.drawArc(o, 135f, 270f * value.coerceIn(0, 100) / 100f, false, p)
        p.shader = null; p.maskFilter = null
        p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER
        p.color = Ui.FG; p.textSize = s * 0.22f; p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        c.drawText("$value", cx, cy + s * 0.07f, p)
        p.color = Ui.MUTED; p.textSize = 8 * dp; p.typeface = Typeface.MONOSPACE; p.letterSpacing = 0.2f
        c.drawText(label, cx, cy + s * 0.2f, p); p.letterSpacing = 0f
    }
}
