package de.jarvis.app

import android.content.Context

/** Speichert die Schlüssel und Einstellungen lokal auf dem Handy. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var picovoiceKey: String
        get() = sp.getString("picovoice_key", "") ?: ""
        set(v) = sp.edit().putString("picovoice_key", v.trim()).apply()

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

    var sensitivity: Float
        get() = sp.getFloat("sensitivity", 0.6f)
        set(v) = sp.edit().putFloat("sensitivity", v.coerceIn(0.1f, 0.95f)).apply()

    companion object {
        // Schnell und günstig – ideal für Sprachantworten. Kann in der App geändert werden.
        const val DEFAULT_MODEL = "claude-haiku-4-5-20251001"
    }
}
