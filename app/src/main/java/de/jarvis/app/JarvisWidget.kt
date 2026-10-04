package de.jarvis.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import de.jarvis.app.logic.TimeLogic
import de.jarvis.app.tools.AgendaTool
import de.jarvis.app.tools.CalendarTool
import org.json.JSONObject
import kotlin.concurrent.thread

/** Startbildschirm-Widget: Uhrzeit, nächster Termin, Wetter, Mikrofon-Knopf. */
class JarvisWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        thread { try { update(ctx.applicationContext, mgr, ids) } finally { pending.finish() } }
    }

    companion object {
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, JarvisWidget::class.java))
            if (ids.isNotEmpty()) thread { try { update(ctx.applicationContext, mgr, ids) } catch (_: Exception) {} }
        }

        private fun update(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_jarvis)
            val now = System.currentTimeMillis()
            // Nächster Termin / Erinnerung (heute und morgen)
            val ag = AgendaTool.collect(ctx, now, TimeLogic.range("morgen").second)
            val ev = ag.events.firstOrNull { !it.allDay && it.end > now }
            val rem = ag.reminders.firstOrNull()
            val next = listOfNotNull(ev?.let { it.begin to it.title }, rem?.let { it.next!! to "🔔 " + it.text }).minByOrNull { it.first }
            v.setTextViewText(R.id.w_next, next?.let {
                val today = TimeLogic.range("heute").second > it.first
                (if (today) "" else "Morgen ") + TimeLogic.time(it.first) + "  " + it.second
            } ?: "Heute nichts mehr geplant")
            val w = runCatching { JSONObject(Prefs(ctx).weatherCache) }.getOrNull()
            v.setTextViewText(R.id.w_weather, w?.let { "${it.optInt("t")}° ${it.optString("desc")} · ${it.optInt("min")}°/${it.optInt("max")}°" } ?: "Jarvis · tippe zum Sprechen")
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            v.setOnClickPendingIntent(R.id.w_mic, PendingIntent.getActivity(ctx, 11,
                Intent(ctx, JarvisActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags))
            v.setOnClickPendingIntent(R.id.w_chat, PendingIntent.getActivity(ctx, 12,
                Intent(ctx, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags))
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 13,
                Intent(ctx, DashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags))
            mgr.updateAppWidget(ids, v)
        }
    }

    @Suppress("unused") private val keep = CalendarTool
}
