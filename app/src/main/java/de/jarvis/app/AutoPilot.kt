package de.jarvis.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import de.jarvis.app.tools.AgendaStore
import de.jarvis.app.tools.BirthdayTool
import de.jarvis.app.tools.BriefingTool
import de.jarvis.app.tools.CalendarTool
import de.jarvis.app.tools.JsonStore
import de.jarvis.app.tools.SchoolTool
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Autopilot: Dinge, die Jarvis von selbst erledigt oder vorschlägt.
 * Alles ist einzeln abschaltbar (Autopilot-Zentrale). Jarvis verschickt, löscht oder kauft nie etwas heimlich –
 * bei solchen Dingen kommt immer erst eine Benachrichtigung zum Antippen bzw. die Bestätigung von Android.
 */
object AutoPilot {
    const val ACTION = "de.jarvis.app.AUTOPILOT"
    const val CH = "autopilot"

    // Jobs
    const val J_BED = "bed"; const val J_WAKE = "wake"; const val J_NIGHT = "night"; const val J_EVENING = "evening"
    const val J_MORNING = "morning"; const val J_SCHOOL_ON = "school_on"; const val J_SCHOOL_OFF = "school_off"
    const val J_SCREEN = "screen"; const val J_WEEKLY = "weekly"; const val J_WAKECHECK = "wakecheck"; const val J_TRADE = "trade"

    private val RC = mapOf(J_BED to 880_001, J_WAKE to 880_002, J_NIGHT to 880_003, J_EVENING to 880_004, J_MORNING to 880_005,
        J_SCHOOL_ON to 880_006, J_SCHOOL_OFF to 880_007, J_SCREEN to 880_008, J_WEEKLY to 880_009,
        AutoPilot2.J_TRANSIT to 880_010, AutoPilot2.J_VOCAB to 880_011, AutoPilot2.J_SKY to 880_012, J_WAKECHECK to 880_013, J_TRADE to 880_014)

    /** Alle Schalter mit Beschreibung (für die Autopilot-Zentrale). Standard: an, außer wo es ins Handy eingreift. */
    data class Feature(val key: String, val title: String, val info: String, val def: Boolean)
    val FEATURES = listOf(
        Feature("sleep", "🌙 Schlafmodus", "Zur Schlafenszeit: Nicht stören an, Bildschirm dunkler, Dunkelmodus (mit Profi-Modus). Morgens wird alles zurückgestellt.", false),
        Feature("morning_brief", "☀️ Briefing beim ersten Entsperren", "Morgens beim ersten Entsperren: Wetter, Termine, Aufgaben als Benachrichtigung.", true),
        Feature("alarm_plan", "⏰ Wecker nach Stundenplan", "Abends Vorschlag für die Weckzeit passend zur ersten Stunde morgen (ein Tipp stellt ihn).", true),
        Feature("evening", "📋 Abend-Check (20 Uhr)", "Termine morgen, Packliste, offene Hausaufgaben, Akku laden.", true),
        Feature("school", "🏫 Schulmodus", "Während des Unterrichts lautlos (Vibration), danach wieder laut + Hausaufgaben-Erinnerung.", false),
        Feature("battery", "🔋 Akku-Wächter", "Unter 20 %: Energiesparen (mit Profi-Modus) und Bildschirm dunkler. Beim Laden zurück.", true),
        Feature("cleanup", "🧹 Aufräum-Hinweis (Sonntag)", "Zählt alte Screenshots. Löschen nur nach deiner Bestätigung von Android.", true),
        Feature("birthdays", "🎂 Geburtstage", "Morgens Hinweis auf Geburtstage – mit fertigem Gruß zum Abschicken.", true),
        Feature("breaks", "💧 Trink- & Pausen-Erinnerung", "Nach 60 Minuten Bildschirm am Stück: kurz trinken, Augen ausruhen.", true),
        Feature("screen_coach", "📱 Bildschirmzeit-Coach", "Zählt, wie lange du welche App nutzt (bleibt auf dem Handy). 21 Uhr Zusammenfassung, Limits pro App.", true),
        Feature("goodnight", "😴 Gute-Nacht-Routine", "30 Min. vor dem Schlafen: Bildschirm dimmen, Gute-Nacht-Geschichte oder Einschlafmusik anbieten.", true),
        Feature("fact", "💡 Fakt des Tages", "Jeden Morgen ein spannender Fakt – antippen und Jarvis erklärt ihn mit Hologramm.", true),
        Feature("weekly", "📊 Wochenrückblick (Sonntag 18 Uhr)", "Lernzeit, Bildschirmzeit, offene Aufgaben, Geburtstage nächste Woche.", true),
        Feature("wake_call", "📞 Verschlafen-Schutz", "Warst du 15 Minuten nach dem Wecker noch nicht am Handy, ruft Jarvis dich an (klingelt auch bei lautlos).", false),
    ) + AutoPilot2.FEATURES

