package de.jarvis.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Claude-API mit Werkzeugen: Claude entscheidet selbst, ob es eine Handy-Funktion
 * (Wecker, Anruf, Musik …) oder die Websuche braucht. Diese Klasse führt die Schleife
 * "Claude fragt Werkzeug an → Handy führt aus → Ergebnis zurück → Claude antwortet" aus.
 * Läuft blockierend – immer aus einem Hintergrund-Thread aufrufen.
 */
class ClaudeClient(
    private val apiKey: String,
    private val model: String,
    private val userName: String,
    private val tools: PhoneTools,
    private val memory: Memory,
) {
    /** Kompletter Verlauf dieser Sitzung im API-Format (inkl. Werkzeug-Ergebnisse). */
    private val messages = JSONArray()
    private var webSearch = true

    /**
     * Stellt eine Frage und gibt die gesprochene Antwort zurück.
     * [onStep] meldet, welches Werkzeug gerade läuft (für die Anzeige).
     */
    fun ask(userText: String, onStep: (String) -> Unit = {}): String {
        trimHistory()
        val rollback = messages.length()
        messages.put(JSONObject().put("role", "user").put("content", userText))
        try {
            repeat(MAX_ROUNDS) {
                val resp = request()
                val content = resp.getJSONArray("content")
                messages.put(JSONObject().put("role", "assistant").put("content", content))

                when (resp.optString("stop_reason")) {
                    "tool_use" -> {
                        val results = JSONArray()
                        for (i in 0 until content.length()) {
                            val b = content.getJSONObject(i)
                            if (b.optString("type") != "tool_use") continue
                            val name = b.getString("name")
                            onStep(tools.label(name))
                            val out = tools.execute(name, b.optJSONObject("input") ?: JSONObject())
                            results.put(JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", b.getString("id"))
                                .put("content", out)
                                .apply { if (out.startsWith("Fehler")) put("is_error", true) })
                        }
                        messages.put(JSONObject().put("role", "user").put("content", results))
                    }
                    "pause_turn" -> onStep(tools.label("web_search")) // Websuche läuft noch: einfach weiter
                    else -> return extractText(content)
                }
            }
            return "Das war mir zu verschachtelt. Sag es mir bitte nochmal einfacher."
        } catch (e: Exception) {
            while (messages.length() > rollback) messages.remove(messages.length() - 1)
            return friendlyError(e)
        }
    }

    private fun extractText(content: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString()
            .replace(Regex("[*#_`]"), "")          // falls doch Markdown kommt: nicht vorlesen
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { "Erledigt." }
    }

    /** Hält den Verlauf kurz; schneidet nur an echten Nutzerfragen ab, nie mitten in Werkzeug-Runden. */
    private fun trimHistory() {
        if (messages.length() <= MAX_MESSAGES) return
        var cut = messages.length() - MAX_MESSAGES
        while (cut < messages.length()) {
            val m = messages.getJSONObject(cut)
            if (m.optString("role") == "user" && m.opt("content") is String) break
            cut++
        }
        repeat(cut) { messages.remove(0) }
    }

    private fun systemPrompt(): String {
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
              ist "morgen früh um halb 8" gemeint, nimm 07:30. Nur bei echter Mehrdeutigkeit kurz nachfragen.
            - Behaupte nie, etwas getan zu haben, das ein Werkzeug nicht bestätigt hat. Meldet ein Werkzeug
              einen Fehler oder dass der Nutzer noch tippen muss, sag das ehrlich.
            - Was kein Werkzeug kann (z. B. WLAN selbst umschalten, Nachrichten ohne Antippen senden,
              Wecker löschen), sag kurz, was stattdessen geht.
            - Nutze "remember" nur, wenn der Nutzer ausdrücklich will, dass du dir etwas merkst.
        """.trimIndent() + mem
    }

    private fun toolList(): JSONArray {
        val list = JSONArray()
        val defs = PhoneTools.DEFINITIONS
        for (i in 0 until defs.length()) list.put(defs.get(i))
        if (webSearch) {
            list.put(JSONObject()
                .put("type", "web_search_20250305")
                .put("name", "web_search")
                .put("max_uses", 3)
                .put("user_location", JSONObject()
                    .put("type", "approximate")
                    .put("country", "DE")
                    .put("timezone", "Europe/Berlin")))
        }
        return list
    }

    private fun request(): JSONObject {
        try {
            return post()
        } catch (e: ApiException) {
            // Falls das Konto/Modell keine Websuche erlaubt: ohne Websuche weitermachen
            if (webSearch && e.code == 400 && e.body.contains("web_search")) {
                webSearch = false
                return post()
            }
            throw e
        }
    }

    private fun post(): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", systemPrompt())
            .put("tools", toolList())
            .put("messages", messages)

        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiException(code, text)
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is ApiException -> when (e.code) {
            401 -> "Mein API-Schlüssel funktioniert nicht. Bitte prüf ihn in der Jarvis-App."
            400 -> if (e.body.contains("credit", ignoreCase = true))
                "Dein Anthropic-Guthaben ist leer. Bitte lade es in der Console auf."
            else "Die Anfrage wurde abgelehnt. Vielleicht stimmt der Modellname nicht."
            404 -> "Das eingestellte Modell gibt es nicht. Bitte prüf den Modellnamen."
            429 -> "Ich bekomme gerade zu viele Anfragen. Versuch es gleich nochmal."
            529, in 500..599 -> "Die Server sind gerade überlastet. Versuch es gleich nochmal."
            else -> "Da ist etwas schiefgelaufen, Fehler ${e.code}."
        }
        is IOException -> "Ich erreiche das Internet gerade nicht."
        else -> "Da ist etwas schiefgelaufen."
    }

    class ApiException(val code: Int, val body: String) : Exception("HTTP $code: $body")

    companion object {
        private const val MAX_ROUNDS = 8
        private const val MAX_MESSAGES = 30
    }
}
