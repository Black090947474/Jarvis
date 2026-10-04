package de.jarvis.app

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import de.jarvis.app.tools.Res
import de.jarvis.app.tools.ToolContext
import de.jarvis.app.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Alles, was Jarvis auf dem Handy wirklich tun kann.
 * Claude entscheidet, welches Werkzeug gebraucht wird; hier wird es ausgeführt.
 * Jede Funktion meldet ehrlich zurück, ob es geklappt hat.
 */
class PhoneTools(private val activity: Activity) {

    private val ctx: Context get() = activity
    val appContext: Context get() = activity
    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(activity)

    /** Ergebnis-Text eines Werkzeugs gilt als Fehler (für is_error an die KI). */
    fun isError(result: String) = Res.isError(result)

    /** Nur die Werkzeuge schicken, die zur Anfrage passen (spart Tokens beim kostenlosen Limit). */
    private val recentGroups = ArrayDeque<Set<String>>()

    /** Aktuelle Anfrage merken: Gruppen der letzten Anfragen bleiben aktiv (damit „Ja“ nach einer Rückfrage funktioniert). */
    @Synchronized fun noteQuery(query: String) {
        recentGroups.addLast(ToolRegistry.groupsFor(query))
        while (recentGroups.size > 3) recentGroups.removeFirst()
    }

    @Synchronized fun definitionsFor(query: String): JSONArray {
        if (recentGroups.isEmpty()) noteQuery(query)
        val groups = recentGroups.flatten().toSet()
        val out = JSONArray()
        for (i in 0 until DEFINITIONS.length()) {
            val d = DEFINITIONS.getJSONObject(i)
            val n = d.optString("name")
            if (n == "add_calendar_event") continue // ersetzt durch calendar (mit Bestätigung)
            if ((GROUP_OF[n] ?: "core") in groups) out.put(d)
        }
        for (t in ToolRegistry.all) if (t.group in groups && (t.feature == null || prefs.feature(t.feature!!))) out.put(t.definition)
        return out
    }

    /** Wird true, sobald eine andere App geöffnet wurde – dann beendet sich Jarvis nach der Antwort. */
    @Volatile var leftApp = false

    /** Zeigt eine Ergebnis-Karte auf dem Jarvis-Bildschirm (wird von JarvisActivity gesetzt). */
    var onCard: ((JSONObject) -> String)? = null

    /** Bei Anrufen soll Jarvis nicht in das Telefonat hineinreden. */
    @Volatile var silentExit = false

    /** Kurzer deutscher Name für die Anzeige, während ein Werkzeug läuft. */
    fun label(name: String): String = LABELS[name] ?: "Arbeite …"

    /** Führt ein Werkzeug auf dem Hauptthread aus (aus einem Hintergrund-Thread aufrufen). */
    fun execute(name: String, input: JSONObject): String {
        // Funktionsschalter aus dem Berechtigungszentrum respektieren
        FEATURE_OF[name]?.let { f -> if (!prefs.feature(f)) return Res.disabled(FEATURE_NAMES[f] ?: f) }
        // Neue, modulare Werkzeuge (Kalender, Erinnerungen, Aufgaben, Wetter …) laufen im Hintergrund-Thread
        ToolRegistry.find(name)?.let { tool ->
            tool.feature?.let { f -> if (!prefs.feature(f)) return Res.disabled(FEATURE_NAMES[f] ?: f) }
            return safe { tool.run(ToolContext(activity) { n, a -> execute(n, a) }, input) }
        }
        // Wichtige Aktionen nur nach Bestätigung
        if (name == "notifications" && input.optString("action") == "reply" && !input.optBoolean("confirmed")) {
            val m = JarvisNotificationListener.find(input.optInt("id", -1))
            return Res.confirm("Antwort an ${m?.title ?: "den Absender"} (${m?.app ?: "App"}) senden: „${input.optString("text")}“.")
        }
        if (name == "toggle" && input.optString("what") in setOf("airplane", "mobile_data", "hotspot") && !input.optBoolean("confirmed"))
            return Res.confirm("${mapOf("airplane" to "Flugmodus", "mobile_data" to "Mobile Daten", "hotspot" to "Hotspot")[input.optString("what")]} umschalten.")
        // Bildschirmsteuerung wartet auf Apps – darf nicht auf dem Hauptthread laufen
        // Was wartet (Bildschirm, Schnelleinstellungen, Ortsbestimmung), darf nicht auf dem Hauptthread laufen
        when (name) {
            "screen" -> return safe { screen(input) }
            "toggle" -> return safe { toggle(input.optString("what")) }
            "location" -> return safe { location() }
            "second_phone" -> return safe { secondPhone(input) }
        }
        var result = "Fehler: Zeitüberschreitung"
        val latch = CountDownLatch(1)
        main.post {
            result = try { run(name, input) } catch (e: SecurityException) {
                "Fehler: Berechtigung fehlt (${e.message}). Der Nutzer kann sie in der Jarvis-App erteilen."
            } catch (e: Exception) {
                "Fehler: ${e.javaClass.simpleName}: ${e.message}"
            }
            latch.countDown()
        }
        latch.await(15, TimeUnit.SECONDS)
        return result
    }

    private fun safe(block: () -> String): String = try { block() } catch (e: SecurityException) {
        "Fehler: Berechtigung fehlt (${e.message}). Der Nutzer kann sie in der Jarvis-App erteilen."
    } catch (e: Exception) { "Fehler: ${e.javaClass.simpleName}: ${e.message}" }