    private fun sp(ctx: Context) = ctx.getSharedPreferences("autopilot", Context.MODE_PRIVATE)
    fun on(ctx: Context, key: String) = sp(ctx).getBoolean(key, FEATURES.firstOrNull { it.key == key }?.def ?: false)
    fun set(ctx: Context, key: String, v: Boolean) { sp(ctx).edit().putBoolean(key, v).apply(); scheduleAll(ctx) }
    fun str(ctx: Context, key: String, def: String) = sp(ctx).getString(key, def) ?: def
    fun putStr(ctx: Context, key: String, v: String) { sp(ctx).edit().putString(key, v).apply(); scheduleAll(ctx) }
    private fun int(ctx: Context, key: String, def: Int) = sp(ctx).getInt(key, def)
    private fun putInt(ctx: Context, key: String, v: Int) = sp(ctx).edit().putInt(key, v).apply()
    private fun once(ctx: Context, key: String): Boolean {  // true beim ersten Mal heute
        val k = "once_" + key + "_" + LocalDate.now()
        if (sp(ctx).getBoolean(k, false)) return false
        sp(ctx).edit().putBoolean(k, true).apply(); return true
    }

    fun bed(ctx: Context) = parse(str(ctx, "bed", "22:00"), LocalTime.of(22, 0))
    fun wake(ctx: Context) = parse(str(ctx, "wake", "06:30"), LocalTime.of(6, 30))
    fun lead(ctx: Context) = str(ctx, "lead", "75").toIntOrNull()?.coerceIn(20, 180) ?: 75

