package de.jarvis.app.logic

/**
 * Feste Liste der Systembefehle, die Jarvis im Profi-Modus (Shizuku) ausführen darf.
 * Die KI kann keine freien Shell-Befehle schicken – nur diese Aktionen mit geprüften Werten.
 */
object ProCommands {
    data class Cmd(val shell: List<String>, val confirm: String? = null, val done: String)

    /** Apps, die Jarvis nie beendet oder deinstalliert (Schutz, Family Link, System, Jarvis selbst). */
    private val PROTECTED = listOf("de.jarvis.app", "moe.shizuku", "com.google.android.apps.kids", "familylink", "com.google.android.gms",
        "com.android.vending", "com.samsung.android.kgclient", "com.samsung.android.knox", "com.android.systemui", "com.android.settings",
        "com.android.phone", "com.samsung.android.dialer", "com.google.android.dialer", "android", "com.sec.android.app.launcher")

    fun isProtected(pkg: String) = PROTECTED.any { pkg == it || pkg.startsWith("$it.") || pkg.contains(it) }

    private val PKG = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    /**
     * @param action z. B. "airplane", "mobile_data", "wifi", "bluetooth", "nfc", "location", "dark_mode", "battery_saver",
     *   "dnd", "screen_timeout", "force_stop", "uninstall", "screenshot", "screen_record", "reboot"
     * @param on an/aus (für Schalter); [value] Zahl (Sekunden) oder Paketname
     * @return Befehl oder Fehlermeldung (Left = Fehler)
     */
    fun build(action: String, on: Boolean?, value: String?, sdk: Int, now: Long = System.currentTimeMillis()): Pair<Cmd?, String?> {
        fun sw(): Boolean? = on
        val v = value?.trim().orEmpty()
        return when (action) {
            "airplane" -> sw()?.let { Cmd(listOf("cmd connectivity airplane-mode ${if (it) "enable" else "disable"}"), done = "Flugmodus ${onOff(it)}.") to null } ?: needOn()
            "mobile_data" -> sw()?.let { Cmd(listOf("svc data ${if (it) "enable" else "disable"}"), done = "Mobile Daten ${onOff(it)}.") to null } ?: needOn()
            "wifi" -> sw()?.let { Cmd(listOf(if (sdk >= 31) "cmd wifi set-wifi-enabled ${if (it) "enabled" else "disabled"}" else "svc wifi ${if (it) "enable" else "disable"}"), done = "WLAN ${onOff(it)}.") to null } ?: needOn()
            "bluetooth" -> sw()?.let { Cmd(listOf(if (sdk >= 33) "cmd bluetooth_manager ${if (it) "enable" else "disable"}" else "svc bluetooth ${if (it) "enable" else "disable"}"), done = "Bluetooth ${onOff(it)}.") to null } ?: needOn()
            "nfc" -> sw()?.let { Cmd(listOf("svc nfc ${if (it) "enable" else "disable"}"), done = "NFC ${onOff(it)}.") to null } ?: needOn()
            "location" -> sw()?.let { Cmd(listOf(if (sdk >= 29) "cmd location set-location-enabled $it" else "settings put secure location_mode ${if (it) 3 else 0}"), done = "Standort ${onOff(it)}.") to null } ?: needOn()
            "dark_mode" -> sw()?.let { Cmd(listOf("cmd uimode night ${if (it) "yes" else "no"}"), done = "Dunkelmodus ${onOff(it)}.") to null } ?: needOn()
            "battery_saver" -> sw()?.let { Cmd(listOf("settings put global low_power ${if (it) 1 else 0}"), done = "Energiesparmodus ${onOff(it)}.") to null } ?: needOn()
            "dnd" -> sw()?.let { Cmd(listOf("cmd notification set_dnd ${if (it) "priority" else "off"}"), done = "Nicht stören ${onOff(it)}.") to null } ?: needOn()
            "screen_timeout" -> {
                val sec = v.filter { it.isDigit() }.toIntOrNull() ?: return null to "Nach wie vielen Sekunden soll der Bildschirm ausgehen?"
                val s = sec.coerceIn(15, 1800)
                Cmd(listOf("settings put system screen_off_timeout ${s * 1000}"), done = "Bildschirm geht jetzt nach ${if (s >= 60) "${s / 60} Minute(n)" else "$s Sekunden"} aus.") to null
            }
            "force_stop", "uninstall" -> {
                if (!PKG.matches(v)) return null to "Welche App genau? (Paketname fehlt)"
                if (isProtected(v)) return null to "Diese App ist geschützt – die beende oder lösche ich nicht."
                if (action == "force_stop") Cmd(listOf("am force-stop $v"), confirm = "App $v hart beenden.", done = "App beendet.") to null
                else Cmd(listOf("pm uninstall --user 0 $v"), confirm = "App $v deinstallieren (für dich; Neuinstallation über den Play Store möglich).", done = "App deinstalliert.") to null
            }
            "screenshot" -> Cmd(listOf("mkdir -p /sdcard/Pictures/Screenshots", "screencap -p /sdcard/Pictures/Screenshots/Jarvis_$now.png",
                "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/Screenshots/Jarvis_$now.png"), done = "Screenshot gespeichert (Galerie → Screenshots).") to null
            "screen_record" -> {
                val sec = (v.filter { it.isDigit() }.toIntOrNull() ?: 15).coerceIn(3, 180)
                Cmd(listOf("mkdir -p /sdcard/Movies", "screenrecord --time-limit $sec /sdcard/Movies/Jarvis_$now.mp4 > /dev/null 2>&1 &"), done = "Ich nehme $sec Sekunden den Bildschirm auf (Galerie → Movies).") to null
            }
            "reboot" -> Cmd(listOf("svc power reboot"), confirm = "Handy jetzt neu starten (danach Shizuku einmal neu starten).", done = "Starte neu …") to null
            else -> null to "Unbekannte Aktion."
        }
    }

