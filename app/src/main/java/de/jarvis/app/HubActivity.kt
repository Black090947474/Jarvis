package de.jarvis.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import de.jarvis.app.Ui.Companion.AMBER
import de.jarvis.app.Ui.Companion.BG
import de.jarvis.app.Ui.Companion.GREEN
import de.jarvis.app.Ui.Companion.MUTED
import de.jarvis.app.Ui.Companion.RED
import de.jarvis.app.Ui.Companion.SOFT
import de.jarvis.app.logic.TimeLogic
import de.jarvis.app.tools.AgendaStore
import de.jarvis.app.tools.JsonStore
import de.jarvis.app.tools.ToolRegistry
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Sammel-Bildschirm für die Command-Center-Bereiche: Gedächtnis, Persönlichkeit, Aktionsverlauf, Statistik,
 * Geräte, Notfall, Plugins, Personalisierung, Sicherung. Welcher Bereich, steht im Extra "page".
 */
class HubActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private val page get() = intent.getStringExtra("page") ?: "memory"

    companion object {
        fun open(a: Activity, page: String) = a.startActivity(Intent(a, HubActivity::class.java).putExtra("page", page))
        private const val REQ_IMPORT = 41
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this); Ui.load(prefs); ui = Ui(this)
        window.statusBarColor = BG; window.navigationBarColor = BG; Ui.lightBars(window)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(18), ui.px(16), ui.px(18), ui.px(32)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(col) })
    }

    override fun onResume() { super.onResume(); build() }

    private fun header(title: String, sub: String) {
        val head = ui.row()
        head.addView(ui.text("‹", 30f, Ui.FG).apply { setPadding(0, 0, ui.px(16), 0); setOnClickListener { finish() } })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(ui.text(title, 21f, Ui.FG, true)); t.addView(ui.text(sub, 12f, RED))
        head.addView(t)
        col.addView(head)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun input(hint: String, value: String = "", number: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(value); setTextColor(Ui.FG); setHintTextColor(MUTED); textSize = 15f
        background = ui.round(Ui.CARD2, 12, Ui.LINE); setPadding(ui.px(12), ui.px(10), ui.px(12), ui.px(10))
        if (number) inputType = InputType.TYPE_CLASS_PHONE
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(6) }
    }

    private fun slider(title: String, value: Int, set: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ui.px(10), 0, 0) }
        val label = ui.text("$title: $value %", 14f, SOFT)
        box.addView(label)
        box.addView(SeekBar(this).apply {
            max = 100; progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { label.text = "$title: $p %"; if (u) set(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        return box
    }

    private fun build() {
        col.removeAllViews()
        when (page) {
            "memory" -> memory()
            "personality" -> personality()
            "log" -> log()
            "stats" -> stats()
            "devices" -> devices()
            "emergency" -> emergency()
            "plugins" -> plugins()
            "personalize" -> personalize()
            "backup" -> backup()
            else -> memory()
        }
    }

    // ---------- Gedächtnis ----------

    private fun memory() {
        header("Gedächtnis", "WAS JARVIS ÜBER DICH WEISS")
        col.addView(ui.section("Profil (freiwillig)"))
        val prof = Personality.profile(prefs)
        val fields = Personality.PROFILE_FIELDS.map { (k, label) -> k to input(label, prof.optString(k)) }
        val card = ui.card(); fields.forEach { card.addView(it.second) }; col.addView(card)
        col.addView(ui.button("Profil speichern") {
            val o = JSONObject(); fields.forEach { (k, e) -> e.text.toString().trim().takeIf { it.isNotBlank() }?.let { o.put(k, it) } }
            prefs.profileJson = o.toString()
            o.optString("name").takeIf { it.isNotBlank() }?.let { prefs.userName = it }
            toast("Profil gespeichert")
        })

        col.addView(ui.section("Langzeit-Gedächtnis"))
        col.addView(ui.text("Das hat Jarvis sich dauerhaft gemerkt. Antippen = ändern, lange drücken = löschen.", 12f, MUTED))
        val mem = Memory(this)
        val facts = mem.all()
        if (facts.isEmpty()) col.addView(ui.card().apply { addView(ui.text("Noch nichts gemerkt.", 14f, SOFT)) })
        facts.forEachIndexed { i, f ->
            col.addView(ui.card().apply {
                addView(ui.text(f, 15f, Ui.FG))
                setOnClickListener {
                    val e = input("Eintrag", f)
                    AlertDialog.Builder(this@HubActivity).setTitle("Ändern").setView(e)
                        .setPositiveButton("Speichern") { _, _ -> mem.set(i, e.text.toString()); build() }.setNegativeButton("Abbrechen", null).show()
                }
                setOnLongClickListener {
                    AlertDialog.Builder(this@HubActivity).setMessage("„$f“ löschen?")
                        .setPositiveButton("Löschen") { _, _ -> mem.removeAt(i); build() }.setNegativeButton("Abbrechen", null).show(); true
                }
            })
        }
        col.addView(ui.button("Etwas hinzufügen", filled = false) {
            val e = input("z. B. Ich spiele Fußball im Verein")
            AlertDialog.Builder(this).setTitle("Merken").setView(e)
                .setPositiveButton("Merken") { _, _ -> if (e.text.isNotBlank()) mem.add(e.text.toString()); build() }.setNegativeButton("Abbrechen", null).show()
        })

        col.addView(ui.section("Kurzzeit – letzte Gespräche"))
        val convo = Convo(this).recent(30)
        if (convo.isEmpty()) col.addView(ui.text("Leer.", 13f, MUTED))
        val c2 = ui.card()
        convo.takeLast(14).forEach { (r, t) -> c2.addView(ui.text((if (r == "user") "Du: " else "Jarvis: ") + t.take(160), 13f, if (r == "user") SOFT else RED).apply { setPadding(0, ui.px(3), 0, ui.px(3)) }) }
        if (convo.isNotEmpty()) col.addView(c2)
        col.addView(ui.button("Gesprächsverlauf vergessen", filled = false) { Convo(this).clear(); build() })
        col.addView(ui.text("Das aktuelle Gespräch kennt Jarvis nur, solange es läuft. Alles bleibt auf deinem Handy; was Jarvis zum Antworten braucht, geht an die KI. Passwörter und PINs speichert Jarvis nie.", 12f, MUTED).apply { setPadding(0, ui.px(12), 0, 0) })
    }

    // ---------- Persönlichkeit ----------

    private fun personality() {
        header("Persönlichkeit", "SO REDET JARVIS MIT DIR")
        col.addView(ui.section("Charakter"))
        for ((k, label) in Personality.PRESETS) {
            val on = prefs.personality == k
            col.addView(ui.card().apply {
                background = ui.round(if (on) Color.argb(60, Color.red(RED), Color.green(RED), Color.blue(RED)) else Ui.CARD, 16, if (on) RED else Ui.LINE)
                addView(ui.text((if (on) "● " else "○ ") + label, 15f, Ui.FG, on))
                setOnClickListener { prefs.personality = k; build() }
            })
        }
        col.addView(ui.section("Feinabstimmung"))
        val c = ui.card()
        c.addView(slider("Humor", prefs.humor) { prefs.humor = it })
        c.addView(slider("Gesprächigkeit", prefs.talkative) { prefs.talkative = it })
        c.addView(slider("Förmlichkeit", prefs.formality) { prefs.formality = it })
        c.addView(slider("Eigeninitiative", prefs.proactivity) { prefs.proactivity = it })
        col.addView(c)
        col.addView(ui.text("Stimme, Tempo, Tonhöhe und Jarvis-Klang stellst du unter ⚙ Einstellungen → Stimme ein.", 12f, MUTED).apply { setPadding(0, ui.px(10), 0, 0) })
        col.addView(ui.button("Stimme einstellen", filled = false) { startActivity(Intent(this, MainActivity::class.java)) })
    }

    // ---------- Aktionsverlauf ----------

    private fun log() {
        header("Aktionsverlauf", "WAS JARVIS GETAN HAT")
        val list = ActionLog.entries(this)
        if (list.isEmpty()) col.addView(ui.card().apply { addView(ui.text("Noch keine Aktionen.", 14f, SOFT)) })
        for (e in list.take(150)) {
            col.addView(ui.card().apply {
                val top = ui.row()
                top.addView(ui.weighted(ui.text(e.label, 15f, Ui.FG, true)))
                top.addView(ui.text(e.status, 12f, when (e.status) { "OK" -> GREEN; "Rückfrage" -> AMBER; else -> Color.rgb(255, 110, 110) }))
                addView(top)
                addView(ui.text(TimeLogic.short(e.at) + (if (e.detail.isNotBlank()) " · ${e.detail}" else ""), 12f, SOFT))
                val extra = listOfNotNull(if (e.confirmed) "von dir bestätigt" else null,
                    e.feature?.let { "Berechtigung: " + (PhoneTools.FEATURE_NAMES[it]?.removePrefix("den ")?.removePrefix("die ") ?: it) })
                if (extra.isNotEmpty()) addView(ui.text(extra.joinToString(" · "), 11f, MUTED))
            })
        }
        if (list.isNotEmpty()) col.addView(ui.button("Verlauf löschen", filled = false) { ActionLog.clear(this); build() })
    }

    // ---------- Statistik ----------

    private fun stats() {
        header("Statistik", "DEIN JARVIS IN ZAHLEN")
        val store = AgendaStore(this)
        val tasks = store.tasks()
        val log = ActionLog.entries(this)
        val extra = JsonStore(this)
        val decks = extra.obj("decks")
        var cards = 0; var known = 0; var answers = 0
        decks.keys().forEach { k -> extra.list(k).forEach { c -> cards++; answers += c.optInt("ok") + c.optInt("bad"); if (c.optInt("ok") > c.optInt("bad")) known++ } }
        val grid = android.widget.GridLayout(this).apply { columnCount = 2 }
        fun tile(v: String, l: String) {
            val b = ui.card().apply { gravity = Gravity.CENTER_HORIZONTAL }
            b.addView(ui.text(v, 26f, RED, true).apply { gravity = Gravity.CENTER })
            b.addView(ui.text(l, 12f, SOFT).apply { gravity = Gravity.CENTER })
            grid.addView(b, android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)).apply { width = 0; setMargins(ui.px(4), ui.px(4), ui.px(4), ui.px(4)) })
        }
        tile("${tasks.count { it.done }}", "Aufgaben erledigt")
        tile("${tasks.count { !it.done }}", "Aufgaben offen")
        tile("${store.reminders().count { it.active }}", "Erinnerungen aktiv")
        tile("${Automations.all(this).count { it.optBoolean("on", true) }}", "Automationen aktiv")
        tile("$known / $cards", "Lernkarten sitzen")
        tile("$answers", "Lern-Antworten")
        tile("${log.size}", "Aktionen (Verlauf)")
        tile("${extra.list("routines").size}", "Eigene Kommandos")
        tile("${FocusActivity.minutes(this, 7)} min", "Fokuszeit (7 Tage)")
        tile("${FocusActivity.minutes(this, 3650) / 60} h", "Fokuszeit gesamt")
        col.addView(grid)
        col.addView(ui.section("Meistgenutzte Werkzeuge"))
        val top = log.groupBy { it.label }.mapValues { it.value.size }.entries.sortedByDescending { it.value }.take(8)
        col.addView(ui.card().apply {
            if (top.isEmpty()) addView(ui.text("Noch keine Daten.", 13f, MUTED))
            top.forEach { (k, v) -> addView(ui.row().apply { addView(ui.weighted(ui.text(k, 14f, Ui.FG))); addView(ui.text("$v×", 14f, RED, true)) }) }
        })
    }

    // ---------- Geräte ----------

    private fun devices() {
        header("Meine Geräte", "GEKOPPELTE HANDYS")
        val bat = (getSystemService(BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        col.addView(ui.card().apply {
            addView(ui.text("📱 Dieses Handy", 16f, Ui.FG, true))
            addView(ui.text("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} · Online · Akku $bat %", 13f, GREEN))
            addView(ui.text("Alle Funktionen", 12f, MUTED))
        })
        if (prefs.remoteHost.isBlank()) {
            col.addView(ui.card().apply { addView(ui.text("Noch kein zweites Gerät gekoppelt.", 14f, SOFT)) })
        } else {
            val status = ui.text("Prüfe Verbindung …", 13f, AMBER)
            col.addView(ui.card().apply {
                addView(ui.text("📱 ${prefs.remoteName.ifBlank { "Zweites Handy" }}", 16f, Ui.FG, true))
                addView(status)
                addView(ui.text("Fernsteuerbar: Musik, Wecker, Timer, Taschenlampe, Lautstärke, Apps, Kamera …", 12f, MUTED))
            })
            thread {
                val r = try { StationLink.send(prefs.remoteHost, prefs.remotePort, prefs.remoteCode, "battery", "{}") } catch (e: Exception) { false to (e.message ?: "") }
                runOnUiThread { status.text = if (r.first) "Online · ${r.second.take(60)}" else "Offline – Station nicht erreichbar"; status.setTextColor(if (r.first) GREEN else MUTED) }
            }
        }
        col.addView(ui.button("Gerät koppeln / Station einrichten", filled = false) { startActivity(Intent(this, MainActivity::class.java)) })
        col.addView(ui.text("Fernsteuerung nur nach Kopplung mit Code. Synchronisation über ein Online-Konto gibt es nicht – nutze „Sicherung“ zum Übertragen.", 12f, MUTED).apply { setPadding(0, ui.px(10), 0, 0) })
    }

    // ---------- Notfall ----------

    private fun emergency() {
        header("Notfall", "SCHNELLE HILFE")
        fun big(text: String, color: Int, act: () -> Unit) = col.addView(ui.text(text, 20f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = ui.round(color, 18); setPadding(0, ui.px(22), 0, ui.px(22))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(12) }; setOnClickListener { act() } })
        big("📞 112 – Rettung & Feuerwehr", Color.rgb(210, 30, 40)) { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))) }
        big("🚓 110 – Polizei", Color.rgb(30, 80, 200)) { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:110"))) }
        val contact = prefs.emergencyNumber
        if (contact.isNotBlank()) {
            big("☎ ${prefs.emergencyName.ifBlank { "Notfallkontakt" }} anrufen", Color.rgb(20, 140, 90)) { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$contact"))) }
            big("📍 Meinen Standort schicken", Color.rgb(200, 120, 20)) {
                val loc = de.jarvis.app.tools.Geo.lastKnown(this)
                val body = "Ich brauche Hilfe." + (loc?.let { " Mein Standort: https://maps.google.com/?q=${it.first},${it.second}" } ?: "")
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$contact")).putExtra("sms_body", body))
            }
        }
        col.addView(ui.text("Jarvis wählt die Nummer vor – du tippst selbst auf Anrufen bzw. Senden. Jarvis ruft niemanden automatisch an.", 12f, MUTED).apply { setPadding(0, ui.px(14), 0, 0) })
        col.addView(ui.section("Notfallkontakt"))
        val name = input("Name, z. B. Mama", prefs.emergencyName); val num = input("Telefonnummer", prefs.emergencyNumber, number = true)
        col.addView(name); col.addView(num)
        col.addView(ui.button("Speichern", filled = false) { prefs.emergencyName = name.text.toString(); prefs.emergencyNumber = num.text.toString(); toast("Gespeichert"); build() })
        col.addView(ui.text("Tipp: Android hat zusätzlich „Notfall-SOS“ (5× Seitentaste drücken) – einstellbar unter Einstellungen → Sicherheit und Notfall.", 12f, MUTED).apply { setPadding(0, ui.px(10), 0, 0) })
    }

    // ---------- Plugins ----------

    private fun plugins() {
        header("Plugins", "MODULE EIN- UND AUSSCHALTEN")
        col.addView(ui.text("Jedes Modul kannst du für Jarvis abschalten. Die Android-Berechtigungen verwaltest du unter Berechtigungen.", 12f, MUTED))
        for (t in ToolRegistry.all) {
            val key = "tool_" + t.name
            col.addView(ui.card(false).apply {
                gravity = Gravity.CENTER_VERTICAL
                val info = LinearLayout(this@HubActivity).apply { orientation = LinearLayout.VERTICAL }
                info.addView(ui.text(PhoneTools.labelFor(t.name), 15f, Ui.FG, true))
                info.addView(ui.text(t.definition.optString("description").take(90) + "…", 11f, MUTED))
                if (t.permissions.isNotEmpty()) info.addView(ui.text("Braucht: " + t.permissions.joinToString { it.substringAfterLast('.').replace('_', ' ').lowercase() }, 11f, AMBER))
                addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Switch(this@HubActivity).apply { isChecked = prefs.feature(key); setOnCheckedChangeListener { _, on -> prefs.setFeature(key, on) } })
            })
        }
        col.addView(ui.button("Berechtigungen", filled = false) { startActivity(Intent(this, PermissionsActivity::class.java)) })
    }

    // ---------- Personalisierung ----------

    private fun personalize() {
        header("Personalisierung", "DEIN LOOK")
        fun choice(title: String, options: List<Pair<String, String>>, current: String, set: (String) -> Unit) {
            col.addView(ui.section(title))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((k, l) in options) row.addView(ui.text(l, 13f, if (k == current) Color.WHITE else Ui.FG, k == current).apply {
                gravity = Gravity.CENTER; setPadding(ui.px(10), ui.px(10), ui.px(10), ui.px(10))
                background = ui.round(if (k == current) RED else Ui.CARD2, 12, Ui.LINE)
                setOnClickListener { set(k); Ui.load(prefs); recreate() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.px(3), 0, ui.px(3), 0) })
            col.addView(row)
        }
        choice("Hintergrund", listOf("BLAU" to "Nachtblau", "LILA" to "Lila", "SCHWARZ" to "Schwarz", "HELL" to "Hell"), prefs.uiTheme) { prefs.uiTheme = it }
        choice("Akzentfarbe", listOf("AUTO" to "Passend", "CYAN" to "Cyan", "GRUEN" to "Grün", "ROT" to "Rot", "GOLD" to "Gold"), prefs.accent) { prefs.accent = it }
        choice("Schriftgröße", listOf("0.9" to "Klein", "1.0" to "Normal", "1.15" to "Groß"), prefs.fontScale.toString()) { prefs.fontScale = it.toFloat() }
        choice("Startseite", listOf("home" to "Home", "tools" to "Tools", "system" to "System"), prefs.startPage) { prefs.startPage = it }
        col.addView(ui.section("Animationen"))
        col.addView(ui.card(false).apply {
            addView(ui.weighted(ui.text("Leuchtender Kern & Partikel animieren", 14f, Ui.FG)))
            addView(Switch(this@HubActivity).apply { isChecked = prefs.animations; setOnCheckedChangeListener { _, on -> prefs.animations = on } })
        })
        col.addView(ui.section("Begrüßung"))
        col.addView(ui.card(false).apply {
            addView(ui.weighted(ui.text("Beim Nachhausekommen begrüßen (Wetter, Aufgaben, Nachrichten) – braucht den Ort „Zuhause“", 14f, Ui.FG)))
            addView(Switch(this@HubActivity).apply { isChecked = prefs.greetOnArrive; setOnCheckedChangeListener { _, on -> prefs.greetOnArrive = on; SmartHints.scheduleHome(this@HubActivity) } })
        })
        col.addView(ui.text("Stimme & Jarvis-Klang: ⚙ Einstellungen → Stimme. Widget: lange auf den Startbildschirm drücken → Widgets → Jarvis.", 12f, MUTED).apply { setPadding(0, ui.px(12), 0, 0) })
    }

    // ---------- Sicherung ----------

    private fun backup() {
        header("Sicherung", "DATEN SICHERN & ÜBERTRAGEN")
        col.addView(ui.text("Sichert Erinnerungen, Aufgaben, Notizen, Einkaufsliste, Stundenplan, Lernkarten, Kommandos, Automationen, Gedächtnis und Einstellungen in eine Datei (Downloads). API-Schlüssel werden aus Sicherheitsgründen NICHT mitgesichert.", 13f, SOFT))
        col.addView(ui.button("Sicherung erstellen") { thread { val r = Backup.export(this); runOnUiThread { toast(r) } } })
        col.addView(ui.button("Sicherung wiederherstellen", filled = false) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT)
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(req, res, data)
        if (req == REQ_IMPORT && res == RESULT_OK) data?.data?.let { uri ->
            AlertDialog.Builder(this).setMessage("Sicherung einspielen? Vorhandene Daten werden überschrieben.")
                .setPositiveButton("Einspielen") { _, _ -> thread { val r = Backup.import(this, uri); runOnUiThread { toast(r); Scheduler.scheduleAll(this) } } }
                .setNegativeButton("Abbrechen", null).show()
        }
    }

    @Suppress("unused") private val keep = listOf(ContentValues::class, MediaStore::class, TextView::class)
}
