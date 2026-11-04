package de.jarvis.app

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import de.jarvis.app.tools.JsonStore
import org.json.JSONObject

/**
 * „Jarvis ruft dich an“: ein Anruf-Bildschirm mit Klingelton (auch im Lautlos-Modus, über den Wecker-Ton),
 * für wichtige Erinnerungen, Verschlafen-Schutz oder wenn du es dir wünschst („Ruf mich um 7 an“).
 * Nach dem Annehmen sagt Jarvis, worum es geht. Es wird kein echtes Telefonat geführt und nichts kostet Geld.
 */
object JarvisCall {
    const val CH = "jarvis_call"
    const val ACTION = "de.jarvis.app.JARVIS_CALL"
    private const val NOTIF = 87_000

    /** Geplante Anrufe: {id, at, text}. */
    fun list(ctx: Context): List<JSONObject> = JsonStore(ctx).list("jarvis_calls").filter { it.optLong("at") > System.currentTimeMillis() - 60_000 }

    private fun pi(ctx: Context, id: Int, text: String) = PendingIntent.getBroadcast(ctx, 870_000 + (id % 9_000),
        Intent(ctx, JarvisCallReceiver::class.java).setAction(ACTION).putExtra("id", id).putExtra("text", text),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(ctx: Context, at: Long, text: String): Int {
        val store = JsonStore(ctx)
        val id = store.newId()
        store.save("jarvis_calls", list(ctx) + JSONObject().put("id", id).put("at", at).put("text", text))
        Scheduler.exactAt(ctx, at, pi(ctx, id, text))
        return id
    }

    fun cancel(ctx: Context, id: Int): Boolean {
        val c = list(ctx).firstOrNull { it.optInt("id") == id } ?: return false
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pi(ctx, id, c.optString("text")))
        JsonStore(ctx).save("jarvis_calls", list(ctx).filter { it.optInt("id") != id })
        return true
    }

    /** Nach Neustart wieder einplanen. */
    fun rescheduleAll(ctx: Context) {
        for (c in list(ctx)) if (c.optLong("at") > System.currentTimeMillis()) Scheduler.exactAt(ctx, c.optLong("at"), pi(ctx, c.optInt("id"), c.optString("text")))
    }

    /** Darf Jarvis den Anruf-Bildschirm über allem zeigen? */
    fun canFullScreen(ctx: Context): Boolean = Build.VERSION.SDK_INT < 34 ||
        ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** Jetzt anrufen. */
    fun ring(ctx: Context, text: String) {
        val i = Intent(ctx, CallActivity::class.java).putExtra("text", text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Jarvis ruft an", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); setBypassDnd(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
        val full = PendingIntent.getActivity(ctx, 87_001, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Notifier.canPost(ctx)) {
            val n = Notification.Builder(ctx, CH).setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("📞 Jarvis ruft an").setContentText(text.take(80))
                .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setAutoCancel(true)
                .setFullScreenIntent(full, true).setContentIntent(full).build()
            nm.notify(NOTIF, n)
        }
        // Mit „Über anderen Apps einblenden“ darf Jarvis den Bildschirm auch direkt öffnen
        if (Settings.canDrawOverlays(ctx)) try { ctx.startActivity(i) } catch (_: Exception) {}
    }

    fun dismiss(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF)
}

class JarvisCallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getIntExtra("id", -1)
        val text = i.getStringExtra("text").orEmpty()
        JsonStore(ctx).save("jarvis_calls", JarvisCall.list(ctx).filter { it.optInt("id") != id })
        JarvisCall.ring(ctx, text.ifBlank { "Du wolltest, dass ich dich anrufe." })
    }
}

