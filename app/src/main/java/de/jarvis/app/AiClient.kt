package de.jarvis.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Ein Anbieter mit OpenAI-kompatibler Schnittstelle (GitHub Models, Mistral). */
class Provider(
    val name: String,
    val url: String,
    val models: List<String>,
    val visionModels: List<String>,
    val maxTokensKey: String,
    val imageUrlAsString: Boolean = false,
) {
    companion object {
        // GitHub Models: kostenlos mit GitHub-Konto (ca. 15 Anfragen/Minute, 150/Tag) 
        val GITHUB = Provider("GitHub", "https://models.github.ai/inference/chat/completions",
            listOf("openai/gpt-4.1-mini", "openai/gpt-4o-mini"),
            listOf("openai/gpt-4.1-mini", "openai/gpt-4o-mini"), "max_tokens")
        // Mistral „Experiment“: kostenlos, sehr hohes Minutenlimit (ca. 1 Anfrage pro Sekunde)
        val MISTRAL = Provider("Mistral", "https://api.mistral.ai/v1/chat/completions",
            listOf("mistral-medium-latest", "mistral-small-latest"),
            listOf("mistral-medium-latest", "mistral-small-latest"), "max_tokens", imageUrlAsString = true)
    }
}

/**
 * OpenAI-kompatibler Client (GitHub Models oder Mistral) mit denselben Handy-Werkzeugen.
 * Läuft blockierend – immer aus einem Hintergrund-Thread aufrufen.
 * quickFail = bei vollem Minutenlimit nicht warten, sondern sofort den nächsten Anbieter nehmen.
 */
