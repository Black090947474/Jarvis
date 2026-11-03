package de.jarvis.app.logic

/**
 * Feste Liste der Systembefehle, die Jarvis im Profi-Modus (Shizuku) ausführen darf.
 * Die KI kann keine freien Shell-Befehle schicken – nur diese Aktionen mit geprüften Werten.
 */
object ProCommands {
    data class Cmd(val shell: List<String>, val confirm: String? = null, val done: String)

    /** Apps, die Jarvis nie beendet oder deinstalliert (Schutz, Family Link, System, Jarvis selbst). */
    private val PROTECTED = listOf("de.jarvis.app", "moe.shizuku", "com.google.android.apps.kids", "com.google.android.gms", "com.google.android.gsf",
        "com.android.vending", "com.android.systemui", "com.android.settings", "com.android.phone", "com.samsung.android.dialer",
        "com.google.android.dialer", "android", "com.sec.android.app.launcher", "com.android.shell", "com.android.providers",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.google.android.packageinstaller",
        "com.android.packageinstaller", "com.google.android.webview", "com.android.webview", "com.android.bluetooth", "com.android.nfc",
        "com.google.android.apps.wellbeing", "com.samsung.android.forest", "com.samsung.android.app.telephonyui", "com.samsung.android.incallui",
        "com.samsung.android.honeyboard", "com.android.server.telecom", "com.samsung.android.lool", "com.samsung.android.sm")
    /** Teile von Paketnamen, die immer geschützt sind (Family Link, Knox, Tastaturen). */
    private val PROTECTED_PARTS = listOf("familylink", "kids", "knox", "kgclient", "inputmethod", "keyboard", "honeyboard", "securitylog", "emergency")

    fun isProtected(pkg: String) = PROTECTED.any { pkg == it || pkg.startsWith("$it.") } || PROTECTED_PARTS.any { pkg.contains(it, true) }

    /** Sprechbare Namen → Android-Berechtigungen. */
    val PERMS = mapOf(
        "mikrofon" to listOf("android.permission.RECORD_AUDIO"),
        "kamera" to listOf("android.permission.CAMERA"),
        "standort" to listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION"),
        "kontakte" to listOf("android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS"),
        "fotos" to listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_EXTERNAL_STORAGE"),
        "benachrichtigungen" to listOf("android.permission.POST_NOTIFICATIONS"),
        "kalender" to listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"),
        "sms" to listOf("android.permission.READ_SMS", "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS"),
        "telefon" to listOf("android.permission.CALL_PHONE", "android.permission.READ_PHONE_STATE", "android.permission.READ_CALL_LOG"),
        "nahe geräte" to listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN", "android.permission.NEARBY_WIFI_DEVICES"),
    )

    fun permKey(s: String): String? {
        val t = s.lowercase().trim()
        return when {
            "mikro" in t || "audio" in t -> "mikrofon"; "kamera" in t || "camera" in t -> "kamera"
            "standort" in t || "location" in t || "ort" == t -> "standort"; "kontakt" in t -> "kontakte"
            "foto" in t || "bild" in t || "galerie" in t || "medien" in t -> "fotos"; "benachrichtig" in t || "notification" in t -> "benachrichtigungen"
            "kalender" in t -> "kalender"; "sms" in t -> "sms"; "telefon" in t || "anruf" in t -> "telefon"
            "nahe" in t || "bluetooth" in t -> "nahe geräte"
            else -> PERMS.keys.firstOrNull { it == t }
        }
    }