/** Der Anruf-Bildschirm. */
class CallActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var speaker: Speaker? = null
    private var text = ""
    private var answered = false
    private lateinit var status: android.widget.TextView
    private lateinit var msg: android.widget.TextView
    private lateinit var buttons: LinearLayout
    private lateinit var orb: OrbView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        Ui.load(Prefs(this)); val ui = Ui(this)
        window.statusBarColor = Ui.BG; window.navigationBarColor = Ui.BG
        text = intent.getStringExtra("text").orEmpty()

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.px(24), ui.px(70), ui.px(24), ui.px(40)) }
        col.addView(ui.text("EINGEHENDER ANRUF", 12f, Ui.RED).apply { letterSpacing = 0.25f; typeface = Typeface.MONOSPACE })
        col.addView(ui.text("Jarvis", 40f, Ui.FG, true).apply { setPadding(0, ui.px(10), 0, 0) })
        status = ui.text("ruft dich an …", 16f, Ui.SOFT); col.addView(status)
        orb = OrbView(this)
        col.addView(orb, LinearLayout.LayoutParams(ui.px(230), ui.px(230)).apply { topMargin = ui.px(36) })
        msg = ui.text("", 18f, Ui.FG).apply { gravity = Gravity.CENTER; setPadding(0, ui.px(20), 0, 0) }
        col.addView(msg)
        val space = FrameLayout(this); col.addView(space, LinearLayout.LayoutParams(1, 0, 1f))
        buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun round(label: String, color: Int, sub: String, act: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.px(22), 0, ui.px(22), 0)
            addView(ui.text(label, 30f, Color.WHITE).apply { gravity = Gravity.CENTER; background = ui.round(color, 40)
                layoutParams = LinearLayout.LayoutParams(ui.px(78), ui.px(78)); setOnClickListener { act() } })
            addView(ui.text(sub, 13f, Ui.SOFT).apply { setPadding(0, ui.px(8), 0, 0) })
        }
        buttons.addView(round("✕", Color.rgb(230, 50, 60), "Ablehnen") { decline() })
        buttons.addView(round("⏰", Color.rgb(70, 80, 110), "In 5 Min.") { snooze() })
        buttons.addView(round("✆", Color.rgb(30, 190, 100), "Annehmen") { answer() })
        col.addView(buttons)
        setContentView(FrameLayout(this).apply { background = ui.aurora(); addView(col, FrameLayout.LayoutParams(-1, -1)) })

        speaker = Speaker(this, onDone = { h.post { if (answered) status.text = "Anruf beendet" } })
        ring()
        h.postDelayed({ if (!answered) missed() }, 60_000)
    }

    private fun ring() {
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            player = MediaPlayer().apply {
                // Wecker-Kanal: klingelt auch, wenn das Handy auf lautlos steht
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@CallActivity, uri); isLooping = true; prepare(); start()
            }
        } catch (_: Exception) {}
        try { getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)) } catch (_: Exception) {}
        pulse()
    }

    private fun pulse() {
        if (answered || isFinishing) return
        orb.level = if (orb.level > 0.3f) 0.05f else 0.8f
        h.postDelayed({ pulse() }, 450)
    }

    private fun stopRing() {
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
        try { getSystemService(Vibrator::class.java)?.cancel() } catch (_: Exception) {}
        JarvisCall.dismiss(this)
    }

    private fun answer() {
        if (answered) return
        answered = true; stopRing()
        status.text = "verbunden"; orb.level = 0.4f
        msg.text = text
        buttons.removeAllViews(); buttons.orientation = LinearLayout.VERTICAL
        val ui = Ui(this)
        buttons.addView(ui.button("Mit Jarvis reden") {
            startActivity(Intent(this, JarvisActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish() })
        buttons.addView(ui.button("Auflegen", false) { finish() })
        val name = Prefs(this).userName.ifBlank { "" }
        val say = (if (name.isNotBlank()) "Hallo $name, hier ist Jarvis. " else "Hallo, hier ist Jarvis. ") + text
        // Sprachausgabe startet, sobald die Stimme bereit ist
        fun trySpeak(n: Int) { val s = speaker ?: return; if (s.ready) s.speak(say, "call") else if (n < 20) h.postDelayed({ trySpeak(n + 1) }, 150) }
        trySpeak(0)
    }

    private fun decline() { answered = true; stopRing(); finish() }

    private fun snooze() {
        answered = true; stopRing()
        JarvisCall.schedule(this, System.currentTimeMillis() + 5 * 60_000L, text)
        finish()
    }

    private fun missed() {
        stopRing()
        Notifier.post(this, Notifier.CH_REMINDER, 87_002, "📞 Verpasster Anruf von Jarvis", text)
        finish()
    }

    override fun onDestroy() { stopRing(); speaker?.shutdown(); h.removeCallbacksAndMessages(null); super.onDestroy() }
}
