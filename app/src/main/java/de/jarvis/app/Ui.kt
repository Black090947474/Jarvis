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

    fun text(t: CharSequence, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(a).apply {
        text = t; textSize = size * fontScale; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    fun section(t: String) = text(t.uppercase(), 12f, MUTED, true).apply {
        letterSpacing = 0.14f
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22); bottomMargin = px(4) }
    }

    fun card(vertical: Boolean = true) = LinearLayout(a).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(CARD2, CARD)).apply {
            cornerRadius = 18 * dp; setStroke(px(1), LINE) }
        setPadding(px(16), px(14), px(16), px(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) }
    }

    fun chip(t: String, ok: Boolean) = text(t, 12f, if (ok) GREEN else MUTED).apply {
        background = round(if (ok) Color.argb(40, 60, 210, 120) else Color.argb(40, 140, 140, 150), 20)
        setPadding(px(10), px(5), px(10), px(5))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(6); topMargin = px(6) }
    }

    fun button(t: String, filled: Boolean = true, onClick: () -> Unit) = text(t, 15f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        background = if (filled) gradient(RED, ACCENT2, 14) else round(CARD2, 14, LINE)
        setPadding(px(14), px(12), px(14), px(12))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) }
    }

    fun row() = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun weighted(v: View, w: Float = 1f) = v.apply { layoutParams = LinearLayout.LayoutParams(0, -2, w) }

    companion object {
        /** Farbschema: "BLAU" (Command Center, Standard) oder "LILA". Wird beim Start aus den Einstellungen gesetzt. */
        @JvmStatic var theme = "BLAU"
        @JvmStatic var accent = "AUTO"
        @JvmStatic var fontScale = 1f
        @JvmStatic var animations = true
        private val blue get() = theme != "LILA"
        private val black get() = theme == "SCHWARZ"

        val BG get() = if (black) Color.rgb(0, 0, 0) else if (blue) Color.rgb(4, 9, 17) else Color.rgb(10, 5, 16)
        val CARD get() = if (black) Color.rgb(14, 14, 18) else if (blue) Color.rgb(9, 20, 34) else Color.rgb(22, 11, 31)
        val CARD2 get() = if (black) Color.rgb(22, 22, 28) else if (blue) Color.rgb(12, 27, 45) else Color.rgb(30, 15, 42)
        val LINE get() = if (black) Color.rgb(44, 44, 54) else if (blue) Color.rgb(22, 52, 82) else Color.rgb(58, 28, 78)
        /** Akzentfarbe (heißt aus historischen Gründen RED). */
        val RED get() = when (accent) {
            "CYAN" -> Color.rgb(0, 210, 255); "GRUEN" -> Color.rgb(40, 220, 140); "ROT" -> Color.rgb(255, 60, 80); "GOLD" -> Color.rgb(255, 190, 60)
            else -> if (blue) Color.rgb(42, 168, 255) else Color.rgb(214, 64, 255)
        }
        val ACCENT2 get() = when (accent) {
            "CYAN" -> Color.rgb(120, 240, 255); "GRUEN" -> Color.rgb(150, 255, 200); "ROT" -> Color.rgb(255, 140, 90); "GOLD" -> Color.rgb(255, 230, 140)
            else -> if (blue) Color.rgb(0, 229, 255) else Color.rgb(255, 72, 176)
        }
        val MUTED get() = if (blue) Color.rgb(120, 146, 172) else Color.rgb(150, 128, 166)
        val SOFT = Color.rgb(205, 214, 226)
        val GREEN = Color.rgb(50, 220, 130)
        val AMBER = Color.rgb(255, 184, 60)

        fun load(p: Prefs) { theme = p.uiTheme; accent = p.accent; fontScale = p.fontScale; animations = p.animations }
    }
}
