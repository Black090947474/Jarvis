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
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    fun section(t: String) = text(t.uppercase(), 12f, MUTED, true).apply {
        letterSpacing = 0.14f
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22); bottomMargin = px(4) }
    }

    fun card(vertical: Boolean = true) = LinearLayout(a).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        background = round(CARD, 18, LINE)
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
        background = if (filled) round(RED, 14) else round(CARD2, 14, LINE)
        setPadding(px(14), px(12), px(14), px(12))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) }
    }

    fun row() = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun weighted(v: View, w: Float = 1f) = v.apply { layoutParams = LinearLayout.LayoutParams(0, -2, w) }

    companion object {
        val BG = Color.rgb(7, 7, 11)
        val CARD = Color.rgb(20, 20, 27)
        val CARD2 = Color.rgb(28, 28, 36)
        val LINE = Color.rgb(40, 40, 52)
        val RED = Color.rgb(255, 45, 60)
        val MUTED = Color.rgb(140, 140, 152)
        val SOFT = Color.rgb(200, 200, 212)
        val GREEN = Color.rgb(60, 210, 120)
        val AMBER = Color.rgb(255, 180, 60)
    }
}
