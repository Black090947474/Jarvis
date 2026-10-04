package de.jarvis.app

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import de.jarvis.app.Ui.Companion.BG
import de.jarvis.app.Ui.Companion.MUTED
import kotlin.concurrent.thread

/** Alle Apps im Jarvis-Design, mit Suche. */
class AppsActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var grid: GridLayout
    private var apps: List<Pair<String, ResolveInfo>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.load(Prefs(this)); ui = Ui(this)
        window.statusBarColor = BG; window.navigationBarColor = BG
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(18), ui.px(16), ui.px(18), ui.px(28)) }
        val head = ui.row()
        head.addView(ui.text("‹", 30f, Color.WHITE).apply { setPadding(0, 0, ui.px(16), 0); setOnClickListener { finish() } })
        head.addView(ui.text("Apps", 20f, Color.WHITE, true))
        col.addView(head)
        val search = EditText(this).apply {
            hint = "Apps durchsuchen …"; setTextColor(Color.WHITE); setHintTextColor(MUTED); isSingleLine = true
            background = ui.round(Ui.CARD2, 22, Ui.LINE); setPadding(ui.px(16), ui.px(12), ui.px(16), ui.px(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { fill(s?.toString().orEmpty()) }
            })
        }
        col.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(12) })
        grid = GridLayout(this).apply { columnCount = 4 }
        col.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(12) })
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(col) })
        thread {
            val pm = packageManager
            val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { it.loadLabel(pm).toString() to it }.sortedBy { it.first.lowercase() }
            runOnUiThread { apps = list; fill("") }
        }
    }

    private fun fill(q: String) {
        grid.removeAllViews()
        for ((label, ri) in apps.filter { q.isBlank() || it.first.contains(q, true) }) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.px(2), ui.px(8), ui.px(2), ui.px(8))
                setOnClickListener {
                    packageManager.getLaunchIntentForPackage(ri.activityInfo.packageName)?.let { startActivity(it) }
                }
            }
            cell.addView(ImageView(this).apply { setImageDrawable(ri.loadIcon(packageManager)) }, LinearLayout.LayoutParams(ui.px(48), ui.px(48)))
            cell.addView(ui.text(label, 11f, Ui.SOFT).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(0, ui.px(4), 0, 0) })
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
        }
    }
}
