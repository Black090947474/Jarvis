package de.jarvis.app

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Selbst-Update: prüft die neueste Version auf GitHub und installiert sie über die alte (Daten bleiben erhalten). */
object Updater {
    private const val API = "https://api.github.com/repos/Black090947474/Jarvis/releases/tags/latest"
    data class Info(val name: String, val code: Long, val apk: String)

    fun installedCode(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (_: Exception) { 0 }

    fun installedName(ctx: Context): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }

    /** Neueste Version abfragen (Netzwerk – nicht auf dem Hauptthread aufrufen). null = nichts gefunden. */
    fun latest(): Info? = try {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000
            setRequestProperty("Accept", "application/vnd.github+json"); setRequestProperty("User-Agent", "JarvisApp")
        }
        val o = JSONObject(c.inputStream.bufferedReader().readText())
        val name = o.optString("name")
        val code = Regex("build (\\d+)").find(name)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val assets = o.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url").orEmpty()
        if (apk.isBlank()) null else Info(Regex("Jarvis ([\\d.]+)").find(name)?.groupValues?.get(1) ?: name, code, apk)
    } catch (_: Exception) { null }

    /** Prüfen und – wenn neuer – fragen, ob installiert werden soll. [quiet] = keine Meldung, wenn schon aktuell. */
    fun check(a: Activity, quiet: Boolean) {
        thread {
            val info = latest()
            val mine = installedCode(a)
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                when {
                    info == null -> if (!quiet) Toast.makeText(a, "Konnte nicht nach Updates suchen (Internet?).", Toast.LENGTH_LONG).show()
                    info.code <= mine -> if (!quiet) Toast.makeText(a, "Jarvis ist aktuell (${installedName(a)}).", Toast.LENGTH_LONG).show()
                    else -> AlertDialog.Builder(a).setTitle("Update verfügbar: Jarvis ${info.name}")
                        .setMessage("Du hast ${installedName(a)}. Jetzt herunterladen und installieren? Deine Daten und Einstellungen bleiben erhalten.")
                        .setPositiveButton("Installieren") { _, _ -> install(a, info) }
                        .setNegativeButton("Später", null).show()
                }
            }
        }
    }

    fun install(a: Activity, info: Info) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(a, "Bitte „Unbekannte Apps installieren“ für Jarvis erlauben, dann nochmal auf Update tippen.", Toast.LENGTH_LONG).show()
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            return
        }
        Toast.makeText(a, "Lade Jarvis ${info.name} herunter …", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val f = File(a.cacheDir, "update.apk")
                var url = info.apk
                var conn: HttpURLConnection
                var hops = 0
                while (true) {   // GitHub leitet auf den Download-Server um
                    conn = (URL(url).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false; connectTimeout = 10000; readTimeout = 30000
                        setRequestProperty("User-Agent", "JarvisApp") }
                    val code = conn.responseCode
                    if (code in 300..399 && hops++ < 5) { url = conn.getHeaderField("Location"); conn.disconnect(); continue }
                    break
                }
                conn.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                val pi = a.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(a.packageName) }
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    s.openWrite("jarvis.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pending = PendingIntent.getBroadcast(a, 77, Intent(a, UpdateReceiver::class.java), flags)
                    s.commit(pending.intentSender)
                }
            } catch (e: Exception) {
                a.runOnUiThread { Toast.makeText(a, "Update fehlgeschlagen: ${e.message?.take(80)}", Toast.LENGTH_LONG).show() }
            }
        }
    }
}

/** Bekommt den Status vom Installer und zeigt die Bestätigung („Aktualisieren?“) an. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Toast.makeText(ctx, "Update nicht installiert: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "abgebrochen"), Toast.LENGTH_LONG).show()
        }
    }
}
