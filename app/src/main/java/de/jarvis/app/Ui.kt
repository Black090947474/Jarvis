package de.jarvis.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Gemeinsame Bausteine für Dashboard und Berechtigungszentrum (dunkel, rote Akzente wie der Rest der App). */
class Ui(private val a: Activity) {
    val dp = a.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()

    fun round(color: Int, radius: Int = 18, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius * dp
        if (stroke != null) setStroke(px(1), stroke)
    }

    fun gradient(c1: Int, c2: Int, radius: Int = 18) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply { cornerRadius = radius * dp }

    fun text(t: CharSequence, size: Float, color: Int = FG, bold: Boolean = false) = TextView(a).apply {
        text = t; textSize = size * fontScale; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    fun section(t: String) = text(t, 16f, FG, true).apply {
        letterSpacing = 0f
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22); bottomMargin = px(4) }
    }

    /** Glas-Kachel: weicher Verlauf, feiner heller Rand, große Rundung (moderner Look). */
    fun glass(radius: Int = 24): android.graphics.drawable.Drawable =
        if (light) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.WHITE, CARD2)).apply { cornerRadius = radius * dp; setStroke(px(1), LINE) }
        else GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.argb(235, Color.red(CARD2), Color.green(CARD2), Color.blue(CARD2)),
            Color.argb(215, Color.red(CARD), Color.green(CARD), Color.blue(CARD)))).apply {
            cornerRadius = radius * dp; setStroke(px(1), Color.argb(26, 255, 255, 255)) }

    /** Hintergrund mit sanftem „Polarlicht“ (Cyan oben links, Blau rechts, Violett unten). */
    fun aurora(): android.graphics.drawable.Drawable {
        if (light) return android.graphics.drawable.ColorDrawable(BG)
        val w = a.resources.displayMetrics.widthPixels.toFloat()
        fun glow(c: Int, alpha: Int, cx: Float, cy: Float, r: Float) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT; colors = intArrayOf(Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c)), Color.argb(0, Color.red(c), Color.green(c), Color.blue(c)))
            setGradientCenter(cx, cy); gradientRadius = r }
        return android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(BG),
            glow(RED, 46, 0.1f, 0.03f, w * 0.85f), glow(ACCENT2, 50, 1f, 0.22f, w * 0.8f), glow(ACCENT3, 38, 0.5f, 1f, w * 0.95f)))
    }

    fun card(vertical: Boolean = true) = LinearLayout(a).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        background = glass(22)
        setPadding(px(16), px(14), px(16), px(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) }
    }

    fun chip(t: String, ok: Boolean) = text(t, 12f, if (ok) (if (light) Color.rgb(20, 150, 80) else GREEN) else MUTED).apply {
        background = round(if (ok) Color.argb(40, 60, 210, 120) else Color.argb(40, 140, 140, 150), 20)
        setPadding(px(10), px(5), px(10), px(5))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(6); topMargin = px(6) }
    }

    fun button(t: String, filled: Boolean = true, onClick: () -> Unit) = text(t, 14.5f, if (filled && !light) Color.rgb(0, 16, 24) else Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        background = if (filled) gradient(RED, ACCENT2, 14) else round(CARD2, 14, Color.argb(110, Color.red(RED), Color.green(RED), Color.blue(RED)))
        minHeight = px(46)
        if (filled) elevation = 4 * dp
        if (!filled) setTextColor(FG)
        setPadding(px(14), px(12), px(14), px(12))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) }
    }

    fun row() = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false }

    fun weighted(v: View, w: Float = 1f) = v.apply { layoutParams = LinearLayout.LayoutParams(0, -2, w) }

    companion object {
        /** Farbschema: "BLAU" (Command Center, Standard) oder "LILA". Wird beim Start aus den Einstellungen gesetzt. */
        @JvmStatic var theme = "BLAU"
        @JvmStatic var accent = "AUTO"
        @JvmStatic var fontScale = 1f
        @JvmStatic var animations = true
        private val blue get() = theme != "LILA"
        private val black get() = theme == "SCHWARZ"
        /** Heller Modus (weißer Hintergrund, dunkle Schrift). */
        val light get() = theme == "HELL"

        /** Haupt-Textfarbe (weiß im Dunkeln, fast schwarz im hellen Modus). */
        val FG get() = if (light) Color.rgb(20, 24, 38) else Color.WHITE

        /** Statusleiste/Navigationsleiste: dunkle Symbole im hellen Modus. */
        fun lightBars(w: android.view.Window) {
            if (!light) return
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = w.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        val BG get() = if (light) Color.rgb(244, 245, 251) else if (black) Color.rgb(0, 0, 0) else if (blue) Color.rgb(3, 8, 20) else Color.rgb(10, 5, 16)
        val CARD get() = if (light) Color.rgb(255, 255, 255) else if (black) Color.rgb(14, 14, 18) else if (blue) Color.rgb(13, 21, 38) else Color.rgb(22, 11, 31)
        val CARD2 get() = if (light) Color.rgb(236, 236, 248) else if (black) Color.rgb(22, 22, 28) else if (blue) Color.rgb(22, 32, 54) else Color.rgb(30, 15, 42)
        val LINE get() = if (light) Color.rgb(214, 214, 230) else if (black) Color.rgb(44, 44, 54) else if (blue) Color.rgb(36, 48, 74) else Color.rgb(58, 28, 78)
        /** Akzentfarbe (heißt aus historischen Gründen RED). */
        val RED get() = when (accent) {
            "CYAN" -> Color.rgb(0, 210, 255); "GRUEN" -> Color.rgb(40, 220, 140); "ROT" -> Color.rgb(255, 60, 80); "GOLD" -> Color.rgb(255, 190, 60)
            else -> if (light) Color.rgb(118, 70, 255) else if (blue) Color.rgb(0, 229, 255) else Color.rgb(214, 64, 255)
        }
        val ACCENT2 get() = when (accent) {
            "CYAN" -> Color.rgb(120, 240, 255); "GRUEN" -> Color.rgb(150, 255, 200); "ROT" -> Color.rgb(255, 140, 90); "GOLD" -> Color.rgb(255, 230, 140)
            else -> if (light) Color.rgb(214, 64, 255) else if (blue) Color.rgb(61, 107, 255) else Color.rgb(255, 72, 176)
        }
        /** Dritte Verlaufsfarbe (Violett) für den modernen Look. */
        val ACCENT3 get() = if (light) Color.rgb(255, 72, 176) else Color.rgb(138, 92, 255)
        val MUTED get() = if (light) Color.rgb(110, 112, 132) else if (blue) Color.rgb(127, 147, 173) else Color.rgb(150, 128, 166)
        val SOFT get() = if (light) Color.rgb(60, 64, 84) else Color.rgb(205, 214, 226)
        val GREEN = Color.rgb(50, 220, 130)
        val AMBER = Color.rgb(255, 184, 60)

        fun load(p: Prefs) { theme = p.uiTheme; accent = p.accent; fontScale = p.fontScale; animations = p.animations }
    }
}
