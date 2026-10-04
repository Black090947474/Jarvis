package de.jarvis.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import de.jarvis.app.logic.TimeLogic
import de.jarvis.app.tools.AgendaStore
import de.jarvis.app.tools.AgendaTool
import de.jarvis.app.tools.CalendarTool
import de.jarvis.app.tools.Reminder
import de.jarvis.app.tools.TaskTool
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.concurrent.thread

/** Benachrichtigungen von Jarvis – jede Art hat einen eigenen Kanal, den du abschalten kannst. */
object Notifier {
    const val CH_REMINDER = "reminders"
    const val CH_EVENTS = "events"
    const val CH_CONFLICT = "conflicts"
    const val CH_BRIEFING = "briefing"
    const val CH_TASKS = "tasks"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(NotificationChannel(CH_REMINDER, "Erinnerungen", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_EVENTS, "Bevorstehende Termine", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_CONFLICT, "Kalender-Konflikte", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_BRIEFING, "Tagesüberblick", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_TASKS, "Aufgaben", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(ctx: Context, channel: String, id: Int, title: String, text: String,
             actions: List<Notification.Action> = emptyList(), open: PendingIntent? = null) {
        if (!canPost(ctx)) return
        channels(ctx)
        val tap = open ?: PendingIntent.getActivity(ctx, 0, Intent(ctx, DashboardActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(tap)
            .apply { actions.forEach { addAction(it) } }
            .build()
        ctx.getSystemService(NotificationManager::class.java)!!.notify(id, n)
    }

    fun cancel(ctx: Context, id: Int) = ctx.getSystemService(NotificationManager::class.java)!!.cancel(id)
}

/** Plant Erinnerungen, Termin-Hinweise, Konflikt-Prüfung und Tagesüberblick. */
object Scheduler {
    private const val TAG = "JarvisSched"
    const val ACTION_REMINDER = "de.jarvis.app.REMINDER"
    const val ACTION_SNOOZED = "de.jarvis.app.REMINDER_SNOOZED"
    const val ACTION_DONE = "de.jarvis.app.REMINDER_DONE"
    const val ACTION_SNOOZE = "de.jarvis.app.REMINDER_SNOOZE"
    const val ACTION_GEOFENCE = "de.jarvis.app.GEOFENCE"
    const val ACTION_SCAN = "de.jarvis.app.SCAN"
    const val ACTION_EVENT = "de.jarvis.app.EVENT_ALERT"
    const val ACTION_BRIEFING = "de.jarvis.app.BRIEFING"
    private const val RC_SCAN = 900_001
    private const val RC_BRIEFING = 900_002
    private const val RC_GEOFENCE = 900_003

    private fun pi(ctx: Context, action: String, rc: Int, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getBroadcast(ctx, rc, Intent(ctx, JarvisReceiver::class.java).setAction(action).apply(extras),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun exactAt(ctx: Context, at: Long, p: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)!!
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) }
    }

    // ---------- Erinnerungen ----------

    fun schedule(ctx: Context, r: Reminder) {
        cancel(ctx, r)
        if (!r.active) return
        if (r.place != null) { addGeofence(ctx, r); return }
        val at = r.next ?: return
        exactAt(ctx, at, pi(ctx, ACTION_REMINDER, r.id) { putExtra("id", r.id) })
    }

    fun cancel(ctx: Context, r: Reminder) {
        ctx.getSystemService(AlarmManager::class.java)!!.cancel(pi(ctx, ACTION_REMINDER, r.id) { putExtra("id", r.id) })
        if (r.place != null) try {
            LocationServices.getGeofencingClient(ctx).removeGeofences(listOf("rem_${r.id}"))
        } catch (_: Exception) {}
    }

    fun snooze(ctx: Context, id: Int, minutes: Int = 10) =
        exactAt(ctx, System.currentTimeMillis() + minutes * 60_000L, pi(ctx, ACTION_SNOOZED, 500_000 + id) { putExtra("id", id) })

    private fun geofencePi(ctx: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, RC_GEOFENCE, Intent(ctx, JarvisReceiver::class.java).setAction(ACTION_GEOFENCE), flags)
    }

    /** Ortsbasierte Erinnerung. Braucht Standort „Immer erlauben“. */
    @Suppress("MissingPermission")
    fun addGeofence(ctx: Context, r: Reminder): Boolean {
        val p = r.place ?: return false
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        if (Build.VERSION.SDK_INT >= 29 &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val fence = Geofence.Builder().setRequestId("rem_${r.id}")
                .setCircularRegion(p.lat, p.lon, p.radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(if (p.onEnter) Geofence.GEOFENCE_TRANSITION_ENTER else Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
            val req = GeofencingRequest.Builder()
                .setInitialTrigger(0)   // nicht sofort auslösen, nur bei echtem Ankommen/Verlassen
                .addGeofence(fence).build()
            LocationServices.getGeofencingClient(ctx).addGeofences(req, geofencePi(ctx))
            true
        } catch (e: Exception) { Log.w(TAG, "Geofence", e); false }
    }

    // ---------- Alles neu planen (Start, Neustart, Einstellungen) ----------

    fun scheduleAll(ctx: Context) {
        val p = Prefs(ctx)
        val store = AgendaStore(ctx)
        val now = System.currentTimeMillis()
        for (r in store.reminders().filter { it.active }) {
            var cur = r
            if (cur.place == null && cur.next != null && cur.next!! < now) {
                val nx = TimeLogic.nextOccurrence(cur.anchor ?: cur.next!!, cur.repeat, cur.days, now)
                if (nx == null) {
                    // Während das Handy aus war fällig geworden: einmal nachholen
                    Notifier.post(ctx, Notifier.CH_REMINDER, 10_000 + cur.id, "Verpasste Erinnerung", cur.text)
                    cur = cur.copy(active = false, next = null)
                } else cur = cur.copy(next = nx)
                store.putReminder(cur)
            }
            schedule(ctx, cur)
        }
        val am = ctx.getSystemService(AlarmManager::class.java)!!
        val scan = pi(ctx, ACTION_SCAN, RC_SCAN)
        // Scan läuft auch ohne proaktive Hinweise (hält das Widget aktuell); Hinweise prüft scan() selbst
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, now + 60_000, AlarmManager.INTERVAL_HALF_HOUR, scan)
        scheduleBriefing(ctx)
    }

    fun scheduleBriefing(ctx: Context) {
        val p = Prefs(ctx)
        val b = pi(ctx, ACTION_BRIEFING, RC_BRIEFING)
        if (!p.proactive || !p.notifyBriefing) { ctx.getSystemService(AlarmManager::class.java)!!.cancel(b); return }
        val t = runCatching { LocalTime.parse(p.briefingTime) }.getOrDefault(LocalTime.of(7, 0))
        val z = ZoneId.systemDefault()
        var next = ZonedDateTime.now(z).with(t)
        if (!next.isAfter(ZonedDateTime.now(z))) next = next.plusDays(1)
        exactAt(ctx, next.toInstant().toEpochMilli(), b)
    }

    // ---------- Proaktive Prüfung (alle ~30 Minuten) ----------

    fun scan(ctx: Context) {
        JarvisWidget.updateAll(ctx)
        val p = Prefs(ctx)
        if (!p.proactive || !p.feature("calendar")) return
        val now = System.currentTimeMillis()
        p.pruneNotified(now)
        if (p.notifyEvents) {
            for (e in CalendarTool.safeEvents(ctx, now, now + 6 * 3_600_000L).filter { !it.allDay && it.begin > now }) {
                val lead = p.eventLead + (if (e.location.isNotBlank()) p.travelBuffer else 0)
                val alertAt = e.begin - lead * 60_000L
                val key = "ev:${e.id}:${e.begin}"
                if (p.wasNotified(key)) continue
                p.markNotified(key)
                val rc = (key.hashCode() and 0x7fffffff) % 400_000 + 100_000
                val intent: Intent.() -> Unit = {
                    putExtra("title", e.title); putExtra("begin", e.begin); putExtra("location", e.location)
                    putExtra("lead", lead); putExtra("nid", rc)
                }
                if (alertAt > now) exactAt(ctx, alertAt, pi(ctx, ACTION_EVENT, rc, intent))
                else eventAlert(ctx, Intent().apply(intent))
            }
        }
        if (p.notifyConflicts) {
            val ev = CalendarTool.safeEvents(ctx, now, now + 7 * 86_400_000L).filter { !it.allDay }
            val spans = ev.map { TimeLogic.Span(it.begin, it.end, it.title, it.id) }
            for ((a, b) in TimeLogic.overlappingPairs(spans)) {
                val key = "cf:${a.id}:${b.id}:${a.start}"
                if (p.wasNotified(key)) continue
                p.markNotified(key)
                Notifier.post(ctx, Notifier.CH_CONFLICT, (key.hashCode() and 0xffff) + 20_000, "Kalender-Konflikt",
                    "„${a.title}“ (${TimeLogic.short(a.start)}–${TimeLogic.time(a.end)}) überschneidet sich mit " +
                    "„${b.title}“ (${TimeLogic.time(b.start)}–${TimeLogic.time(b.end)}). Sag z. B. „Jarvis, verschieb ${b.title}“.")
            }
        }
    }

    fun eventAlert(ctx: Context, i: Intent) {
        val title = i.getStringExtra("title") ?: return
        val begin = i.getLongExtra("begin", 0)
        val loc = i.getStringExtra("location").orEmpty()
        val mins = ((begin - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        val actions = mutableListOf<Notification.Action>()
        var text = "Um ${TimeLogic.time(begin)}" + (if (loc.isNotBlank()) " in $loc" else "") + "."
        if (loc.isNotBlank()) {
            text += " Mit Weg-Puffer solltest du jetzt bald los."
            val nav = PendingIntent.getActivity(ctx, 3, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(loc))),
                PendingIntent.FLAG_IMMUTABLE)
            actions += Notification.Action.Builder(null, "Navigation", nav).build()
        }
        Notifier.post(ctx, Notifier.CH_EVENTS, i.getIntExtra("nid", 30_000), "In $mins Min.: $title", text, actions)
    }

    fun briefing(ctx: Context) {
        val p = Prefs(ctx)
        if (p.proactive && p.notifyBriefing) {
            val (from, to) = TimeLogic.range("heute")
            val ag = AgendaTool.collect(ctx, from, to)
            if (!ag.isEmpty()) Notifier.post(ctx, Notifier.CH_BRIEFING, 40_000, "Guten Morgen – dein Tag", AgendaTool.summary(ag, "heute", detailed = true))
        }
        scheduleBriefing(ctx)
    }
}

/** Empfängt Alarme, Geofences und den Neustart des Handys. */
class JarvisReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        thread {
            try { handle(ctx.applicationContext, intent) } catch (e: Exception) { Log.w("JarvisReceiver", "Fehler", e) }
            finally { pending.finish() }
        }
    }

    private fun handle(ctx: Context, intent: Intent) {
        val store = AgendaStore(ctx)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.QUICKBOOT_POWERON" ->
                Scheduler.scheduleAll(ctx)

            Scheduler.ACTION_REMINDER, Scheduler.ACTION_SNOOZED -> {
                val r = store.reminder(intent.getIntExtra("id", -1)) ?: return
                if (!r.active) return
                showReminder(ctx, r)
                if (intent.action == Scheduler.ACTION_REMINDER) {
                    val nx = TimeLogic.nextOccurrence(r.anchor ?: r.next ?: return, r.repeat, r.days, System.currentTimeMillis() + 1000)
                    val upd = if (nx == null) r.copy(next = null, active = false) else r.copy(next = nx)
                    store.putReminder(upd)
                    Scheduler.schedule(ctx, upd)
                }
            }

            Scheduler.ACTION_DONE -> {
                val id = intent.getIntExtra("id", -1)
                Notifier.cancel(ctx, 10_000 + id)
                store.reminder(id)?.taskId?.let { TaskTool.complete(ctx, it) }
            }

            Scheduler.ACTION_SNOOZE -> {
                val id = intent.getIntExtra("id", -1)
                Notifier.cancel(ctx, 10_000 + id)
                Scheduler.snooze(ctx, id)
            }

            Scheduler.ACTION_GEOFENCE -> {
                val ev = GeofencingEvent.fromIntent(intent) ?: return
                if (ev.hasError()) return
                for (g in ev.triggeringGeofences.orEmpty()) {
                    val id = g.requestId.removePrefix("rem_").toIntOrNull() ?: continue
                    val r = store.reminder(id) ?: continue
                    if (!r.active) continue
                    showReminder(ctx, r)
                    if (r.repeat == TimeLogic.Repeat.NONE) {
                        store.putReminder(r.copy(active = false)); Scheduler.cancel(ctx, r)
                    }
                }
            }

            Scheduler.ACTION_SCAN -> Scheduler.scan(ctx)
            Scheduler.ACTION_EVENT -> if (Prefs(ctx).proactive && Prefs(ctx).notifyEvents) Scheduler.eventAlert(ctx, intent)
            Scheduler.ACTION_BRIEFING -> Scheduler.briefing(ctx)
        }
    }

    private fun showReminder(ctx: Context, r: Reminder) {
        fun act(action: String, label: String, rc: Int) = Notification.Action.Builder(null, label,
            PendingIntent.getBroadcast(ctx, rc, Intent(ctx, JarvisReceiver::class.java).setAction(action).putExtra("id", r.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        Notifier.post(ctx, Notifier.CH_REMINDER, 10_000 + r.id, "Erinnerung", r.text,
            listOf(act(Scheduler.ACTION_DONE, "Erledigt", 600_000 + r.id), act(Scheduler.ACTION_SNOOZE, "In 10 Min.", 700_000 + r.id)))
    }
}