    /** Einfache Schalter-Sätze direkt erkennen („Flugmodus an“, „WLAN aus“) – geht dann sofort und auch offline. */
    fun parseLocal(text: String): Pair<String, Boolean>? {
        val t = text.lowercase().replace(Regex("[^a-zäöüß ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.split(" ").size > 8 || " und " in " $t ") return null
        val on = Regex("\\b(an|ein|einschalten|anmachen|aktivier\\w*)\\b").containsMatchIn(t)
        val off = Regex("\\b(aus|ausschalten|ausmachen|deaktivier\\w*)\\b").containsMatchIn(t)
        if (on == off) return null
        val what = when {
            "flugmodus" in t || "flugzeugmodus" in t -> "airplane"
            "mobile daten" in t || "datenverbindung" in t || "mobiles internet" in t -> "mobile_data"
            Regex("\\b(wlan|wifi|wi fi)\\b").containsMatchIn(t) -> "wifi"
            "bluetooth" in t -> "bluetooth"
            Regex("\\bnfc\\b").containsMatchIn(t) -> "nfc"
            "dunkelmodus" in t || "dark mode" in t || "dunkler modus" in t -> "dark_mode"
            "energiespar" in t || "stromspar" in t -> "battery_saver"
            "nicht stören" in t -> "dnd"
            "standort" in t || "gps" in t -> "location"
            else -> return null
        }
        return what to on
    }

    private fun onOff(b: Boolean) = if (b) "an" else "aus"
    private fun needOn(): Pair<Cmd?, String?> = null to "Soll ich es an- oder ausschalten?"

    /** Jarvis in die Liste der Bedienungshilfen aufnehmen, ohne andere zu entfernen. */
    fun addAccessibility(current: String?, mine: String): String {
        val list = current.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() && it != "null" && it.contains('/') && !it.contains(' ') }.toMutableList()
        if (list.none { it.equals(mine, true) }) list += mine
        return list.joinToString(":")
    }
}
