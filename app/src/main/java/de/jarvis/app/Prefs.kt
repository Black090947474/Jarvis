package de.jarvis.app

import android.content.Context

/** Speichert die Schlüssel und Einstellungen lokal auf dem Handy. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var geminiKey: String
        get() = sp.getString("gemini_key", "") ?: ""
        set(v) = sp.edit().putString("gemini_key", v.trim()).apply()

    var geminiModel: String
        get() = sp.getString("gemini_model", GeminiClient.DEFAULT_MODEL) ?: GeminiClient.DEFAULT_MODEL
        set(v) = sp.edit().putString("gemini_model", v.trim().ifEmpty { GeminiClient.DEFAULT_MODEL }).apply()

    var anthropicKey: String
        get() = sp.getString("anthropic_key", "") ?: ""
        set(v) = sp.edit().putString("anthropic_key", v.trim()).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()

    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(v) = sp.edit().putString("user_name", v.trim()).apply()

    /** Ob der Wake-Word-Dienst laufen soll (merkt sich den Schalter). */
    var listeningEnabled: Boolean
        get() = sp.getBoolean("listening_enabled", false)
        set(v) = sp.edit().putBoolean("listening_enabled", v).apply()

    /** Ab welcher Sicherheit (0..1) "Hey Jarvis" als erkannt gilt. Kleiner = reagiert leichter. */
    var sensitivity: Float
        get() = sp.getFloat("threshold", 0.5f)
        set(v) = sp.edit().putFloat("threshold", v.coerceIn(0.1f, 0.95f)).apply()

    companion object {
        // Schnell und günstig – ideal für Sprachantworten. Kann in der App geändert werden.
        const val DEFAULT_MODEL = "claude-haiku-4-5-20251001"
    }
}
