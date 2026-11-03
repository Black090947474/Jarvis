package de.jarvis.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Schwebender Jarvis: kleine leuchtende Kugel über allen Apps. Antippen = mit Jarvis reden
 * (er merkt sich vorher kurz, was auf dem Bildschirm war), lang drücken = Chat, ziehen = verschieben.
 */
class BubbleService : Service() {
    private var wm: WindowManager? = null
    private var bubble: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(NotificationChannel(CH, "Schwebender Jarvis", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        val stop = PendingIntent.getService(this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_notify).setContentTitle("Schwebender Jarvis ist an")
            .setContentText("Kugel antippen = reden · ziehen = verschieben")
            .addAction(Notification.Action.Builder(null, "Ausblenden", stop).build()).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF, n)
        show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { Prefs(this).bubble = false; stopSelf() }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        if (!Settings.canDrawOverlays(this) || bubble != null) return
        val dp = resources.displayMetrics.density
        val size = (58 * dp).toInt()
        val prefs = Prefs(this)
        val w = getSystemService(WindowManager::class.java)!!.also { wm = it }
        val params = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.bubbleX.takeIf { it >= 0 } ?: (resources.displayMetrics.widthPixels - size - (6 * dp).toInt())
            y = prefs.bubbleY.takeIf { it >= 0 } ?: (resources.displayMetrics.heightPixels / 3)
        }
        lp = params
        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL
                colors = intArrayOf(Color.argb(235, 6, 22, 38), Color.argb(235, 2, 8, 16)); gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = size / 2f; setStroke((1.5f * dp).toInt(), Color.argb(200, 0, 230, 255)) }
            elevation = 8 * dp
            alpha = 0.92f
        }
        frame.addView(OrbView(this), FrameLayout.LayoutParams(-1, -1).apply { val m = (4 * dp).toInt(); setMargins(m, m, m, m) })
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false; var downAt = 0L
        frame.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false; downAt = System.currentTimeMillis() }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 10 * dp || abs(dy) > 10 * dp) moved = true
                    if (moved) { params.x = (startX + dx).toInt(); params.y = (startY + dy).toInt(); try { w.updateViewLayout(frame, params) } catch (_: Exception) {} }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // an den nächsten Rand schnappen
                        val sw = resources.displayMetrics.widthPixels
                        params.x = if (params.x + size / 2 < sw / 2) (6 * dp).toInt() else sw - size - (6 * dp).toInt()
                        try { w.updateViewLayout(frame, params) } catch (_: Exception) {}
                        prefs.bubbleX = params.x; prefs.bubbleY = params.y
                    } else if (System.currentTimeMillis() - downAt > 600) openChat() else talk()
                }
            }
            true
        }
        try { w.addView(frame, params); bubble = frame } catch (_: Exception) {}
    }

    private fun talk() {
        JarvisAccessibility.instance?.grabForLater()
        bubble?.postDelayed({
            startActivity(Intent(this, JarvisActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }, 120)
    }

    private fun openChat() = startActivity(Intent(this, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Während der Gesprächsbildschirm offen ist, Kugel verstecken. */
    fun setHidden(hidden: Boolean) { bubble?.visibility = if (hidden) View.GONE else View.VISIBLE }

    override fun onDestroy() {
        try { bubble?.let { wm?.removeView(it) } } catch (_: Exception) {}
        bubble = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val CH = "bubble"
        private const val NOTIF = 4
        const val ACTION_STOP = "de.jarvis.app.BUBBLE_STOP"
        @Volatile var instance: BubbleService? = null
            private set

        fun start(ctx: Context) {
            if (!Settings.canDrawOverlays(ctx)) return
            Prefs(ctx).bubble = true
            try { ctx.startForegroundService(Intent(ctx, BubbleService::class.java)) } catch (_: Exception) {}
        }

        fun stop(ctx: Context) { Prefs(ctx).bubble = false; ctx.stopService(Intent(ctx, BubbleService::class.java)) }
    }
}
