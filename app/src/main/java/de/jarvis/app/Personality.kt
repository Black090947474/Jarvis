package de.jarvis.app

import org.json.JSONObject

/** Jarvis' einstellbare Persönlichkeit und das Benutzerprofil (beides nur lokal gespeichert). */
object Personality {
    val PRESETS = linkedMapOf(
        "BUTLER" to "Butler – höflich, elegant, trockener britischer Humor",
        "FREUNDLICH" to "Freundlich – locker, warm, wie ein guter Kumpel",
        "PROFESSIONELL" to "Professionell – sachlich, präzise, effizient",
        "TECHNISCH" to "Technisch – detailliert, nennt Zahlen und Hintergründe",
        "HUMORVOLL" to "Humorvoll – witzig, verspielt, Sprüche erlaubt",
        "MINIMAL" to "Minimalistisch – so kurz wie möglich",
    )

    fun styleText(p: Prefs): String {
        val preset = PRESETS[p.personality]?.substringAfter("– ") ?: PRESETS.getValue("BUTLER").substringAfter("– ")
        fun lvl(v: Int, low: String, mid: String, high: String) = when { v < 34 -> low; v < 67 -> mid; else -> high }
        return "Persönlichkeit: $preset. Humor: ${lvl(p.humor, "kaum", "dezent", "viel")}. " +
            "Gesprächigkeit: ${lvl(p.talkative, "sehr knapp", "normal", "ausführlich")}. " +
            "Ton: ${lvl(p.formality, "locker, duzen", "freundlich, duzen", "förmlich, aber herzlich")}. " +
            "Eigeninitiative: ${lvl(p.proactivity, "nur auf Nachfrage handeln", "passende Vorschläge machen", "aktiv mitdenken und Vorschläge machen (immer mit Rückfrage vor Änderungen)")}."
    }

    val PROFILE_FIELDS = linkedMapOf(
        "name" to "Bevorzugter Name",
        "interests" to "Interessen & Hobbys",
        "music" to "Lieblingsmusik",
        "learning" to "So lerne ich am besten",
        "times" to "Bevorzugte Erinnerungszeiten",
        "apps" to "Häufig genutzte Apps",
        "school" to "Schule / Klasse",
    )

    fun profile(p: Prefs): JSONObject = try { JSONObject(p.profileJson) } catch (_: Exception) { JSONObject() }

    fun profileText(p: Prefs): String {
        val o = profile(p)
        val lines = PROFILE_FIELDS.mapNotNull { (k, label) -> o.optString(k).takeIf { it.isNotBlank() }?.let { "- $label: $it" } }
        return if (lines.isEmpty()) "" else "\nProfil des Nutzers (von ihm selbst eingetragen):\n" + lines.joinToString("\n")
    }
}
