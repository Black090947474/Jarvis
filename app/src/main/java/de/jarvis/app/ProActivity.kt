package de.jarvis.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import de.jarvis.app.Ui.Companion.AMBER
import de.jarvis.app.Ui.Companion.BG
import de.jarvis.app.Ui.Companion.GREEN
import de.jarvis.app.Ui.Companion.MUTED
import de.jarvis.app.Ui.Companion.RED
import de.jarvis.app.Ui.Companion.SOFT
import kotlin.concurrent.thread

/** Profi-Modus (Shizuku): Einrichtung Schritt für Schritt, Status, Auto-Einrichtung und Test-Schalter. */
class ProActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.load(Prefs(this)); ui = Ui(this)
        window.statusBarColor = BG; window.navigationBarColor = BG; Ui.lightBars(window)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(18), ui.px(22), ui.px(18), ui.px(40)) }
        setContentView(ScrollView(this).apply { background = ui.aurora(); addView(col) })
    }

    override fun onResume() { super.onResume(); build() }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun build() {
        col.removeAllViews()
        col.addView(ui.row().apply {
            addView(ui.text("‹", 30f).apply { setPadding(0, 0, ui.px(16), 0); setOnClickListener { finish() } })
            addView(LinearLayout(this@ProActivity).apply { orientation = LinearLayout.VERTICAL
                addView(ui.text("Profi-Modus", 21f, Ui.FG, true)); addView(ui.text("SYSTEMRECHTE ÜBER SHIZUKU", 11f, RED).apply { letterSpacing = 0.18f; typeface = Typeface.MONOSPACE }) })
        })
        val installed = ProShell.installed(this); val running = ProShell.running(); val granted = ProShell.granted()
        col.addView(ui.card().apply {
            fun line(ok: Boolean, t: String) = addView(ui.text((if (ok) "● " else "○ ") + t, 14f, if (ok) GREEN else AMBER).apply { setPadding(0, ui.px(3), 0, ui.px(3)) })
            line(installed, if (installed) "Shizuku installiert" else "Shizuku nicht installiert")
            line(running, if (running) "Shizuku läuft" else "Shizuku läuft nicht")
            line(granted, if (granted) "Jarvis darf Systembefehle ausführen" else "Jarvis noch nicht erlaubt")
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(14) })

        if (granted) {
            col.addView(ui.text("Fertig! Sag z. B. „Jarvis, Flugmodus an“, „mobile Daten aus“, „Dunkelmodus an“, „Bildschirm nach 2 Minuten aus“, " +
                "„mach einen Screenshot“ oder „beende Instagram“.", 14f, SOFT).apply { setPadding(0, ui.px(12), 0, 0) })
            col.addView(ui.button("Auto-Einrichtung: Jarvis alle Rechte geben") {
                thread { val log = ProShell.autoSetup(this); runOnUiThread {
                    android.app.AlertDialog.Builder(this).setTitle("Auto-Einrichtung").setMessage(log.joinToString("\n")).setPositiveButton("OK", null).show(); build() } }
            })
            col.addView(ui.text("Setzt alle Freigaben auf einmal (Bildschirmsteuerung, Benachrichtigungen, Mikrofon, Kalender, Standort …) – " +
                "das, was du sonst einzeln in den Einstellungen machst. Keine Schutzfunktion wird abgeschaltet.", 12f, MUTED).apply { setPadding(0, ui.px(6), 0, 0) })
            col.addView(ui.section("Testen"))
            fun test(label: String, action: String, on: Boolean?) = col.addView(ui.button(label, false) { thread {
                val (cmd, err) = de.jarvis.app.logic.ProCommands.build(action, on, null, android.os.Build.VERSION.SDK_INT)
                val r = cmd?.shell?.map { ProShell.run(it) }?.lastOrNull()
                runOnUiThread { toast(if (cmd == null) err ?: "Fehler" else if (r?.first == 0) cmd.done else "Abgelehnt: ${r?.second}") } } })
            test("Dunkelmodus an", "dark_mode", true)
            test("Dunkelmodus aus", "dark_mode", false)
            test("Screenshot machen", "screenshot", null)
            return
        }

        col.addView(ui.section("Einrichtung (einmalig, ca. 3 Minuten)"))
        fun step(n: Int, title: String, text: String, button: String?, act: (() -> Unit)?) = col.addView(ui.card().apply {
            addView(ui.text("$n · $title", 16f, Ui.FG, true))
            addView(ui.text(text, 13f, SOFT).apply { setPadding(0, ui.px(4), 0, 0) })
            if (button != null && act != null) addView(ui.button(button, false) { act() })
        })
        step(1, "Shizuku installieren", "Kostenlose App aus dem Play Store (Entwickler: RikkaApps).",
            if (installed) null else "Im Play Store öffnen") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ProShell.SHIZUKU_PKG}"))) }
            catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${ProShell.SHIZUKU_PKG}"))) }
        }
        step(2, "Kabelloses Debugging einschalten", "Entwickleroptionen → „Kabelloses Debugging“ an (WLAN muss verbunden sein). " +
            "Tipp: Bugjaeger vorher trennen, damit sich nichts in die Quere kommt.", "Entwickleroptionen öffnen") {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (_: Exception) { toast("Entwickleroptionen nicht gefunden.") }
        }
        step(3, "Shizuku koppeln", "In Shizuku: „Kopplung“ antippen → Benachrichtigung erscheint. Dann in den Entwickleroptionen → Kabelloses Debugging → " +
            "„Gerät mit Kopplungscode koppeln“ → den 6-stelligen Code in die Shizuku-Benachrichtigung eintippen.", if (installed) "Shizuku öffnen" else null) {
            packageManager.getLaunchIntentForPackage(ProShell.SHIZUKU_PKG)?.let { startActivity(it) }
        }
        step(4, "Shizuku starten", "In Shizuku auf „Starten“ tippen. Nach jedem Neustart des Handys muss das einmal wiederholt werden.", if (installed) "Shizuku öffnen" else null) {
            packageManager.getLaunchIntentForPackage(ProShell.SHIZUKU_PKG)?.let { startActivity(it) }
        }
        step(5, "Jarvis erlauben", "Dann hier tippen und im Shizuku-Fenster „Immer erlauben“ wählen.", "Jarvis verbinden") {
            if (!ProShell.running()) toast("Shizuku läuft noch nicht – erst Schritt 4.")
            else ProShell.request { ok -> runOnUiThread { toast(if (ok) "Profi-Modus aktiv!" else "Nicht erlaubt."); build() } }
        }
        col.addView(ui.text("Sicherheit: Jarvis führt nur eine feste Liste von Befehlen aus (Schalter, Screenshot, App beenden …). Löschen, Deinstallieren und " +
            "Neustart nur nach deinem Ja. Geschützte Apps (Family Link, Play-Dienste, Einstellungen, Telefon) fasst er nicht an.", 12f, MUTED).apply { setPadding(0, ui.px(14), 0, 0) })
    }
}
