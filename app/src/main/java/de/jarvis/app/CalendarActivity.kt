package de.jarvis.app

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.CalendarContract
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.jarvis.app.Ui.Companion.BG
import de.jarvis.app.Ui.Companion.MUTED
import de.jarvis.app.Ui.Companion.RED
import de.jarvis.app.logic.TimeLogic
import de.jarvis.app.tools.AgendaStore
import de.jarvis.app.tools.CalendarTool
import de.jarvis.app.tools.SchoolTool
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

/** Kalender im Jarvis-Design: Wochenleiste und Tagesansicht mit Terminen, Schule, Erinnerungen und Aufgaben. */
class CalendarActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var monthText: TextView
    private lateinit var week: LinearLayout
    private lateinit var list: LinearLayout
    private var selected: LocalDate = LocalDate.now()
    private val zone = ZoneId.systemDefault()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.load(Prefs(this)); ui = Ui(this)
        window.statusBarColor = BG; window.navigationBarColor = BG
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(18), ui.px(16), ui.px(18), ui.px(28)) }

        val head = ui.row()
        head.addView(ui.text("‹", 30f, Color.WHITE).apply { setPadding(0, 0, ui.px(16), 0); setOnClickListener { finish() } })
        head.addView(ui.weighted(ui.text("Kalender", 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER }))
        head.addView(ui.text("+", 30f, RED).apply { setPadding(ui.px(16), 0, 0, 0); setOnClickListener { newEvent() } })
        col.addView(head)

        val nav = ui.card(false).apply { gravity = Gravity.CENTER_VERTICAL }
        nav.addView(ui.text("‹", 26f, Color.WHITE).apply { setPadding(ui.px(6), 0, ui.px(6), 0); setOnClickListener { selected = selected.minusWeeks(1); render() } })
        monthText = ui.text("", 17f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        nav.addView(ui.weighted(monthText))
        nav.addView(ui.text("›", 26f, Color.WHITE).apply { setPadding(ui.px(6), 0, ui.px(6), 0); setOnClickListener { selected = selected.plusWeeks(1); render() } })
        col.addView(nav)

        week = ui.card(false).apply { setPadding(ui.px(6), ui.px(10), ui.px(6), ui.px(10)) }
        col.addView(week)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        col.addView(ui.button("Frag Jarvis nach diesem Tag", filled = false) {
            startActivity(Intent(this, ChatActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Was steht am ${selected} an, und wann habe ich da Zeit?"))
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(col) })
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        monthText.text = selected.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMANY)).replaceFirstChar { it.uppercase() }
        week.removeAllViews()
        val monday = selected.minusDays((selected.dayOfWeek.value - 1).toLong())
        val names = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        for (i in 0..6) {
            val d = monday.plusDays(i.toLong())
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setOnClickListener { selected = d; render() } }
            cell.addView(ui.text(names[i], 12f, MUTED).apply { gravity = Gravity.CENTER })
            val sel = d == selected; val today = d == LocalDate.now()
            cell.addView(ui.text("${d.dayOfMonth}", 15f, Color.WHITE, sel).apply {
                gravity = Gravity.CENTER
                if (sel || today) background = GradientDrawable().apply { shape = GradientDrawable.OVAL
                    if (sel) setColor(RED) else setStroke(ui.px(1), RED) }
            }, LinearLayout.LayoutParams(ui.px(34), ui.px(34)).apply { topMargin = ui.px(6) })
            week.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        list.removeAllViews()
        list.addView(ui.text("Lade …", 13f, MUTED).apply { setPadding(ui.px(4), ui.px(12), 0, 0) })
        val day = selected
        thread {
            val from = day.atStartOfDay(zone).toInstant().toEpochMilli(); val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            data class Item(val time: String, val sort: Long, val title: String, val sub: String, val color: Int, val open: (() -> Unit)?)
            val items = mutableListOf<Item>()
            val calOk = checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
            for (e in CalendarTool.safeEvents(this, from, to)) items += Item(if (e.allDay) "ganzt." else TimeLogic.time(e.begin), if (e.allDay) from - 1 else e.begin,
                e.title, listOf(if (e.allDay) "" else "bis ${TimeLogic.time(e.end)}", e.location).filter { it.isNotBlank() }.joinToString(" · "), RED) {
                startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))) }
            for (l in SchoolTool.lessons(this, day.dayOfWeek)) {
                val m = Regex("^(\\d{1,2})[:.](\\d{2})\\s*(.*)").find(l)
                val t = m?.let { "%02d:%s".format(it.groupValues[1].toInt(), it.groupValues[2]) } ?: "Schule"
                items += Item(t, m?.let { day.atTime(it.groupValues[1].toInt(), it.groupValues[2].toInt()).atZone(zone).toInstant().toEpochMilli() } ?: from,
                    m?.groupValues?.get(3)?.ifBlank { l } ?: l, "Schule", Color.rgb(255, 140, 60), null)
            }
            val store = AgendaStore(this)
            for (r in store.reminders().filter { it.active && it.next != null && it.next in from until to })
                items += Item(TimeLogic.time(r.next!!), r.next, r.text, "Erinnerung", Color.rgb(255, 196, 0), null)
            for (t in store.tasks().filter { !it.done && it.due != null && it.due in from until to })
                items += Item(if (t.hasTime) TimeLogic.time(t.due!!) else "Aufgabe", t.due!!, t.title, t.category.ifBlank { "Aufgabe" } + " · Priorität ${t.priority}", Color.rgb(50, 220, 130), null)
            runOnUiThread {
                if (isDestroyed || day != selected) return@runOnUiThread
                list.removeAllViews()
                if (!calOk) list.addView(ui.text("Kalender-Zugriff fehlt – unter Berechtigungen erlauben.", 13f, Ui.AMBER).apply {
                    setPadding(ui.px(4), ui.px(10), 0, 0); setOnClickListener { startActivity(Intent(this@CalendarActivity, PermissionsActivity::class.java)) } })
                if (items.isEmpty()) { list.addView(ui.card().apply { addView(ui.text("Nichts geplant.", 15f, Ui.SOFT)) }); return@runOnUiThread }
                for (it in items.sortedBy { it.sort }) {
                    val row = ui.card(false).apply { gravity = Gravity.CENTER_VERTICAL; it.open?.let { o -> setOnClickListener { o() } } }
                    row.addView(ui.text(it.time, 14f, Ui.SOFT).apply { width = ui.px(64) })
                    row.addView(View(this).apply { background = GradientDrawable().apply { setColor(it.color); cornerRadius = 3 * ui.dp } },
                        LinearLayout.LayoutParams(ui.px(4), ui.px(40)).apply { rightMargin = ui.px(12) })
                    val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    t.addView(ui.text(it.title, 16f, Color.WHITE, true).apply { maxLines = 2 })
                    if (it.sub.isNotBlank()) t.addView(ui.text(it.sub, 12f, MUTED).apply { maxLines = 1 })
                    row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
                    list.addView(row)
                }
            }
        }
    }

    private fun newEvent() {
        val start = selected.atTime(java.time.LocalTime.now().plusHours(1).withMinute(0)).atZone(zone).toInstant().toEpochMilli()
        try {
            startActivity(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 3_600_000))
        } catch (_: Exception) { android.widget.Toast.makeText(this, "Keine Kalender-App gefunden.", android.widget.Toast.LENGTH_SHORT).show() }
    }
}