    private fun scale(v: String): String? = when {
        Regex("schnell|flott|halb|0[.,]5").containsMatchIn(v) -> "0.5"
        Regex("\\baus\\b|keine|^0$").containsMatchIn(v) -> "0"
        Regex("normal|standard|1|zurück").containsMatchIn(v) -> "1"
        else -> null
    }

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
            "animations" -> {
                val s = scale(v.lowercase()) ?: when (on) { true -> "0.5"; false -> "1"; null -> return null to "Schnell, aus oder normal?" }
                Cmd(listOf("settings put global window_animation_scale $s", "settings put global transition_animation_scale $s", "settings put global animator_duration_scale $s"),
                    done = when (s) { "0" -> "Animationen aus – alles springt sofort."; "0.5" -> "Animationen doppelt so schnell – fühlt sich flotter an."; else -> "Animationen wieder normal." }) to null
            }
            "clear_cache" -> Cmd(listOf("pm trim-caches 999G"), done = "Cache aller Apps geleert – Speicher ist frei.") to null
            "kill_background" -> Cmd(listOf("am kill-all"), done = "Hintergrund-Apps beendet, Arbeitsspeicher ist frei.") to null
            "freeze", "unfreeze", "restrict_background" -> {
                if (!PKG.matches(v)) return null to "Welche App genau?"
                if (isProtected(v)) return null to "Diese App ist geschützt – die fasse ich nicht an."
                when (action) {
                    "freeze" -> Cmd(listOf("pm disable-user --user 0 $v"), confirm = "App $v einfrieren (verschwindet, bis du sie wieder auftaust – nichts wird gelöscht).", done = "App eingefroren. Wieder auftauen: „Jarvis, taue $v auf“ oder im Profi-Modus.") to null
                    "unfreeze" -> Cmd(listOf("pm enable --user 0 $v"), done = "App ist wieder da.") to null
                    else -> if (on == false) Cmd(listOf("am set-standby-bucket $v active", "cmd appops set $v RUN_ANY_IN_BACKGROUND allow"), done = "Die App darf wieder normal im Hintergrund laufen.") to null
                            else Cmd(listOf("am set-standby-bucket $v restricted", "cmd appops set $v RUN_ANY_IN_BACKGROUND ignore"), done = "Die App ist im Hintergrund gebremst und zieht weniger Akku. Benachrichtigungen können später kommen.") to null
                }
            }
            "permission" -> {
                val parts = v.split(Regex("\\s+"), limit = 2)
                val pkg = parts.getOrNull(0).orEmpty(); val key = permKey(parts.getOrNull(1).orEmpty()) ?: return null to "Welche Berechtigung? (Mikrofon, Kamera, Standort, Kontakte, Fotos …)"
                if (!PKG.matches(pkg)) return null to "Welche App genau?"
                if (isProtected(pkg)) return null to "Diese App ist geschützt – deren Rechte ändere ich nicht."
                val give = on ?: return null to "Erlauben oder wegnehmen?"
                val perms = PERMS[key]!!
                val cmds = perms.map { "pm ${if (give) "grant" else "revoke"} $pkg $it 2>/dev/null; true" }
                if (give) Cmd(cmds, confirm = "App $pkg die Berechtigung „$key“ geben.", done = "$pkg darf jetzt: $key.") to null
                else Cmd(cmds, done = "$pkg darf nicht mehr: $key. (Fragt die App wieder, kannst du „Ablehnen“ tippen.)") to null
            }
            "adblock" -> sw()?.let { if (it) Cmd(listOf("settings put global private_dns_specifier dns.adguard-dns.com", "settings put global private_dns_mode hostname"),
                    done = "Werbeblocker an: weniger Werbung und Tracker in Apps und im Browser (privates DNS von AdGuard). Lädt etwas nicht, sag „Werbeblocker aus“.") to null
                else Cmd(listOf("settings put global private_dns_mode opportunistic"), done = "Werbeblocker aus – privates DNS wieder automatisch.") to null } ?: needOn()
            "lock_screen" -> Cmd(listOf("input keyevent 223"), done = "Bildschirm gesperrt.") to null
            "font_size" -> {
                val t = v.lowercase()
                val s = t.replace(',', '.').toDoubleOrNull()?.coerceIn(0.85, 1.5)?.toString() ?: when {
                    "sehr" in t && ("groß" in t || "gross" in t) -> "1.3"; "größer" in t || "groß" in t || "gross" in t -> "1.15"
                    "klein" in t -> "0.9"; "normal" in t || "standard" in t || "zurück" in t -> "1.0"; else -> return null to "Größer, kleiner oder normal?" }
                Cmd(listOf("settings put system font_scale $s"), done = "Schriftgröße angepasst.") to null
            }
            "display_size" -> {
                val t = v.lowercase()
                val c = when { "größer" in t || "groß" in t || "gross" in t -> "d=$(wm density | grep -o '[0-9]*' | head -1); wm density $((d*90/100))"
                    "klein" in t -> "d=$(wm density | grep -o '[0-9]*' | head -1); wm density $((d*110/100))"
                    "normal" in t || "standard" in t || "zurück" in t -> "wm density reset"; else -> return null to "Größer, kleiner oder normal?" }
                Cmd(listOf(c), done = "Anzeigegröße geändert. Zurück: „Anzeigegröße normal“.") to null
            }
            "auto_rotate" -> sw()?.let { Cmd(listOf("settings put system accelerometer_rotation ${if (it) 1 else 0}"), done = "Automatisch drehen ${onOff(it)}.") to null } ?: needOn()
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
            "werbeblocker" in t || "werbung blocker" in t -> "adblock"
            "automatisch drehen" in t || "bildschirm drehen" in t || "auto rotation" in t || "autorotation" in t -> "auto_rotate"
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
