package de.jarvis.app

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import de.jarvis.app.Ui.Companion.BG
import de.jarvis.app.Ui.Companion.MUTED
import de.jarvis.app.Ui.Companion.RED
import de.jarvis.app.Ui.Companion.SOFT
import org.json.JSONArray
import org.json.JSONObject

/** Visueller Automation-Builder: WENN (Auslöser) → DANN (Aktionen). */
class AutomationsActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.load(Prefs(this)); ui = Ui(this)
        window.statusBarColor = BG; window.navigationBarColor = BG
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(18), ui.px(16), ui.px(18), ui.px(32)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(col) })
    }

    override fun onResume() { super.onResume(); build() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dlg() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
    private fun field(hint: String, v: String = "", number: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(v); setTextColor(Color.WHITE); setHintTextColor(MUTED)
        if (number) inputType = InputType.TYPE_CLASS_NUMBER
    }
    private fun box(vararg v: android.view.View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(ui.px(20), ui.px(8), ui.px(20), 0); v.forEach { addView(it) } }

    private fun build() {
        col.removeAllViews()
        val head = ui.row()
        head.addView(ui.text("‹", 30f, Color.WHITE).apply { setPadding(0, 0, ui.px(16), 0); setOnClickListener { finish() } })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(ui.text("Automationen", 21f, Color.WHITE, true)); t.addView(ui.text("WENN … DANN …", 12f, RED))
        head.addView(t); col.addView(head)

        col.addView(ui.button("+ Neue Automation") { edit(JSONObject()) })
        val list = Automations.all(this)
        if (list.isEmpty()) col.addView(ui.text("Noch keine Automationen. Nimm eine Vorlage oder sag Jarvis z. B.: „Wenn meine Kopfhörer verbunden werden, starte Spotify und stell die Lautstärke auf 40 Prozent.“", 13f, SOFT).apply { setPadding(0, ui.px(10), 0, 0) })
        for (a in list) {
            col.addView(ui.card().apply {
                val top = ui.row()
                top.addView(ui.weighted(ui.text(a.optString("name", "Automation"), 16f, Color.WHITE, true)))
                top.addView(Switch(this@AutomationsActivity).apply {
                    isChecked = a.optBoolean("on", true)
                    setOnCheckedChangeListener { _, on -> Automations.put(this@AutomationsActivity, a.put("on", on)) }
                })
                addView(top)
                addView(ui.text(Automations.describe(a), 13f, SOFT))
                setOnClickListener { edit(JSONObject(a.toString())) }
                setOnLongClickListener {
                    dlg().setMessage("„${a.optString("name")}“ löschen?")
                        .setPositiveButton("Löschen") { _, _ -> Automations.remove(this@AutomationsActivity, a.optInt("id")); build() }
                        .setNegativeButton("Abbrechen", null).show(); true
                }
            })
        }
        col.addView(ui.section("Vorlagen"))
        fun tpl(name: String, trigger: JSONObject, vararg steps: Pair<String, JSONObject>) =
            col.addView(ui.button(name, filled = false) {
                edit(JSONObject().put("name", name).put("trigger", trigger)
                    .put("steps", JSONArray().apply { steps.forEach { (tool, args) -> put(JSONObject().put("tool", tool).put("args", args)) } }))
            })
        tpl("Kopfhörer → Spotify", JSONObject().put("type", "headphones"),
            "play_music" to JSONObject().put("query", "Meine Playlist").put("app", "spotify"), "set_volume" to JSONObject().put("percent", 40))
        tpl("Zuhause → Hausaufgaben", JSONObject().put("type", "place").put("name", "Zuhause").put("when", "arrive"),
            "notify" to JSONObject().put("text", "Willkommen zu Hause! Denk an deine Hausaufgaben."))
        tpl("Akku unter 15 %", JSONObject().put("type", "battery").put("below", 15),
            "speak" to JSONObject().put("text", "Achtung, dein Akku ist fast leer."))
        tpl("Morgens 7 Uhr → Briefing", JSONObject().put("type", "time").put("hour", 7).put("minute", 0).put("days", JSONArray(listOf(1, 2, 3, 4, 5))),
            "briefing" to JSONObject())
        tpl("Ladekabel → Helligkeit runter", JSONObject().put("type", "charging"), "brightness" to JSONObject().put("percent", 20))
        col.addView(ui.text("Hinweise: Akku, Ladekabel, Kabel-Kopfhörer und WLAN brauchen „Benachrichtigungen lesen“. „App wird geöffnet“ braucht die Bildschirmsteuerung. " +
            "Damit Aktionen automatisch starten dürfen, „Über anderen Apps einblenden“ erlauben – sonst kommt eine Benachrichtigung zum Antippen. " +
            "Senden, Löschen oder Kaufen geht in Automationen nie ohne Rückfrage.", 12f, MUTED).apply { setPadding(0, ui.px(12), 0, 0) })
    }

    // ---------- Editor ----------

    private fun edit(a: JSONObject) {
        val steps = a.optJSONArray("steps") ?: JSONArray()
        val name = field("Name, z. B. Kopfhörer-Modus", a.optString("name"))
        val trig = ui.text("WENN: " + (a.optJSONObject("trigger")?.let { Automations.describeTrigger(it) } ?: "– antippen –"), 15f, RED, true).apply {
            setPadding(0, ui.px(12), 0, ui.px(6)) }
        val stepsView = ui.text("", 14f, Color.WHITE)
        fun refresh() {
            trig.text = "WENN: " + (a.optJSONObject("trigger")?.let { Automations.describeTrigger(it) } ?: "– antippen zum Wählen –")
            stepsView.text = "DANN:\n" + if (steps.length() == 0) "– noch keine Aktion –" else
                (0 until steps.length()).joinToString("\n") { "${it + 1}. " + Automations.describeStep(steps.getJSONObject(it)) }
        }
        refresh()
        trig.setOnClickListener { chooseTrigger(a) { refresh() } }
        val addBtn = ui.text("+ Aktion hinzufügen", 15f, RED, true).apply { setPadding(0, ui.px(12), 0, 0); setOnClickListener { addAction(steps) { refresh() } } }
        val clearBtn = ui.text("Aktionen leeren", 13f, MUTED).apply { setPadding(0, ui.px(10), 0, 0); setOnClickListener { while (steps.length() > 0) steps.remove(0); refresh() } }
        dlg().setTitle(if (a.has("id")) "Automation bearbeiten" else "Neue Automation")
            .setView(ScrollView(this).apply { addView(box(name, trig, stepsView, addBtn, clearBtn)) })
            .setPositiveButton("Speichern") { _, _ ->
                if (!a.has("trigger") || steps.length() == 0) { toast("Bitte Auslöser und mindestens eine Aktion wählen."); return@setPositiveButton }
                a.put("name", name.text.toString().ifBlank { Automations.describeTrigger(a.optJSONObject("trigger")) }).put("steps", steps)
                Automations.put(this, a)
                if (a.getJSONObject("trigger").optString("type") == "place" && !Prefs(this).homeSet && !a.getJSONObject("trigger").has("lat"))
                    toast("Gespeichert. Setz noch dein Zuhause unter Berechtigungen → „Zuhause = mein aktueller Ort“.")
                else toast("Gespeichert")
                build()
            }
            .setNeutralButton("Testen") { _, _ ->
                a.put("steps", steps)
                dlg().setTitle("Testmodus – würde ausführen")
                    .setMessage(Automations.describe(a) + "\n\nIm Testmodus wird nichts ausgeführt.")
                    .setPositiveButton("Jetzt wirklich ausführen") { _, _ -> Automations.run(this, a.put("name", name.text.toString().ifBlank { "Test" })) }
                    .setNegativeButton("Schließen", null).show()
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun chooseTrigger(a: JSONObject, done: () -> Unit) {
        val keys = Automations.TRIGGERS.keys.toList()
        dlg().setTitle("WENN …").setItems(Automations.TRIGGERS.values.toTypedArray()) { _, w ->
            val type = keys[w]; val t = JSONObject().put("type", type)
            fun ok() { a.put("trigger", t); done() }
            when (type) {
                "time" -> TimePickerDialog(this, { _, h, m ->
                    t.put("hour", h).put("minute", m)
                    val names = arrayOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"); val sel = BooleanArray(7) { true }
                    dlg().setTitle("An welchen Tagen?").setMultiChoiceItems(names, sel) { _, i, on -> sel[i] = on }
                        .setPositiveButton("OK") { _, _ -> t.put("days", JSONArray((1..7).filter { sel[it - 1] })); ok() }.show()
                }, 7, 0, true).show()
                "place" -> dlg().setItems(arrayOf("Zuhause – ankommen", "Zuhause – verlassen", "Hier (aktueller Ort) – ankommen")) { _, i ->
                    if (i == 2) {
                        val l = de.jarvis.app.tools.Geo.lastKnown(this) ?: run { toast("Kein Standort verfügbar."); return@setItems }
                        t.put("name", "dieser Ort").put("lat", l.first).put("lon", l.second).put("when", "arrive")
                    } else t.put("name", "Zuhause").put("when", if (i == 0) "arrive" else "leave")
                    ok()
                }.show()
                "bluetooth" -> { val f = field("Gerätename (leer = jedes), z. B. Auto"); dlg().setTitle("Welches Bluetooth-Gerät?").setView(box(f))
                    .setPositiveButton("OK") { _, _ -> t.put("device", f.text.toString().trim()); ok() }.show() }
                "wifi" -> { val f = field("WLAN-Name (leer = jedes)"); dlg().setTitle("Welches WLAN?").setView(box(f))
                    .setPositiveButton("OK") { _, _ -> t.put("ssid", f.text.toString().trim()); ok() }.show() }
                "battery" -> { val f = field("Prozent", "20", true); dlg().setTitle("Akku unter …").setView(box(f))
                    .setPositiveButton("OK") { _, _ -> t.put("below", f.text.toString().toIntOrNull()?.coerceIn(1, 99) ?: 20); ok() }.show() }
                "event" -> { val f = field("Minuten vorher (0 = beim Start)", "0", true); dlg().setTitle("Termin beginnt").setView(box(f))
                    .setPositiveButton("OK") { _, _ -> t.put("minutes_before", f.text.toString().toIntOrNull() ?: 0); ok() }.show() }
                "notification" -> { val app = field("App, z. B. WhatsApp (leer = jede)"); val txt = field("enthält Text (optional)")
                    dlg().setTitle("Benachrichtigung").setView(box(app, txt))
                        .setPositiveButton("OK") { _, _ -> t.put("app", app.text.toString().trim()).put("text", txt.text.toString().trim()); ok() }.show() }
                "app" -> { val f = field("App-Name, z. B. Brawl Stars"); dlg().setTitle("Welche App?").setView(box(f))
                    .setPositiveButton("OK") { _, _ -> t.put("app", f.text.toString().trim()); ok() }.show() }
                else -> ok()
            }
        }.show()
    }

    private fun addAction(steps: JSONArray, done: () -> Unit) {
        val keys = Automations.ACTIONS.keys.toList()
        dlg().setTitle("DANN …").setItems(Automations.ACTIONS.values.toTypedArray()) { _, w ->
            val tool = keys[w]
            fun add(args: JSONObject) { steps.put(JSONObject().put("tool", tool).put("args", args)); done() }
            fun ask(title: String, hint: String, number: Boolean = false, build: (String) -> JSONObject) {
                val f = field(hint, number = number)
                dlg().setTitle(title).setView(box(f)).setPositiveButton("OK") { _, _ -> if (f.text.isNotBlank()) add(build(f.text.toString().trim())) }.show()
            }
            when (tool) {
                "notify" -> ask("Nachricht anzeigen", "Text") { JSONObject().put("text", it) }
                "speak" -> ask("Jarvis sagt …", "Text") { JSONObject().put("text", it) }
                "reminders" -> ask("Erinnerung (in 60 Min.)", "Woran?") { JSONObject().put("action", "add").put("text", it).put("in_minutes", 60) }
                "play_music" -> ask("Musik starten", "Lied, Künstler oder Playlist") { JSONObject().put("query", it).put("app", "spotify") }
                "media_control" -> dlg().setItems(arrayOf("Play/Pause", "Pause", "Weiter", "Nächstes Lied")) { _, i ->
                    add(JSONObject().put("action", listOf("play_pause", "pause", "play", "next")[i])) }.show()
                "set_volume" -> ask("Lautstärke", "Prozent, z. B. 40", true) { JSONObject().put("percent", it.toIntOrNull()?.coerceIn(0, 100) ?: 40).put("stream", "media") }
                "open_app" -> ask("App öffnen", "App-Name") { JSONObject().put("name", it) }
                "set_timer" -> ask("Timer", "Minuten", true) { JSONObject().put("seconds", (it.toIntOrNull() ?: 5) * 60) }
                "flashlight" -> dlg().setItems(arrayOf("An", "Aus")) { _, i -> add(JSONObject().put("on", i == 0)) }.show()
                "brightness" -> ask("Helligkeit", "Prozent", true) { JSONObject().put("percent", it.toIntOrNull()?.coerceIn(0, 100) ?: 50) }
                "routines" -> {
                    val names = de.jarvis.app.tools.RoutineTool.names(this)
                    if (names.isEmpty()) toast("Noch keine eigenen Kommandos.")
                    else dlg().setItems(names.toTypedArray()) { _, i -> add(JSONObject().put("action", "run").put("name", names[i])) }.show()
                }
                else -> add(JSONObject())
            }
        }.show()
    }

    @Suppress("unused") private val g = Gravity.CENTER + SOFT
}
