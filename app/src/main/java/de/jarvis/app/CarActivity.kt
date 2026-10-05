package de.jarvis.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextClock
import de.jarvis.app.Ui.Companion.BG
import de.jarvis.app.Ui.Companion.MUTED
import de.jarvis.app.Ui.Companion.RED

/** Auto-Modus: riesige Knöpfe, wenig Ablenkung. Startet automatisch, wenn sich das Auto per Bluetooth verbindet. */
class CarActivity : Activity() {
    private lateinit var ui: Ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this); Ui.load(prefs); ui = Ui(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG; window.navigationBarColor = BG; Ui.lightBars(window)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(16), ui.px(16), ui.px(16), ui.px(16)); setBackgroundColor(BG) }
        val head = ui.row()
        head.addView(ui.weighted(TextClock(this).apply { format24Hour = "HH:mm"; format12Hour = "HH:mm"; textSize = 44f; setTextColor(Ui.FG)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }))
        head.addView(ui.text("AUTO-MODUS", 13f, RED, true).apply { letterSpacing = 0.2f })
        col.addView(head)
        val grid = GridLayout(this).apply { columnCount = 2 }
        fun big(icon: String, label: String, act: () -> Unit) {
            val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = ui.round(Ui.CARD2, 22, Ui.LINE); setPadding(0, ui.px(26), 0, ui.px(26)); setOnClickListener { act() } }
            b.addView(IconView(this, icon, RED), LinearLayout.LayoutParams(ui.px(54), ui.px(54)))
            b.addView(ui.text(label, 18f, Ui.FG, true).apply { gravity = Gravity.CENTER; setPadding(0, ui.px(8), 0, 0) })
            grid.addView(b, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; height = 0; setMargins(ui.px(6), ui.px(6), ui.px(6), ui.px(6)) })
        }
        big("mic", "Jarvis") { startActivity(Intent(this, JarvisActivity::class.java)) }
        big("home", "Nach Hause") {
            val q = if (prefs.homeSet) "${prefs.homeLat},${prefs.homeLon}" else "Zuhause"
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q"))) } catch (_: Exception) {}
        }
        big("music", "Play / Pause") { media(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        big("bolt", "Nächstes Lied") { media(android.view.KeyEvent.KEYCODE_MEDIA_NEXT) }
        big("phone", "Anrufen") { try { startActivity(Intent(Intent.ACTION_DIAL)) } catch (_: Exception) {} }
        big("message", "Nachrichten") { startActivity(Intent(this, JarvisActivity::class.java)) }
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(ui.text("Nachrichten vorlesen: auf „Nachrichten“ tippen und „Lies meine Nachrichten vor“ sagen. Während der Fahrt nur per Stimme bedienen.", 12f, MUTED).apply { setPadding(ui.px(6), ui.px(8), 0, 0) })
        col.addView(ui.row().apply {
            addView(ui.weighted(ui.text("Auto-Gerät: " + prefs.carDevice.ifBlank { "nicht festgelegt" }, 13f, MUTED)))
            addView(ui.text("Ändern", 13f, RED, true).apply { setPadding(ui.px(12), ui.px(10), ui.px(4), ui.px(10)); setOnClickListener {
                val f = EditText(this@CarActivity).apply { hint = "Bluetooth-Name des Autos, z. B. VW Radio"; setText(prefs.carDevice) }
                AlertDialog.Builder(this@CarActivity).setTitle("Auto-Modus automatisch starten bei …").setView(f)
                    .setPositiveButton("Speichern") { _, _ -> prefs.carDevice = f.text.toString(); recreate() }.show() } })
            addView(ui.text("Beenden", 13f, Ui.FG, true).apply { setPadding(ui.px(12), ui.px(10), ui.px(4), ui.px(10)); setOnClickListener { finish() } })
        })
        setContentView(col)
    }

    private fun media(code: Int) {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
    }
}
