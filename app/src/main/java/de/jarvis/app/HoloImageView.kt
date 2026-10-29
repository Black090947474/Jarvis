package de.jarvis.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.min
import kotlin.math.sin

/**
 * Flaches Hologramm aus einem Foto (für Themen ohne eigenes 3D-Modell):
 * Bild wird blau eingefärbt, mit Scan-Linien, Flackern, weichem Rand und Projektor-Licht gezeigt.
 */
class HoloImageView(context: Context) : View(context) {
    private var bmp: Bitmap? = null
    var title: String = ""
    private val start = SystemClock.elapsedRealtime()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dp = resources.displayMetrics.density

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    fun show(b: Bitmap?, t: String) { bmp = b; title = t; visibility = if (b != null) VISIBLE else GONE; invalidate() }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        val t = (SystemClock.elapsedRealtime() - start) / 1000f
        val w = width.toFloat(); val h = height.toFloat()
        val emY = h * 0.9f
        // Projektor-Kegel
        p.shader = LinearGradient(0f, emY, 0f, 0f, Color.argb(70, 0, 220, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        val path = android.graphics.Path().apply { moveTo(w * 0.36f, emY); lineTo(w * 0.64f, emY); lineTo(w * 0.98f, h * 0.05f); lineTo(w * 0.02f, h * 0.05f); close() }
        c.drawPath(path, p); p.shader = null
        // Bild als Hologramm
        val maxW = w * 0.82f; val maxH = h * 0.72f
        val s = min(maxW / b.width, maxH / b.height)
        val bw = b.width * s; val bh = b.height * s
        val bob = sin(t * 1.3f) * 4 * dp
        val r = RectF((w - bw) / 2, emY - bh - 18 * dp + bob, (w + bw) / 2, emY - 18 * dp + bob)
        val layer = c.saveLayer(r, null)
        val cm = ColorMatrix(floatArrayOf(
            0.10f, 0.20f, 0.05f, 0f, 0f,
            0.30f, 0.55f, 0.12f, 0f, 10f,
            0.35f, 0.65f, 0.15f, 0f, 40f,
            0f, 0f, 0f, 1f, 0f))
        p.colorFilter = ColorMatrixColorFilter(cm)
        p.alpha = (215 + 40 * sin(t * 23f)).toInt().coerceIn(0, 255)
        c.drawBitmap(b, null, r, p)
        p.colorFilter = null; p.alpha = 255
        // Scan-Linien
        p.color = Color.argb(90, 0, 0, 0)
        var y = r.top
        while (y < r.bottom) { c.drawRect(r.left, y, r.right, y + 1.2f * dp, p); y += 3.5f * dp }
        // wandernder heller Streifen
        val by = r.top + ((t * 0.35f) % 1f) * r.height()
        p.shader = LinearGradient(0f, by - 14 * dp, 0f, by + 14 * dp, intArrayOf(Color.TRANSPARENT, Color.argb(110, 190, 250, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(r.left, by - 14 * dp, r.right, by + 14 * dp, p); p.shader = null
        // weicher Rand
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        p.shader = RadialGradient(r.centerX(), r.centerY(), maxOf(r.width(), r.height()) * 0.62f,
            intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(r, p); p.shader = null; p.xfermode = null
        c.restoreToCount(layer)
        // Rahmen-Ecken
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * dp; p.color = Color.rgb(0, 240, 255)
        val k = 14 * dp
        c.drawLines(floatArrayOf(r.left, r.top, r.left + k, r.top, r.left, r.top, r.left, r.top + k,
            r.right, r.bottom, r.right - k, r.bottom, r.right, r.bottom, r.right, r.bottom - k), p)
        // Emitter-Ring
        p.strokeWidth = 2 * dp; p.color = Color.argb(230, 0, 240, 255)
        c.drawOval(RectF(w * 0.32f, emY - 7 * dp, w * 0.68f, emY + 7 * dp), p)
        p.style = Paint.Style.FILL; p.color = Color.argb(70, 0, 240, 255)
        c.drawOval(RectF(w * 0.38f, emY - 4 * dp, w * 0.62f, emY + 4 * dp), p)
        if (title.isNotBlank()) {
            p.color = Color.rgb(63, 212, 255); p.textSize = 10 * dp; p.typeface = android.graphics.Typeface.MONOSPACE
            p.letterSpacing = 0.18f; p.textAlign = Paint.Align.CENTER
            c.drawText(title.uppercase(), w / 2, emY + 22 * dp, p)
            p.textAlign = Paint.Align.LEFT; p.letterSpacing = 0f
        }
        postInvalidateDelayed(40)
    }

    companion object {
        /** Sucht in der deutschen Wikipedia nach einem Bild zum Begriff. Rückgabe: Bild + Titel, oder null. */
        fun wikiImage(query: String): Pair<Bitmap, String>? {
            if (query.isBlank()) return null
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "https://de.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrlimit=1&gsrsearch=$q" +
                "&prop=pageimages&piprop=thumbnail&pithumbsize=640"
            val json = get(url) ?: return null
            val pages = JSONObject(json).optJSONObject("query")?.optJSONObject("pages") ?: return null
            val page = pages.keys().asSequence().map { pages.getJSONObject(it) }.firstOrNull() ?: return null
            val src = page.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.isNotBlank() } ?: return null
            val bytes = getBytes(src) ?: return null
            val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            return b to page.optString("title")
        }

        private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 8000
            setRequestProperty("User-Agent", "JarvisApp/20 (private Nutzung)")
        }
        private fun get(url: String): String? = try { open(url).run { if (responseCode == 200) inputStream.bufferedReader().readText() else null } } catch (_: Exception) { null }
        private fun getBytes(url: String): ByteArray? = try { open(url).run { if (responseCode == 200) inputStream.readBytes() else null } } catch (_: Exception) { null }
    }
}
