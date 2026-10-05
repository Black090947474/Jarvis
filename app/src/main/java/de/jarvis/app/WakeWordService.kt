package de.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Läuft dauerhaft im Hintergrund und hört (offline) auf „Jarvis“.
 * Bei Erkennung wird das Mikrofon freigegeben und der Gesprächsbildschirm geöffnet.
 */
class WakeWordService : Service() {

    private var engine: WakeWordEngine? = null
    private val main = Handler(Looper.getMainLooper())
    private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs(this).listeningEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            goForeground(READY_TEXT)
        } catch (e: Exception) {
            // Android verbietet z. B. nach einem Neustart, das Mikrofon aus dem Hintergrund zu öffnen
            Log.w(TAG, "Vordergrund-Start nicht erlaubt", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!paused) startListening()
        return START_STICKY
    }

    private fun goForeground(text: String) {
        val n = statusNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_STATUS, n)
        }
    }

    // ---------- Wake Word ----------

    private fun startListening() {
        try {
            val e = engine ?: WakeWordEngine(applicationContext, Prefs(this).sensitivity) {
                main.post { onWakeWord() }
            }.also { engine = it }
            e.start()
            updateStatus(READY_TEXT)
        } catch (e: Exception) {
            Log.e(TAG, "Start fehlgeschlagen", e)
            updateStatus("Fehler beim Starten der Spracherkennung")
        }
    }

    private fun stopListening() {
        engine?.stop()
    }

    /** Vom Gesprächsbildschirm aufgerufen: Mikrofon wird gleich anderweitig gebraucht. */
    fun pause() {
        paused = true
        stopListening()
        updateStatus("Gespräch läuft …")
    }

    /** Vom Gesprächsbildschirm beim Schließen aufgerufen. */
    fun resume() {
        paused = false
        startListening()
    }

    private fun onWakeWord() {
        if (paused) return
        pause()
        wakeScreen()
        launchConversation()
    }

    // ---------- Bildschirm an + Gespräch öffnen ----------

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "jarvis:wake"
            )
            wl.acquire(5_000)
        }
    }

    private fun launchConversation() {
        val intent = Intent(this, JarvisActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        // Weg 1: Mit "Über anderen Apps einblenden" darf die App direkt starten.
        if (Settings.canDrawOverlays(this)) {
            try { startActivity(intent); return } catch (e: Exception) { Log.w(TAG, "Direktstart fehlgeschlagen", e) }
        }

        // Weg 2: Vollbild-Benachrichtigung (wie bei einem Anruf / Wecker).
        val pi = PendingIntent.getActivity(this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH_WAKE)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis")
            .setContentText("Ich höre zu – tippen zum Öffnen")
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_WAKE, n)

        // Falls niemand reagiert: nach 20 s wieder auf "Jarvis" hören
        main.postDelayed({ if (paused && !JarvisActivity.isOpen) resume() }, 20_000)
    }

    // ---------- Benachrichtigungen ----------

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Jarvis hört zu",
            NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_WAKE, "Jarvis wurde gerufen",
            NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
    }

    private fun statusNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis ist bereit")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Ausschalten", stop).build())
            .build()
    }

    private fun updateStatus(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_STATUS, statusNotification(text))
    }

    override fun onDestroy() {
        engine?.close()
        engine = null
        main.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "JarvisWake"
        const val ACTION_STOP = "de.jarvis.app.STOP"
        const val READY_TEXT = "Sag „Jarvis“, um zu reden"
        const val CH_STATUS = "status"
        const val CH_WAKE = "wake"
        const val NOTIF_STATUS = 1
        const val NOTIF_WAKE = 2

        @Volatile var instance: WakeWordService? = null
            private set

        val isRunning: Boolean get() = instance != null

        fun start(context: Context) {
            Prefs(context).listeningEnabled = true
            context.startForegroundService(Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            Prefs(context).listeningEnabled = false
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
