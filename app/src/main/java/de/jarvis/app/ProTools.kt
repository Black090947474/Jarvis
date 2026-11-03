package de.jarvis.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import de.jarvis.app.logic.ProCommands
import java.io.File

/** Profi-Werkzeuge, die etwas auslesen (Akkufresser, Rechte, eingefrorene Apps) und Hilfen dafür. */
object ProTools {
    private val PKG = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)).toString()
    } catch (_: Exception) { pkg }

    private fun apps(ctx: Context): List<ApplicationInfo> = try {
        ctx.packageManager.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
    } catch (_: Exception) { emptyList() }

    /** App-Name („TikTok“) oder Paketname → Paketname. */
    fun resolve(ctx: Context, name: String): String? {
        val n = name.trim()
        if (n.isBlank()) return null
        if (PKG.matches(n) && n.contains('.') && !n.contains(' ')) return n
        val pm = ctx.packageManager
        val all = apps(ctx).map { it.packageName to (try { pm.getApplicationLabel(it).toString() } catch (_: Exception) { it.packageName }) }
        return all.firstOrNull { it.second.equals(n, true) }?.first
            ?: all.filter { it.second.contains(n, true) }.minByOrNull { it.second.length }?.first
    }

    private fun userApp(a: ApplicationInfo) = (a.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || (a.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    /** Welche (eigenen) Apps haben eine Berechtigung? */
    fun whoHas(ctx: Context, key: String): String {
        val perms = ProCommands.PERMS[key] ?: return "Welche Berechtigung? (Mikrofon, Kamera, Standort, Kontakte, Fotos …)"
        val pm = ctx.packageManager
        val list: List<PackageInfo> = try { pm.getInstalledPackages(PackageManager.GET_PERMISSIONS) } catch (_: Exception) { emptyList() }
        val hits = list.filter { p ->
            val ai = p.applicationInfo ?: return@filter false
            if (!userApp(ai) || p.packageName == ctx.packageName) return@filter false
            val req = p.requestedPermissions ?: return@filter false
            val flags = p.requestedPermissionsFlags ?: return@filter false
            req.indices.any { i -> req[i] in perms && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0 }
        }.map { label(ctx, it.packageName) }.sorted()
        return if (hits.isEmpty()) "Keine deiner Apps darf gerade: $key." else "Diese Apps dürfen $key (${hits.size}):\n" + hits.joinToString(", ")
    }

    /** Akkuverbrauch seit dem letzten Laden pro App (aus den System-Statistiken). */
    fun batteryHogs(ctx: Context): String {
        if (!ProShell.ready) return "Dafür braucht es den Profi-Modus."
        val (c1, stats) = ProShell.run("dumpsys batterystats | grep -E 'UID u[0-9]+a[0-9]+: [0-9.]+' | head -150", 40)
        if (c1 != 0 && stats.isBlank()) return "Konnte die Akku-Statistik nicht lesen."
        val (_, pk) = ProShell.run("pm list packages -U", 20)
        val uidToPkg = HashMap<Int, String>()
        Regex("package:(\\S+) uid:([0-9,]+)").findAll(pk).forEach { m -> m.groupValues[2].split(',').forEach { u -> u.toIntOrNull()?.let { uidToPkg.putIfAbsent(it, m.groupValues[1]) } } }
        val seen = HashSet<Int>()
        val rows = Regex("UID u(\\d+)a(\\d+): ([0-9.]+)").findAll(stats).mapNotNull { m ->
            val uid = m.groupValues[1].toInt() * 100_000 + 10_000 + m.groupValues[2].toInt()
            if (!seen.add(uid)) return@mapNotNull null   // erster Abschnitt = seit dem Laden
            val mah = m.groupValues[3].toDoubleOrNull() ?: return@mapNotNull null
            val pkg = uidToPkg[uid] ?: return@mapNotNull null
            pkg to mah
        }.sortedByDescending { it.second }.take(6).toList()
        if (rows.isEmpty()) return "Noch keine Akku-Daten seit dem letzten Laden."
        return "Größte Akkufresser seit dem letzten Laden:\n" + rows.joinToString("\n") { "• ${label(ctx, it.first)}: ${"%.0f".format(it.second)} mAh" } +
            "\nSag z. B. „Bremse ${label(ctx, rows.first().first)} im Hintergrund“, dann zieht sie weniger."
    }

    /** Eingefrorene (deaktivierte) Apps. */
    fun frozen(ctx: Context): List<String> {
        if (!ProShell.ready) return emptyList()
        val (_, out) = ProShell.run("pm list packages -d", 15)
        return Regex("package:(\\S+)").findAll(out).map { it.groupValues[1] }.filter { !ProCommands.isProtected(it) }.toList()
    }

    /** Vorinstallierte Extras, die man gefahrlos einfrieren kann (nur die, die es auf dem Handy gibt). */
    val BLOAT = linkedMapOf(
        "com.samsung.android.app.tips" to "Samsung Tipps",
        "com.samsung.android.arzone" to "AR Zone",
        "com.samsung.android.app.spage" to "Samsung Free",
        "com.samsung.android.bixby.agent" to "Bixby",
        "com.samsung.android.visionintelligence" to "Bixby Vision",
        "com.samsung.android.game.gamehome" to "Game Launcher",
        "com.samsung.android.app.watchmanager" to "Galaxy Wearable",
        "com.samsung.android.tvplus" to "Samsung TV Plus",
        "com.samsung.android.spay" to "Samsung Wallet",
        "com.facebook.katana" to "Facebook",
        "com.facebook.appmanager" to "Facebook App Manager",
        "com.facebook.services" to "Facebook Services",
        "com.facebook.system" to "Facebook App Installer",
        "com.microsoft.skydrive" to "OneDrive",
        "com.microsoft.office.outlook" to "Outlook",
        "com.netflix.partner.activation" to "Netflix-Aktivierung",
        "com.linkedin.android" to "LinkedIn",
        "com.google.android.apps.tachyon" to "Google Meet",
        "com.google.android.videos" to "Google TV",
        "com.google.android.apps.youtube.music" to "YouTube Music",
    )

    fun bloatPresent(ctx: Context): List<Pair<String, String>> {
        val installed = apps(ctx).filter { it.enabled }.map { it.packageName }.toSet()
        return BLOAT.entries.filter { it.key in installed }.map { it.key to it.value }
    }

    // ---------- Fehlerbericht

    fun errorReport(ctx: Context): String {
        val sb = StringBuilder("Jarvis ${Updater.installedName(ctx)} (build ${Updater.installedCode(ctx)}) · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n")
        val f = JarvisApp.crashFile(ctx)
        if (f.exists()) sb.append("\n— Letzter Absturz —\n").append(try { f.readText().take(4000) } catch (_: Exception) { "" }).append('\n')
        else sb.append("\nKein Absturz gespeichert.\n")
        if (ProShell.ready) {
            val (_, crash) = ProShell.run("logcat -d -b crash -t 80", 15)
            val mine = crash.lines().filter { it.contains("de.jarvis.app") || it.contains("FATAL") || it.contains("at de.jarvis") }.takeLast(40)
            if (mine.isNotEmpty()) sb.append("\n— System-Absturzprotokoll —\n").append(mine.joinToString("\n")).append('\n')
            val (_, log) = ProShell.run("logcat -d -t 400 '*:W'", 15)
            val rel = log.lines().filter { it.contains("jarvis", true) }.takeLast(30)
            if (rel.isNotEmpty()) sb.append("\n— Letzte Warnungen —\n").append(rel.joinToString("\n"))
        }
        return sb.toString().take(12_000)
    }

    // ---------- Stilles Update

    /** Installiert eine heruntergeladene APK ohne Nachfrage über den Profi-Modus. true = gestartet. */
    fun silentInstall(ctx: Context, apk: File): Boolean {
        if (!ProShell.ready) return false
        return try {
            val dir = ctx.getExternalFilesDir(null) ?: return false
            val dst = File(dir, "jarvis-update.apk")
            apk.copyTo(dst, overwrite = true)
            // Nach erfolgreicher Installation wird Jarvis neu gestartet – das Ergebnis kommt evtl. nicht mehr an
            val (code, out) = ProShell.run("pm install -r -i ${ctx.packageName} '${dst.absolutePath}'", 180)
            code == 0 || out.contains("Success")
        } catch (_: Exception) { false }
    }
}
