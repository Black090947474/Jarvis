package de.jarvis.app

import android.content.Context

/** Speichert die Schlüssel und Einstellungen lokal auf dem Handy. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)



    var mistralKey: String
        get() = sp.getString("mistral_key", "") ?: ""
        set(v) = sp.edit().putString("mistral_key", v.trim()).apply()

    var groqKey: String
        get() = sp.getString("groq_key", "") ?: ""
        set(v) = sp.edit().putString("groq_key", v.filter { it.isLetterOrDigit() && it.code < 128 || it == '_' }).apply()

    /** Mindestens ein KI-Schlüssel eingetragen? */
    val hasAnyKey get() = mistralKey.isNotBlank() || groqKey.isNotBlank() || geminiKey.isNotBlank() || anthropicKey.isNotBlank()

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

    /** Gewählte Stimme ("" = automatisch eine männliche deutsche Stimme). */
    var voiceName: String
        get() = sp.getString("voice_name", "") ?: ""
        set(v) = sp.edit().putString("voice_name", v).apply()

    /** Tonhöhe: 1.0 = normal, kleiner = tiefer. */
    var voicePitch: Float
        get() = sp.getFloat("voice_pitch2", 1.0f)
        set(v) = sp.edit().putFloat("voice_pitch2", v.coerceIn(0.5f, 1.5f)).apply()

    /** Tempo: 1.0 = normal. */
    var voiceRate: Float
        get() = sp.getFloat("voice_rate2", 1.0f)
        set(v) = sp.edit().putFloat("voice_rate2", v.coerceIn(0.6f, 1.6f)).apply()

    /** Leichter Raumhall für den KI-Sound. */
    var voiceEffect: Boolean
        get() = sp.getBoolean("voice_effect2", false)
        set(v) = sp.edit().putBoolean("voice_effect2", v).apply()

    /** Design des Gesprächsbildschirms: GLUT, AURORA, LINIE oder GLAS. */
    var design: String
        get() = sp.getString("design", "NEXUS") ?: "NEXUS"
        set(v) = sp.edit().putString("design", v).apply()

    // ---------- Zweites Handy ----------

    /** Kopplungscode dieses Handys, wenn es als Station läuft (wird einmal zufällig erzeugt). */
    val stationCode: String
        get() = sp.getString("station_code", null) ?: (100000 + java.security.SecureRandom().nextInt(900000)).toString()
            .also { sp.edit().putString("station_code", it).apply() }

    var remoteHost: String
        get() = sp.getString("remote_host", "") ?: ""
        set(v) = sp.edit().putString("remote_host", v.trim()).apply()

    var remotePort: Int
        get() = sp.getInt("remote_port", StationLink.PORT)
        set(v) = sp.edit().putInt("remote_port", v).apply()

    var remoteCode: String
        get() = sp.getString("remote_code", "") ?: ""
        set(v) = sp.edit().putString("remote_code", v.trim()).apply()

    var remoteName: String
        get() = sp.getString("remote_name", "") ?: ""
        set(v) = sp.edit().putString("remote_name", v.trim()).apply()

    /** Ob der Wake-Word-Dienst laufen soll (merkt sich den Schalter). */
    var listeningEnabled: Boolean
        get() = sp.getBoolean("listening_enabled", false)
        set(v) = sp.edit().putBoolean("listening_enabled", v).apply()

    /** Ab welcher Sicherheit (0..1) "Hey Jarvis" als erkannt gilt. Kleiner = reagiert leichter. */
    var sensitivity: Float
        get() = sp.getFloat("threshold", 0.5f)
        set(v) = sp.edit().putFloat("threshold", v.coerceIn(0.1f, 0.95f)).apply()

    // ---------- Assistent: Kalender, Erinnerungen, Hinweise ----------

    /** Puffer in Minuten zwischen Terminen (für freie Zeiten). */
    var calendarBuffer: Int
        get() = sp.getInt("cal_buffer", 15)
        set(v) = sp.edit().putInt("cal_buffer", v.coerceIn(0, 120)).apply()

    /** Funktionsschalter im Berechtigungszentrum (Standard: an). */
    fun feature(key: String) = sp.getBoolean("feat_$key", true)
    fun setFeature(key: String, on: Boolean) = sp.edit().putBoolean("feat_$key", on).apply()

    /** Proaktive Hinweise (Termine, Konflikte, Tagesüberblick). Standard aus – kein Spam. */
    var proactive: Boolean
        get() = sp.getBoolean("proactive", false)
        set(v) = sp.edit().putBoolean("proactive", v).apply()

    var notifyEvents: Boolean
        get() = sp.getBoolean("n_events", true)
        set(v) = sp.edit().putBoolean("n_events", v).apply()
    var notifyConflicts: Boolean
        get() = sp.getBoolean("n_conflicts", true)
        set(v) = sp.edit().putBoolean("n_conflicts", v).apply()
    var notifyBriefing: Boolean
        get() = sp.getBoolean("n_briefing", true)
        set(v) = sp.edit().putBoolean("n_briefing", v).apply()

    /** Uhrzeit des Tagesüberblicks, "HH:MM". */
    var briefingTime: String
        get() = sp.getString("briefing_time", "07:00") ?: "07:00"
        set(v) = sp.edit().putString("briefing_time", v).apply()

    /** Wie viele Minuten vor einem Termin erinnert wird. */
    var eventLead: Int
        get() = sp.getInt("event_lead", 30)
        set(v) = sp.edit().putInt("event_lead", v.coerceIn(0, 240)).apply()

    /** Zusätzliche Wegzeit (Minuten) bei Terminen mit Ort. */
    var travelBuffer: Int
        get() = sp.getInt("travel_buffer", 20)
        set(v) = sp.edit().putInt("travel_buffer", v.coerceIn(0, 240)).apply()

    // Zuhause (für „wenn ich zu Hause ankomme“)
    val homeSet get() = sp.contains("home_lat")
    val homeLat get() = sp.getFloat("home_lat", 0f).toDouble()
    val homeLon get() = sp.getFloat("home_lon", 0f).toDouble()
    fun setHome(lat: Double, lon: Double) = sp.edit().putFloat("home_lat", lat.toFloat()).putFloat("home_lon", lon.toFloat()).apply()

    // Wetter-Cache (für Dashboard und offline)
    var weatherCache: String
        get() = sp.getString("weather_cache", "") ?: ""
        set(v) = sp.edit().putString("weather_cache", v).apply()
    var weatherTime: Long
        get() = sp.getLong("weather_time", 0)
        set(v) = sp.edit().putLong("weather_time", v).apply()

    // Schon gemeldete Hinweise (damit nichts doppelt kommt). Format "key|zeit".
    @Synchronized fun wasNotified(key: String) = notified().any { it.substringBefore('|') == key }
    @Synchronized fun markNotified(key: String) =
        sp.edit().putStringSet("notified", notified() + "$key|${System.currentTimeMillis()}").apply()
    @Synchronized fun pruneNotified(now: Long) {
        val keep = notified().filter { (it.substringAfterLast('|').toLongOrNull() ?: 0) > now - 8 * 86_400_000L }.toSet()
        sp.edit().putStringSet("notified", keep).apply()
    }
    private fun notified(): Set<String> = HashSet(sp.getStringSet("notified", emptySet()) ?: emptySet())

    companion object {
        // Schnell und günstig – ideal für Sprachantworten. Kann in der App geändert werden.
        const val DEFAULT_MODEL = "claude-haiku-4-5-20251001"
    }
}
