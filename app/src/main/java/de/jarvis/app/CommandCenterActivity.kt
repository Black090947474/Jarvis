package de.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.concurrent.thread

/** Kontrollzentrum: Systemdiagnose, alle Bereiche auf einen Blick, Module, Jarvis aufs neue Handy übertragen. */
class CommandCenterActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.load(Prefs(this)); ui = Ui(this)
        window.statusBarColor = Ui.BG; window.navigationBarColor = Ui.BG; Ui.lightBars(window)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(20), ui.px(22), ui.px(20), ui.px(40)) }
        setContentView(ScrollView(this).apply { background = ui.aurora(); addView(col) })
    }

    override fun onResume() { super.onResume(); build() }

    private fun perm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun build() {
        col.removeAllViews()
        col.addView(ui.row().apply {
            addView(ui.text("‹", 30f).apply { setPadding(0, 0, ui.px(16), 0); setOnClickListener { finish() } })
            addView(LinearLayout(this@CommandCenterActivity).apply { orientation = LinearLayout.VERTICAL
                addView(ui.text("Kontrollzentrum", 28f, Ui.FG, true)); addView(ui.text("Alles über Jarvis auf einen Blick", 13f, Ui.MUTED)) }) })

        val prefs = Prefs(this)
        val checks = listOf(
            Triple(prefs.hasAnyKey, "KI-Schlüssel", Intent(this, ProvidersActivity::class.java)),
            Triple(WakeWordService.isRunning, "Weckwort „Jarvis“ hört zu", Intent(this, MainActivity::class.java)),
            Triple(perm(Manifest.permission.RECORD_AUDIO), "Mikrofon", Intent(this, PermissionsActivity::class.java)),
            Triple(JarvisAccessibility.instance != null, "Bildschirmsteuerung (Bedienungshilfe)", Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
            Triple(Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty().contains(packageName), "Benachrichtigungen lesen", Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")),
            Triple(perm(Manifest.permission.ACCESS_FINE_LOCATION), "Standort", Intent(this, PermissionsActivity::class.java)),
            Triple(perm(Manifest.permission.READ_CALENDAR), "Kalender", Intent(this, PermissionsActivity::class.java)),
            Triple(getSystemService(android.os.PowerManager::class.java)!!.isIgnoringBatteryOptimizations(packageName), "Läuft im Hintergrund (Akku nicht eingeschränkt)", Intent(this, PermissionsActivity::class.java)),
            Triple(JarvisCall.canFullScreen(this), "Vollbild-Anruf erlaubt", Intent(this, AutoPilotActivity::class.java)),
            Triple(ProShell.ready, "Profi-Modus (Shizuku)", Intent(this, ProActivity::class.java)),
        )
        val ok = checks.count { it.first }
        col.addView(ui.section("Systemdiagnose · $ok von ${checks.size} bereit"))
        col.addView(ui.card().apply {
            for ((good, label, fix) in checks) addView(ui.text((if (good) "●  " else "○  ") + label + (if (!good) "  → einrichten" else ""), 14f, if (good) Ui.GREEN else Ui.AMBER).apply {
                setPadding(0, ui.px(5), 0, ui.px(5)); if (!good) setOnClickListener { try { startActivity(fix) } catch (_: Exception) {} } })
        })
        col.addView(ui.button("KI-Verbindung testen", false) {
            val t0 = System.currentTimeMillis()
            thread {
                val r = try { Brains.build(prefs, PhoneTools(this), Memory(this), chat = true).ask("Antworte nur mit: OK", { }, null) } catch (e: Exception) { "Fehler: ${e.message?.take(80)}" }
                runOnUiThread { android.app.AlertDialog.Builder(this).setTitle("KI-Test").setMessage("Antwort: ${r.take(120)}\nDauer: ${System.currentTimeMillis() - t0} ms").setPositiveButton("OK", null).show() }
            }
        })

        col.addView(ui.section("Bereiche"))
        fun nav(label: String, sub: String, i: Intent) = col.addView(ui.card().apply {
            addView(ui.text(label, 15.5f, Ui.FG, true)); addView(ui.text(sub, 12.5f, Ui.MUTED)); setOnClickListener { startActivity(i) } })
        nav("🧩 Module", "Jede Funktion einzeln an- oder ausschalten", Intent(this, HubActivity::class.java).putExtra("page", "plugins"))
        nav("🤖 Autopilot", "Was Jarvis von selbst macht", Intent(this, AutoPilotActivity::class.java))
        nav("🛡 Sicherheit", "Check, Phishing, Passwort, WLAN-Geräte", Intent(this, SecurityActivity::class.java))
        nav("⚡ Profi-Modus", "Systemschalter, Werbeblocker, Apps einfrieren", Intent(this, ProActivity::class.java))
        nav("🔑 KI-Modelle", "Anbieter & Schlüssel (Groq, Gemini, ChatGPT …)", Intent(this, ProvidersActivity::class.java))
        nav("🎭 Persönlichkeit", "Butler, sarkastisch, Experte, Diskussion …", Intent(this, HubActivity::class.java).putExtra("page", "personality"))
        nav("🧠 Gedächtnis", "Was Jarvis über dich weiß – ansehen & löschen", Intent(this, HubActivity::class.java).putExtra("page", "memory"))
        nav("📜 Aktivitätsprotokoll", "Was Jarvis wann gemacht hat", Intent(this, HubActivity::class.java).putExtra("page", "log"))
        nav("🔐 Berechtigungen", "Alle Freigaben einsehen und ändern", Intent(this, PermissionsActivity::class.java))
        nav("⚙ Stimme & Einstellungen", "Stimme, Design, Weckwort", Intent(this, MainActivity::class.java))

        col.addView(ui.section("Jarvis übertragen"))
        col.addView(ui.text("Neues Handy? Jarvis nimmt Gedächtnis, Einstellungen, Listen und Automationen mit. KI-Schlüssel kommen aus Sicherheitsgründen nicht mit.", 13f, Ui.SOFT))
        col.addView(ui.button("Übertragungs-Datei erstellen") {
            thread { val r = Backup.export(this)
                runOnUiThread { android.app.AlertDialog.Builder(this).setTitle("📦 Jarvis übertragen")
                    .setMessage("$r\n\nSo geht's:\n1. Datei aufs neue Handy schicken (z. B. per Quick Share oder Google Drive).\n2. Dort Jarvis installieren.\n3. Einstellungen → Sicherung → „Sicherung laden“ und die Datei wählen.\n4. KI-Schlüssel neu einfügen.")
                    .setPositiveButton("OK", null).show() } } })
    }
}
