package de.jarvis.app

import android.app.Activity
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import de.jarvis.app.tools.JsonStore
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.concurrent.thread

/**
 * Sicherheitszentrale: Handy-Check, verdächtige Rechte, Phishing-/Fake-Seiten-Erkennung, Passwort-Check, Geräte im WLAN.
 * Alles nur lesend – Jarvis ändert nichts ohne dich. Passwörter werden nie gespeichert oder an die KI geschickt.
 */
object Security {
    data class Finding(val level: Int, val title: String, val detail: String, val fix: String? = null)   // level 0 ok, 1 Hinweis, 2 Warnung

    private fun userApp(a: ApplicationInfo) = (a.flags and ApplicationInfo.FLAG_SYSTEM) == 0

    fun check(ctx: Context): Pair<Int, List<Finding>> {
        val f = ArrayList<Finding>()
        val pm = ctx.packageManager
        // Bildschirmsperre
        val km = ctx.getSystemService(KeyguardManager::class.java)
        f += if (km?.isDeviceSecure == true) Finding(0, "Bildschirmsperre aktiv", "PIN, Muster oder Fingerabdruck schützt dein Handy.")
             else Finding(2, "Keine Bildschirmsperre", "Jeder, der dein Handy findet, kommt an alles ran.", Settings.ACTION_SECURITY_SETTINGS)
        // Sicherheits-Update
        val patch = runCatching { LocalDate.parse(Build.VERSION.SECURITY_PATCH) }.getOrNull()
        if (patch != null) {
            val months = ChronoUnit.MONTHS.between(patch, LocalDate.now())
            f += if (months <= 3) Finding(0, "Sicherheits-Update aktuell", "Stand ${Build.VERSION.SECURITY_PATCH}.")
                 else Finding(if (months > 6) 2 else 1, "Sicherheits-Update $months Monate alt", "Stand ${Build.VERSION.SECURITY_PATCH}. Schau nach Updates.", "android.settings.SYSTEM_UPDATE_SETTINGS")
        }
        // USB-Debugging
        val adb = Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        if (adb) f += Finding(1, "USB-/WLAN-Debugging an", "Brauchst du für den Profi-Modus. Wenn du ihn nicht nutzt, lieber ausschalten.", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        // Fremde Bedienungshilfen (können alles auf dem Bildschirm lesen)
        val acc = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').map { it.substringBefore('/') }.filter { it.isNotBlank() && it != ctx.packageName }.distinct()
        if (acc.isNotEmpty()) f += Finding(1, "${acc.size} weitere App(s) mit Bildschirm-Zugriff", acc.joinToString(", ") { ProTools.label(ctx, it) } +
            " – darf alles sehen, was du tippst. Kennst du die?", Settings.ACTION_ACCESSIBILITY_SETTINGS)
        else f += Finding(0, "Keine fremden Bedienungshilfen", "Nur Jarvis darf den Bildschirm lesen.")
        // Benachrichtigungen mitlesen
        val nl = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
            .split(':').map { it.substringBefore('/') }.filter { it.isNotBlank() && it != ctx.packageName }.distinct()
        if (nl.isNotEmpty()) f += Finding(1, "${nl.size} App(s) lesen Benachrichtigungen mit", nl.joinToString(", ") { ProTools.label(ctx, it) }, "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        // Geräte-Administratoren
        val admins = ctx.getSystemService(DevicePolicyManager::class.java)?.activeAdmins.orEmpty().map { it.packageName }.distinct()
            .filter { it != ctx.packageName && !it.startsWith("com.google.android.gms") && !it.contains("kids") && !it.contains("familylink") }
        if (admins.isNotEmpty()) f += Finding(1, "Geräte-Administratoren", admins.joinToString(", ") { ProTools.label(ctx, it) } + " – solche Apps sind schwer zu entfernen.", "android.settings.DEVICE_ADMIN_SETTINGS")
        // Apps aus unbekannten Quellen + verdächtige Rechte
        val pkgs: List<PackageInfo> = try { pm.getInstalledPackages(PackageManager.GET_PERMISSIONS) } catch (_: Exception) { emptyList() }
        val sideload = ArrayList<String>(); val heavy = ArrayList<String>(); val overlay = ArrayList<String>()
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        for (p in pkgs) {
            val ai = p.applicationInfo ?: continue
            if (!userApp(ai) || p.packageName == ctx.packageName || p.packageName.startsWith("moe.shizuku")) continue
            val src = try { if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(p.packageName).installingPackageName else @Suppress("DEPRECATION") pm.getInstallerPackageName(p.packageName) } catch (_: Exception) { null }
            if (src == null || src !in setOf("com.android.vending", "com.sec.android.app.samsungapps", "com.google.android.packageinstaller.store")) sideload += p.packageName
            val req = p.requestedPermissions ?: continue; val fl = p.requestedPermissionsFlags ?: continue
            val granted = req.indices.filter { (fl[it] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0 }.map { req[it] }.toSet()
            val danger = listOf("android.permission.READ_SMS", "android.permission.RECORD_AUDIO", "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.READ_CONTACTS", "android.permission.CAMERA", "android.permission.READ_CALL_LOG").count { it in granted }
            if (danger >= 4) heavy += p.packageName
            try {
                @Suppress("DEPRECATION")
                if (ops?.checkOpNoThrow("android:system_alert_window", ai.uid, p.packageName) == AppOpsManager.MODE_ALLOWED) overlay += p.packageName
            } catch (_: Exception) {}
        }
        if (sideload.isNotEmpty()) f += Finding(1, "${sideload.size} App(s) nicht aus dem Play Store", sideload.take(8).joinToString(", ") { ProTools.label(ctx, it) } +
            ". Nur behalten, wenn du weißt, woher sie kommen.")
        if (heavy.isNotEmpty()) f += Finding(1, "Apps mit sehr vielen Rechten", heavy.take(8).joinToString(", ") { ProTools.label(ctx, it) } +
            " dürfen 4+ von: SMS, Mikrofon, Standort, Kontakte, Kamera, Anrufliste. Sag z. B. „Nimm X das Mikrofon weg“.")
        if (overlay.size > 3) f += Finding(1, "${overlay.size} Apps dürfen sich über andere legen", overlay.take(8).joinToString(", ") { ProTools.label(ctx, it) }, Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        // Akkufresser im Hintergrund (nur mit Profi-Modus)
        if (ProShell.ready) runCatching { ProTools.batteryHogs(ctx) }.getOrNull()?.takeIf { it.startsWith("Größte") }?.let {
            f += Finding(0, "Akku-Verbrauch", it.lines().drop(1).take(3).joinToString("; ") { l -> l.removePrefix("• ") }) }
        val score = (100 - f.sumOf { when (it.level) { 2 -> 25; 1 -> 7; else -> 0 }.toInt() }).coerceIn(0, 100)
        return score to f.sortedByDescending { it.level }
    }

    fun report(ctx: Context): String {
        val (score, f) = check(ctx)
        return "Sicherheits-Check: $score/100\n" + f.joinToString("\n") { (when (it.level) { 2 -> "⚠️ "; 1 -> "• "; else -> "✓ " }) + it.title + " – " + it.detail }
    }

    // ---------- Phishing & Fake-Seiten

    private val BRANDS = listOf("paypal", "amazon", "dhl", "sparkasse", "volksbank", "postbank", "commerzbank", "deutschebank", "ing", "apple", "icloud",
        "google", "microsoft", "netflix", "instagram", "snapchat", "whatsapp", "tiktok", "steam", "epicgames", "roblox", "fortnite", "hermes", "dpd", "ups", "post", "telekom", "vodafone", "o2")
    private val SHORT = listOf("bit.ly", "tinyurl", "t.co", "goo.gl", "is.gd", "cutt.ly", "rb.gy", "shorturl", "ow.ly", "tiny.cc")
    private val URGENT = Regex("(konto|account).{0,30}(gesperrt|eingeschränkt|deaktiviert)|bestätigen sie|verifizier|innerhalb von 24|letzte mahnung|paket.{0,40}(zugestellt|zollgebühr|gebühr)|gewonnen|gewinnspiel|gratis.{0,20}(v-bucks|robux|iphone)|klicken sie (hier|auf den link)|passwort.{0,20}(abgelaufen|ändern)|sendungsverfolgung|ungewöhnliche aktivität", RegexOption.IGNORE_CASE)

    /** Bewertung 0 (unbedenklich) .. 100 (sehr verdächtig) mit Gründen. */
    fun phishing(text: String): Pair<Int, List<String>> {
        val why = ArrayList<String>(); var s = 0
        val urls = Regex("(https?://)?([a-z0-9-]+\\.)+[a-z]{2,}(/\\S*)?", RegexOption.IGNORE_CASE).findAll(text).map { it.value.lowercase() }.toList()
        if (URGENT.containsMatchIn(text)) { s += 30; why += "Typische Druck-/Lockformulierung (Konto gesperrt, Paket, Gewinn …)" }
        for (u in urls) {
            val host = u.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?')
            val parts = host.split('.'); val domain = parts.takeLast(2).joinToString(".")
            if (u.startsWith("http://")) { s += 10; why += "Keine verschlüsselte Verbindung (http) bei $host" }
            if (host.contains("xn--")) { s += 35; why += "Getarnte Buchstaben im Namen (Punycode): $host" }
            if (Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(host)) { s += 35; why += "Link geht direkt auf eine IP-Adresse" }
            if (SHORT.any { host.contains(it) }) { s += 20; why += "Verkürzter Link ($host) – Ziel ist versteckt" }
            if (u.contains("@")) { s += 25; why += "„@“ im Link – klassischer Trick" }
            val brand = BRANDS.firstOrNull { b -> host.replace("-", "").contains(b) }
            if (brand != null && !domain.startsWith("$brand.") && !domain.replace("-", "").startsWith(brand)) { s += 45; why += "Tut so, als wäre es $brand – die echte Seite ist aber „$domain“" }
            if (Regex("(paypa1|amaz0n|g00gle|faceb00k|micr0soft|app1e|netf1ix|1nstagram|rnicrosoft|arnazon)").containsMatchIn(host)) { s += 50; why += "Nachgemachter Name mit vertauschten Zeichen: $host" }
            if (parts.size > 4) { s += 10; why += "Ungewöhnlich viele Unterebenen: $host" }
            if (listOf(".zip", ".mov", ".top", ".xyz", ".click", ".gq", ".tk", ".ml", ".cf", ".ru", ".cn").any { host.endsWith(it) }) { s += 15; why += "Endung „.${parts.last()}“ wird oft für Betrug benutzt" }
        }
        if (Regex("(pin|tan|passwort|kreditkarte|iban).{0,40}(eingeben|senden|bestätigen|mitteilen)", RegexOption.IGNORE_CASE).containsMatchIn(text)) { s += 30; why += "Fragt nach PIN/TAN/Passwort/Kartendaten – das macht keine echte Firma per Nachricht" }
        return s.coerceIn(0, 100) to why.distinct()
    }

    fun phishingText(text: String): String {
        val (s, why) = phishing(text)
        val verdict = when { s >= 60 -> "🚨 Sehr wahrscheinlich Betrug! Nicht klicken, nichts eingeben, Nachricht löschen."; s >= 30 -> "⚠️ Verdächtig – lieber nicht klicken. Im Zweifel die Firma über ihre echte App/Webseite prüfen."
            s > 0 -> "Etwas auffällig, aber kein klares Betrugsmuster."; else -> "✓ Keine typischen Betrugsmerkmale gefunden (100 % Sicherheit gibt es nie)." }
        return "$verdict (Risiko $s/100)" + (if (why.isNotEmpty()) "\nGründe:\n" + why.joinToString("\n") { "• $it" } else "")
    }

    // ---------- Passwort (bleibt auf dem Handy; an den Dienst gehen nur 5 Zeichen eines Hash-Werts)

    fun passwordCheck(pw: String): String {
        if (pw.isEmpty()) return "Kein Passwort eingegeben."
        var score = 0
        if (pw.length >= 8) score++; if (pw.length >= 12) score++; if (pw.length >= 16) score++
        if (pw.any { it.isLowerCase() } && pw.any { it.isUpperCase() }) score++
        if (pw.any { it.isDigit() }) score++
        if (pw.any { !it.isLetterOrDigit() }) score++
        val common = listOf("123456", "passwort", "password", "qwertz", "qwerty", "hallo", "schalke", "fussball", "iloveyou", "111111", "abc123", "123123")
        val weakPattern = common.any { pw.lowercase().contains(it) } || Regex("^(.)\\1+$").matches(pw) || Regex("(19|20)\\d\\d$").containsMatchIn(pw)
        if (weakPattern) score -= 2
        val strength = when { score >= 6 -> "sehr stark 💪"; score >= 4 -> "gut"; score >= 2 -> "mittel"; else -> "schwach" }
        val leaked = try {
            val sha = MessageDigest.getInstance("SHA-1").digest(pw.toByteArray()).joinToString("") { "%02X".format(it) }
            val c = URL("https://api.pwnedpasswords.com/range/" + sha.take(5)).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000; c.setRequestProperty("User-Agent", "JarvisApp"); c.setRequestProperty("Add-Padding", "true")
            val body = c.inputStream.bufferedReader().readText(); c.disconnect()
            body.lines().firstOrNull { it.substringBefore(':').equals(sha.drop(5), true) }?.substringAfter(':')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        } catch (_: Exception) { -1 }
        val sb = StringBuilder("Stärke: $strength (${pw.length} Zeichen).")
        when {
            leaked == null -> sb.append("\n✓ Nicht in bekannten Datenlecks gefunden.")
            leaked < 0 -> sb.append("\n(Datenleck-Prüfung gerade nicht erreichbar.)")
            else -> sb.append("\n🚨 Dieses Passwort taucht $leaked-mal in Datenlecks auf – sofort überall ändern!")
        }
        if (score < 4) sb.append("\nTipp: Mindestens 12 Zeichen, z. B. ein Satz aus 4 zufälligen Wörtern mit Zahl und Zeichen.")
        return sb.toString()
    }

    // ---------- Geräte im WLAN (Profi-Modus)

    fun lanDevices(ctx: Context): String {
        if (!ProShell.ready) return "Für den WLAN-Scan braucht Jarvis den Profi-Modus."
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val lp = cm?.getLinkProperties(cm.activeNetwork) ?: return "Nicht mit einem Netzwerk verbunden."
        val ip = lp.linkAddresses.map { it.address.hostAddress.orEmpty() }.firstOrNull { Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(it) } ?: return "Keine WLAN-Adresse gefunden."
        val net = ip.substringBeforeLast('.')
        if (!Regex("^\\d+\\.\\d+\\.\\d+$").matches(net)) return "Netz nicht erkannt."
        // Kurz alle Adressen anpingen, dann die Nachbarschaftstabelle lesen
        ProShell.run("for i in \$(seq 1 254); do ping -c1 -W1 $net.\$i >/dev/null 2>&1 & done; wait", 25)
        val (_, out) = ProShell.run("ip neigh show", 10)
        val devs = out.lines().mapNotNull { Regex("^(\\d+\\.\\d+\\.\\d+\\.\\d+) .*lladdr ([0-9a-f:]{17}) (\\w+)").find(it.trim()) }
            .filter { it.groupValues[3] != "FAILED" }.map { it.groupValues[1] to it.groupValues[2] }.distinctBy { it.second }
        val store = JsonStore(ctx)
        val known = store.obj("lan_known")
        val first = known.length() == 0
        val fresh = devs.filter { !known.has(it.second) }
        devs.forEach { known.put(it.second, it.first) }
        store.saveObj("lan_known", known)
        return "Im WLAN gefunden: ${devs.size + 1} Geräte (mit deinem Handy).\n" + devs.sortedBy { it.first.substringAfterLast('.').toIntOrNull() ?: 0 }
            .joinToString("\n") { (a, m) -> "• $a  ($m)" + if (!first && fresh.any { it.second == m }) "  🆕 neu" else "" } +
            (if (first) "\nErster Scan – ich merke mir diese Geräte und zeige beim nächsten Mal, welche neu sind." else if (fresh.isEmpty()) "\nKeine neuen Geräte seit dem letzten Scan." else "\n${fresh.size} neue(s) Gerät(e)! Kennst du die nicht, Router-Passwort ändern.")
    }
}

/** Sicherheitszentrale (Bildschirm). */
class SecurityActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.load(Prefs(this)); ui = Ui(this)
        window.statusBarColor = Ui.BG; window.navigationBarColor = Ui.BG; Ui.lightBars(window)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(20), ui.px(22), ui.px(20), ui.px(40)) }
        setContentView(ScrollView(this).apply { background = ui.aurora(); addView(col) })
        if (intent.getBooleanExtra("password", false)) passwordDialog()
    }

