package de.jarvis.app

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
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
import kotlin.concurrent.thread

/** Autopilot-Zentrale: alles, was Jarvis von selbst macht – einzeln an/aus, mit Zeiten und Limits. */
class AutoPilotActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var col: LinearLayout
    private val fields = mutableListOf<Pair<String, EditText>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.load(Prefs(this)); ui = Ui(this)
        window.statusBarColor = BG; window.navigationBarColor = BG; Ui.lightBars(window)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(18), ui.px(22), ui.px(18), ui.px(48)) }
        setContentView(ScrollView(this).apply { background = ui.aurora(); addView(col) })
    }

    override fun onResume() { super.onResume(); build() }
    override fun onPause() { super.onPause(); save() }
    private fun save() { fields.forEach { (k, e) -> AutoPilot.putStr(this, k, e.text.toString().trim()) }; AutoPilot.scheduleAll(this) }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun field(key: String, def: String, hint: String, numeric: Boolean = false) = EditText(this).apply {
        setText(AutoPilot.str(this@AutoPilotActivity, key, def)); this.hint = hint
        setTextColor(Ui.FG); setHintTextColor(MUTED); textSize = 15f
        background = ui.round(Ui.CARD2, 14); setPadding(ui.px(12), ui.px(10), ui.px(12), ui.px(10))
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        setOnFocusChangeListener { _, has -> if (!has) AutoPilot.putStr(this@AutoPilotActivity, key, text.toString().trim()) }
    }.also { fields += key to it }

    private fun build() {
        if (fields.isNotEmpty()) { fields.forEach { (k, e) -> AutoPilot.putStr(this, k, e.text.toString().trim()) }; fields.clear() }
        col.removeAllViews()
        col.addView(ui.row().apply {
            addView(ui.text("‹", 30f).apply { setPadding(0, 0, ui.px(16), 0); setOnClickListener { finish() } })
            addView(LinearLayout(this@AutoPilotActivity).apply { orientation = LinearLayout.VERTICAL
                addView(ui.text("Autopilot", 21f, Ui.FG, true)); addView(ui.text("WAS JARVIS VON SELBST ERLEDIGT", 11f, RED).apply { letterSpacing = 0.18f; typeface = Typeface.MONOSPACE }) })
        })
        col.addView(ui.text("Jarvis macht diese Dinge automatisch. Etwas verschicken, löschen oder kaufen tut er nie heimlich – " +
            "dafür bekommst du immer erst eine Benachrichtigung zum Antippen.", 13f, SOFT).apply { setPadding(0, ui.px(10), 0, ui.px(4)) })

        // Freigaben, die einige Funktionen brauchen
        val dnd = getSystemService(NotificationManager::class.java)!!.isNotificationPolicyAccessGranted
        val write = Settings.System.canWrite(this)
        val acc = JarvisAccessibility.instance != null
        val pro = ProShell.ready
        col.addView(ui.card().apply {
            fun line(ok: Boolean, t: String, fix: (() -> Unit)?) = addView(ui.text((if (ok) "● " else "○ ") + t + (if (!ok && fix != null) "  → antippen" else ""), 13.5f, if (ok) GREEN else AMBER).apply {
                setPadding(0, ui.px(3), 0, ui.px(3)); if (!ok && fix != null) setOnClickListener { fix() } })
            line(dnd, "Nicht stören steuern (Schlafmodus)") { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            line(write, "Helligkeit ändern (Dimmen)") { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
            line(acc, "Bedienungshilfe (Bildschirmzeit)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            line(pro, "Profi-Modus (Energiesparen, Dunkelmodus)") { startActivity(Intent(this@AutoPilotActivity, ProActivity::class.java)) }
            line(JarvisCall.canFullScreen(this@AutoPilotActivity), "Vollbild-Anruf (Jarvis ruft an)") {
                try { startActivity(Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", Uri.parse("package:$packageName"))) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) } }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(10) })

        col.addView(ui.section("Funktionen"))
        for (f in AutoPilot.FEATURES) {
            col.addView(ui.card(false).apply {
                addView(ui.weighted(LinearLayout(this@AutoPilotActivity).apply { orientation = LinearLayout.VERTICAL
                    addView(ui.text(f.title, 15f, Ui.FG, true))
                    addView(ui.text(f.info, 12.5f, SOFT).apply { setPadding(0, ui.px(3), ui.px(8), 0) })
                }))
                addView(Switch(this@AutoPilotActivity).apply { isChecked = AutoPilot.on(this@AutoPilotActivity, f.key)
                    setOnCheckedChangeListener { _, on ->
                        AutoPilot.set(this@AutoPilotActivity, f.key, on)
                        if (on && f.key == "sos" && checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            requestPermissions(arrayOf(android.Manifest.permission.SEND_SMS), 41)
                        if (on && f.key == "arrival" && checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            toast("Für „Ankunft melden“ braucht Jarvis Standort „Immer erlauben“ (Berechtigungen → Standort).")
                    } })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(8) })
        }

        col.addView(ui.section("Zeiten"))
        fun labeled(label: String, v: android.view.View) = col.addView(ui.row().apply {
            addView(ui.weighted(ui.text(label, 14f, Ui.FG)))
            addView(v, LinearLayout.LayoutParams(ui.px(110), -2))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(8) })
        labeled("Schlafenszeit", field("bed", "22:00", "22:00"))
        labeled("Aufstehen / Schlafmodus aus", field("wake", "06:30", "06:30"))
        labeled("Wecker so viele Minuten vor der 1. Stunde", field("lead", "75", "75", true))

        col.addView(ui.section("Bildschirmzeit"))
        col.addView(ui.card().apply { addView(ui.text(AutoPilot.usageText(this@AutoPilotActivity), 13.5f, Ui.FG)) })
        col.addView(ui.text("Limits pro App (Name=Minuten, mit Komma getrennt):", 13f, SOFT).apply { setPadding(0, ui.px(10), 0, ui.px(4)) })
        col.addView(field("limits", "", "TikTok=60, Instagram=45"))

        col.addView(ui.section("Bus & Bahn"))
        labeled("Start-Haltestelle", field("tr_from", "", "z. B. Hauptbahnhof"))
        labeled("Ziel (Schule)", field("tr_to", "", "z. B. Schulzentrum"))

        col.addView(ui.section("Ankunft melden"))
        labeled("Orte (gemerkte Orte, Komma)", field("arr_places", "", "Schule, Oma, Zuhause"))
        labeled("Wem schreiben?", field("arr_contact", "", "Mama"))
        labeled("Mit welcher App?", field("arr_app", "", "WhatsApp"))
        col.addView(ui.text("Orte merken: an dem Ort sagen „Jarvis, merk dir diesen Ort als Schule“.", 12f, MUTED).apply { setPadding(0, ui.px(4), 0, 0) })

        col.addView(ui.section("Vokabeln & Notfall"))
        labeled("Uhrzeit für die Vokabel", field("vocab_time", "16:30", "16:30"))
        labeled("Notfall-Person (Name)", field("sos_name", "", "Mama"))
        labeled("Notfall-Nummer", field("sos_number", "", "+49 …").apply { inputType = InputType.TYPE_CLASS_PHONE })
        col.addView(ui.text("Die Notfall-SMS geht nur raus, wenn „Akku-Notfall-SMS“ oben an ist – höchstens einmal am Tag, und du bekommst Bescheid.", 12f, MUTED).apply { setPadding(0, ui.px(4), 0, 0) })

        col.addView(ui.section("Jetzt ausprobieren"))
        fun test(label: String, job: String) = col.addView(ui.button(label, false) {
            thread { AutoPilot.handle(applicationContext, job) }; toast("Schau in deine Benachrichtigungen.") })
        col.addView(ui.button("📞 Jarvis-Anruf testen (in 10 Sekunden)") {
            JarvisCall.schedule(this, System.currentTimeMillis() + 10_000L, "Das ist ein Test-Anruf. So rufe ich dich an, wenn es wichtig ist.")
            toast("Sperr ruhig das Handy – in 10 Sekunden klingelt es.") })
        test("Abend-Check jetzt zeigen", AutoPilot.J_EVENING)
        test("Morgen-Hinweise (Geburtstage, Fakt)", AutoPilot.J_MORNING)
        test("Wochenrückblick jetzt zeigen", AutoPilot.J_WEEKLY)
        test("Bildschirmzeit-Zusammenfassung", AutoPilot.J_SCREEN)
        test("Vokabel-Frage jetzt", AutoPilot2.J_VOCAB)
        test("Wetter-Überraschungen prüfen", AutoPilot2.J_SKY)
        col.addView(ui.button("Bus & Bahn jetzt abfragen", false) { save(); thread {
            val r = AutoPilot2.transit(applicationContext)
            runOnUiThread { android.app.AlertDialog.Builder(this).setTitle("🚌 Schulweg").setMessage(r).setPositiveButton("OK", null).show() } } })
        col.addView(ui.text("Schulmodus braucht Uhrzeiten im Stundenplan, z. B. „08:00 Mathe“ oder „08:00-09:30 Mathe“. " +
            "Sag dazu: „Jarvis, speichere meinen Stundenplan für Montag …“.", 12f, MUTED).apply { setPadding(0, ui.px(12), 0, 0) })
        if (!pro) col.addView(ui.text("Ohne Profi-Modus: Energiesparen und Dunkelmodus kann Android-Apps normal nicht schalten – dann bekommst du einen Hinweis.", 12f, AMBER).apply { setPadding(0, ui.px(6), 0, 0) })
    }
}
