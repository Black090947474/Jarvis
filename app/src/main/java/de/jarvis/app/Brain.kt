package de.jarvis.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Jarvis' Persönlichkeit und Regeln – gleich für Gemini und Claude. */
object Persona {
    fun systemPrompt(userName: String, memory: Memory): String {
        val now = SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm 'Uhr'", Locale.GERMANY).format(Date())
        val name = if (userName.isNotBlank()) "Der Nutzer heißt $userName. " else ""
        val facts = memory.all()
        val mem = if (facts.isEmpty()) "" else
            "\nDas hat dir der Nutzer zum Merken gesagt:\n" + facts.joinToString("\n") { "- $it" }
        return """
            Du bist JARVIS, der persönliche Sprachassistent auf dem Android-Handy des Nutzers –
            höflich, schlagfertig, mit einem Hauch britischem Butler-Humor. ${name}Jetzt ist es $now.
            Der Nutzer ist in Deutschland, sofern er nichts anderes sagt.

            Deine Antworten werden laut vorgelesen:
            - Antworte auf Deutsch, kurz und natürlich gesprochen (meist 1–2 Sätze).
            - Kein Markdown, keine Aufzählungszeichen, keine Emojis, keine Links, keine Quellenangaben.
            - Uhrzeiten so schreiben, wie man sie sagt, z. B. "halb acht" oder "sieben Uhr dreißig".

            Werkzeuge:
            - Du kannst das Handy wirklich steuern: Wecker, Timer, Taschenlampe, Lautstärke, Musik,
              Apps öffnen, anrufen, Nachrichten vorbereiten, Navigation, Termine, Akku, Einstellungen.
            - Nutze die Websuche für alles Aktuelle: Wetter, Nachrichten, Öffnungszeiten, Sportergebnisse, Preise.
            - Handle direkt, ohne nachzufragen, wenn klar ist, was gemeint ist. "Weck mich um 7" heißt 07:00;
              "morgen früh um halb 8" heißt 07:30. Nur bei echter Mehrdeutigkeit kurz nachfragen.
            - Behaupte nie, etwas getan zu haben, das ein Werkzeug nicht bestätigt hat. Meldet ein Werkzeug
              einen Fehler oder dass der Nutzer noch tippen muss, sag das ehrlich.
            - Was kein Werkzeug kann (z. B. WLAN selbst umschalten, Nachrichten ohne Antippen senden,
              Wecker löschen), sag kurz, was stattdessen geht.
            - Nutze "remember" nur, wenn der Nutzer ausdrücklich will, dass du dir etwas merkst.
        """.trimIndent() + mem
    }

    /** Entfernt Zeichen, die beim Vorlesen stören. */
    fun clean(text: String): String = text
        .replace(Regex("[*#_`]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifEmpty { "Erledigt." }
}

/** Ein "Gehirn" für Jarvis. */
interface Brain {
    /** Gibt die gesprochene Antwort zurück. Wirft [Unavailable], wenn dieses Gehirn gerade nicht kann. */
    fun ask(userText: String, onStep: (String) -> Unit): String

    /** Kontingent leer, überlastet, Schlüssel ungültig o. ä. – ein anderes Gehirn soll übernehmen. */
    class Unavailable(val spoken: String) : Exception(spoken)
}

/**
 * Gemini zuerst (kostenlos), Claude als Ersatz, falls Gemini nicht kann.
 * Ist nur einer der beiden Schlüssel eingetragen, wird nur dieser benutzt.
 */
class BrainRouter(private val primary: Brain?, private val backup: Brain?) {
    private var useBackupFirst = false

    fun ask(userText: String, onStep: (String) -> Unit): String {
        val order = listOfNotNull(if (useBackupFirst) backup else primary, if (useBackupFirst) primary else backup)
        if (order.isEmpty()) return "Mir fehlt noch ein Schlüssel. Trag ihn bitte in der Jarvis-App ein."
        var last: Brain.Unavailable? = null
        for (b in order) {
            try {
                return b.ask(userText, onStep)
            } catch (e: Brain.Unavailable) {
                last = e
                // Ist Gemini z. B. am Tageslimit, für den Rest des Gesprächs gleich Claude nehmen
                if (b === primary && backup != null) useBackupFirst = true
            }
        }
        return last?.spoken ?: "Da ist etwas schiefgelaufen."
    }
}
