package de.jarvis.app.logic

/**
 * Vorlagen für zusätzliche KI-Anbieter mit OpenAI-kompatibler Schnittstelle.
 * Jeder Anbieter = ein eigenes, kostenloses Konto. Jarvis wechselt automatisch, wenn eines am Limit ist.
 * {account} in der URL wird durch die Konto-ID ersetzt (z. B. bei Cloudflare).
 */
object ProviderCatalog {
    data class Preset(val id: String, val name: String, val url: String, val models: List<String>, val signup: String,
                      val note: String, val needsAccount: Boolean = false, val keyOptional: Boolean = false)

    val PRESETS = listOf(
        Preset("gemini_oa", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            listOf("gemini-flash-latest", "gemini-3.8-flash", "gemini-3.5-flash-lite"), "https://aistudio.google.com/apikey", "Kostenlos, ohne Karte, großes Tageslimit."),
        Preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/chat/completions",
            listOf("openrouter/free", "meta-llama/llama-3.3-70b-instruct:free"), "https://openrouter.ai/keys", "Kostenlose Modelle (Endung :free), ca. 50 Anfragen/Tag."),
        Preset("cloudflare", "Cloudflare Workers AI", "https://api.cloudflare.com/client/v4/accounts/{account}/ai/v1/chat/completions",
            listOf("@cf/meta/llama-3.3-70b-instruct-fp8-fast", "@cf/qwen/qwen3-30b-a3b-fp8"), "https://dash.cloudflare.com/profile/api-tokens",
            "Täglich 10.000 „Neurons“ gratis. Braucht API-Token (Workers AI) + Konto-ID.", needsAccount = true),
        Preset("cohere", "Cohere", "https://api.cohere.ai/compatibility/v1/chat/completions",
            listOf("command-a-03-2025", "command-r-plus-08-2024"), "https://dashboard.cohere.com/api-keys", "Test-Schlüssel kostenlos, ca. 1.000 Anfragen/Monat."),
        Preset("huggingface", "Hugging Face", "https://router.huggingface.co/v1/chat/completions",
            listOf("meta-llama/Llama-3.3-70B-Instruct", "Qwen/Qwen2.5-72B-Instruct"), "https://huggingface.co/settings/tokens", "Kleines Gratis-Guthaben jeden Monat."),
        Preset("zai", "Z.ai (GLM)", "https://api.z.ai/api/paas/v4/chat/completions",
            listOf("glm-4.5-flash", "glm-4.7-flash"), "https://z.ai/manage-apikey/apikey-list", "Flash-Modelle kostenlos."),
        Preset("sambanova", "SambaNova", "https://api.sambanova.ai/v1/chat/completions",
            listOf("Meta-Llama-3.3-70B-Instruct", "DeepSeek-V3-0324"), "https://cloud.sambanova.ai/apis", "Sehr schnell, Gratis-Kontingent (Bedingungen ändern sich manchmal)."),
        Preset("openai", "ChatGPT (OpenAI)", "https://api.openai.com/v1/chat/completions",
            listOf("gpt-5-mini", "gpt-4.1-mini", "gpt-4o-mini"), "https://platform.openai.com/api-keys", "Kostet Geld – nur mit Guthaben bei OpenAI."),
        Preset("cerebras", "Cerebras", "https://api.cerebras.ai/v1/chat/completions",
            listOf("gpt-oss-120b", "llama-3.3-70b"), "https://cloud.cerebras.ai", "Extrem schnell; verlangt inzwischen evtl. eine Karte."),
        Preset("pollinations", "Pollinations", "https://text.pollinations.ai/openai",
            listOf("openai", "mistral"), "https://auth.pollinations.ai", "Geht sogar ohne Schlüssel (dann langsamer/begrenzt).", keyOptional = true),
        Preset("custom", "Eigener Anbieter", "", listOf(""), "", "Jeder Dienst mit OpenAI-kompatibler Adresse (…/v1/chat/completions)."),
    )

    fun preset(id: String) = PRESETS.firstOrNull { it.id == id }

    /** Schlüssel säubern (beim Kopieren am Handy rutschen oft Leerzeichen/Zeilenumbrüche mit). */
    fun cleanKey(k: String) = k.trim().filter { !it.isWhitespace() && it.code in 33..126 }

    fun url(template: String, account: String) = template.replace("{account}", account.trim())
}
