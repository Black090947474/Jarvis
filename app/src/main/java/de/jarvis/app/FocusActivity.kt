package de.jarvis.app

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import de.jarvis.app.tools.JsonStore
import org.json.JSONObject
import kotlin.math.min

/**
 * Fokus-Timer (Pomodoro): Lernblöcke mit Pausen, großer Ring, optional „Nicht stören“.
 * „Nicht stören“ wird nur eingeschaltet, wenn du Jarvis das im Berechtigungszentrum erlaubt hast,
 * und am Ende (oder beim Abbrechen) wieder auf den vorherigen Zustand zurückgesetzt.
 */
class FocusActivity : Activity() {
    private lateinit var ui: Ui
    private val h = Handler(Looper.getMainLooper())
    private var workMin = 25; private var pauseMin = 5; private var rounds = 4; private var subject = ""
    private var round = 1; private var inPause = false; private var running = false
    private var endAt = 0L; private var remainMs = 0L; private var totalMs = 0L
    private var focusedMs = 0L; private var blockStart = 0L
    private var prevFilter = -1
    private lateinit var ring: Ring
    private lateinit var timeTxt: android.widget.TextView
    private lateinit var stateTxt: android.widget.TextView
    private lateinit var startBtn: android.widget.TextView
    private lateinit var dndChip: android.widget.TextView
    private var dnd = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this); Ui.load(prefs); ui = Ui(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Ui.BG; window.navigationBarColor = Ui.BG
        Ui.lightBars(window)
        workMin = intent.getIntExtra("minutes", 25); pauseMin = intent.getIntExtra("pause", 5)
        rounds = intent.getIntExtra("rounds", 4); subject = intent.getStringExtra("subject").orEmpty()

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.px(20), ui.px(20), ui.px(20), ui.px(20)); setBackgroundColor(Ui.BG) }
        col.addView(ui.row().apply {
            addView(ui.weighted(ui.text("FOKUS", 13f, Ui.RED, true).apply { letterSpacing = 0.25f }))
            addView(ui.text("✕", 20f, Ui.MUTED).apply { setPadding(ui.px(12), ui.px(4), ui.px(4), ui.px(4)); setOnClickListener { finish() } })
        })
        col.addView(ui.text(if (subject.isBlank()) "Konzentriert arbeiten" else subject, 24f, Ui.FG, true).apply {
            gravity = Gravity.CENTER; setPadding(0, ui.px(10), 0, 0) })
        stateTxt = ui.text("", 14f, Ui.MUTED).apply { gravity = Gravity.CENTER }
        col.addView(stateTxt)

        val frame = android.widget.FrameLayout(this)
        ring = Ring(this)
        frame.addView(ring, android.widget.FrameLayout.LayoutParams(-1, -1))
        timeTxt = ui.text("", 58f, Ui.FG).apply { gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        frame.addView(timeTxt, android.widget.FrameLayout.LayoutParams(-1, -1))
        col.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.px(16); bottomMargin = ui.px(16) })

        // Schnellwahl
        col.addView(ui.row().apply {
            gravity = Gravity.CENTER
            listOf(15, 25, 45, 60).forEach { m ->
                addView(ui.text("$m min", 13f, Ui.SOFT).apply {
                    background = ui.round(Ui.CARD2, 16, Ui.LINE); setPadding(ui.px(12), ui.px(7), ui.px(12), ui.px(7))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.px(4), 0, ui.px(4), 0) }
                    setOnClickListener { if (!running) { workMin = m; resetBlock() } }
                })
            }
        })
        dndChip = ui.text("", 13f, Ui.SOFT).apply { gravity = Gravity.CENTER; setPadding(0, ui.px(12), 0, 0)
            setOnClickListener { dnd = !dnd; updateDndChip() } }
        col.addView(dndChip)
        startBtn = ui.button("Start") { toggle() }
        col.addView(startBtn)
        col.addView(ui.button("Beenden", false) { finish() })
        setContentView(col)
        resetBlock(); updateDndChip()
        if (intent.getBooleanExtra("autostart", false)) toggle()
    }

    private fun dndAllowed(): Boolean = (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted

    private fun updateDndChip() {
        dndChip.text = when {
            !dndAllowed() -> "„Nicht stören“: nicht erlaubt – tippen zum Erlauben"
            dnd -> "🔕 „Nicht stören“ während der Lernzeit: an"
            else -> "🔔 „Nicht stören“ während der Lernzeit: aus"
        }
        if (!dndAllowed()) dndChip.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) } catch (_: Exception) {}
        } else dndChip.setOnClickListener { dnd = !dnd; updateDndChip() }
    }

    override fun onResume() { super.onResume(); if (::dndChip.isInitialized) updateDndChip() }

    private fun resetBlock() {
        totalMs = (if (inPause) pauseMin else workMin) * 60_000L
        remainMs = totalMs; render()
    }

    private fun toggle() {
        if (running) {
            remainMs = endAt - SystemClock.elapsedRealtime(); running = false
            if (!inPause) focusedMs += SystemClock.elapsedRealtime() - blockStart
            h.removeCallbacks(tick); setDnd(false); startBtn.text = "Weiter"
        } else {
            endAt = SystemClock.elapsedRealtime() + remainMs; running = true; blockStart = SystemClock.elapsedRealtime()
            if (!inPause) setDnd(true)
            startBtn.text = "Pause"; h.post(tick)
        }
        render()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            remainMs = endAt - SystemClock.elapsedRealtime()
            if (remainMs <= 0) { next(); return }
            render(); h.postDelayed(this, 250)
        }
    }

    private fun beep() = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_ACK, 400) } catch (_: Exception) { false }

    private fun next() {
        beep()
        if (!inPause) {
            focusedMs += SystemClock.elapsedRealtime() - blockStart
            if (round >= rounds) { running = false; setDnd(false); done(); return }
            inPause = true; setDnd(false)
        } else { inPause = false; round++; setDnd(true) }
        totalMs = (if (inPause) pauseMin else workMin) * 60_000L
        remainMs = totalMs; endAt = SystemClock.elapsedRealtime() + remainMs; blockStart = SystemClock.elapsedRealtime()
        h.post(tick)
    }

    private fun done() {
        saveStats(); focusedMs = 0
        stateTxt.text = "Geschafft! Alle $rounds Runden erledigt."
        timeTxt.text = "✓"; ring.progress = 1f; ring.invalidate(); startBtn.text = "Nochmal"
        round = 1; inPause = false; remainMs = workMin * 60_000L; totalMs = remainMs
    }

    private fun saveStats() {
        val min = (focusedMs / 60_000L).toInt()
        if (min <= 0) return
        val store = JsonStore(this)
        val list = store.list("focus_log")
        list.add(JSONObject().put("t", System.currentTimeMillis()).put("min", min).put("subject", subject))
        store.save("focus_log", list.takeLast(500))
    }

    private fun setDnd(on: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return
        try {
            if (on && dnd) {
                if (prevFilter == -1) prevFilter = nm.currentInterruptionFilter
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            } else if (!on && prevFilter != -1) {
                nm.setInterruptionFilter(prevFilter); prevFilter = -1
            }
        } catch (_: Exception) {}
    }

    private fun render() {
        val s = (maxOf(0L, remainMs) + 999) / 1000
        timeTxt.text = "%d:%02d".format(s / 60, s % 60)
        stateTxt.text = (if (inPause) "Pause" else "Lernblock") + " · Runde $round von $rounds" + (if (!running) " · angehalten" else "")
        ring.progress = if (totalMs > 0) 1f - remainMs.toFloat() / totalMs else 0f
        ring.pause = inPause; ring.invalidate()
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        if (running && !inPause) focusedMs += SystemClock.elapsedRealtime() - blockStart
        saveStats(); setDnd(false)
        super.onDestroy()
    }

    class Ring(ctx: Context) : View(ctx) {
        var progress = 0f; var pause = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val s = min(width, height) * 0.86f
            val r = RectF((width - s) / 2, (height - s) / 2, (width + s) / 2, (height + s) / 2)
            p.strokeWidth = 14 * d; p.color = Ui.LINE; p.shader = null
            c.drawArc(r, 0f, 360f, false, p)
            val col = if (pause) Ui.GREEN else Ui.RED
            p.shader = android.graphics.SweepGradient(width / 2f, height / 2f, intArrayOf(col, if (pause) Ui.GREEN else Ui.ACCENT2, col), null)
            c.save(); c.rotate(-90f, width / 2f, height / 2f)
            c.drawArc(r, 0f, 360f * progress.coerceIn(0f, 1f), false, p)
            c.restore()
            p.shader = null; p.strokeWidth = 2 * d; p.color = Color.argb(60, Color.red(col), Color.green(col), Color.blue(col))
            val inner = RectF(r.left + 22 * d, r.top + 22 * d, r.right - 22 * d, r.bottom - 22 * d)
            c.drawArc(inner, 0f, 360f, false, p)
        }
    }

    companion object {
        /** Fokus-Minuten der letzten [days] Tage (für Statistik und Briefing). */
        fun minutes(ctx: Context, days: Int = 7): Int {
            val since = System.currentTimeMillis() - days * 86_400_000L
            return JsonStore(ctx).list("focus_log").filter { it.optLong("t") >= since }.sumOf { it.optInt("min") }
        }
    }
}
