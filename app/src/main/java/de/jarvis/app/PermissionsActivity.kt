package de.jarvis.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import de.jarvis.app.Ui.Companion.AMBER
import de.jarvis.app.Ui.Companion.BG
import de.jarvis.app.Ui.Companion.GREEN
import de.jarvis.app.Ui.Companion.MUTED
import de.jarvis.app.Ui.Companion.RED
import de.jarvis.app.Ui.Companion.SOFT
import de.jarvis.app.tools.Geo
import kotlin.concurrent.thread

/**
 * JARVIS → Berechtigungen. Zeigt jede Freigabe mit Status und Erklärung, führt zur Android-Einstellung
 * und hat pro Funktion einen Jarvis-Schalter. Jarvis umgeht keine Sicherheitsmechanismen: Erteilen und
 * Entziehen passiert immer in den Android-Einstellungen bzw. im System-Dialog.
 */
class PermissionsActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout

    /** Eine Funktion von Jarvis: was sie tut, welche Freigabe sie braucht, welcher Schalter sie sperrt. */
    private class Item(
        val title: String,
        val why: String,
        val feature: String?,
        val granted: () -> Boolean,
        val grant: () -> Unit,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); prefs = Prefs(this)
        window.statusBarColor = BG; window.navigationBarColor = BG
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(18), ui.px(22), ui.px(18), ui.px(40)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(col) })
    }

    override fun onResume() { super.onResume(); build() }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun secureListHas(key: String) = (Settings.Secure.getString(contentResolver, key) ?: "").contains(packageName)
    private fun appSettings() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    private val asked = mutableSetOf<String>()

    private fun ask(vararg perms: String) {
        val p = perms.first()
        if (shouldShowRequestPermissionRationale(p) || p !in asked) { asked += p; requestPermissions(arrayOf(*perms), 7) }
        else appSettings() // schon abgelehnt → nur noch über die App-Einstellungen möglich
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        build()
        thread { Scheduler.scheduleAll(applicationContext) }
    }

    private fun items(): List<Item> {
        val photo = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        val list = mutableListOf(
            Item("Mikrofon", "Für „Hey Jarvis“ und Spracheingabe. Wird nur auf dem Handy ausgewertet, bis du sprichst.", null,
                { has(Manifest.permission.RECORD_AUDIO) }, { ask(Manifest.permission.RECORD_AUDIO) }),
            Item("Kalender", "Termine anzeigen, suchen, freie Zeiten finden, eintragen, verschieben, löschen – Ändern nur nach deinem Ja.", "calendar",
                { has(Manifest.permission.READ_CALENDAR) && has(Manifest.permission.WRITE_CALENDAR) },
                { ask(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR) }),
            Item("Erinnerungen", "Jarvis-Erinnerungen als Benachrichtigung – auch offline und nach Neustart.", "reminders",
                { exactAlarms() && notifOk() }, { if (!notifOk()) ask(Manifest.permission.POST_NOTIFICATIONS) else openExactAlarms() }),
            Item("Aufgaben", "Deine Aufgabenliste (bleibt nur auf diesem Handy).", "tasks", { true }, {}),
            Item("Telefon & Anrufliste", "Anrufen und „Wer hat mich zuletzt angerufen?“.", "calls",
                { has(Manifest.permission.CALL_PHONE) && has(Manifest.permission.READ_CALL_LOG) && has(Manifest.permission.READ_CONTACTS) },
                { ask(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS) }),
            Item("Nachrichten schreiben", "SMS/WhatsApp vorbereiten. Senden tippst du selbst – Jarvis sendet nie heimlich.", "messages",
                { has(Manifest.permission.READ_CONTACTS) }, { ask(Manifest.permission.READ_CONTACTS) }),
            Item("Benachrichtigungen lesen", "Neue Nachrichten und E-Mails vorlesen und zusammenfassen, antworten nur nach deinem Ja.", "notifications_read",
                { secureListHas("enabled_notification_listeners") }, { restrictedHint(); startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }),
            Item("Standort", "Wetter hier, „Was ist in meiner Nähe?“, Navigation, Zuhause speichern.", "location",
                { has(Manifest.permission.ACCESS_FINE_LOCATION) }, { ask(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) }),
        )
        if (Build.VERSION.SDK_INT >= 29) list += Item("Standort im Hintergrund",
            "Nur für ortsbasierte Erinnerungen („wenn ich zu Hause ankomme“). Android verlangt dafür „Immer erlauben“ in den Einstellungen.", "location",
            { has(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
            { if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) toast("Erst „Standort“ erlauben.") else ask(Manifest.permission.ACCESS_BACKGROUND_LOCATION) })
        list += listOf(
            Item("Fotos", "Neuestes Foto zeigen oder teilen, Fotos zählen.", "photos", { has(photo) }, { ask(photo) }),
            Item("Kamera", "Kamera-App öffnen (Foto, Selfie, Video). Braucht keine eigene Freigabe.", "camera", { true }, {}),
            Item("Bildschirmsteuerung", "Apps bedienen wie ein Mensch und WLAN/Bluetooth-Kacheln umschalten (Bedienungshilfe).", "screen",
                { secureListHas(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) }, { restrictedHint(); startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),
            Item("Dateien", "Datei-Assistent: Dokumente finden, PDFs/Word/Excel lesen und zusammenfassen (nur lesen, nie löschen).", "files",
                { Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager() },
                { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }),
            Item("Bluetooth-Geräte erkennen", "Für Automationen („Wenn Kopfhörer/Auto verbunden …“) und den Auto-Modus.", null,
                { Build.VERSION.SDK_INT < 31 || has(Manifest.permission.BLUETOOTH_CONNECT) },
                { if (Build.VERSION.SDK_INT >= 31) ask(Manifest.permission.BLUETOOTH_CONNECT) }),
            Item("Über anderen Apps einblenden", "Damit Automationen und „Hey Jarvis“ Aktionen auch im Hintergrund starten dürfen.", null,
                { Settings.canDrawOverlays(this) }, { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }),
            Item("Systemeinstellungen ändern", "Für die Bildschirmhelligkeit.", "settings",
                { Settings.System.canWrite(this) }, { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }),
        )
        return list
    }

    private fun notifOk() = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)
    private fun exactAlarms() = Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager::class.java)!!.canScheduleExactAlarms()
    private fun openExactAlarms() {
        if (Build.VERSION.SDK_INT >= 31) try {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        } catch (_: Exception) { appSettings() }
    }

    private fun restrictedHint() = Toast.makeText(this,
        "Ausgegraut oder „Eingeschränkte Einstellung“? Einstellungen → Apps → Jarvis → ⋮ → „Eingeschränkte Einstellungen zulassen“.",
        Toast.LENGTH_LONG).show()

    private fun build() {
        col.removeAllViews()
        col.addView(ui.text("Berechtigungen", 28f, Color.WHITE, true))
        col.addView(ui.text("Was Jarvis darf – und wofür. Erteilen und Entziehen geht nur über Android; mit dem Schalter " +
            "kannst du eine Funktion für Jarvis zusätzlich sperren.", 13f, MUTED).apply { setPadding(0, ui.px(6), 0, 0) })

        col.addView(ui.section("Zugriffe"))
        for (it in items()) col.addView(itemCard(it))
        col.addView(ui.button("Alle Android-Berechtigungen von Jarvis", filled = false) { appSettings() })

        buildProactive()
    }

    private fun itemCard(it: Item): LinearLayout {
        val c = ui.card()
        val ok = it.granted()
        val enabled = it.feature?.let { f -> prefs.feature(f) } ?: true
        val top = ui.row()
        top.addView(ui.text(if (ok) "✓" else "○", 18f, if (ok) GREEN else MUTED).apply { width = ui.px(28) })
        top.addView(ui.weighted(ui.text(it.title, 16f, Color.WHITE, true)))
        if (it.feature != null) top.addView(Switch(this).apply {
            isChecked = enabled
            contentDescription = "Jarvis-Zugriff auf ${it.title}"
            setOnCheckedChangeListener { _, on ->
                prefs.setFeature(it.feature, on)
                toast(if (on) "${it.title}: für Jarvis an" else "${it.title}: für Jarvis gesperrt")
                if (it.feature in setOf("reminders", "calendar")) thread { Scheduler.scheduleAll(applicationContext) }
            }
        })
        c.addView(top)
        c.addView(ui.text(it.why, 13f, SOFT).apply { setPadding(ui.px(28), ui.px(4), 0, 0) })
        val status = when {
            !enabled -> "Für Jarvis gesperrt"
            ok -> "Erlaubt"
            else -> "Nicht erlaubt – antippen"
        }
        c.addView(ui.text(status, 12f, if (!enabled) MUTED else if (ok) GREEN else AMBER).apply { setPadding(ui.px(28), ui.px(6), 0, 0) })
        c.setOnClickListener { _ -> if (!ok) it.grant() else appSettings() }
        return c
    }

    // ---------- Proaktive Hinweise & Benachrichtigungen ----------

    private fun buildProactive() {
        col.addView(ui.section("Hinweise & Benachrichtigungen"))
        val c = ui.card()
        fun sw(title: String, desc: String, value: Boolean, set: (Boolean) -> Unit) {
            val r = ui.row().apply { setPadding(0, ui.px(6), 0, ui.px(6)) }
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(ui.text(title, 15f, Color.WHITE, true)); t.addView(ui.text(desc, 12f, MUTED))
            r.addView(ui.weighted(t))
            r.addView(Switch(this).apply { isChecked = value; setOnCheckedChangeListener { _, on -> set(on); reschedule() } })
            c.addView(r)
        }
        sw("Proaktive Hinweise", "Jarvis meldet sich von selbst – nur wenn du das willst.", prefs.proactive) { prefs.proactive = it; build() }
        if (prefs.proactive) {
            sw("Bevorstehende Termine", "Erinnert ${prefs.eventLead} Min. vorher (mit Ort + ${prefs.travelBuffer} Min. Weg).", prefs.notifyEvents) { prefs.notifyEvents = it }
            sw("Kalender-Konflikte", "Meldet, wenn sich Termine überschneiden.", prefs.notifyConflicts) { prefs.notifyConflicts = it }
            sw("Tagesüberblick", "Jeden Morgen um ${prefs.briefingTime} – nur wenn etwas ansteht.", prefs.notifyBriefing) { prefs.notifyBriefing = it }
        }
        col.addView(c)

        col.addView(ui.button("Uhrzeit Tagesüberblick: ${prefs.briefingTime}", filled = false) {
            val (h, m) = prefs.briefingTime.split(":").map { it.toIntOrNull() ?: 0 }.let { it[0] to it.getOrElse(1) { 0 } }
            TimePickerDialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, hh, mm ->
                prefs.briefingTime = "%02d:%02d".format(hh, mm); reschedule(); build()
            }, h, m, true).show()
        })
        col.addView(ui.button("Vorlauf für Termine: ${prefs.eventLead} Min.", filled = false) {
            pick("Wie lange vorher erinnern?", listOf(10, 15, 30, 45, 60, 90)) { prefs.eventLead = it }
        })
        col.addView(ui.button("Wegzeit-Puffer: ${prefs.travelBuffer} Min.", filled = false) {
            pick("Extra-Zeit für Termine mit Ort", listOf(0, 10, 20, 30, 45, 60)) { prefs.travelBuffer = it }
        })
        col.addView(ui.button("Puffer zwischen Terminen: ${prefs.calendarBuffer} Min.", filled = false) {
            pick("Puffer beim Suchen freier Zeiten", listOf(0, 5, 10, 15, 30)) { prefs.calendarBuffer = it }
        })
        col.addView(ui.button(if (prefs.homeSet) "Zuhause neu setzen (aktueller Ort)" else "Zuhause = mein aktueller Ort", filled = false) {
            val loc = Geo.lastKnown(this)
            if (loc == null) toast("Kein Standort – erst „Standort“ erlauben und einschalten.")
            else { prefs.setHome(loc.first, loc.second); toast("Zuhause gespeichert."); build() }
        })
        col.addView(ui.button(if (prefs.actionPin.isBlank()) "Aktions-PIN einrichten (optional)" else "Aktions-PIN ändern / entfernen", filled = false) {
            val f = android.widget.EditText(this).apply { hint = "4–8 Ziffern, leer = keine PIN"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD }
            AlertDialog.Builder(this).setTitle("Aktions-PIN")
                .setMessage("Wenn gesetzt, fragt Jarvis vor wichtigen bestätigten Aktionen (Senden, Löschen, Kaufen …) zusätzlich diese PIN ab. Das ist NICHT deine Handy-PIN.")
                .setView(f).setPositiveButton("Speichern") { _, _ ->
                    val v = f.text.toString()
                    if (v.isNotEmpty() && v.length < 4) toast("Mindestens 4 Ziffern.") else { prefs.actionPin = v; build() }
                }.setNegativeButton("Abbrechen", null).show()
        })
        col.addView(ui.button("Benachrichtigungs-Kategorien in Android", filled = false) {
            Notifier.channels(this)
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        })
        col.addView(ui.text("Erinnerungen, Termine, Konflikte, Tagesüberblick und Aufgaben sind eigene Kategorien – du kannst jede " +
            "in Android einzeln stumm schalten.", 12f, MUTED).apply { setPadding(ui.px(4), ui.px(8), 0, 0); gravity = Gravity.START })
        col.addView(ui.text("JARVIS v${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("")}", 11f, RED)
            .apply { setPadding(0, ui.px(20), 0, 0); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(-1, -2) })
    }

    private fun pick(title: String, options: List<Int>, set: (Int) -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title)
            .setItems(options.map { "$it Minuten" }.toTypedArray()) { _, i -> set(options[i]); reschedule(); build() }.show()
    }

    private fun reschedule() = thread { Scheduler.scheduleAll(applicationContext) }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
