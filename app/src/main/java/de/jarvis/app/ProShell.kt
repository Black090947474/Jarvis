package de.jarvis.app

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * Profi-Modus über Shizuku: führt einzelne, fest vorgegebene Systembefehle mit ADB-Rechten aus.
 * Funktioniert nur, wenn du Shizuku installiert, gestartet und Jarvis darin erlaubt hast.
 */
object ProShell {
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val REQ = 4711
    @Volatile private var listening = false

    fun installed(ctx: Context) = try { ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true } catch (_: Exception) { false }

    /** Läuft der Shizuku-Dienst? */
    fun running(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun granted(): Boolean = try {
        running() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    val ready: Boolean get() = granted()

    /** Fragt in Shizuku nach Erlaubnis (zeigt den Shizuku-Dialog). */
    fun request(onResult: (Boolean) -> Unit) {
        try {
            if (!running()) { onResult(false); return }
            if (granted()) { onResult(true); return }
            if (!listening) {
                listening = true
                Shizuku.addRequestPermissionResultListener { code, result ->
                    if (code == REQ) pending?.invoke(result == PackageManager.PERMISSION_GRANTED)
                }
            }
            pending = onResult
            Shizuku.requestPermission(REQ)
        } catch (_: Throwable) { onResult(false) }
    }
    @Volatile private var pending: ((Boolean) -> Unit)? = null

    /** Einen Befehl ausführen. Rückgabe: Exit-Code und Ausgabe. Nur intern mit geprüften Befehlen benutzen. */
    fun run(cmd: String, timeoutSec: Long = 15): Pair<Int, String> {
        if (!granted()) return -1 to "Profi-Modus nicht aktiv"
        return try {
            val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = StringBuilder()
            val reader = Thread { try { out.append(p.inputStream.bufferedReader().readText()); out.append(p.errorStream.bufferedReader().readText()) } catch (_: Exception) {} }
            reader.start()
            val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!done) { p.destroy(); return -2 to "Zeitüberschreitung" }
            reader.join(1000)
            p.exitValue() to out.toString().trim()
        } catch (e: Throwable) { -3 to (e.cause?.message ?: e.message ?: "Fehler") }
    }

    /**
     * Einmalige Auto-Einrichtung (nur auf deinen Knopfdruck): Jarvis bekommt die Rechte, die du sonst
     * einzeln in den Einstellungen setzen müsstest. Keine Sicherheitsfunktion wird abgeschaltet.
     */
    fun autoSetup(ctx: Context): List<String> {
        val pkg = ctx.packageName
        val log = mutableListOf<String>()
        fun step(label: String, cmd: String) { val (code, out) = run(cmd); log += (if (code == 0) "✓ " else "✗ ") + label + (if (code != 0 && out.isNotBlank()) " ($out)".take(90) else "") }
        step("Systemeinstellungen schreiben (für Bedienungshilfe-Schutz)", "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
        val mine = "$pkg/$pkg.JarvisAccessibility"
        val current = run("settings get secure enabled_accessibility_services").second
        val merged = de.jarvis.app.logic.ProCommands.addAccessibility(current, mine)
        step("Bildschirmsteuerung einschalten", "settings put secure enabled_accessibility_services '$merged' && settings put secure accessibility_enabled 1")
        step("Über anderen Apps einblenden", "appops set $pkg SYSTEM_ALERT_WINDOW allow")
        step("Akku: nicht einschränken", "dumpsys deviceidle whitelist +$pkg")
        step("Systemeinstellungen ändern (Helligkeit)", "appops set $pkg WRITE_SETTINGS allow")
        for (perm in listOf("RECORD_AUDIO", "READ_CALENDAR", "WRITE_CALENDAR", "READ_CONTACTS", "CALL_PHONE", "READ_CALL_LOG",
                "ANSWER_PHONE_CALLS", "READ_PHONE_STATE", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS",
                "READ_MEDIA_IMAGES", "ACCESS_MEDIA_LOCATION", "BLUETOOTH_CONNECT"))
            run("pm grant $pkg android.permission.$perm")
        log += "✓ Einzelne Freigaben (Mikrofon, Kalender, Kontakte, Telefon, Standort, Fotos …)"
        step("Standort im Hintergrund", "pm grant $pkg android.permission.ACCESS_BACKGROUND_LOCATION")
        step("Benachrichtigungen lesen", "cmd notification allow_listener $pkg/$pkg.JarvisNotificationListener")
        return log
    }

    /** Bedienungshilfe nach Updates wieder einschalten (Samsung schaltet sie manchmal ab). Braucht WRITE_SECURE_SETTINGS. */
    fun keepAccessibility(ctx: Context) {
        if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return
        try {
            val mine = "${ctx.packageName}/${ctx.packageName}.JarvisAccessibility"
            val cur = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (cur?.contains(mine, true) == true) return
            Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                de.jarvis.app.logic.ProCommands.addAccessibility(cur, mine))
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (_: Exception) {}
    }
}
