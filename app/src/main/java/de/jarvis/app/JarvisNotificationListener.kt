package de.jarvis.app

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Merkt sich die neuesten Benachrichtigungen (WhatsApp, Instagram, SMS …), damit Jarvis
 * sie vorlesen und – wenn die App das anbietet – direkt darauf antworten kann.
 * Alles bleibt im Arbeitsspeicher des Handys.
 */
class JarvisNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        try { activeNotifications?.forEach { add(it) } } catch (_: Exception) {}
    }

    override fun onListenerDisconnected() { if (instance === this) instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) { add(sbn) }

    private fun add(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val reply = n.actions?.firstOrNull { (it.remoteInputs?.size ?: 0) > 0 }
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: Exception) { sbn.packageName }
        synchronized(items) {
            if (items.any { it.pkg == sbn.packageName && it.title == title && it.text == text }) return
            items.addLast(Msg(++counter, sbn.packageName, app, title, text.take(400), sbn.postTime, reply))
            while (items.size > MAX) items.removeFirst()
        }
    }

    data class Msg(
        val id: Int, val pkg: String, val app: String, val title: String,
        val text: String, val time: Long, val reply: Notification.Action?,
    )

    companion object {
        private const val MAX = 40
        private val items = ArrayDeque<Msg>()
        private var counter = 0
        @Volatile var instance: JarvisNotificationListener? = null
            private set

        val isConnected: Boolean get() = instance != null

        fun recent(appFilter: String?, limit: Int = 10): List<Msg> = synchronized(items) {
            val f = appFilter?.trim()?.lowercase().orEmpty()
            items.filter { f.isEmpty() || it.app.lowercase().contains(f) || it.pkg.contains(f) }
                .takeLast(limit).reversed()
        }

        fun find(id: Int): Msg? = synchronized(items) { items.firstOrNull { it.id == id } }

        /** Antwortet über die "Antworten"-Funktion der Benachrichtigung – ohne die App zu öffnen. */
        fun reply(ctx: Context, m: Msg, text: String): String {
            val a = m.reply ?: return "Fehler: Auf diese Nachricht kann man nicht direkt antworten."
            val inputs = a.remoteInputs ?: return "Fehler: Keine Antwortmöglichkeit."
            val results = Bundle()
            for (ri in inputs) results.putCharSequence(ri.resultKey, text)
            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            return try {
                a.actionIntent.send(ctx, 0, intent)
                "Antwort an ${m.title} (${m.app}) gesendet: „$text“"
            } catch (_: PendingIntent.CanceledException) {
                "Fehler: Die Benachrichtigung ist nicht mehr da, Antworten geht nur noch in der App."
            }
        }
    }
}
