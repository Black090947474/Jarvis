package de.jarvis.app

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Jarvis-Station: das zweite Handy als Dauer-Bildschirm (am besten am Ladekabel).
 * Zeigt Kern-Animation, Uhr, Datum, Akku und führt Befehle vom Haupthandy aus.
 * Antippen = mit Jarvis reden.
 */
class StationActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var core: CoreView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var info: TextView
    private lateinit var log: TextView
    private var server: StationLink.Server? = null
    private val events = ArrayDeque<String>()

    private val BG = Color.rgb(5, 7, 13)
    private val FG = Color.rgb(232, 236, 255)
    private val MUTED = Color.rgb(120, 128, 160)
    private val ACCENT = Color.rgb(34, 224, 224)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.statusBarColor = BG; window.navigationBarColor = BG

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val thin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        val mono = Typeface.MONOSPACE

        core = CoreView(this, CoreView.styleFrom(Prefs(this).design)).apply { mode = CoreView.Mode.IDLE }
        clock = TextView(this).apply { textSize = 72f; typeface = thin; setTextColor(FG); gravity = Gravity.CENTER }
        date = TextView(this).apply { textSize = 15f; letterSpacing = 0.12f; setTextColor(MUTED); gravity = Gravity.CENTER }
        info = TextView(this).apply {
            textSize = 11f; letterSpacing = 0.14f; typeface = mono; setTextColor(ACCENT); gravity = Gravity.CENTER
        }
        log = TextView(this).apply {
            textSize = 12f; typeface = mono; setTextColor(MUTED); gravity = Gravity.CENTER; maxLines = 3
        }
        val hint = TextView(this).apply {
            text = "Antippen = mit Jarvis reden · Zurück = Station beenden"
            textSize = 11f; setTextColor(Color.argb(120, 120, 128, 160)); gravity = Gravity.CENTER
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(px(24), px(28), px(24), px(24))
            setOnClickListener {
                startActivity(Intent(this@StationActivity, JarvisActivity::class.java))
            }
        }
        fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(top) }
        root.addView(TextView(this).apply {
            text = "J.A.R.V.I.S  ·  STATION"; textSize = 12f; letterSpacing = 0.3f; typeface = mono
            setTextColor(MUTED); gravity = Gravity.CENTER
        }, lp())
        root.addView(core, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(clock, lp())
        root.addView(date, lp(2))
        root.addView(info, lp(18))
        root.addView(log, lp(10))
        root.addView(hint, lp(16))
        setContentView(root)

        val prefs = Prefs(this)
        server = StationLink.Server(this, prefs.stationCode) { e -> main.post { onEvent(e) } }.also { it.start() }
        tick()
    }

    private fun onEvent(e: String) {
        events.addLast(SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date()) + "  " + e.take(80))
        while (events.size > 3) events.removeFirst()
        log.text = events.reversed().joinToString("\n")
        core.mode = if (e.startsWith("▶")) CoreView.Mode.THINKING else CoreView.Mode.IDLE
        if (e.startsWith("✓") || e.startsWith("✕")) {
            core.mode = CoreView.Mode.SPEAKING
            main.postDelayed({ core.mode = CoreView.Mode.IDLE }, 1500)
        }
    }

    private fun tick() {
        val now = Date()
        clock.text = SimpleDateFormat("HH:mm", Locale.GERMANY).format(now)
        date.text = SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY).format(now).uppercase(Locale.GERMANY)
        val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = bat?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0) l * 100 / s else -1
        } ?: -1
        val charging = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val ip = StationLink.localIp() ?: "kein WLAN"
        info.text = "AKKU ${if (lvl >= 0) "$lvl %" else "?"}${if (charging) " ⚡" else ""}   ·   CODE ${Prefs(this).stationCode}   ·   $ip"
        main.postDelayed({ if (!isFinishing) tick() }, 10_000)
    }

    override fun onDestroy() {
        server?.stop()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