class AiClient(
    private val apiKey: String,
    model: String,
    private val userName: String,
    private val tools: PhoneTools,
    private val memory: Memory,
    private val chat: Boolean = false,
    private val provider: Provider = Provider.GITHUB,
    private val quickFail: Boolean = false,
) : Brain {

    /** Sobald ein Bild im Gespräch ist, antwortet ein Bild-Modell. */
    private var vision = false
    private val visionModels = provider.visionModels.toMutableList()

    private val models = (listOf(model) + provider.models).filter { it.isNotBlank() }.distinct().toMutableList()
    private val messages = JSONArray()  // Verlauf ohne System-Nachricht
    private var webSearch = true

    private var step: (String) -> Unit = {}

    override fun seed(history: List<Pair<String, String>>) {
        history.forEach { (r, t) -> messages.put(JSONObject().put("role", r).put("content", t)) }
    }

    private var lastQuery = ""

    override fun ask(userText: String, onStep: (String) -> Unit, image: String?): String {
        lastQuery = userText
        tools.noteQuery(userText)
        step = onStep
        trimHistory()
        val rollback = messages.length()
        if (image != null) {
            vision = true
            messages.put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", userText.ifBlank { "Was siehst du auf dem Bild?" }))
                .put(JSONObject().put("type", "image_url")
                    .put("image_url", if (provider.imageUrlAsString) "data:image/jpeg;base64,$image"
                        else JSONObject().put("url", "data:image/jpeg;base64,$image")))))
        } else messages.put(JSONObject().put("role", "user").put("content", userText))
        try {
            repeat(MAX_ROUNDS) {
                val resp = request()
                val msg = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?: throw Brain.Unavailable("${provider.name} hat keine Antwort geliefert.")
                val calls = msg.optJSONArray("tool_calls")

                // Nur die Felder zurückgeben, die die Schnittstelle erwartet
                val clean = JSONObject().put("role", "assistant")
                    .put("content", if (msg.isNull("content")) (if (provider === Provider.MISTRAL) "" else JSONObject.NULL) else msg.optString("content"))
                if (calls != null && calls.length() > 0) clean.put("tool_calls", calls)
                messages.put(clean)

                if (calls == null || calls.length() == 0) {
                    val text = msg.optString("content")
                    return if (chat) Persona.cleanChat(text) else Persona.clean(text)
                }

                for (i in 0 until calls.length()) {
                    val c = calls.getJSONObject(i)
                    val fn = c.optJSONObject("function") ?: continue
                    val name = fn.optString("name")
                    onStep(tools.label(name))
                    val args = try { JSONObject(fn.optString("arguments").ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
                    val out = tools.execute(name, args)
                    messages.put(JSONObject().put("role", "tool").put("name", name)
                        .put("tool_call_id", c.optString("id")).put("content", out))
                }
            }
            return "Das war mir zu verschachtelt. Sag es mir bitte nochmal einfacher."
        } catch (e: Brain.Unavailable) {
            rollbackTo(rollback); throw e
        } catch (e: IOException) {
            rollbackTo(rollback); throw Brain.Unavailable("Ich erreiche das Internet gerade nicht.")
        } catch (e: Exception) {
            rollbackTo(rollback); throw Brain.Unavailable("Bei ${provider.name} ist etwas schiefgelaufen.")
        }
    }

    private fun rollbackTo(n: Int) { while (messages.length() > n) messages.remove(messages.length() - 1) }

    /** Verlauf kurz halten; nur an echten Nutzerfragen abschneiden. */
    private fun trimHistory() {
        if (messages.length() <= MAX_MESSAGES) return
        var cut = messages.length() - MAX_MESSAGES
        while (cut < messages.length() && messages.getJSONObject(cut).optString("role") != "user") cut++
        repeat(cut) { messages.remove(0) }
    }

    private fun toolList(model: String): JSONArray {
        val list = JSONArray()
        val defs = tools.definitionsFor(lastQuery)
        for (i in 0 until defs.length()) {
            val d = defs.getJSONObject(i)
            list.put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", d.getString("name"))
                .put("description", d.getString("description"))
                .put("parameters", d.optJSONObject("input_schema") ?: JSONObject().put("type", "object"))))
        }
        // Eingebaute Websuche der GPT-OSS-Modelle (wird abgeschaltet, falls nicht unterstützt)
        if (webSearch && model.startsWith("openai/gpt-oss")) {
            list.put(JSONObject().put("type", "browser_search"))
        }
        return list
    }

    /**
     * Probiert die Modelle der Reihe nach. Das kostenlose Kontingent zählt pro Modell und Minute –
     * ist eines kurz am Limit (429), wird das nächste genommen; sind alle am Limit, kurz warten.
     */
    private fun request(): JSONObject {
        var lastErr: ApiException? = null
        for (attempt in 0..3) {
            var waitSec = 0
            var i = 0
            val list = if (vision) visionModels else models
            while (i < list.size) {
                try {
                    return post(list[i])
                } catch (e: ApiException) {
                    lastErr = e
                    when {
                        // Ein 400er kann von der eingebauten Websuche kommen: einmal ohne sie probieren
                        e.code == 400 && webSearch -> { webSearch = false; continue }
                        e.code == 429 && quickFail && i == list.size - 1 -> throw toUnavailable(e)
                        e.code == 429 -> { waitSec = if (waitSec == 0) e.retryAfter else minOf(waitSec, e.retryAfter); i++ }
                        e.code == 404 || (e.code == 400 && e.body.contains("model", true)) -> list.removeAt(i)
                        else -> throw toUnavailable(e)
                    }
                }
            }
            // Alle Modelle kurz am Minutenlimit: warten statt aufgeben
            if (lastErr?.code == 429 && attempt < 3) {
                val w = waitSec.coerceIn(if (provider === Provider.MISTRAL) 1 else 3, 30)
                step("Kurze Pause, ${provider.name}-Limit … ($w s)")
                Thread.sleep(w * 1000L)
            } else break
        }
        throw toUnavailable(lastErr ?: ApiException(0, ""))
    }

    private fun toUnavailable(e: ApiException) = Brain.Unavailable(when (e.code) {
        401, 403 -> "Mein ${provider.name}-Schlüssel funktioniert nicht. Bitte prüf ihn in der Jarvis-App."
        429 -> "Das kostenlose ${provider.name}-Kontingent ist gerade aufgebraucht. Versuch es etwas später nochmal."
        in 500..599 -> "${provider.name} ist gerade überlastet. Versuch es gleich nochmal."
        else -> "Bei ${provider.name} ist etwas schiefgelaufen, Fehler ${e.code}."
    })

    private var lastPost = 0L

    private fun post(model: String): JSONObject {
        // Mistral erlaubt im Gratis-Tarif etwa eine Anfrage pro Sekunde
        if (provider === Provider.MISTRAL) {
            val wait = 1100 - (System.currentTimeMillis() - lastPost)
            if (wait > 0) Thread.sleep(wait)
            lastPost = System.currentTimeMillis()
        }
        val all = JSONArray().put(JSONObject().put("role", "system").put("content", Persona.systemPrompt(userName, memory, chat)))
        // Ältere Werkzeug-Ergebnisse (v. a. Bildschirminhalte) kürzen: spart viele Tokens,
        // damit das kostenlose Minutenlimit bei längeren Aufgaben nicht sofort voll ist.
        // Nur das neueste Bild mitschicken (jedes Bild kostet viele Tokens); ältere werden zu Text
        var lastImage = -1
        for (i in 0 until messages.length()) if (messages.getJSONObject(i).opt("content") is JSONArray) lastImage = i
        var toolSeen = 0
        val keep = BooleanArray(messages.length())
        for (i in messages.length() - 1 downTo 0) {
            if (messages.getJSONObject(i).optString("role") == "tool") { toolSeen++; keep[i] = toolSeen <= 2 }
            else keep[i] = true
        }
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val arr = m.opt("content") as? JSONArray
            if (arr != null && i != lastImage) {
                val t = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .firstOrNull { it.optString("type") == "text" }?.optString("text").orEmpty()
                all.put(JSONObject().put("role", "user").put("content", "[früheres Bild] $t"))
                continue
            }
            if (!keep[i]) {
                val c = m.optString("content")
                all.put(JSONObject(m.toString()).put("content",
                    if (c.length > 160) c.take(140) + " … [älterer Inhalt gekürzt]" else c))
            } else all.put(m)
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", all)
            .put("tools", toolList(model))
            .put("tool_choice", "auto")
            .put(provider.maxTokensKey, if (chat) 2000 else 1200)
            .apply { if (model.startsWith("openai/gpt-oss")) put("reasoning_effort", "low") }

        val conn = (URL(provider.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiException(code, text,
                conn.getHeaderField("retry-after")?.toDoubleOrNull()?.let { Math.ceil(it).toInt() } ?: 5)
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    class ApiException(val code: Int, val body: String, val retryAfter: Int = 5) : Exception("HTTP $code: $body")

    companion object {
        private const val MAX_ROUNDS = 16
        private const val MAX_MESSAGES = 16  // GitHub Models: max. 8000 Tokens pro Anfrage
    }
}
