package de.jarvis.app

import android.content.ClipboardManager
import android.content.Context

/**
 * Erkennt einen kopierten API-Schlüssel in der Zwischenablage (nur wenn du gerade auf dem Anbieter-Bildschirm bist)
 * und schlägt vor, ihn einzutragen. Es wird nichts ohne deine Bestätigung gespeichert.
 */
object KeySniffer {
    data class Hit(val target: String, val name: String, val key: String)

    /** target: groq, nvidia, gemini, claude, mistral oder eine Vorlagen-ID aus ProviderCatalog. */
    fun detect(text: String, expected: String?): Hit? {
        val k = de.jarvis.app.logic.ProviderCatalog.cleanKey(text)
        if (k.length !in 20..220) return null
        return when {
            k.startsWith("gsk_") -> Hit("groq", "Groq", k)
            k.startsWith("nvapi-") -> Hit("nvidia", "NVIDIA", k)
            k.startsWith("sk-ant-") -> Hit("claude", "Claude", k)
            k.startsWith("AIza") -> Hit("gemini", "Google Gemini", k)
            k.startsWith("sk-or-") -> Hit("openrouter", "OpenRouter", k)
            k.startsWith("hf_") -> Hit("huggingface", "Hugging Face", k)
            k.startsWith("sk-proj-") || (k.startsWith("sk-") && expected == null) -> Hit("openai", "ChatGPT (OpenAI)", k)
            expected != null && Regex("^[A-Za-z0-9._\\-]+$").matches(k) ->
                Hit(expected, de.jarvis.app.logic.ProviderCatalog.preset(expected)?.name ?: expected, k)
            else -> null
        }
    }

    fun clipboard(ctx: Context): String? = try {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
    } catch (_: Exception) { null }

    /** Schon eingetragen? Dann nicht nochmal fragen. */
    fun known(p: Prefs, h: Hit): Boolean = when (h.target) {
        "groq" -> p.groqKey == h.key; "nvidia" -> p.nvidiaKey == h.key; "gemini" -> p.geminiKey == h.key
        "claude" -> p.anthropicKey == h.key; "mistral" -> p.mistralKey == h.key
        else -> p.extraList().any { it.optString("key") == h.key }
    }
}
