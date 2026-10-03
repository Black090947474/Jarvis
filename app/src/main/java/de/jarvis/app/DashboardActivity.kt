package de.jarvis.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import de.jarvis.app.Ui.Companion.AMBER
import de.jarvis.app.Ui.Companion.BG
import de.jarvis.app.Ui.Companion.CARD2
import de.jarvis.app.Ui.Companion.GREEN
import de.jarvis.app.Ui.Companion.MUTED
import de.jarvis.app.Ui.Companion.RED
import de.jarvis.app.Ui.Companion.SOFT
import de.jarvis.app.logic.LocalCommands
import de.jarvis.app.logic.TimeLogic
import de.jarvis.app.tools.AgendaStore
import de.jarvis.app.tools.AgendaTool
import de.jarvis.app.tools.ReminderTool
import de.jarvis.app.tools.Res
import de.jarvis.app.tools.TaskTool
import de.jarvis.app.tools.ToolContext
import de.jarvis.app.tools.WeatherTool
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Startbildschirm: Uhrzeit, Wetter, Termine, Erinnerungen, Aufgaben, Nachrichten, Schnellaktionen, Status. */
class DashboardActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var greeting: TextView
    private lateinit var dateText: TextView
    private lateinit var chips: LinearLayout
    private lateinit var weatherBox: LinearLayout
    private lateinit var todayBox: LinearLayout
    private lateinit var weekText: TextView
    private lateinit var msgBox: LinearLayout
    private var torchOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); prefs = Prefs(this)
        window.statusBarColor = BG; window.navigationBarColor = BG
        Notifier.channels(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(18), ui.px(22), ui.px(18), ui.px(36))
        }

        // ---- Kopf: Uhr, Datum, Begrüßung ----
        val head = ui.row()
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"; textSize = 54f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL); includeFontPadding = false
        })
        dateText = ui.text("", 15f, SOFT); left.addView(dateText)
        greeting = ui.text("", 14f, MUTED); left.addView(greeting)
        head.addView(ui.weighted(left))
        head.addView(ui.text("J", 26f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = ui.gradient(RED, Color.rgb(120, 10, 30), 40)
            layoutParams = LinearLayout.LayoutParams(ui.px(56), ui.px(56))
            setOnClickListener { talk() }
        })
        col.addView(head)

        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        // ---- Sprechen ----
        col.addView(ui.text("🎙  Mit Jarvis sprechen", 17f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = ui.gradient(RED, Color.rgb(150, 15, 40), 22)
            setPadding(0, ui.px(16), 0, ui.px(16))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(16) }
            setOnClickListener { talk() }
        })

        // ---- Wetter ----
        col.addView(ui.section("Wetter"))
        weatherBox = ui.card(); col.addView(weatherBox)

        // ---- Heute ----
        col.addView(ui.section("Heute"))
        todayBox = ui.card(); col.addView(todayBox)
        weekText = ui.text("", 13f, MUTED).apply { setPadding(ui.px(4), ui.px(8), 0, 0) }
        col.addView(weekText)

        // ---- Nachrichten ----
        col.addView(ui.section("Nachrichten"))
        msgBox = ui.card(); col.addView(msgBox)

        // ---- Schnellaktionen ----
        col.addView(ui.section("Schnellaktionen"))
        val grid = GridLayout(this).apply { columnCount = 3 }
        fun quick(icon: String, label: String, action: () -> Unit) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = ui.round(CARD2, 16, Ui.LINE)
                setPadding(ui.px(4), ui.px(14), ui.px(4), ui.px(12))
                setOnClickListener { action() }
            }
            box.addView(ui.text(icon, 22f).apply { gravity = Gravity.CENTER })
            box.addView(ui.text(label, 12f, SOFT).apply { gravity = Gravity.CENTER; maxLines = 1 })
            grid.addView(box, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; setMargins(ui.px(4), ui.px(4), ui.px(4), ui.px(4))
            })
        }
        quick("💬", "Chat") { startActivity(Intent(this, ChatActivity::class.java)) }
        quick("✅", "Aufgabe") { addTaskDialog() }
        quick("🔔", "Erinnerung") { addReminderDialog() }
        quick("⏱", "Timer") { timerDialog() }
        quick("🔦", "Taschenlampe") { toggleTorch() }
        quick("📅", "Kalender") { openCalendar() }
        quick("📋", "Aufgaben") { showTasks() }
        quick("🛡", "Berechtigungen") { startActivity(Intent(this, PermissionsActivity::class.java)) }
        quick("⚙", "Einstellungen") { startActivity(Intent(this, MainActivity::class.java)) }
        col.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(6) })

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        dateText.text = SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY).format(Date())
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val hi = when (h) { in 5..10 -> "Guten Morgen"; in 11..17 -> "Hallo"; in 18..22 -> "Guten Abend"; else -> "Gute Nacht" }
        greeting.text = hi + (if (prefs.userName.isNotBlank()) ", ${prefs.userName}" else "") + "."
        refreshChips()
        refreshWeather()
        refreshAgenda()
        refreshMessages()
    }

    private fun online(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun refreshChips() {
        chips.removeAllViews()
        val bm = getSystemService(BatteryManager::class.java)
        val bat = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        chips.addView(ui.chip(if (WakeWordService.isRunning) "● Hört zu" else "○ Wake-Word aus", WakeWordService.isRunning).apply {
            setOnClickListener { startActivity(Intent(this@DashboardActivity, MainActivity::class.java)) }
        })
        val hasKey = prefs.groqKey.isNotBlank() || prefs.geminiKey.isNotBlank() || prefs.anthropicKey.isNotBlank()
        chips.addView(ui.chip(if (hasKey) "KI bereit" else "KI-Schlüssel fehlt", hasKey))
        chips.addView(ui.chip(if (online()) "Online" else "Offline", online()))
        if (bat >= 0) chips.addView(ui.chip("Akku $bat %", bat > 20))
        chips.addView(ui.chip(if (prefs.proactive) "Hinweise an" else "Hinweise aus", prefs.proactive).apply {
            setOnClickListener { startActivity(Intent(this@DashboardActivity, PermissionsActivity::class.java)) }
        })
    }

    // ---------- Wetter ----------

    private fun showWeather() {
        weatherBox.removeAllViews()
        val c = runCatching { JSONObject(prefs.weatherCache) }.getOrNull()
        if (c == null) {
            weatherBox.addView(ui.text(if (online()) "Lade Wetter …" else "Kein Wetter (offline).", 14f, MUTED))
            return
        }
        val r = ui.row()
        r.addView(ui.text("${c.optInt("t")}°", 40f, Color.WHITE).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(14), 0, 0, 0) }
        info.addView(ui.text(c.optString("desc").replaceFirstChar { it.uppercase() }, 16f, Color.WHITE, true))
        val rain = c.optInt("rain", -1)
        info.addView(ui.text("${c.optInt("min")}° / ${c.optInt("max")}°" + (if (rain >= 0) " · Regen $rain %" else "") +
            " · ${c.optString("place")}", 13f, MUTED))
        val age = (System.currentTimeMillis() - prefs.weatherTime) / 60_000
        if (age > 90) info.addView(ui.text("Stand vor ${if (age < 60 * 24) "${age / 60} Std." else "${age / 1440} Tagen"}", 11f, AMBER))
        r.addView(ui.weighted(info))
        weatherBox.addView(r)
        weatherBox.setOnClickListener { askJarvis("Wie wird das Wetter heute und morgen?") }
    }

    private fun refreshWeather() {
        showWeather()
        if (!online() || System.currentTimeMillis() - prefs.weatherTime < 30 * 60_000) return
        thread {
            val r = try { WeatherTool.fetch(applicationContext) } catch (e: Exception) { Res.error(e.message ?: "") }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (Res.isError(r) && prefs.weatherCache.isBlank()) {
                    weatherBox.removeAllViews(); weatherBox.addView(ui.text(Res.spoken(r), 13f, MUTED))
                } else showWeather()
            }
        }
    }

    // ---------- Termine, Erinnerungen, Aufgaben ----------

    private fun refreshAgenda() {
        thread {
            val (from, to) = TimeLogic.range("heute")
            val ag = AgendaTool.collect(applicationContext, from, to)
            val (wf, wt) = TimeLogic.range("woche")
            val week = AgendaTool.summary(AgendaTool.collect(applicationContext, wf, wt), "woche", false)
            runOnUiThread { if (!isDestroyed) showAgenda(ag, week) }
        }
    }

    private fun showAgenda(ag: AgendaTool.Agenda, week: String) {
        todayBox.removeAllViews()
        weekText.text = "Woche: " + week.removePrefix("Diese Woche: ").removePrefix("Diese Woche ")
        if (!ag.calendarAllowed) todayBox.addView(ui.text("Kalender nicht freigegeben – tippen zum Erlauben", 13f, AMBER).apply {
            setOnClickListener { startActivity(Intent(this@DashboardActivity, PermissionsActivity::class.java)) }
            setPadding(0, 0, 0, ui.px(8))
        })
        if (ag.isEmpty()) { todayBox.addView(ui.text("Heute steht nichts an. 🎉", 15f, SOFT)); return }
        val now = System.currentTimeMillis()
        for (e in ag.events) {
            val r = ui.row().apply { setPadding(0, ui.px(5), 0, ui.px(5)) }
            val past = !e.allDay && e.end < now
            r.addView(ui.text(if (e.allDay) "ganzt." else TimeLogic.time(e.begin), 14f, if (past) MUTED else RED, true).apply { width = ui.px(64) })
            r.addView(ui.weighted(ui.text(e.title + (if (e.location.isNotBlank()) "  ·  ${e.location}" else ""), 15f, if (past) MUTED else Color.WHITE).apply { maxLines = 2 }))
            r.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            todayBox.addView(r)
        }
        for (rem in ag.reminders) {
            val r = ui.row().apply { setPadding(0, ui.px(5), 0, ui.px(5)) }
            r.addView(ui.text("🔔 " + TimeLogic.time(rem.next!!), 14f, AMBER, true).apply { width = ui.px(78) })
            r.addView(ui.weighted(ui.text(rem.text, 15f)))
            todayBox.addView(r)
        }
        for (t in ag.overdue + ag.tasks) {
            val r = ui.row().apply { setPadding(0, ui.px(5), 0, ui.px(5)) }
            val overdue = t in ag.overdue
            r.addView(ui.text("☐", 18f, if (overdue) RED else GREEN).apply { width = ui.px(30) })
            r.addView(ui.weighted(ui.text(t.title + (if (overdue) "  (überfällig)" else "") + (if (t.priority == "hoch") "  !" else ""), 15f)))
            r.setOnClickListener { completeTask(t.id) }
            todayBox.addView(r)
        }
    }

    private fun completeTask(id: Int) = thread {
        val msg = TaskTool.complete(applicationContext, id)
        runOnUiThread { if (msg != null) toast(msg); refreshAgenda() }
    }

    // ---------- Nachrichten ----------

    private fun refreshMessages() {
        msgBox.removeAllViews()
        if (!prefs.feature("notifications_read")) { msgBox.addView(ui.text("Nachrichten-Zugriff im Berechtigungszentrum ausgeschaltet.", 13f, MUTED)); return }
        if (!JarvisNotificationListener.isConnected) {
            msgBox.addView(ui.text("Jarvis darf Benachrichtigungen nicht lesen – tippen zum Erlauben.", 13f, AMBER))
            msgBox.setOnClickListener { startActivity(Intent(this, PermissionsActivity::class.java)) }
            return
        }
        val list = JarvisNotificationListener.recent(null, 40)
        if (list.isEmpty()) { msgBox.addView(ui.text("Keine neuen Nachrichten.", 14f, SOFT)); return }
        val byApp = list.groupBy { it.app }.entries.sortedByDescending { it.value.size }
        msgBox.addView(ui.text(byApp.joinToString("  ·  ") { "${it.key} ${it.value.size}" }, 14f, Color.WHITE, true))
        list.take(3).forEach { m -> msgBox.addView(ui.text("${m.title}: ${m.text}", 13f, MUTED).apply { maxLines = 1; setPadding(0, ui.px(4), 0, 0) }) }
        msgBox.setOnClickListener { askJarvis("Fass meine neuen Nachrichten kurz zusammen.") }
    }

    // ---------- Aktionen ----------

    private fun talk() = startActivity(Intent(this, JarvisActivity::class.java))

    private fun askJarvis(q: String) = startActivity(Intent(this, ChatActivity::class.java).setAction(Intent.ACTION_SEND)
        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, q))

    private fun input(hint: String, number: Boolean = false) = EditText(this).apply {
        this.hint = hint; setTextColor(Color.WHITE); setHintTextColor(MUTED)
        if (number) inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun dialog(title: String, views: List<EditText>, ok: () -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(20), ui.px(8), ui.px(20), 0) }
        views.forEach { box.addView(it) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title).setView(box).setPositiveButton("OK") { _, _ -> ok() }.setNegativeButton("Abbrechen", null).show()
    }

    private fun addTaskDialog() {
        val title = input("Aufgabe, z. B. Mathe lernen")
        val due = input("Fällig (optional): heute, morgen, Fr oder 24.12.")
        val prio = input("Priorität (optional): hoch, mittel, niedrig")
        dialog("Neue Aufgabe", listOf(title, due, prio)) {
            val a = JSONObject().put("action", "add").put("title", title.text.toString())
            parseDue(due.text.toString())?.let { a.put("due", it) }
            prio.text.toString().trim().takeIf { it.isNotBlank() }?.let { a.put("priority", it) }
            runTool { TaskTool.run(ToolContext(this), a) }
        }
    }

    /** "heute", "morgen", Wochentag oder TT.MM. → YYYY-MM-DD */
    private fun parseDue(s: String): String? {
        val t = s.trim().lowercase(Locale.GERMANY)
        if (t.isBlank()) return null
        val today = java.time.LocalDate.now()
        val d = when {
            t == "heute" -> today
            t == "morgen" -> today.plusDays(1)
            t == "übermorgen" -> today.plusDays(2)
            TimeLogic.parseDay(t) != null -> { val dow = TimeLogic.parseDay(t)!!; var x = today.plusDays(1); while (x.dayOfWeek != dow) x = x.plusDays(1); x }
            Regex("""(\d{1,2})\.(\d{1,2})\.?(\d{2,4})?""").matches(t) -> Regex("""(\d{1,2})\.(\d{1,2})\.?(\d{2,4})?""").find(t)!!.groupValues.let {
                val y = it[3].toIntOrNull()?.let { yy -> if (yy < 100) 2000 + yy else yy }
                var x = runCatching { java.time.LocalDate.of(y ?: today.year, it[2].toInt(), it[1].toInt()) }.getOrNull() ?: return null
                if (y == null && x.isBefore(today)) x = x.plusYears(1); x }
            else -> TimeLogic.parseLocal(t)?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
        } ?: return null
        return d.toString()
    }

    private fun addReminderDialog() {
        val text = input("Woran? z. B. Wäsche aufhängen")
        val time = input("Wann? z. B. 18:30, „in 20 Minuten“ oder „morgen 9 Uhr“")
        dialog("Neue Erinnerung", listOf(text, time)) {
            val w = time.text.toString().trim()
            val cmd = if (w.toIntOrNull() != null) null
                else LocalCommands.parse("erinnere mich ${if (w.firstOrNull()?.isDigit() == true && !w.contains("min")) "um $w" else w} an ${text.text}")
            val a = if (cmd != null && cmd.tool == "reminders") JSONObject(cmd.args).put("text", text.text.toString())
                else JSONObject().put("text", text.text.toString()).also { j ->
                    LocalCommands.parseClock(w.lowercase())?.let { (h, m) -> j.put("hour", h).put("minute", m).put("tomorrow", w.lowercase().contains("morgen")) }
                    w.toIntOrNull()?.let { j.remove("hour"); j.put("in_minutes", it) }
                }
            a.put("action", "add")
            runTool { ReminderTool.run(ToolContext(this), a) }
        }
    }

    private fun runTool(block: () -> String) = thread {
        val r = try { block() } catch (e: Exception) { Res.error(e.message ?: "Unbekannter Fehler") }
        runOnUiThread { toast(Res.spoken(r)); refreshAgenda() }
    }

    private fun timerDialog() {
        val min = input("Minuten", number = true)
        dialog("Timer", listOf(min)) {
            val m = min.text.toString().toIntOrNull() ?: return@dialog
            try {
                startActivity(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, m * 60)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                toast("Timer: $m Minuten")
            } catch (_: Exception) { toast("Keine Uhr-App gefunden.") }
        }
    }

    private fun toggleTorch() {
        val cm = getSystemService(CameraManager::class.java)!!
        try {
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return toast("Keine Taschenlampe gefunden.")
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
        } catch (e: Exception) { toast("Taschenlampe geht gerade nicht.") }
    }

    private fun openCalendar() {
        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also { ContentUris.appendId(it, System.currentTimeMillis()) }.build()
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { toast("Keine Kalender-App gefunden.") }
    }

    private fun showTasks() {
        val tasks = AgendaStore(this).tasks().filter { !it.done }.sortedWith(compareBy({ it.priority != "hoch" }, { it.due ?: Long.MAX_VALUE }))
        if (tasks.isEmpty()) { toast("Keine offenen Aufgaben."); return }
        val labels = tasks.map { t ->
            "☐ ${t.title}" + (t.due?.let { "  ·  " + if (t.hasTime) TimeLogic.short(it) else TimeLogic.day(it) } ?: "") +
                (if (t.category.isNotBlank()) "  ·  ${t.category}" else "") + (if (t.priority == "hoch") "  !" else "")
        }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Offene Aufgaben – antippen = erledigt")
            .setItems(labels) { _, i -> completeTask(tasks[i].id) }
            .setNegativeButton("Schließen", null).show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    @Suppress("unused") private val needed = Manifest.permission.READ_CALENDAR
}
