package de.jarvis.app.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import de.jarvis.app.Prefs
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ein Werkzeug, das die KI aufrufen kann. Neue Fähigkeiten = neue Klasse + Eintrag in [ToolRegistry].
 *
 * Jedes Werkzeug liefert seinen Zustand am Anfang des Ergebnisses (siehe [Res]):
 *   OK · BESTÄTIGUNG NÖTIG · FEHLER · BERECHTIGUNG FEHLT
 */
interface JarvisTool {
    /** Name, unter dem die KI das Werkzeug aufruft. */
    val name: String
    /** Gruppe für die Werkzeugauswahl (spart Tokens): core, agenda, comm, device, info. */
    val group: String
    /** Schlüssel des Funktionsschalters im Berechtigungszentrum (null = immer erlaubt). */
    val feature: String?
    /** Android-Berechtigungen, die das Werkzeug braucht (zur Anzeige und Prüfung). */
    val permissions: List<String>
    /** Beschreibung für die KI: {name, description, input_schema}. */
    val definition: JSONObject
    fun run(t: ToolContext, a: JSONObject): String
}

/** Einheitliche Ergebnis-Zustände für die KI. */
object Res {
    fun ok(msg: String) = "OK: $msg"
    fun confirm(what: String) = "BESTÄTIGUNG NÖTIG: $what Frag den Nutzer, ob du das tun sollst. " +
        "Erst nach einem klaren Ja dasselbe Werkzeug nochmal mit confirmed=true aufrufen."
    fun error(msg: String) = "FEHLER: $msg"
    fun noPermission(what: String, how: String) = "BERECHTIGUNG FEHLT: Für $what brauche ich Zugriff. $how"
    fun disabled(what: String) = "BERECHTIGUNG FEHLT: Du hast Jarvis den Zugriff auf $what im Berechtigungszentrum ausgeschaltet."

    fun isError(r: String) = r.startsWith("Fehler") || r.startsWith("FEHLER") || r.startsWith("BERECHTIGUNG FEHLT")

    /** Für die Sprachausgabe ohne KI (offline): Zustands-Präfix entfernen. */
    fun spoken(r: String) = r.removePrefix("OK: ").removePrefix("FEHLER: ").removePrefix("BERECHTIGUNG FEHLT: ")
}

/** Was ein Werkzeug zum Arbeiten bekommt. Läuft im Hintergrund-Thread. */
class ToolContext(val activity: Activity, val runner: ((String, JSONObject) -> String)? = null) {
    val ctx: Context get() = activity
    val prefs = Prefs(activity)
    private val main = Handler(Looper.getMainLooper())

    fun has(perm: String) = activity.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /** Etwas auf dem Hauptthread ausführen und auf das Ergebnis warten (z. B. App starten). */
    fun <T> ui(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var r: T? = null
        val l = CountDownLatch(1)
        main.post { try { r = block() } finally { l.countDown() } }
        l.await(5, TimeUnit.SECONDS)
        return r
    }

    fun launch(i: Intent, ok: String): String = ui {
        try { activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Res.ok(ok) }
        catch (_: Exception) { Res.error("Keine passende App gefunden.") }
    } ?: Res.error("Zeitüberschreitung beim Öffnen.")
}

object ToolRegistry {
    val all: List<JarvisTool> by lazy {
        listOf(AgendaTool, CalendarTool, ReminderTool, TaskTool, CallLogTool, EmailTool, WeatherTool, NearbyTool, BrightnessTool,
            WebTool, BriefingTool, SchoolTool, ShoppingTool, NotesTool, QuizTool, RoutineTool, BirthdayTool, PlacesTool, MusicTool,
            AutomationTool, FilesTool, RouteTool, NotifyHubTool, EmergencyTool, MoneyTool, PartyQuizTool, FocusTool, PhotosTool)
    }

    fun find(name: String): JarvisTool? = all.firstOrNull { it.name == name }

    /** Stichwörter, bei denen eine Werkzeug-Gruppe mitgeschickt wird. */
    private val KEYWORDS = mapOf(
        "agenda" to listOf("termin", "kalender", "heute", "morgen", "woche", "monat", "frei", "zeit", "plan", "erinner",
            "aufgabe", "todo", "to-do", "steht an", "wann", "verschieb", "lösch", "absag", "montag", "dienstag", "mittwoch",
            "donnerstag", "freitag", "samstag", "sonntag", "wochenende", "uhr", "datum", "geburtstag", "lernen", "hausaufgabe",
            "zuhause", "zu hause", "ankomm", "erledigt", "fertig", "liste", "schule", "stundenplan", "fach", "stunde",
            "einkauf", "kaufen", "notiz", "notier", "aufschreib", "geburtstag", "mathe", "deutsch", "englisch",
            "geld", "taschengeld", "ausgegeben", "ausgabe", "spar", "euro", "€", "fokus", "pomodoro", "konzentr", "packen", "pack", "ranzen", "tasche"),
        "comm" to listOf("ruf", "anruf", "angerufen", "telefon", "nachricht", "schreib", "sms", "whatsapp", "mail", "e-mail",
            "antwort", "benachrichtig", "kontakt", "insta", "snap", "verpasst"),
        "device" to listOf("wlan", "wifi", "bluetooth", "flugmodus", "hotspot", "helligkeit", "hell", "dunkel", "einstellung",
            "kamera", "foto", "bild", "selfie", "screenshot", "bildschirm", "tipp", "scroll", "öffne", "app", "standort",
            "wo bin", "navig", "fahr", "route", "nähe", "karte", "maps", "drehen", "nicht stören", "daten", "zurück", "sperr",
            "like", "folg", "poste", "schick", "geparkt", "park", "auto steht", "merk dir den ort", "wo steht"),
        "info" to listOf("wetter", "regen", "temperatur", "sonne", "grad", "kalt", "warm", "jacke", "schirm", "website",
            "webseite", "homepage", "zweite", "station", "anderen handy", "vergiss", "merk", "vokabel", "abfrag",
            "lern", "quiz", "teste mich", "karteikart", "übersetz", "auf englisch", "auf französisch", "auf spanisch", "dolmetsch",
            "geschichte", "witz", "punkte", "spieleabend", "runde"),
    )

    /** Welche Gruppen für diese Anfrage gebraucht werden ("core" immer). */
    fun groupsFor(query: String): Set<String> {
        val q = query.lowercase()
        return setOf("core") + KEYWORDS.filter { (_, words) -> words.any { q.contains(it) } }.keys
    }
}
