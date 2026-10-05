package de.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.jarvis.app.logic.WakeMath
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Training für das Weckwort „Jarvis“: du sagst 6× „Jarvis“ und einmal einen normalen Satz.
 * Daraus entstehen Zahlen-Vorlagen deiner Stimme (keine Tonaufnahme wird gespeichert).
 * Alles passiert offline auf dem Handy.
 */
class WakeTrainActivity : Activity() {
    private lateinit var ui: Ui
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var big: TextView
    private lateinit var orb: OrbView
    private lateinit var dots: LinearLayout
    private lateinit var startBtn: TextView
    @Volatile private var cancelled = false
    private var worker: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this); Ui.load(prefs); ui = Ui(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Ui.BG; window.navigationBarColor = Ui.BG; Ui.lightBars(window)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(20), ui.px(20), ui.px(20), ui.px(28)) }
        col.addView(ui.row().apply {
            addView(ui.text("‹", 30f).apply { setPadding(0, 0, ui.px(16), 0); setOnClickListener { finish() } })
            addView(LinearLayout(this@WakeTrainActivity).apply { orientation = LinearLayout.VERTICAL
                addView(ui.text("Weckwort trainieren", 21f, Ui.FG, true)); addView(ui.text("NUR „JARVIS“ – AUF DEINE STIMME", 12f, Ui.RED)) })
        })
        col.addView(ui.text("So geht's: Tippe auf Start und sag nach jedem Signal einmal deutlich „Jarvis“ – so, wie du es später sagen willst. " +
            "Am Ende sprichst du noch einen normalen Satz ohne „Jarvis“, damit Jarvis den Unterschied lernt. Am besten in einem ruhigen Raum.", 14f, Ui.SOFT).apply {
            setPadding(0, ui.px(14), 0, 0) })
        orb = OrbView(this)
        col.addView(orb, LinearLayout.LayoutParams(ui.px(200), ui.px(200)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.px(18) })
        big = ui.text("Bereit", 26f, Ui.FG, true).apply { gravity = Gravity.CENTER }
        col.addView(big)
        status = ui.text("", 14f, Ui.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, ui.px(6), 0, 0) }
        col.addView(status)
        dots = ui.row().apply { gravity = Gravity.CENTER; setPadding(0, ui.px(12), 0, 0) }
        col.addView(dots); drawDots(0)
        startBtn = ui.button("Start") { startTraining() }
        col.addView(startBtn)
        val model = PersonalWake.load(this)
        if (model != null) {
            status.text = "Training vorhanden (${model.count} Aufnahmen" + (if (model.good) ", gute Trennung)." else ", Trennung schwach – nochmal trainieren hilft).")
            col.addView(ui.button("Training löschen", false) { PersonalWake.clear(this); status.text = "Gelöscht. Jarvis nutzt jetzt nur das eingebaute Modell."; restartWake() })
        }
        col.addView(ui.text("Tipp: Klappt das Aufwecken danach zu oft aus Versehen, trainiere nochmal und sag beim normalen Satz ähnlich klingende Wörter (z. B. „Jawohl, Paris, gar nicht“).", 12f, Ui.MUTED).apply {
            setPadding(0, ui.px(16), 0, 0) })
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) })
    }

    private fun drawDots(done: Int) {
        dots.removeAllViews()
        for (i in 0 until SAMPLES + 1) dots.addView(TextView(this).apply {
            text = if (i < done) "●" else "○"; textSize = 18f
            setTextColor(if (i < done) Ui.RED else Ui.MUTED); setPadding(ui.px(4), 0, ui.px(4), 0) })
    }

    private fun startTraining() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7); return
        }
        if (worker?.isAlive == true) return
        cancelled = false
        WakeWordService.instance?.pause()
        startBtn.isEnabled = false; startBtn.alpha = 0.5f
        worker = Thread {
            try { train() } catch (e: Exception) {
                main.post { big.text = "Fehler"; status.text = e.message ?: "Unbekannter Fehler" }
            } finally {
                main.post { startBtn.isEnabled = true; startBtn.alpha = 1f; startBtn.text = "Nochmal trainieren"; orb.level = 0f }
                restartWake()
            }
        }.apply { start() }
    }

    private fun restartWake() { main.postDelayed({ WakeWordService.instance?.resume() }, 500) }

    private fun toShorts(pcm: ByteArray): ShortArray {
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(bb.remaining()).also { bb.get(it) }
    }

    private fun beep() = try { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 70).startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150) } catch (_: Exception) { false }

    private fun train() {
        val engine = WakeWordEngine(this, 0.5f) {}
        try {
            val skip = WakeWordEngine.SR / WakeWordEngine.CHUNK   // die ergänzte 1 s Ruhe
            val tpls = ArrayList<List<FloatArray>>()
            val background = ArrayList<FloatArray>()
            var tries = 0
            while (tpls.size < SAMPLES && tries < SAMPLES + 4 && !cancelled) {
                tries++
                main.post { big.text = "Sag „Jarvis“"; status.text = "Aufnahme ${tpls.size + 1} von $SAMPLES"; drawDots(tpls.size) }
                Thread.sleep(500); beep(); Thread.sleep(200)
                val pcm = Whisper.record({ l -> main.post { orb.level = l } }, { cancelled }, maxMs = 3000, silenceMs = 450, startTimeoutMs = 5000)
                if (pcm == null) { main.post { status.text = "Nichts gehört – nochmal." }; Thread.sleep(900); continue }
                val (embs, rms) = engine.embed(toShorts(pcm))
                val t = WakeMath.template(embs, rms, skip)
                if (t == null) { main.post { status.text = "Zu leise – bitte etwas lauter." }; Thread.sleep(900); continue }
                tpls.add(t)
                background.addAll(embs.drop(skip).take(2))
                main.post { big.text = "✓"; drawDots(tpls.size) }
                Thread.sleep(500)
            }
            if (cancelled) return
            if (tpls.size < 3) { main.post { big.text = "Abgebrochen"; status.text = "Zu wenige Aufnahmen. Probier es nochmal in einem ruhigen Raum." }; return }

            main.post { big.text = "Jetzt ein normaler Satz"; status.text = "z. B. „Heute ist das Wetter schön und ich gehe gleich raus.“ (ohne „Jarvis“)" }
            Thread.sleep(1600); beep(); Thread.sleep(200)
            val neg = Whisper.record({ l -> main.post { orb.level = l } }, { cancelled }, maxMs = 7000, silenceMs = 900, startTimeoutMs = 6000)
            val negEmbs = if (neg != null) engine.embed(toShorts(neg)).first else emptyList()
            background.addAll(negEmbs.drop(skip))
            val mean = WakeMath.mean(background)
            val pos = WakeMath.positives(tpls, mean)
            val negMax = if (negEmbs.size > skip + WakeMath.K) WakeMath.negMax(negEmbs, tpls, mean, skip) else (pos.minOrNull() ?: 0.5f) - 0.15f
            val calib = WakeMath.calibrate(pos, negMax)
            PersonalWake.save(this, tpls, mean, calib)
            Prefs(this).wakeMode = "JARVIS"
            main.post {
                drawDots(SAMPLES + 1)
                big.text = if (calib.good) "Fertig!" else "Gespeichert"
                status.text = (if (calib.good) "Jarvis kennt jetzt deine Stimme. Sag einfach „Jarvis“."
                    else "Deine Aufnahmen und der normale Satz klingen sich recht ähnlich – es klappt, aber nochmal trainieren (ruhiger, deutlicher) macht es zuverlässiger.") +
                    "\n\nWerte: eigene %.2f · Satz %.2f · Schwelle %.2f".format(calib.posMin, calib.negMax, calib.threshold)
            }
        } finally { engine.close() }
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    companion object { const val SAMPLES = 6 }
}
