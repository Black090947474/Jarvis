package de.jarvis.app

import android.content.Context

/** Speichert die Schlüssel und Einstellungen lokal auf dem Handy. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)



    var mistralKey: String
        get() = sp.getString("mistral_key", "") ?: ""
        set(v) = sp.edit().putString("mistral_key", v.trim()).apply()

    var nvidiaKey: String
        get() = sp.getString("nvidia_key", "") ?: ""
        set(v) = sp.edit().putString("nvidia_key", v.filter { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }).apply()

    var groqKey: String
        get() = sp.getString("groq_key", "") ?: ""
        set(v) = sp.edit().putString("groq_key", v.filter { it.isLetterOrDigit() && it.code < 128 || it == '_' }).apply()

    /** Mindestens ein KI-Schlüssel eingetragen? */
    val hasAnyKey get() = nvidiaKey.isNotBlank() || mistralKey.isNotBlank() || groqKey.isNotBlank() || geminiKey.isNotBlank() || anthropicKey.isNotBlank()

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

    /** Jarvis-Klang: AUS, BUTLER, KI, HOLOGRAMM. Alte „KI-Hall“-Einstellung wird übernommen. */
    var voiceFx: String
        get() = sp.getString("voice_fx", null) ?: if (sp.getBoolean("voice_effect2", false)) "BUTLER" else "KI"
        set(v) = sp.edit().putString("voice_fx", v).apply()

    /** Stärke des Jarvis-Klangs 0..1. */
    var voiceFxStrength: Float
        get() = sp.getFloat("voice_fx_strength", 0.7f)
        set(v) = sp.edit().putFloat("voice_fx_strength", v.coerceIn(0f, 1f)).apply()

    /** Leichter Raumhall für den KI-Sound. */
    var voiceEffect: Boolean
        get() = sp.getBoolean("voice_effect2", false)
        set(v) = sp.edit().putBoolean("voice_effect2", v).apply()

    // ---------- Persönlichkeit & Profil ----------
    var personality: String
        get() = sp.getString("personality", "BUTLER") ?: "BUTLER"
        set(v) = sp.edit().putString("personality", v).apply()
    var humor: Int
        get() = sp.getInt("p_humor", 50)
        set(v) = sp.edit().putInt("p_humor", v.coerceIn(0, 100)).apply()
    var talkative: Int
        get() = sp.getInt("p_talk", 35)
        set(v) = sp.edit().putInt("p_talk", v.coerceIn(0, 100)).apply()
    var formality: Int
        get() = sp.getInt("p_formal", 40)
        set(v) = sp.edit().putInt("p_formal", v.coerceIn(0, 100)).apply()
    var proactivity: Int
        get() = sp.getInt("p_proactive", 50)
        set(v) = sp.edit().putInt("p_proactive", v.coerceIn(0, 100)).apply()
    /** Optionale PIN für wichtige Aktionen (nur in Jarvis, nicht die Handy-PIN). */
    var actionPin: String
        get() = sp.getString("action_pin", "") ?: ""
        set(v) = sp.edit().putString("action_pin", v.filter { it.isDigit() }.take(8)).apply()

    /** Name des Bluetooth-Geräts im Auto (startet den Auto-Modus). */
    var carDevice: String
        get() = sp.getString("car_device", "") ?: ""
        set(v) = sp.edit().putString("car_device", v.trim()).apply()

    var emergencyName: String
        get() = sp.getString("em_name", "") ?: ""
        set(v) = sp.edit().putString("em_name", v.trim()).apply()
    var emergencyNumber: String
        get() = sp.getString("em_number", "") ?: ""
        set(v) = sp.edit().putString("em_number", v.filter { it.isDigit() || it == '+' }).apply()

    // ---------- Personalisierung ----------
    var accent: String
        get() = sp.getString("accent", "AUTO") ?: "AUTO"
        set(v) = sp.edit().putString("accent", v).apply()
    var fontScale: Float
        get() = sp.getFloat("font_scale", 1.0f)
        set(v) = sp.edit().putFloat("font_scale", v.coerceIn(0.8f, 1.3f)).apply()
    var startPage: String
        get() = sp.getString("start_page", "home") ?: "home"
        set(v) = sp.edit().putString("start_page", v).apply()
    var animations: Boolean
        get() = sp.getBoolean("animations", true)
        set(v) = sp.edit().putBoolean("animations", v).apply()

    var profileJson: String
        get() = sp.getString("profile", "{}") ?: "{}"
        set(v) = sp.edit().putString("profile", v).apply()

    /** Farbschema der App: BLAU (Command Center) oder LILA. */
    var uiTheme: String
        get() = sp.getString("ui_theme", "BLAU") ?: "BLAU"
        set(v) = sp.edit().putString("ui_theme", v).apply()

    /** Spracherkennung: GOOGLE (Standard) oder WHISPER (genauer, über Groq). */
    var sttMode: String
        get() = sp.getString("stt_mode", "GOOGLE") ?: "GOOGLE"
        set(v) = sp.edit().putString("stt_mode", v).apply()

    /** Sprachausgabe-Engine (Paketname), leer = Standard des Handys. */
    var ttsEngine: String
        get() = sp.getString("tts_engine", "") ?: ""
        set(v) = sp.edit().putString("tts_engine", v).apply()

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

    /** Weckwort: "JARVIS" (nur „Jarvis“, Standard) oder "HEY" (klassisch „Hey Jarvis“). */
    var wakeMode: String
        get() = sp.getString("wake_mode", "JARVIS") ?: "JARVIS"
        set(v) = sp.edit().putString("wake_mode", v).apply()

    /** Begrüßung beim Nachhausekommen (WLAN/Ort „Zuhause“). */
    var greetOnArrive: Boolean
        get() = sp.getBoolean("greet_arrive", true)
        set(v) = sp.edit().putBoolean("greet_arrive", v).apply()

    /** Ab welcher Sicherheit (0..1) das Weckwort als erkannt gilt. Kleiner = reagiert leichter. */
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