    private fun run(name: String, a: JSONObject): String = when (name) {
        "set_alarm" -> setAlarm(a)
        "set_timer" -> setTimer(a)
        "show_alarms" -> launch(Intent(AlarmClock.ACTION_SHOW_ALARMS), "Wecker-App geöffnet.")
        "flashlight" -> flashlight(a.optBoolean("on", true))
        "set_volume" -> setVolume(a)
        "media_control" -> mediaControl(a.optString("action"))
        "play_music" -> playMusic(a.optString("query"), a.optString("app"))
        "open_app" -> openApp(a.optString("name"))
        "call" -> call(a.optString("who"))
        "send_message" -> sendMessage(a.optString("who"), a.optString("text"), a.optString("app", "sms"))
        "navigate" -> navigate(a.optString("destination"), a.optString("mode", "d"))
        "open_url" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse(fixUrl(a.optString("url")))), "Seite geöffnet.")
        "add_calendar_event" -> addEvent(a)
        "battery" -> battery()
        "open_settings" -> openSettings(a.optString("page"))
        "remember" -> remember(a.optString("fact"))
        "forget" -> forget(a.optString("fact"))
        "app_search" -> appSearch(a.optString("app"), a.optString("query"))
        "camera" -> camera(a.optString("mode", "photo"))
        "notifications" -> notifications(a)
        "location" -> location()
        "latest_photo" -> latestPhoto(a.optString("who"))
        "count_photos" -> countPhotos()
        "toggle" -> toggle(a.optString("what"))
        "show_card" -> onCard?.invoke(a) ?: "Fehler: Karten gehen hier nicht."
        "build_website" -> buildWebsite(a.optString("title"), a.optString("html"))
        else -> "Fehler: Unbekanntes Werkzeug $name"
    }

    // ---------- Wecker & Timer ----------

    private fun setAlarm(a: JSONObject): String {
        val hour = a.optInt("hour", -1)
        val minute = a.optInt("minute", 0)
        if (hour !in 0..23 || minute !in 0..59) return "Fehler: Ungültige Uhrzeit."
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        a.optString("label").takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val days = a.optJSONArray("days")
        if (days != null && days.length() > 0) {
            val list = ArrayList<Int>()
            for (k in 0 until days.length()) dayToCalendar(days.optString(k))?.let { list += it }
            if (list.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_DAYS, list)
        }
        return startHeadless(i, "Wecker auf %02d:%02d gestellt.".format(hour, minute))
    }

    private fun setTimer(a: JSONObject): String {
        val sec = a.optInt("seconds", 0)
        if (sec !in 1..86_400) return "Fehler: Dauer muss zwischen 1 Sekunde und 24 Stunden liegen."
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, sec)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        a.optString("label").takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return startHeadless(i, "Timer über ${formatDuration(sec)} gestartet.")
    }

    private fun dayToCalendar(d: String): Int? = when (d.lowercase(Locale.ROOT).take(2)) {
        "mo" -> Calendar.MONDAY; "di", "tu" -> Calendar.TUESDAY; "mi", "we" -> Calendar.WEDNESDAY
        "do", "th" -> Calendar.THURSDAY; "fr" -> Calendar.FRIDAY; "sa" -> Calendar.SATURDAY
        "so", "su" -> Calendar.SUNDAY; else -> null
    }

    private fun formatDuration(s: Int): String {
        val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
        return listOfNotNull(
            if (h > 0) "$h Std." else null, if (m > 0) "$m Min." else null, if (r > 0) "$r Sek." else null
        ).joinToString(" ")
    }

    // ---------- Taschenlampe, Lautstärke, Medien ----------

    private fun flashlight(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Fehler: Dieses Handy hat keine Taschenlampe."
        cm.setTorchMode(id, on)
        return if (on) "Taschenlampe an." else "Taschenlampe aus."
    }

    private fun setVolume(a: JSONObject): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val (stream, label) = when (a.optString("stream", "media")) {
            "ring" -> AudioManager.STREAM_RING to "Klingelton"
            "alarm" -> AudioManager.STREAM_ALARM to "Wecker"
            "notification" -> AudioManager.STREAM_NOTIFICATION to "Benachrichtigungen"
            else -> AudioManager.STREAM_MUSIC to "Medien"
        }
        val max = am.getStreamMaxVolume(stream)
        val pct = a.optInt("percent", 50).coerceIn(0, 100)
        am.setStreamVolume(stream, Math.round(max * pct / 100f), AudioManager.FLAG_SHOW_UI)
        return "$label-Lautstärke auf $pct % gestellt."
    }

    private fun mediaControl(action: String): String {
        val code = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        return "Medienbefehl gesendet ($action). Ob es gewirkt hat, hängt von der aktiven Musik-App ab."
    }

    private fun playMusic(query: String, app: String): String {
        if (app.equals("spotify", true) || (app.isBlank() && isInstalled("com.spotify.music"))) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query)))
                .setPackage("com.spotify.music")
            if (isInstalled("com.spotify.music")) {
                val r = launch(i, "Spotify sucht nach „$query“. Zum Abspielen eventuell noch antippen.")
                if (!r.startsWith("Fehler")) return r
            }
        }
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(android.app.SearchManager.QUERY, query)
        val r = launch(i, "Musik-App sucht nach „$query“.")
        if (!r.startsWith("Fehler")) return r
        return launch(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))),
            "YouTube-Suche nach „$query“ geöffnet.")
    }

    // ---------- Apps ----------

    private fun openApp(name: String): String {
        if (name.isBlank()) return "Fehler: Kein App-Name."
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val q = name.lowercase(Locale.GERMANY).trim()
        val scored = apps.map { it to it.loadLabel(pm).toString() }.mapNotNull { (ri, label) ->
            val l = label.lowercase(Locale.GERMANY)
            val score = when {
                l == q -> 3
                l.startsWith(q) -> 2
                l.contains(q) || q.contains(l) -> 1
                else -> 0
            }
            if (score > 0) Triple(ri, label, score) else null
        }.sortedByDescending { it.third }
        val best = scored.firstOrNull()
            ?: return "Fehler: Keine App namens „$name“ gefunden."
        val i = pm.getLaunchIntentForPackage(best.first.activityInfo.packageName)
            ?: return "Fehler: ${best.second} lässt sich nicht öffnen."
        return launch(i, "${best.second} geöffnet.")
    }

    private fun isInstalled(pkg: String) = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    // ---------- Anrufe & Nachrichten ----------

    private fun call(who: String): String {
        val number = resolveNumber(who) ?: return contactError(who)
        if (number.startsWith("Fehler") || number.startsWith("MEHRERE")) return number
        val canCall = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        silentExit = true
        return if (canCall) launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")), "Rufe $who an ($number).")
        else launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")),
            "Wähltastatur mit $number geöffnet. Der Nutzer muss noch auf Anrufen tippen (Anruf-Berechtigung fehlt).")
    }

    private fun sendMessage(who: String, text: String, app: String): String {
        val number = resolveNumber(who) ?: return contactError(who)
        if (number.startsWith("Fehler") || number.startsWith("MEHRERE")) return number
        if (app.equals("whatsapp", true)) {
            val intl = toInternational(number)
            val i = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://wa.me/$intl?text=" + Uri.encode(text)))
            return launch(i, "WhatsApp-Chat mit $who ist geöffnet und die Nachricht eingetragen. " +
                "Der Nutzer muss noch selbst auf Senden tippen.")
        }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", text)
        return launch(i, "SMS an $who ist vorbereitet. Der Nutzer muss noch selbst auf Senden tippen.")
    }

    /** Telefonnummer direkt oder aus den Kontakten. Gibt bei mehreren Treffern eine Liste zurück. */
    private fun resolveNumber(who: String): String? {
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 5) return digits
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return "Fehler: Jarvis darf die Kontakte nicht lesen. Der Nutzer kann das in der Jarvis-App erlauben."
        val found = LinkedHashMap<String, String>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%${who.trim()}%"), null
        )?.use { c ->
            while (c.moveToNext() && found.size < 8) {
                val n = c.getString(0) ?: continue
                if (!found.containsKey(n)) found[n] = c.getString(1) ?: continue
            }
        }
        if (found.isEmpty()) return null
        val exact = found.entries.firstOrNull { it.key.equals(who.trim(), true) }
        if (exact != null || found.size == 1) return (exact ?: found.entries.first()).value.filter { it.isDigit() || it == '+' }
        return "MEHRERE Kontakte passen: ${found.keys.joinToString(", ")}. Frag den Nutzer, wen er meint."
    }

    private fun contactError(who: String) = "Fehler: Kein Kontakt namens „$who“ gefunden."

    private fun toInternational(n: String): String {
        val d = n.filter { it.isDigit() || it == '+' }
        return when {
            d.startsWith("+") -> d.drop(1)
            d.startsWith("00") -> d.drop(2)
            d.startsWith("0") -> "49" + d.drop(1) // deutsche Nummer
            else -> d
        }
    }

    // ---------- Navigation, Web, Kalender ----------

    private fun navigate(dest: String, mode: String): String {
        val m = when (mode) { "w", "walk", "walking" -> "w"; "b", "bike", "bicycling" -> "b"; "transit", "r" -> "r"; else -> "d" }
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest) + "&mode=$m"))
        val r = launch(i, "Navigation nach $dest gestartet.")
        if (!r.startsWith("Fehler")) return r
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))), "Karte mit $dest geöffnet.")
    }

    private fun fixUrl(u: String) = if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"

    private fun addEvent(a: JSONObject): String {
        val title = a.optString("title").ifBlank { return "Fehler: Kein Titel." }
        val start = parseDate(a.optString("start")) ?: return "Fehler: Startzeit nicht verstanden (Format JJJJ-MM-TTTHH:MM)."
        val end = parseDate(a.optString("end")) ?: (start + 60 * 60 * 1000)
        val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        a.optString("location").takeIf { it.isNotBlank() }?.let { i.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        return launch(i, "Kalender-Termin „$title“ ist ausgefüllt. Der Nutzer muss noch auf Speichern tippen.")
    }

    private fun parseDate(s: String): Long? {
        if (s.isBlank()) return null
        for (p in listOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")) {
            try { return SimpleDateFormat(p, Locale.GERMANY).parse(s.take(p.replace("'", "").length))?.time } catch (_: Exception) {}
        }
        return null
    }

    // ---------- Akku & Einstellungen ----------

    private fun battery(): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return "Akku: $pct %${if (charging) ", lädt gerade" else ""}."
    }

    private fun openSettings(page: String): String {
        val (action, label) = when (page) {
            "wifi" -> (if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS) to "WLAN"
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth"
            "mobile_data" -> (if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS) to "Mobile Daten"
            "display" -> Settings.ACTION_DISPLAY_SETTINGS to "Display"
            "sound" -> Settings.ACTION_SOUND_SETTINGS to "Ton"
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY to "Akku"
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "Standort"
            "dnd" -> Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS to "Nicht stören"
            "hotspot" -> Settings.ACTION_WIRELESS_SETTINGS to "Verbindungen"
            else -> Settings.ACTION_SETTINGS to "Einstellungen"
        }
        return launch(Intent(action), "$label-Einstellungen geöffnet. Android erlaubt Apps nicht, das selbst umzuschalten – der Nutzer muss tippen.")
    }

    // ---------- Gedächtnis ----------

    private fun remember(fact: String): String {
        if (fact.isBlank()) return "Fehler: Nichts zum Merken."
        Memory(ctx).add(fact)
        return "Gemerkt: $fact"
    }

    private fun forget(fact: String): String {
        val removed = Memory(ctx).removeMatching(fact)
        return if (removed > 0) "$removed Eintrag/Einträge vergessen." else "Dazu war nichts gespeichert."
    }

    // ---------- In Apps suchen ----------

    private fun appSearch(app: String, q: String): String {
        if (q.isBlank()) return "Fehler: Kein Suchbegriff."
        val e = Uri.encode(q)
        fun web(url: String, msg: String) = launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)), msg)
        fun tryFirst(i: Intent, url: String, msg: String): String {
            val r = launch(i, msg)
            return if (r.startsWith("Fehler")) web(url, msg) else r
        }
        return when (app.lowercase()) {
            "youtube" -> tryFirst(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q),
                "https://www.youtube.com/results?search_query=$e", "YouTube sucht nach „$q“.")
            "google" -> tryFirst(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q),
                "https://www.google.com/search?q=$e", "Google sucht nach „$q“.")
            "maps" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$e")), "Google Maps zeigt „$q“.")
            "playstore" -> tryFirst(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$e")),
                "https://play.google.com/store/search?q=$e", "Play Store sucht nach „$q“.")
            "tiktok" -> web("https://www.tiktok.com/search?q=$e", "TikTok sucht nach „$q“.")
            "instagram" -> if (!q.trim().contains(' '))
                web("https://www.instagram.com/_u/${Uri.encode(q.trim().removePrefix("@"))}", "Instagram-Profil $q geöffnet.")
                else web("https://www.instagram.com/explore/tags/${Uri.encode(q.replace(" ", ""))}", "Instagram zeigt #$q.")
            "netflix" -> web("https://www.netflix.com/search?q=$e", "Netflix sucht nach „$q“.")
            "amazon" -> web("https://www.amazon.de/s?k=$e", "Amazon sucht nach „$q“.")
            "ebay" -> web("https://www.ebay.de/sch/i.html?_nkw=$e", "eBay sucht nach „$q“.")
            "x", "twitter" -> web("https://x.com/search?q=$e", "X sucht nach „$q“.")
            "reddit" -> web("https://www.reddit.com/search/?q=$e", "Reddit sucht nach „$q“.")
            "wikipedia" -> web("https://de.wikipedia.org/w/index.php?search=$e", "Wikipedia sucht nach „$q“.")
            "spotify" -> playMusic(q, "spotify")
            else -> web("https://www.google.com/search?q=$e", "Google sucht nach „$q“.")
        }
    }

    // ---------- Kamera ----------

    private fun camera(mode: String): String {
        val i = when (mode) {
            "video" -> Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)
            else -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        }
        if (mode == "selfie") {
            i.putExtra("android.intent.extras.CAMERA_FACING", 1)
                .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
        val what = when (mode) { "video" -> "Videokamera"; "selfie" -> "Selfie-Kamera"; else -> "Kamera" }
        return launch(i, "$what ist offen. Auslösen musst du selbst (oder Jarvis tippt per Bildschirmsteuerung).")
    }

    // ---------- Benachrichtigungen ----------

    private fun notifications(a: JSONObject): String {
        if (!JarvisNotificationListener.isConnected)
            return "Fehler: Jarvis hat keinen Zugriff auf Benachrichtigungen. Der Nutzer kann ihn in der Jarvis-App erlauben."
        return when (a.optString("action")) {
            "reply" -> {
                val m = JarvisNotificationListener.find(a.optInt("id", -1))
                    ?: return "Fehler: Nachricht mit dieser id nicht gefunden. Erst mit read nachsehen."
                val text = a.optString("text").ifBlank { return "Fehler: Kein Antworttext." }
                JarvisNotificationListener.reply(ctx, m, text)
            }
            else -> {
                val list = JarvisNotificationListener.recent(a.optString("app").ifBlank { null })
                if (list.isEmpty()) return "Keine neuen Benachrichtigungen."
                val now = System.currentTimeMillis()
                list.joinToString("\n") { m ->
                    val min = ((now - m.time) / 60000).coerceAtLeast(0)
                    val ago = if (min < 1) "gerade eben" else if (min < 60) "vor $min Min." else "vor ${min / 60} Std."
                    "id ${m.id} | ${m.app} | ${m.title}: ${m.text} | $ago" + if (m.reply != null) " | Antworten möglich" else ""
                }
            }
        }
    }

    // ---------- Standort ----------

    @Suppress("MissingPermission")
    private fun location(): String {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return "Fehler: Jarvis darf den Standort nicht nutzen. Der Nutzer kann das in der Jarvis-App erlauben."
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val loc = try {
            listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
                .mapNotNull { if (lm.isProviderEnabled(it)) lm.getLastKnownLocation(it) else null }
                .maxByOrNull { it.time }
        } catch (_: Exception) { null } ?: return "Fehler: Kein Standort verfügbar. Ist der Standort eingeschaltet?"
        val place = try {
            @Suppress("DEPRECATION")
            android.location.Geocoder(ctx, Locale.GERMANY).getFromLocation(loc.latitude, loc.longitude, 1)
                ?.firstOrNull()?.let { listOfNotNull(it.thoroughfare, it.locality ?: it.subAdminArea).joinToString(", ") }
        } catch (_: Exception) { null }
        return "Standort: ${place?.takeIf { it.isNotBlank() } ?: "%.4f, %.4f".format(loc.latitude, loc.longitude)} " +
            "(Koordinaten %.5f, %.5f)".format(loc.latitude, loc.longitude)
    }

    // ---------- Fotos ----------

    private fun latestPhoto(who: String): String {
        val uri = newestImage() ?: return "Fehler: Kein Foto gefunden oder keine Foto-Berechtigung."
        if (who.isBlank()) {
            return launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Dein letztes Foto ist geöffnet.")
        }
        val i = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return launch(Intent.createChooser(i, "Foto senden an $who"),
            "Teilen-Fenster für dein letztes Foto ist offen. Wähl $who und tippe auf Senden.")
    }

    private fun countPhotos(): String {
        if (!canReadImages()) return "Fehler: Jarvis darf die Fotos nicht sehen. Der Nutzer kann das in der Jarvis-App erlauben."
        return try {
            ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use {
                "Du hast ${it.count} Fotos auf dem Handy."
            } ?: "Fehler: Fotos nicht lesbar."
        } catch (e: Exception) { "Fehler: ${e.message}" }
    }

    private fun canReadImages(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                   else Manifest.permission.READ_EXTERNAL_STORAGE
        return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun newestImage(): Uri? {
        if (!canReadImages()) return null
        return try {
            ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID), null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                if (c.moveToFirst()) android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null
            }
        } catch (_: Exception) { null }
    }

    // ---------- Zweites Handy (Station) ----------

    private fun secondPhone(a: JSONObject): String {
        val p = Prefs(ctx)
        if (p.remoteCode.isBlank() || p.remoteHost.isBlank())
            return "Fehler: Es ist kein zweites Handy gekoppelt. Das geht in der Jarvis-App unter „Zweites Handy“."
        val tool = a.optString("tool").ifBlank { return "Fehler: Kein Werkzeug angegeben." }
        val args = a.optString("args").ifBlank { "{}" }
        val res = try {
            StationLink.send(p.remoteHost, p.remotePort, p.remoteCode, tool, args)
        } catch (_: java.io.IOException) {
            // IP hat sich evtl. geändert: Station im WLAN neu suchen und nochmal versuchen
            val f = StationLink.find(ctx, 4000)
                ?: return "Fehler: Das zweite Handy ist nicht erreichbar. Ist dort die Station geöffnet und sind beide im selben WLAN?"
            p.remoteHost = f.host; p.remotePort = f.port
            try { StationLink.send(f.host, f.port, p.remoteCode, tool, args) }
            catch (e: Exception) { return "Fehler: Das zweite Handy antwortet nicht (${e.message})." }
        }
        return if (res.first) "Zweites Handy: ${res.second}" else "Fehler (zweites Handy): ${res.second}"
    }

    // ---------- Website bauen ----------

    /** Speichert eine von der KI gebaute Website im Download-Ordner (Jarvis/) und öffnet sie im Browser. */
    private fun buildWebsite(title: String, rawHtml: String): String {
        if (Build.VERSION.SDK_INT < 29) return "Fehler: Websites bauen geht erst ab Android 10."
        var html = rawHtml.trim().removePrefix("```html").removePrefix("```").removeSuffix("```").trim()
        if (html.length < 40) return "Fehler: Die Website war leer. Bitte nochmal mit vollständigem HTML."
        if (!html.contains("<html", ignoreCase = true)) {
            html = "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>" +
                android.text.TextUtils.htmlEncode(title) + "</title></head><body>" + html + "</body></html>"
        }
        val slug = title.lowercase(Locale.GERMANY).replace(Regex("[^a-z0-9äöüß]+"), "-").trim('-').take(40).ifBlank { "website" }
        val name = "$slug-${SimpleDateFormat("HHmmss", Locale.GERMANY).format(java.util.Date())}.html"
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/html")
            put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Jarvis")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return "Fehler: Konnte die Datei nicht speichern."
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(html.toByteArray(Charsets.UTF_8)) }
            ?: return "Fehler: Konnte die Datei nicht schreiben."
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/html")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (isInstalled("com.android.chrome")) view.setPackage("com.android.chrome")
        val r = launch(view, "Website „$title“ gebaut, unter Downloads/Jarvis/$name gespeichert und geöffnet.")
        if (!r.startsWith("Fehler")) return r
        view.setPackage(null)
        val r2 = launch(view, "Website „$title“ gebaut und geöffnet.")
        return if (r2.startsWith("Fehler")) "Website gespeichert unter Downloads/Jarvis/$name, aber kein Browser konnte sie öffnen." else r2
    }

    // ---------- WLAN/Bluetooth/… schalten (über die Schnelleinstellungen) ----------

    private fun toggle(what: String): String {
        val acc = JarvisAccessibility.instance
            ?: return "Fehler: Dafür braucht Jarvis die volle Handy-Steuerung (Bedienungshilfe). Sonst kann ich dir nur die Einstellungen öffnen."
        val tile = when (what) {
            "wifi" -> "WLAN"; "bluetooth" -> "Bluetooth"; "mobile_data" -> "Mobile Daten"
            "flashlight" -> return flashlight(true)
            "airplane" -> "Flugmodus"; "dnd" -> "Nicht stören"; "rotation" -> "Autom. drehen"
            "location" -> "Standort"; "hotspot" -> "Hotspot"; "torch" -> return flashlight(true)
            else -> what
        }
        if (!leftApp) { onMain { activity.moveTaskToBack(true) }; leftApp = true; Thread.sleep(500) }
        return acc.toggleTile(tile)
    }

    // ---------- Bildschirmsteuerung ----------

    private fun screen(a: JSONObject): String {
        val acc = JarvisAccessibility.instance
            ?: return "Fehler: Bildschirmsteuerung ist aus. Der Nutzer kann sie in der Jarvis-App einschalten."
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) return "Fehler: Das Handy ist gesperrt. Der Nutzer muss es erst entsperren."
        // Jarvis' eigenen Bildschirm in den Hintergrund schieben, damit die App sichtbar ist
        if (!leftApp) {
            onMain { activity.moveTaskToBack(true) }
            leftApp = true
            Thread.sleep(700)
        }
        val index = if (a.has("index") && !a.isNull("index")) a.optInt("index") else null
        val text = a.optString("text").takeIf { it.isNotBlank() }
        return when (val action = a.optString("action")) {
            "read" -> acc.read()
            "tap" -> acc.tap(index, text)
            "type" -> acc.type(text ?: return "Fehler: Kein Text angegeben.", index)
            "enter" -> acc.enter()
            "scroll_down" -> acc.scroll(true)
            "scroll_up" -> acc.scroll(false)
            else -> acc.global(action)
        }
    }

    private fun onMain(block: () -> Unit) {
        val latch = CountDownLatch(1)
        main.post { try { block() } finally { latch.countDown() } }
        latch.await(3, TimeUnit.SECONDS)
    }

    // ---------- Starten von Apps ----------

    /** Startet etwas, das keine Oberfläche zeigt (Wecker/Timer im Hintergrund stellen). */
    private fun startHeadless(i: Intent, ok: String): String = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(i); ok
    } catch (_: ActivityNotFoundException) {
        "Fehler: Keine Uhr-App gefunden, die das kann."
    }

    /** Öffnet eine andere App. Bei gesperrtem Handy wird zuerst um Entsperren gebeten. */
    private fun launch(i: Intent, ok: String): String {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return try {
            if (km.isKeyguardLocked) {
                km.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() { try { activity.startActivity(i) } catch (_: Exception) {} }
                })
                leftApp = true
                "$ok (Das Handy war gesperrt, der Nutzer muss es kurz entsperren.)"
            } else {
                activity.startActivity(i)
                leftApp = true
                ok
            }
        } catch (_: ActivityNotFoundException) {
            "Fehler: Keine passende App installiert."
        }
    }

    companion object {
        /** Gruppe je altem Werkzeug (core wird immer mitgeschickt). */
        private val GROUP_OF = mapOf(
            "set_alarm" to "core", "set_timer" to "core", "flashlight" to "core", "open_app" to "core",
            "show_card" to "core", "battery" to "core", "remember" to "core", "set_volume" to "core", "media_control" to "core",
            "play_music" to "core", "app_search" to "core",
            "show_alarms" to "agenda",
            "call" to "comm", "send_message" to "comm", "notifications" to "comm",
            "navigate" to "device", "open_settings" to "device", "camera" to "device", "screen" to "device",
            "location" to "device", "latest_photo" to "device", "count_photos" to "device", "toggle" to "device",
            "open_url" to "info", "forget" to "info", "build_website" to "info", "second_phone" to "info",
        )

        /** Welcher Funktionsschalter (Berechtigungszentrum) ein altes Werkzeug sperrt. */
        private val FEATURE_OF = mapOf(
            "call" to "calls", "send_message" to "messages", "notifications" to "notifications_read",
            "screen" to "screen", "toggle" to "screen", "location" to "location",
            "latest_photo" to "photos", "count_photos" to "photos", "camera" to "camera",
        )

        val FEATURE_NAMES = mapOf(
            "calendar" to "den Kalender", "reminders" to "Erinnerungen", "tasks" to "Aufgaben", "calls" to "Telefon und Anrufliste",
            "messages" to "Nachrichten", "notifications_read" to "Benachrichtigungen", "screen" to "die Bildschirmsteuerung",
            "location" to "den Standort", "photos" to "Fotos", "camera" to "die Kamera", "settings" to "Systemeinstellungen",
        )

        private val LABELS = mapOf(
            "calendar" to "Prüfe Kalender …", "agenda" to "Stelle deinen Tag zusammen …", "reminders" to "Erinnerung …",
            "tasks" to "Aufgaben …", "call_log" to "Lese Anrufliste …", "email" to "Prüfe E-Mails …",
            "weather" to "Hole Wetter …", "nearby" to "Suche in der Nähe …", "brightness" to "Helligkeit …",
            "web" to "Suche im Internet …", "briefing" to "Stelle deinen Tag zusammen …", "school" to "Schaue in den Stundenplan …",
            "shopping" to "Einkaufsliste …", "notes" to "Notizen …", "learn" to "Lern-Modus …", "routines" to "Führe Kommando aus …",
            "birthdays" to "Prüfe Geburtstage …", "places" to "Orte …", "recognize_song" to "Höre mit …",
            "set_alarm" to "Stelle Wecker …", "set_timer" to "Starte Timer …", "show_alarms" to "Öffne Wecker …",
            "flashlight" to "Taschenlampe …", "set_volume" to "Lautstärke …", "media_control" to "Musik …",
            "play_music" to "Suche Musik …", "open_app" to "Öffne App …", "call" to "Rufe an …",
            "send_message" to "Schreibe Nachricht …", "navigate" to "Starte Navigation …",
            "open_url" to "Öffne Seite …", "add_calendar_event" to "Lege Termin an …", "battery" to "Prüfe Akku …",
            "open_settings" to "Öffne Einstellungen …", "remember" to "Merke mir das …", "forget" to "Vergesse das …",
            "web_search" to "Suche im Internet …",
            "app_search" to "Suche in der App …", "camera" to "Öffne Kamera …",
            "notifications" to "Prüfe Nachrichten …", "screen" to "Bediene das Handy …",
            "location" to "Bestimme Standort …", "latest_photo" to "Hole dein Foto …",
            "count_photos" to "Zähle Fotos …", "toggle" to "Schalte um …",
            "show_card" to "Erstelle Übersicht …", "build_website" to "Baue Website …",
            "second_phone" to "Sende an zweites Handy …"
        )

        /** Beschreibung aller Werkzeuge für Claude (JSON-Schema). */
        val DEFINITIONS: JSONArray by lazy { JSONArray(TOOLS_JSON) }

        private const val TOOLS_JSON = """
[
 {"name":"set_alarm","description":"Stellt einen Wecker in der Uhr-App des Handys, ohne sie zu öffnen. 24-Stunden-Format.",
  "input_schema":{"type":"object","properties":{
    "hour":{"type":"integer","description":"0-23"},"minute":{"type":"integer","description":"0-59"},
    "label":{"type":"string","description":"Optionaler Name des Weckers"},
    "days":{"type":"array","items":{"type":"string","enum":["mo","di","mi","do","fr","sa","so"]},"description":"Nur für wiederkehrende Wecker"}},
   "required":["hour","minute"]}},
 {"name":"set_timer","description":"Startet einen Countdown-Timer in der Uhr-App.",
  "input_schema":{"type":"object","properties":{"seconds":{"type":"integer"},"label":{"type":"string"}},"required":["seconds"]}},
 {"name":"show_alarms","description":"Öffnet die Wecker-Übersicht der Uhr-App (zum Ansehen, Ändern oder Löschen von Weckern).",
  "input_schema":{"type":"object","properties":{}}},
 {"name":"flashlight","description":"Schaltet die Taschenlampe an oder aus.",
  "input_schema":{"type":"object","properties":{"on":{"type":"boolean"}},"required":["on"]}},
 {"name":"set_volume","description":"Stellt die Lautstärke in Prozent ein.",
  "input_schema":{"type":"object","properties":{"percent":{"type":"integer","description":"0-100"},
   "stream":{"type":"string","enum":["media","ring","alarm","notification"]}},"required":["percent"]}},
 {"name":"media_control","description":"Steuert die gerade laufende Musik (Spotify, YouTube Music usw.).",
  "input_schema":{"type":"object","properties":{"action":{"type":"string","enum":["play","pause","play_pause","next","previous"]}},"required":["action"]}},
 {"name":"play_music","description":"Sucht ein Lied, einen Künstler oder eine Playlist und öffnet es in Spotify (falls installiert), sonst in einer Musik-App oder YouTube.",
  "input_schema":{"type":"object","properties":{"query":{"type":"string"},"app":{"type":"string","enum":["spotify","any"]}},"required":["query"]}},
 {"name":"open_app","description":"Öffnet eine installierte App über ihren Namen, z. B. WhatsApp, Instagram, TikTok, Kamera.",
  "input_schema":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}},
 {"name":"call","description":"Ruft einen Kontakt (Name aus dem Adressbuch) oder eine Telefonnummer an.",
  "input_schema":{"type":"object","properties":{"who":{"type":"string","description":"Kontaktname oder Nummer"}},"required":["who"]}},
 {"name":"send_message","description":"Bereitet eine SMS oder WhatsApp-Nachricht vor. Der Nutzer muss am Ende selbst auf Senden tippen.",
  "input_schema":{"type":"object","properties":{"who":{"type":"string"},"text":{"type":"string"},
   "app":{"type":"string","enum":["sms","whatsapp"]}},"required":["who","text"]}},
 {"name":"navigate","description":"Startet die Navigation in Google Maps zu einem Ziel.",
  "input_schema":{"type":"object","properties":{"destination":{"type":"string"},
   "mode":{"type":"string","enum":["drive","walk","bike","transit"]}},"required":["destination"]}},
 {"name":"open_url","description":"Öffnet eine Webseite im Browser.",
  "input_schema":{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}},
 {"name":"add_calendar_event","description":"Öffnet einen ausgefüllten neuen Kalendertermin. Der Nutzer tippt dann auf Speichern.",
  "input_schema":{"type":"object","properties":{"title":{"type":"string"},
   "start":{"type":"string","description":"Lokale Zeit, Format YYYY-MM-DDTHH:MM"},
   "end":{"type":"string","description":"Optional, Format YYYY-MM-DDTHH:MM"},"location":{"type":"string"}},"required":["title","start"]}},
 {"name":"battery","description":"Liest den Akkustand des Handys.","input_schema":{"type":"object","properties":{}}},
 {"name":"open_settings","description":"Öffnet eine Einstellungsseite. WLAN, Bluetooth usw. kann Android nicht von Apps umschalten lassen, nur öffnen.",
  "input_schema":{"type":"object","properties":{"page":{"type":"string",
   "enum":["wifi","bluetooth","mobile_data","display","sound","battery","location","dnd","hotspot","main"]}},"required":["page"]}},
 {"name":"remember","description":"Speichert dauerhaft eine Information über den Nutzer, wenn er ausdrücklich sagt, dass Jarvis sich etwas merken soll.",
  "input_schema":{"type":"object","properties":{"fact":{"type":"string"}},"required":["fact"]}},
 {"name":"forget","description":"Löscht gespeicherte Informationen, die zum Stichwort passen.",
  "input_schema":{"type":"object","properties":{"fact":{"type":"string"}},"required":["fact"]}},
 {"name":"app_search","description":"Sucht direkt in einer App oder Seite und öffnet die Ergebnisse.",
  "input_schema":{"type":"object","properties":{
   "app":{"type":"string","enum":["youtube","google","maps","tiktok","instagram","playstore","netflix","amazon","ebay","x","reddit","wikipedia","spotify"]},
   "query":{"type":"string","description":"Suchbegriff; bei instagram ein Benutzername"}},"required":["app","query"]}},
 {"name":"camera","description":"Öffnet die Kamera im Foto-, Selfie- oder Videomodus.",
  "input_schema":{"type":"object","properties":{"mode":{"type":"string","enum":["photo","selfie","video"]}},"required":["mode"]}},
 {"name":"notifications","description":"read: neueste Benachrichtigungen und Nachrichten (WhatsApp, Instagram, SMS …) lesen. reply: direkt auf eine antworten, ohne die App zu öffnen.",
  "input_schema":{"type":"object","properties":{"action":{"type":"string","enum":["read","reply"]},
   "app":{"type":"string","description":"Optional bei read: nur diese App, z. B. WhatsApp"},
   "id":{"type":"integer","description":"Bei reply: id aus read"},
   "text":{"type":"string","description":"Bei reply: Antworttext"},
   "confirmed":{"type":"boolean","description":"reply erst nach Ja des Nutzers mit true"}},"required":["action"]}},
 {"name":"screen","description":"Bedient die gerade offene App wie ein Mensch. read: sichtbare Elemente mit Nummern. tap: Element antippen (index oder text). type: Text ins Eingabefeld. enter: Eingabe bestätigen. scroll_down/scroll_up. back/home/recents/notifications/quick_settings/screenshot/lock. Nach tap/type/scroll kommt der neue Bildschirm zurück.",
  "input_schema":{"type":"object","properties":{"action":{"type":"string","enum":["read","tap","type","enter","scroll_down","scroll_up","back","home","recents","notifications","quick_settings","screenshot","lock"]},
   "index":{"type":"integer"},"text":{"type":"string"}},"required":["action"]}},
 {"name":"location","description":"Bestimmt den aktuellen Standort des Nutzers (Adresse und Koordinaten). Nützlich für Wetter hier, Weg nach Hause usw.",
  "input_schema":{"type":"object","properties":{}}},
 {"name":"latest_photo","description":"Öffnet das neueste Foto oder bereitet es zum Teilen vor. Mit who: Teilen-Fenster, der Nutzer wählt den Empfänger und tippt auf Senden.",
  "input_schema":{"type":"object","properties":{"who":{"type":"string","description":"Optional: an wen teilen"}}}},
 {"name":"count_photos","description":"Sagt, wie viele Fotos auf dem Handy sind.","input_schema":{"type":"object","properties":{}}},
 {"name":"toggle","description":"Schaltet eine Systemfunktion über die Schnelleinstellungen um (tippt selbst auf die Kachel). Braucht die volle Handy-Steuerung.",
  "input_schema":{"type":"object","properties":{"what":{"type":"string","enum":["wifi","bluetooth","mobile_data","airplane","dnd","rotation","location","hotspot","flashlight"]},
   "confirmed":{"type":"boolean","description":"Bei airplane, mobile_data, hotspot erst nach Ja des Nutzers"}},"required":["what"]}},
 {"name":"show_card","description":"Zeigt eine Ergebniskarte auf dem Bildschirm. Nutze sie für Pläne, Schritt-für-Schritt-Anleitungen, Strategien, Listen, Einkaufslisten, Vergleiche, Zahlen, Rezepte. Danach nur 1-2 Sätze dazu sagen statt alles vorzulesen.",
  "input_schema":{"type":"object","properties":{"title":{"type":"string"},
   "kind":{"type":"string","enum":["steps","checklist","stats","text"]},
   "items":{"type":"array","items":{"type":"string"},"description":"Zeilen. Bei stats je 'Bezeichnung | Wert'"},
   "note":{"type":"string","description":"Optionaler Hinweis unten"}},"required":["title","kind","items"]}},
 {"name":"build_website","description":"Baut eine einseitige Website und öffnet sie im Browser. html = vollständiges, kompaktes HTML mit eingebettetem CSS, ohne externe Dateien oder Bilder-Links, modern, dunkel oder hell passend zum Thema, mobilfreundlich, auf Deutsch. Sie liegt nur auf dem Handy (Downloads/Jarvis), nicht online.",
  "input_schema":{"type":"object","properties":{"title":{"type":"string"},"html":{"type":"string"}},"required":["title","html"]}},
 {"name":"second_phone","description":"Führt eines deiner Werkzeuge auf dem zweiten Handy des Nutzers (der Jarvis-Station) aus, wenn er 'auf dem zweiten Handy', 'auf der Station' oder 'am anderen Handy' sagt. tool = Werkzeugname (z. B. play_music, media_control, set_alarm, set_timer, flashlight, set_volume, open_app, app_search, battery, camera, toggle, screen, notifications), args = dessen Parameter als JSON-Text.",
  "input_schema":{"type":"object","properties":{"tool":{"type":"string"},
   "args":{"type":"string","description":"Parameter als JSON-Text, z. B. {\"on\":true} oder {\"query\":\"Drake\"}"}},"required":["tool","args"]}}
]"""
    }
}
