package de.jarvis.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Gemini (kostenloses Kontingent) mit denselben Handy-Werkzeugen wie Claude
 * plus Google-Suche. Läuft blockierend – immer aus einem Hintergrund-Thread aufrufen.
 */
class GeminiClient(
    private val apiKey: String,
    model: String,
    private val userName: String,
    private val tools: PhoneTools,
    private val memory: Memory,
    private val chat: Boolean = false,
) : Brain {

    /** Modelle, die der Reihe nach probiert werden, falls eines nicht existiert oder am Limit ist. */
    private val models = (listOf(model) + FALLBACK_MODELS).filter { it.isNotBlank() }.distinct().toMutableList()
    private val contents = JSONArray()   // Verlauf im Gemini-Format
    private var googleSearch = true

    override fun seed(history: List<Pair<String, String>>) {
        history.forEach { (r, t) ->
            contents.put(JSONObject().put("role", if (r == "user") "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", t))))
        }
    }

    override fun ask(userText: String, onStep: (String) -> Unit, image: String?): String {
        trimHistory()
        val rollback = contents.length()
        val userParts = JSONArray()
        if (image != null) userParts.put(JSONObject().put("inlineData",
            JSONObject().put("mimeType", "image/jpeg").put("data", image)))
        userParts.put(JSONObject().put("text", userText.ifBlank { "Was siehst du auf dem Bild?" }))
        contents.put(JSONObject().put("role", "user").put("parts", userParts))
        try {
            repeat(MAX_ROUNDS) {
                val resp = request()
                val cand = resp.optJSONArray("candidates")?.optJSONObject(0)
                    ?: throw blockedOrEmpty(resp)
                val content = cand.optJSONObject("content")
                    ?: return Persona.clean("") // z. B. nur Sicherheitsfilter ohne Inhalt
                if (!content.has("role")) content.put("role", "model")
                // Inhalt unverändert zurückgeben (enthält ggf. "thoughtSignature", die Gemini 3 verlangt)
                contents.put(content)

                val parts = content.optJSONArray("parts") ?: JSONArray()
                val responses = JSONArray()
                val text = StringBuilder()
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    val call = p.optJSONObject("functionCall")
                    if (call != null) {
                        val name = call.optString("name")
                        onStep(tools.label(name))
                        val out = tools.execute(name, call.optJSONObject("args") ?: JSONObject())
                        val fr = JSONObject().put("name", name)
                            .put("response", JSONObject().put(if (out.startsWith("Fehler")) "error" else "result", out))
                        if (call.has("id")) fr.put("id", call.get("id"))
                        responses.put(JSONObject().put("functionResponse", fr))
                    } else if (p.has("text") && !p.optBoolean("thought", false)) {
                        text.append(p.optString("text"))
                    }
                }
                if (responses.length() == 0) return if (chat) Persona.cleanChat(text.toString()) else Persona.clean(text.toString())
                contents.put(JSONObject().put("role", "user").put("parts", responses))
            }
            return "Das war mir zu verschachtelt. Sag es mir bitte nochmal einfacher."
        } catch (e: Brain.Unavailable) {
            rollbackTo(rollback); throw e
        } catch (e: IOException) {
            rollbackTo(rollback); throw Brain.Unavailable("Ich erreiche das Internet gerade nicht.")
        } catch (e: Exception) {
            rollbackTo(rollback); throw Brain.Unavailable("Bei Gemini ist etwas schiefgelaufen.")
        }
    }

    private fun rollbackTo(n: Int) { while (contents.length() > n) contents.remove(contents.length() - 1) }

    private fun blockedOrEmpty(resp: JSONObject): Exception {
        val reason = resp.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        return if (reason.isNotEmpty()) Brain.Unavailable("Darauf darf ich leider nicht antworten.")
        else Brain.Unavailable("Gemini hat keine Antwort geliefert.")
    }

    /** Verlauf kurz halten; nur an echten Nutzerfragen (Text, kein Werkzeug-Ergebnis) abschneiden. */
    private fun trimHistory() {
        if (contents.length() <= MAX_CONTENTS) return
        var cut = contents.length() - MAX_CONTENTS
        while (cut < contents.length()) {
            val c = contents.getJSONObject(cut)
            val first = c.optJSONArray("parts")?.optJSONObject(0)
            if (c.optString("role") == "user" && first?.has("text") == true) break
            cut++
        }
        repeat(cut) { contents.remove(0) }
    }

    // ---------- HTTP ----------

    /** Probiert Modelle der Reihe nach; Google-Suche wird abgeschaltet, falls das Modell sie nicht mit Werkzeugen mag. */
    private fun request(): JSONObject {
        var lastErr: ApiException? = null
        while (models.isNotEmpty()) {
            val model = models.first()
            try {
                return post(model)
            } catch (e: ApiException) {
                lastErr = e
                when {
                    e.code == 400 && googleSearch &&
                        (e.body.contains("google_search", true) || e.body.contains("googleSearch", true) ||
                         e.body.contains("tool", true)) -> googleSearch = false     // nochmal ohne Suche
                    e.code == 404 || e.code == 429 -> models.removeAt(0)          // nächstes Modell
                    else -> throw toUnavailable(e)
                }
            }
        }
        throw toUnavailable(lastErr ?: ApiException(0, ""))
    }

    private fun toUnavailable(e: ApiException) = Brain.Unavailable(when (e.code) {
        400 -> if (e.body.contains("API_KEY_INVALID") || e.body.contains("API key not valid"))
            "Mein Gemini-Schlüssel funktioniert nicht. Bitte prüf ihn in der Jarvis-App."
        else "Gemini hat die Anfrage abgelehnt."
        401, 403 -> "Mein Gemini-Schlüssel funktioniert nicht. Bitte prüf ihn in der Jarvis-App."
        429 -> "Das kostenlose Gemini-Kontingent für heute ist aufgebraucht. Morgen geht's weiter."
        in 500..599 -> "Gemini ist gerade überlastet. Versuch es gleich nochmal."
        else -> "Bei Gemini ist etwas schiefgelaufen."
    })

    private fun toolList(): JSONArray {
        val decls = JSONArray()
        val defs = PhoneTools.DEFINITIONS
        for (i in 0 until defs.length()) {
            val d = defs.getJSONObject(i)
            val f = JSONObject().put("name", d.getString("name")).put("description", d.getString("description"))
            val schema = d.optJSONObject("input_schema")
            if (schema != null && (schema.optJSONObject("properties")?.length() ?: 0) > 0) f.put("parameters", schema)
            decls.put(f)
        }
        val list = JSONArray().put(JSONObject().put("functionDeclarations", decls))
        if (googleSearch) list.put(JSONObject().put("googleSearch", JSONObject()))
        return list
    }

    private fun post(model: String): JSONObject {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", Persona.systemPrompt(userName, memory, chat)))))
            .put("contents", contents)
            .put("tools", toolList())
            .put("generationConfig", JSONObject().put("maxOutputTokens", 4096))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
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

    class ApiException(val code: Int, val body: String) : Exception("HTTP $code: $body")

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"
        private val FALLBACK_MODELS = listOf("gemini-flash-latest", "gemini-3.5-flash", "gemini-flash-lite-latest", "gemini-3.5-flash-lite")
        private const val MAX_ROUNDS = 8
        private const val MAX_CONTENTS = 30
    }
}