    fun parse(s: String, def: LocalTime): LocalTime = Regex("(\\d{1,2})[:.](\\d{2})").find(s)?.let {
        runCatching { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.getOrNull() } ?: def

    // ---------------------------------------------------------------- Planung

    private fun pi(ctx: Context, job: String) = PendingIntent.getBroadcast(ctx, RC[job] ?: 880_000,
        Intent(ctx, AutoPilotReceiver::class.java).setAction(ACTION).putExtra("job", job),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun nextAt(t: LocalTime, day: DayOfWeek? = null): Long {
        var d = LocalDateTime.of(LocalDate.now(), t)
        if (!d.isAfter(LocalDateTime.now().plusSeconds(30))) d = d.plusDays(1)
        if (day != null) while (d.dayOfWeek != day) d = d.plusDays(1)
        return d.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun at(ctx: Context, job: String, whenMs: Long?) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = pi(ctx, job)
        if (whenMs == null) { am.cancel(p); return }
        Scheduler.exactAt(ctx, whenMs, p)
    }

    fun scheduleAll(ctx: Context) {
        try {
            val bed = bed(ctx)
            at(ctx, J_BED, if (on(ctx, "sleep")) nextAt(bed) else null)
            at(ctx, J_WAKE, if (on(ctx, "sleep") || on(ctx, "morning_brief") || on(ctx, "goodnight")) nextAt(wake(ctx)) else null)
            at(ctx, J_NIGHT, if (on(ctx, "goodnight")) nextAt(bed.minusMinutes(30)) else null)
            at(ctx, J_EVENING, if (on(ctx, "evening") || on(ctx, "alarm_plan")) nextAt(LocalTime.of(20, 0)) else null)
            at(ctx, J_MORNING, if (on(ctx, "birthdays") || on(ctx, "fact") || on(ctx, "school") || Vehicles.all(ctx).isNotEmpty()) nextAt(wake(ctx).plusMinutes(10)) else null)
            at(ctx, J_SCREEN, if (on(ctx, "screen_coach")) nextAt(LocalTime.of(21, 0)) else null)
            at(ctx, J_WEEKLY, if (on(ctx, "weekly") || on(ctx, "cleanup")) nextAt(LocalTime.of(18, 0), DayOfWeek.SUNDAY) else null)
            val alarm = ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
            if (on(ctx, "wake_call") && alarm != null && alarm > System.currentTimeMillis()) {
                sp(ctx).edit().putLong("alarm_at", alarm).apply()
                at(ctx, J_WAKECHECK, alarm + 15 * 60_000L)
            } else at(ctx, J_WAKECHECK, null)
            at(ctx, J_TRADE, if (on(ctx, "trade_bot")) DemoDepot.nextRun() else null)
            planSchool(ctx)
            AutoPilot2.scheduleAll(ctx)
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- Ausführen

    fun handle(ctx: Context, job: String, intent: Intent? = null) {
        try {
            when (job) {
                J_BED -> { if (on(ctx, "sleep")) sleepOn(ctx) }
                J_WAKE -> { if (on(ctx, "sleep")) sleepOff(ctx); restoreBrightness(ctx, "night"); if (on(ctx, "morning_brief")) sp(ctx).edit().putBoolean("brief_pending", true).apply() }
                J_NIGHT -> if (on(ctx, "goodnight")) goodNight(ctx)
                J_EVENING -> evening(ctx)
                J_MORNING -> morning(ctx)
                J_SCHOOL_ON -> if (on(ctx, "school")) schoolOn(ctx)
                J_SCHOOL_OFF -> if (on(ctx, "school")) schoolOff(ctx)
                J_SCREEN -> if (on(ctx, "screen_coach")) screenSummary(ctx)
                J_WEEKLY -> { if (on(ctx, "weekly")) weekly(ctx); if (on(ctx, "cleanup")) cleanup(ctx) }
                J_WAKECHECK -> if (on(ctx, "wake_call")) wakeCheck(ctx)
                J_TRADE -> if (on(ctx, "trade_bot")) {
                    val r = DemoDepot.botRun(ctx)
                    // Nur melden, wenn wirklich gehandelt wurde
                    if (r.isNotEmpty()) post(ctx, 88_600 + (System.currentTimeMillis() / 60_000 % 50).toInt(), "🤖 Demo-Bot hat gehandelt (Spielgeld)",
                        r.joinToString("\n") + "\n\n" + DemoDepot.summary(ctx).lines().first(), emptyList(),
                        PendingIntent.getActivity(ctx, 88_601, Intent(ctx, TradingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
                }
                else -> AutoPilot2.handle(ctx, job, intent)
            }
        } catch (_: Exception) {}
        scheduleAll(ctx)
    }

    fun cancel(ctx: Context, id: Int) = nm(ctx).cancel(id)

    fun post(ctx: Context, id: Int, title: String, text: String, actions: List<Notification.Action> = emptyList(), open: PendingIntent? = null) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(android.app.NotificationChannel(CH, "Autopilot", NotificationManager.IMPORTANCE_DEFAULT))
        Notifier.post(ctx, CH, id, title, text, actions, open)
    }

    /** Öffnet Jarvis und stellt dort direkt eine Frage (Antwort + Hologramm). */
    fun askIntent(ctx: Context, rc: Int, question: String): PendingIntent = PendingIntent.getActivity(ctx, rc,
        Intent(ctx, JarvisActivity::class.java).putExtra("ask", question).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Öffnet den Chat mit vorbereitetem Text – abschicken tust du selbst. */
    fun chatIntent(ctx: Context, rc: Int, text: String): PendingIntent = PendingIntent.getActivity(ctx, rc,
        Intent(ctx, ChatActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra("send", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun action(title: String, p: PendingIntent) = Notification.Action.Builder(null, title, p).build()

    // ---------- 1 Schlafmodus

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)!!

    private fun sleepOn(ctx: Context) {
        val done = mutableListOf<String>()
        val n = nm(ctx)
        if (n.isNotificationPolicyAccessGranted) {
            putInt(ctx, "prev_filter", n.currentInterruptionFilter)
            try { n.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY); done += "Nicht stören" } catch (_: Exception) {}
        } else if (ProShell.ready && ProShell.run("cmd notification set_dnd priority").first == 0) { putInt(ctx, "prev_filter", -2); done += "Nicht stören" }
        if (ProShell.ready) {
            val cur = ProShell.run("cmd uimode night").second
            sp(ctx).edit().putBoolean("prev_dark", cur.contains("yes")).apply()
            if (ProShell.run("cmd uimode night yes").first == 0) done += "Dunkelmodus"
        }
        if (dim(ctx, "sleep", 15)) done += "Bildschirm dunkel"
        val alarm = ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
        val alarmTxt = alarm?.let { "Wecker: " + java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().toString().take(5) }
            ?: "⚠ Kein Wecker gestellt!"
        post(ctx, 88_101, "🌙 Schlafmodus an", (if (done.isEmpty()) "" else done.joinToString(", ") + ". ") + alarmTxt +
            "\nMorgen um ${wake(ctx).toString().take(5)} stelle ich alles zurück.")
    }

    private fun sleepOff(ctx: Context) {
        val n = nm(ctx)
        val prev = int(ctx, "prev_filter", 0)
        if (prev > 0 && n.isNotificationPolicyAccessGranted) try { n.setInterruptionFilter(prev) } catch (_: Exception) {}
        if (prev == -2 && ProShell.ready) ProShell.run("cmd notification set_dnd off")
        putInt(ctx, "prev_filter", 0)
        if (ProShell.ready && sp(ctx).contains("prev_dark")) {
            if (!sp(ctx).getBoolean("prev_dark", false)) ProShell.run("cmd uimode night no")
            sp(ctx).edit().remove("prev_dark").apply()
        }
        restoreBrightness(ctx, "sleep")
        nm(ctx).cancel(88_101)
    }

    /** Helligkeit senken (merkt sich den alten Wert). */
    private fun dim(ctx: Context, tag: String, level: Int): Boolean {
        if (!Settings.System.canWrite(ctx)) return false
        return try {
            val cr = ctx.contentResolver
            if (!sp(ctx).contains("bri_$tag")) sp(ctx).edit()
                .putInt("bri_$tag", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128))
                .putInt("brimode_$tag", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)).apply()
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, level)
            true
        } catch (_: Exception) { false }
    }

    private fun restoreBrightness(ctx: Context, tag: String) {
        if (!sp(ctx).contains("bri_$tag") || !Settings.System.canWrite(ctx)) return
        try {
            val cr = ctx.contentResolver
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, sp(ctx).getInt("bri_$tag", 128))
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, sp(ctx).getInt("brimode_$tag", 0))
        } catch (_: Exception) {}
        sp(ctx).edit().remove("bri_$tag").remove("brimode_$tag").apply()
    }

    /** Verschlafen-Schutz: Wecker hat geklingelt, aber das Handy wurde seitdem nicht entsperrt → Jarvis ruft an. */
    private fun wakeCheck(ctx: Context) {
        val alarm = sp(ctx).getLong("alarm_at", 0L)
        if (alarm == 0L || System.currentTimeMillis() - alarm > 60 * 60_000L) return
        if (sp(ctx).getLong("last_present", 0L) >= alarm) return
        val km = ctx.getSystemService(android.app.KeyguardManager::class.java)
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        if (pm?.isInteractive == true && km?.isKeyguardLocked == false) return   // Handy wird gerade benutzt
        val hhmm = java.time.Instant.ofEpochMilli(alarm).atZone(ZoneId.systemDefault()).toLocalTime().toString().take(5)
        val first = firstLesson(ctx, LocalDate.now().dayOfWeek)
        JarvisCall.ring(ctx, "Guten Morgen! Dein Wecker hat um $hhmm geklingelt und du warst noch nicht am Handy. Zeit aufzustehen!" +
            (first?.let { " Die erste Stunde beginnt um ${it.toString().take(5)}." } ?: ""))
    }

    /** Erstes Entsperren am Morgen → Briefing. Wird von [Triggers] aufgerufen. */
    fun userPresent(ctx: Context) {
        usageResume()
        sp(ctx).edit().putLong("last_present", System.currentTimeMillis()).apply()
        if (!sp(ctx).getBoolean("brief_pending", false)) return
        if (LocalTime.now().hour >= 12) { sp(ctx).edit().putBoolean("brief_pending", false).apply(); return }
        sp(ctx).edit().putBoolean("brief_pending", false).apply()
        Thread {
            val txt = try { BriefingTool.build(ctx, "heute").removePrefix("OK:").trim() } catch (_: Exception) { return@Thread }
            post(ctx, 88_102, "☀️ Dein Morgen", txt, listOf(action("Vorlesen", askIntent(ctx, 88_202, "Briefing"))))
        }.start()
    }

    // ---------- 2 + 3 Abend-Check und Wecker nach Stundenplan

    /** Erste Unterrichtszeit eines Tages, z. B. 08:00 (oder null). */
    fun firstLesson(ctx: Context, d: DayOfWeek): LocalTime? = SchoolTool.lessons(ctx, d)
        .mapNotNull { Regex("^(\\d{1,2})[:.](\\d{2})").find(it.trim()) }
        .mapNotNull { runCatching { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.getOrNull() }.minOrNull()

    /** Ende des Unterrichts: „08:00-09:30 Mathe“ → 09:30, sonst letzter Beginn + 45 Min. */
    fun lastLessonEnd(ctx: Context, d: DayOfWeek): LocalTime? {
        val l = SchoolTool.lessons(ctx, d)
        val ends = l.mapNotNull { s ->
            Regex("^(\\d{1,2})[:.](\\d{2})\\s*[-–]\\s*(\\d{1,2})[:.](\\d{2})").find(s.trim())?.let {
                runCatching { LocalTime.of(it.groupValues[3].toInt(), it.groupValues[4].toInt()) }.getOrNull() }
                ?: Regex("^(\\d{1,2})[:.](\\d{2})").find(s.trim())?.let {
                    runCatching { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()).plusMinutes(45) }.getOrNull() }
        }
        return ends.maxOrNull()
    }

    private fun evening(ctx: Context) {
        val tomorrow = LocalDate.now().plusDays(1)
        val lines = mutableListOf<String>()
        val actions = mutableListOf<Notification.Action>()
        val first = firstLesson(ctx, tomorrow.dayOfWeek)
        if (on(ctx, "evening")) {
            SchoolTool.summary(ctx, tomorrow.dayOfWeek)?.let { lines += "🏫 Morgen $it" }
            try {
                val z = ZoneId.systemDefault()
                val from = tomorrow.atStartOfDay(z).toInstant().toEpochMilli()
                val ev = if (ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    CalendarTool.events(ctx, from, from + 86_400_000L) else emptyList()
                if (ev.isNotEmpty()) lines += "📅 " + ev.take(4).joinToString(", ") { e ->
                    (if (e.allDay) "" else java.time.Instant.ofEpochMilli(e.begin).atZone(z).toLocalTime().toString().take(5) + " ") + e.title }
            } catch (_: Exception) {}
            val hw = AgendaStore(ctx).tasks().filter { !it.done && it.category == SchoolTool.HOMEWORK }
            if (hw.isNotEmpty()) lines += "✏️ Offene Hausaufgaben: " + hw.take(4).joinToString(", ") { it.title }
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val lvl = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            if (lvl in 0..49 && bm?.isCharging != true) lines += "🔋 Akku $lvl % – heute Nacht laden."
            if (SchoolTool.lessons(ctx, tomorrow.dayOfWeek).isNotEmpty())
                actions += action("Packliste", askIntent(ctx, 88_204, "Packliste für morgen"))
        }
        if (on(ctx, "alarm_plan") && first != null) {
            val wakeAt = first.minusMinutes(lead(ctx).toLong())
            val alarm = ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
            val z = ZoneId.systemDefault()
            val target = LocalDateTime.of(tomorrow, wakeAt).atZone(z).toInstant().toEpochMilli()
            val hhmm = wakeAt.toString().take(5)
            if (alarm != null && alarm <= target + 5 * 60_000L && alarm >= target - 90 * 60_000L) {
                lines += "⏰ Wecker steht schon (" + java.time.Instant.ofEpochMilli(alarm).atZone(z).toLocalTime().toString().take(5) + ") – passt zur ersten Stunde um ${first.toString().take(5)}."
            } else {
                lines += "⏰ Erste Stunde ${first.toString().take(5)} → Wecker-Vorschlag: $hhmm" + (if (alarm == null) " (noch kein Wecker gestellt!)" else "")
                val set = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, wakeAt.hour).putExtra(AlarmClock.EXTRA_MINUTES, wakeAt.minute)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Schule").putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                actions.add(0, action("Wecker $hhmm stellen", PendingIntent.getActivity(ctx, 88_205, set, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)))
            }
        }
        if (lines.isEmpty()) return
        post(ctx, 88_103, "📋 Abend-Check", lines.joinToString("\n"), actions.take(3))
    }

    // ---------- 4 Schulmodus

    fun planSchool(ctx: Context) {
        if (!on(ctx, "school")) { at(ctx, J_SCHOOL_ON, null); at(ctx, J_SCHOOL_OFF, null); return }
        val now = LocalTime.now()
        val today = LocalDate.now().dayOfWeek
        val start = firstLesson(ctx, today)?.minusMinutes(5)
        val end = lastLessonEnd(ctx, today)?.plusMinutes(5)
        val z = ZoneId.systemDefault()
        fun ms(t: LocalTime) = LocalDateTime.of(LocalDate.now(), t).atZone(z).toInstant().toEpochMilli()
        at(ctx, J_SCHOOL_ON, if (start != null && start.isAfter(now)) ms(start) else null)
        at(ctx, J_SCHOOL_OFF, if (end != null && end.isAfter(now)) ms(end) else null)
        // Läuft gerade Unterricht und der Schulmodus ist noch nicht an → jetzt einschalten
        if (start != null && end != null && now.isAfter(start) && now.isBefore(end) && !sp(ctx).contains("prev_ringer")) schoolOn(ctx)
    }

    private fun schoolOn(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        if (sp(ctx).contains("prev_ringer")) return
        putInt(ctx, "prev_ringer", am.ringerMode)
        try { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE } catch (_: Exception) {
            if (ProShell.ready) ProShell.run("cmd audio set-ringer-mode VIBRATE")
        }
        post(ctx, 88_104, "🏫 Schulmodus an", "Handy vibriert nur. Nach der letzten Stunde stelle ich den Ton wieder an.")
    }

    private fun schoolOff(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val prev = int(ctx, "prev_ringer", -1)
        sp(ctx).edit().remove("prev_ringer").apply()
        if (prev >= 0) try { am.ringerMode = prev } catch (_: Exception) {}
        nm(ctx).cancel(88_104)
        val hw = AgendaStore(ctx).tasks().filter { !it.done && it.category == SchoolTool.HOMEWORK }
        post(ctx, 88_105, "🏫 Schule aus – Ton ist wieder an",
            if (hw.isEmpty()) "Keine offenen Hausaufgaben eingetragen. Schönen Nachmittag!"
            else "Offene Hausaufgaben: " + hw.take(5).joinToString(", ") { it.title },
            if (hw.isEmpty()) emptyList() else listOf(action("Lernen starten", PendingIntent.getActivity(ctx, 88_206,
                Intent(ctx, FocusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))))
    }

    // ---------- 5 Akku-Wächter

    fun batteryChanged(ctx: Context, level: Int, prev: Int) {
        try { AutoPilot2.battery(ctx, level, prev) } catch (_: Exception) {}
        if (!on(ctx, "battery")) return
        if (level <= 20 && prev > 20 && !sp(ctx).getBoolean("bat_guard", false)) {
            val done = mutableListOf<String>()
            if (ProShell.ready && ProShell.run("settings put global low_power 1").first == 0) done += "Energiesparmodus an"
            if (dim(ctx, "bat", 40)) done += "Bildschirm dunkler"
            sp(ctx).edit().putBoolean("bat_guard", true).apply()
            post(ctx, 88_106, "🔋 Akku-Wächter: $level %",
                (if (done.isEmpty()) "Akku wird knapp. Tipp: Energiesparmodus anschalten (oder Profi-Modus einrichten, dann mache ich das)."
                 else done.joinToString(", ") + ".") + " Sobald du lädst, stelle ich alles zurück.")
        }
    }

    fun charging(ctx: Context) {
        if (!sp(ctx).getBoolean("bat_guard", false)) return
        sp(ctx).edit().putBoolean("bat_guard", false).apply()
        if (ProShell.ready) ProShell.run("settings put global low_power 0")
        restoreBrightness(ctx, "bat")
        nm(ctx).cancel(88_106)
    }

    // ---------- 6 Aufräum-Hinweis

    private fun cleanup(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val olderThan = System.currentTimeMillis() / 1000 - 30L * 86_400
        val uris = mutableListOf<android.net.Uri>()
        var bytes = 0L
        try {
            ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED} < ?",
                arrayOf("%Screenshots%", olderThan.toString()), null)?.use { c ->
                while (c.moveToNext() && uris.size < 400) {
                    uris += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    bytes += c.getLong(1)
                }
            }
        } catch (_: Exception) { return }
        if (uris.size < 15) return
        val mb = bytes / 1_048_576
        val del = try { MediaStore.createDeleteRequest(ctx.contentResolver, uris) } catch (_: Exception) { null }
        post(ctx, 88_107, "🧹 Zeit zum Aufräumen?",
            "${uris.size} Screenshots sind älter als 30 Tage (≈ $mb MB). Beim Antippen fragt Android nochmal nach – erst dann wird gelöscht.",
            if (del != null) listOf(action("Ansehen & löschen", del)) else emptyList())
    }

    // ---------- 8 Geburtstage + 17 Fakt des Tages + Schulmodus planen

    private fun morning(ctx: Context) {
        planSchool(ctx)
        try { Vehicles.dailyCheck(ctx) } catch (_: Exception) {}
        if (on(ctx, "birthdays") && once(ctx, "bday")) {
            BirthdayTool.upcoming(ctx, 0).forEachIndexed { i, b ->
                if (i > 3) return@forEachIndexed
                val age = b.third?.let { " (wird $it)" } ?: ""
                post(ctx, 88_110 + i, "🎂 ${b.first} hat heute Geburtstag$age",
                    "Soll ich dir einen Gruß vorbereiten? Du kannst ihn vor dem Senden noch ändern.",
                    listOf(action("Gruß vorbereiten", chatIntent(ctx, 88_210 + i,
                        "Schreib mir einen kurzen, herzlichen Geburtstagsgruß an ${b.first}$age, den ich per Nachricht schicken kann. Noch nicht senden."))))
            }
        }
        if (on(ctx, "fact") && once(ctx, "fact")) {
            val f = Facts.today()
            post(ctx, 88_120, "💡 Fakt des Tages", f.first, listOf(action("Mehr dazu", askIntent(ctx, 88_220, f.second))),
                askIntent(ctx, 88_221, f.second))
        }
    }

    // ---------- 12 Pausen + 13 Bildschirmzeit (Zähler im Speicher, Summen in den Einstellungen)

    private val h = Handler(Looper.getMainLooper())
    @Volatile private var curPkg: String? = null
    @Volatile private var curLabel: String = ""
    @Volatile private var since = 0L
    @Volatile private var screenOnSince = 0L
    @Volatile private var appCtx: Context? = null
    private var lastBreak = 0L

    private val ticker = object : Runnable {
        override fun run() {
            val c = appCtx ?: return
            flush(c)
            if (on(c, "breaks") && screenOnSince > 0) {
                val now = System.currentTimeMillis()
                if (now - screenOnSince >= 60 * 60_000L && now - lastBreak >= 60 * 60_000L) {
                    lastBreak = now
                    val min = ((now - screenOnSince) / 60_000L).toInt()
                    post(c, 88_130, "💧 Kurze Pause?", "Du bist seit $min Minuten am Handy. Ein Glas Wasser trinken und 20 Sekunden in die Ferne schauen – tut den Augen gut.")
                }
            }
            h.postDelayed(this, 5 * 60_000L)
        }
    }

    fun screenOn(ctx: Context) {
        appCtx = ctx.applicationContext
        if (screenOnSince == 0L) screenOnSince = System.currentTimeMillis()
        h.removeCallbacks(ticker); h.postDelayed(ticker, 5 * 60_000L)
    }

    fun screenOff(ctx: Context) {
        appCtx = ctx.applicationContext
        flush(ctx); since = 0L
        screenOnSince = 0L; lastBreak = 0L
        h.removeCallbacks(ticker)
    }

    private fun usageResume() { if (curPkg != null && since == 0L) since = System.currentTimeMillis() }

    /** Von der Bedienungshilfe: App im Vordergrund gewechselt. */
    fun appChanged(ctx: Context, pkg: String, label: String) {
        appCtx = ctx.applicationContext
        flush(ctx)
        curPkg = pkg; curLabel = label; since = System.currentTimeMillis()
        try { AutoPilot2.appOpened(ctx, pkg, label) } catch (_: Exception) {}
        if (screenOnSince == 0L) screenOn(ctx)
    }

    private fun dayKey(d: LocalDate = LocalDate.now()) = "usage_$d"

    @Synchronized private fun flush(ctx: Context) {
        val pkg = curPkg ?: return
        if (since == 0L || !on(ctx, "screen_coach")) return
        val now = System.currentTimeMillis()
        val sec = ((now - since) / 1000).coerceIn(0, 3600)
        since = now
        if (sec < 2 || pkg.contains("launcher") || pkg == ctx.packageName) return
        val o = try { JSONObject(sp(ctx).getString(dayKey(), "{}")) } catch (_: Exception) { JSONObject() }
        val total = o.optLong(curLabel) + sec
        o.put(curLabel, total)
        sp(ctx).edit().putString(dayKey(), o.toString()).apply()
        // Limit überschritten?
        limits(ctx)[curLabel.lowercase()]?.let { lim ->
            if (total >= lim * 60L && once(ctx, "limit_" + curLabel.lowercase()))
                post(ctx, 88_131, "📱 $curLabel: Limit erreicht", "Heute schon ${total / 60} Minuten $curLabel (dein Limit: $lim Min.). Zeit für was anderes?")
        }
    }

    /** Limits: „TikTok=60, Instagram=45“ → kleingeschriebener App-Name → Minuten. */
    fun limits(ctx: Context): Map<String, Int> = str(ctx, "limits", "").split(',', ';', '\n').mapNotNull { part ->
        val kv = part.split('=', ':'); if (kv.size != 2) null else kv[1].trim().toIntOrNull()?.let { kv[0].trim().lowercase() to it }
    }.filter { it.first.isNotBlank() }.toMap()

    fun usage(ctx: Context, d: LocalDate = LocalDate.now()): List<Pair<String, Long>> {
        if (d == LocalDate.now()) flush(ctx)
        val o = try { JSONObject(sp(ctx).getString(dayKey(d), "{}")) } catch (_: Exception) { JSONObject() }
        return o.keys().asSequence().map { it to o.optLong(it) }.sortedByDescending { it.second }.toList()
    }

    fun fmt(sec: Long): String = if (sec >= 3600) "${sec / 3600} Std. ${(sec % 3600) / 60} Min." else "${sec / 60} Min."

    fun usageText(ctx: Context, d: LocalDate = LocalDate.now()): String {
        val u = usage(ctx, d)
        if (u.isEmpty()) return "Noch keine Bildschirmzeit gezählt (braucht die Bedienungshilfe von Jarvis)."
        return "Gesamt: ${fmt(u.sumOf { it.second })}\n" + u.take(5).joinToString("\n") { "• ${it.first}: ${fmt(it.second)}" }
    }

    private fun screenSummary(ctx: Context) {
        val u = usage(ctx)
        if (u.isEmpty()) return
        val total = u.sumOf { it.second }
        val yesterday = usage(ctx, LocalDate.now().minusDays(1)).sumOf { it.second }
        val cmp = if (yesterday > 0) { val diff = (total - yesterday) / 60; if (diff > 0) " (+$diff Min. ggü. gestern)" else " (${diff} Min. ggü. gestern)" } else ""
        post(ctx, 88_132, "📱 Bildschirmzeit heute: ${fmt(total)}$cmp", usageText(ctx))
        // alte Tage aufräumen (älter als 14 Tage)
        val cutoff = LocalDate.now().minusDays(14)
        val ed = sp(ctx).edit()
        sp(ctx).all.keys.filter { it.startsWith("usage_") }.forEach { k ->
            runCatching { LocalDate.parse(k.removePrefix("usage_")) }.getOrNull()?.let { if (it.isBefore(cutoff)) ed.remove(k) } }
        sp(ctx).all.keys.filter { it.startsWith("once_") && !it.endsWith(LocalDate.now().toString()) }.forEach { ed.remove(it) }
        ed.apply()
    }

    // ---------- 14 Gute-Nacht-Routine

    private fun goodNight(ctx: Context) {
        dim(ctx, "night", 35)
        val tomorrow = LocalDate.now().plusDays(1)
        val first = firstLesson(ctx, tomorrow.dayOfWeek)
        val alarm = ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
        val txt = buildString {
            append("In 30 Minuten ist Schlafenszeit. ")
            if (first != null) append("Morgen geht's um ${first.toString().take(5)} los. ")
            append(alarm?.let { "Wecker: " + java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().toString().take(5) + "." } ?: "Noch kein Wecker gestellt!")
        }
        post(ctx, 88_140, "😴 Gute Nacht", txt, listOf(
            action("Geschichte", askIntent(ctx, 88_240, "Erzähl mir eine kurze, ruhige Gute-Nacht-Geschichte zum Einschlafen.")),
            action("Einschlafmusik", askIntent(ctx, 88_241, "Spiel ruhige Einschlafmusik."))))
    }

    // ---------- 18 Wochenrückblick

    private fun weekly(ctx: Context) {
        val focus = FocusActivity.minutes(ctx, 7)
        var screen = 0L; var days = 0
        for (i in 0..6) { val s = usage(ctx, LocalDate.now().minusDays(i.toLong())).sumOf { it.second }; if (s > 0) { screen += s; days++ } }
        val tasks = AgendaStore(ctx).tasks()
        val open = tasks.count { !it.done }
        val hw = tasks.count { !it.done && it.category == SchoolTool.HOMEWORK }
        val bdays = BirthdayTool.upcoming(ctx, 7)
        val txt = buildString {
            append("⏱ Fokus/Lernen: ${focus} Min.")
            append(if (focus >= 120) " – stark! 💪\n" else if (focus > 0) "\n" else " – nächste Woche mal ein Fokus-Timer?\n")
            if (days > 0) append("📱 Bildschirm: Ø ${fmt(screen / days)} pro Tag\n")
            append("✅ Offene Aufgaben: $open" + (if (hw > 0) " (davon $hw Hausaufgaben)" else "") + "\n")
            if (bdays.isNotEmpty()) append("🎂 Nächste Woche: " + bdays.take(3).joinToString(", ") { BirthdayTool.describe(it) })
        }.trim()
        post(ctx, 88_150, "📊 Dein Wochenrückblick", txt, listOf(action("Woche planen", askIntent(ctx, 88_250, "Was steht nächste Woche an?"))))
    }
}

/** Empfängt die Autopilot-Wecker. */
class AutoPilotReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val job = intent.getStringExtra("job") ?: return
        val pr = goAsync()
        Thread { try { AutoPilot.handle(ctx.applicationContext, job, intent) } finally { pr.finish() } }.start()
    }
}

