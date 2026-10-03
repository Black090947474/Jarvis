package de.jarvis.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fängt Abstürze ab: speichert den Fehlerbericht (wird in der Jarvis-App angezeigt)
 * und zeigt ihn zusätzlich als Benachrichtigung, damit man ihn sofort sieht.
 */
class JarvisApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try { record(e) } catch (_: Throwable) {}
            previous?.uncaughtException(thread, e)
        }
        // Erinnerungen und Hinweise nach App-Update/Start wieder einplanen
        Thread { try { Scheduler.scheduleAll(this) } catch (_: Throwable) {} }.start()
    }

    private fun record(e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val time = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY).format(Date())
        val report = "Jarvis ${versionName()} · Android ${Build.VERSION.RELEASE} · ${Build.MODEL} · $time\n\n" +
            sw.toString().lines().take(40).joinToString("\n")
        crashFile(this).writeText(report)

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Jarvis-Fehler", NotificationManager.IMPORTANCE_DEFAULT))
        val short = "${e.javaClass.simpleName}: ${e.message ?: ""}\n" +
            e.stackTrace.filter { it.className.startsWith("de.jarvis") }.take(4)
                .joinToString("\n") { "${it.fileName}:${it.lineNumber} ${it.methodName}" }
        nm.notify(99, Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis ist abgestürzt – Screenshot an Claude schicken")
            .setContentText(short.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(short))
            .build())
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    companion object {
        private const val CH = "crash"
        fun crashFile(app: android.content.Context) = File(app.filesDir, "last_crash.txt")
    }
}