    override fun onResume() { super.onResume(); build() }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    private fun dialog(title: String, msg: String) = runOnUiThread {
        android.app.AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show() }

    private fun build() {
        col.removeAllViews()
        col.addView(ui.row().apply {
            addView(ui.text("‹", 30f).apply { setPadding(0, 0, ui.px(16), 0); setOnClickListener { finish() } })
            addView(LinearLayout(this@SecurityActivity).apply { orientation = LinearLayout.VERTICAL
                addView(ui.text("Sicherheit", 28f, Ui.FG, true)); addView(ui.text("Jarvis prüft, du entscheidest", 13f, Ui.MUTED)) })
        })
        val scoreView = ui.text("…", 44f, Ui.FG, true)
        val scoreSub = ui.text("Prüfe dein Handy …", 13f, Ui.MUTED)
        col.addView(ui.card().apply { addView(scoreView); addView(scoreSub) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(16) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        thread {
            val (score, f) = Security.check(this)
            runOnUiThread {
                scoreView.text = "$score / 100"
                scoreView.setTextColor(if (score >= 85) Ui.GREEN else if (score >= 60) Ui.AMBER else android.graphics.Color.rgb(255, 80, 90))
                scoreSub.text = if (score >= 85) "Sehr gut geschützt." else if (score >= 60) "Ein paar Dinge kannst du verbessern." else "Bitte die Warnungen ansehen."
                for (x in f) list.addView(ui.card().apply {
                    addView(ui.text((when (x.level) { 2 -> "⚠️  "; 1 -> "•  "; else -> "✓  " }) + x.title, 15f,
                        if (x.level == 2) android.graphics.Color.rgb(255, 110, 110) else if (x.level == 1) Ui.AMBER else Ui.GREEN, true))
                    addView(ui.text(x.detail, 13f, Ui.SOFT).apply { setPadding(0, ui.px(4), 0, 0) })
                    x.fix?.let { action -> setOnClickListener { try { startActivity(Intent(action)) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) } } }
                })
            }
        }
        col.addView(ui.section("Werkzeuge"))
        col.addView(ui.button("Link oder Nachricht prüfen (Phishing)") { phishingDialog() })
        col.addView(ui.button("Passwort prüfen", false) { passwordDialog() })
        col.addView(ui.button("Geräte im WLAN scannen", false) { toast("Scanne das WLAN (ca. 20 Sekunden) …"); thread { dialog("📡 Geräte im WLAN", Security.lanDevices(this)) } })
        col.addView(ui.button("Sicherheitsbericht teilen", false) { thread { val r = Security.report(this)
            runOnUiThread { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r), "Bericht teilen")) } } })
        col.addView(ui.text("Jarvis liest hier nur – geändert wird nichts ohne dich. Passwörter werden nicht gespeichert und nicht an die KI geschickt.", 12f, Ui.MUTED).apply { setPadding(0, ui.px(14), 0, 0) })
    }

    private fun input(hint: String, password: Boolean) = EditText(this).apply {
        this.hint = hint; setTextColor(Ui.FG); setHintTextColor(Ui.MUTED); setPadding(ui.px(14), ui.px(12), ui.px(14), ui.px(12))
        background = ui.round(Ui.CARD2, 14)
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }

    private fun phishingDialog() {
        val e = input("Link oder verdächtige Nachricht einfügen", false)
        val clip = (getSystemService(android.content.ClipboardManager::class.java))?.primaryClip?.getItemAt(0)?.text?.toString()
        if (!clip.isNullOrBlank() && clip.length < 2000) e.setText(clip)
        android.app.AlertDialog.Builder(this).setTitle("🎣 Phishing-Check").setView(LinearLayout(this).apply { setPadding(ui.px(20), ui.px(8), ui.px(20), 0); addView(e, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Prüfen") { _, _ -> dialog("Ergebnis", Security.phishingText(e.text.toString())) }.setNegativeButton("Abbrechen", null).show()
    }

    private fun passwordDialog() {
        val e = input("Passwort (wird nicht gespeichert)", true)
        android.app.AlertDialog.Builder(this).setTitle("🔑 Passwort prüfen")
            .setMessage("Bleibt auf deinem Handy. Für die Datenleck-Prüfung gehen nur die ersten 5 Zeichen eines Hash-Werts raus – nicht das Passwort.")
            .setView(LinearLayout(this).apply { setPadding(ui.px(20), ui.px(8), ui.px(20), 0); addView(e, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Prüfen") { _, _ -> val pw = e.text.toString(); e.setText(""); thread { dialog("Ergebnis", Security.passwordCheck(pw)) } }
            .setNegativeButton("Abbrechen", null).show()
    }
}