/** Offline-Fakten (geprüft, kindgerecht). Paar: Fakt, Frage an Jarvis zum Weitererklären. */
object Facts {
    private val LIST = listOf(
        "Ein Tag auf der Venus dauert länger als ein Jahr auf der Venus." to "Erklär mir, warum ein Tag auf der Venus länger dauert als ihr Jahr",
        "Der Saturn hat eine so geringe Dichte, dass er in einer riesigen Badewanne schwimmen würde." to "Erklär mir den Planeten Saturn",
        "Der T-Rex lebte zeitlich näher an uns Menschen als am Stegosaurus." to "Erzähl mir etwas über den T-Rex",
        "Ein menschliches Herz schlägt etwa 100.000 Mal am Tag." to "Erklär mir, wie das menschliche Herz funktioniert",
        "Der Brachiosaurus war so groß, dass er in ein Fenster im 4. Stock hätte schauen können." to "Erzähl mir etwas über den Brachiosaurus",
        "Oktopusse haben drei Herzen und blaues Blut." to "Warum haben Oktopusse drei Herzen?",
        "Licht von der Sonne braucht etwa 8 Minuten bis zur Erde." to "Wie lange braucht das Sonnenlicht zur Erde und warum?",
        "Auf dem Mond gibt es keinen Wind – die Fußspuren der Astronauten können dort sehr lange bleiben." to "Erzähl mir etwas über die Mondlandung",
        "Honig verdirbt praktisch nie – in ägyptischen Gräbern fand man jahrtausendealten Honig." to "Warum verdirbt Honig nicht?",
        "Ein Blitz ist etwa fünfmal heißer als die Oberfläche der Sonne." to "Wie entsteht ein Blitz?",
        "Der Ozean ist zu über 80 % noch nicht genau erforscht." to "Was weiß man über die Tiefsee?",
        "Ein Atom besteht fast nur aus leerem Raum." to "Erklär mir, wie ein Atom aufgebaut ist",
        "Bananen sind botanisch gesehen Beeren – Erdbeeren dagegen nicht." to "Warum ist eine Banane eine Beere?",
        "Der Mount Everest wächst jedes Jahr ein paar Millimeter." to "Warum wächst der Mount Everest?",
        "Haie gab es schon vor den Bäumen." to "Seit wann gibt es Haie?",
        "Der Triceratops hatte einen der größten Schädel aller Landtiere." to "Erzähl mir etwas über den Triceratops",
        "Auf dem Jupiter tobt ein Sturm, der größer ist als die Erde – der Große Rote Fleck." to "Erklär mir den Großen Roten Fleck auf dem Jupiter",
        "Ein Vulkan auf dem Mars, der Olympus Mons, ist etwa zweieinhalbmal so hoch wie der Mount Everest." to "Erzähl mir etwas über den Olympus Mons auf dem Mars",
        "Im menschlichen Körper wären alle Blutgefäße aneinandergereiht etwa 100.000 Kilometer lang." to "Wie funktioniert der Blutkreislauf?",
        "Die Internationale Raumstation umkreist die Erde etwa alle 90 Minuten." to "Erzähl mir etwas über die Raumstation ISS",
        "Die Sonne macht über 99 % der Masse unseres ganzen Sonnensystems aus." to "Erklär mir unser Sonnensystem",
        "Ein Teelöffel Material eines Neutronensterns würde Milliarden Tonnen wiegen." to "Was ist ein Neutronenstern?",
        "Der Ätna auf Sizilien ist einer der aktivsten Vulkane der Welt." to "Erzähl mir etwas über den Vulkan Ätna",
        "Wasser kann gleichzeitig kochen und gefrieren – am sogenannten Tripelpunkt." to "Was ist der Tripelpunkt von Wasser?",
        "Pinguine gibt es fast nur auf der Südhalbkugel." to "Wo leben Pinguine und warum?",
        "Die Erde ist keine perfekte Kugel – am Äquator ist sie etwas dicker." to "Warum ist die Erde am Äquator dicker?",
        "Ein Faultier braucht bis zu einen Monat, um ein Blatt zu verdauen." to "Warum sind Faultiere so langsam?",
        "Die erste Rakete im Weltall war 1944 eine deutsche V2." to "Erzähl mir die Geschichte der Raumfahrt-Raketen",
        "Schmetterlinge schmecken mit ihren Füßen." to "Wie schmecken Schmetterlinge mit den Füßen?",
        "Es gibt mehr Bäume auf der Erde als Sterne in der Milchstraße." to "Wie viele Bäume gibt es auf der Erde?",
        "Tintenfische können ihre Farbe in Sekundenbruchteilen ändern." to "Wie ändern Tintenfische ihre Farbe?",
        "Ein Kolibri kann rückwärts fliegen." to "Wie fliegt ein Kolibri?",
        "Diamanten bestehen aus reinem Kohlenstoff – genau wie Bleistiftminen aus Graphit." to "Wie entsteht ein Diamant?",
        "Der Schall reist in Wasser etwa viermal schneller als in Luft." to "Warum ist Schall im Wasser schneller?",
        "Die Chinesische Mauer ist vom Mond aus mit bloßem Auge NICHT zu sehen – ein bekannter Irrtum." to "Kann man die Chinesische Mauer aus dem Weltall sehen?",
        "Ein Elektron ist rund 1.800-mal leichter als ein Proton." to "Erklär mir Elektronen und Protonen",
        "Dinosaurier lebten auf allen Kontinenten – sogar in der Antarktis." to "Gab es Dinosaurier in der Antarktis?",
        "Der Planet Uranus rollt quasi auf der Seite um die Sonne." to "Warum liegt der Uranus auf der Seite?",
        "Eisbären haben schwarze Haut unter ihrem Fell." to "Warum haben Eisbären schwarze Haut?",
        "Das Herz eines Blauwals wiegt rund 180 Kilogramm." to "Erzähl mir etwas über das Herz des Blauwals",
    )
    fun today(): Pair<String, String> = LIST[(LocalDate.now().dayOfYear) % LIST.size]
}
