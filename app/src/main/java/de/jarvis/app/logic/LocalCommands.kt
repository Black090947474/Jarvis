package de.jarvis.app.logic

import java.util.Locale

/**
 * Einfache Befehle, die Jarvis auch OHNE Internet versteht (kein KI-Aufruf nötig).
 * Ergebnis ist ein Werkzeugname + Parameter, den PhoneTools direkt ausführen kann.
 */
object LocalCommands {

    data class Cmd(val tool: String, val args: Map<String, Any>)

    private val NUM = mapOf(
        "ein" to 1, "eine" to 1, "einen" to 1, "eins" to 1, "zwei" to 2, "drei" to 3, "vier" to 4, "fünf" to 5,
        "sechs" to 6, "sieben" to 7, "acht" to 8, "neun" to 9, "zehn" to 10, "elf" to 11, "zwölf" to 12,
        "fünfzehn" to 15, "zwanzig" to 20, "dreißig" to 30, "halbe" to 30, "vierzig" to 40, "fünfundvierzig" to 45,
    )

    private fun num(s: String): Int? = s.toIntOrNull() ?: NUM[s]

    fun parse(input: String): Cmd? {
        val t = input.lowercase(Locale.GERMANY).replace(Regex("[!?.,]"), " ").replace(Regex("\\s+"), " ").trim()
            .removePrefix("hey ").removePrefix("jarvis ").trim()

        // Taschenlampe
        if (t.contains("taschenlampe") || t.contains("licht an") || t.contains("licht aus")) {
            val off = Regex("\\b(aus|ausschalten|ausmachen)\\b").containsMatchIn(t)
            return Cmd("flashlight", mapOf("on" to !off))
        }

        // Timer: "timer 10 minuten", "stell einen timer auf fünf minuten", "eine halbe stunde timer"
        if (t.contains("timer")) {
            var sec = 0
            Regex("(\\d+|[a-zäöüß]+) ?(stunden|stunde|std|minuten|minute|min|sekunden|sekunde|sek)").findAll(t).forEach { m ->
                val n = num(m.groupValues[1]) ?: return@forEach
                sec += when {
                    m.groupValues[2].startsWith("st") -> n * 3600
                    m.groupValues[2].startsWith("m") -> n * 60
                    else -> n
                }
            }
            if (t.contains("halbe stunde") && sec == 0) sec = 1800
            if (sec > 0) return Cmd("set_timer", mapOf("seconds" to sec))
        }

        // Wecker: "wecker um 7", "weck mich um 6:30", "wecker auf halb acht"
        if (t.contains("wecker") || t.startsWith("weck mich")) {
            parseClock(t)?.let { (h, m) -> return Cmd("set_alarm", mapOf("hour" to h, "minute" to m)) }
        }

        // Erinnerung in X Minuten: "erinnere mich in 20 minuten an die wäsche"
        Regex("erinner\\w* mich in (\\d+|[a-zäöüß]+) ?(minuten|minute|stunden|stunde) (an |daran |dass )?(.+)").find(t)?.let { m ->
            val n = num(m.groupValues[1]) ?: return@let
            val min = if (m.groupValues[2].startsWith("st")) n * 60 else n
            return Cmd("reminders", mapOf("action" to "add", "in_minutes" to min, "text" to cleanText(m.groupValues[4])))
        }
        // Erinnerung um Uhrzeit: "erinnere mich um 18 uhr an mathe"
        Regex("erinner\\w* mich (heute |morgen )?um (.+?) (an |daran |dass )(.+)").find(t)?.let { m ->
            val clock = parseClock("um " + m.groupValues[2]) ?: return@let
            return Cmd("reminders", mapOf("action" to "add", "hour" to clock.first, "minute" to clock.second,
                "tomorrow" to (m.groupValues[1].trim() == "morgen"), "text" to cleanText(m.groupValues[4])))
        }

        // Agenda
        if (Regex("was steht (heute|morgen)? ?an|was hab(e)? ich (heute|morgen)|meine termine|tagesplan").containsMatchIn(t)) {
            return Cmd("agenda", mapOf("range" to if (t.contains("morgen")) "morgen" else "heute"))
        }

        if (t.contains("akku")) return Cmd("battery", emptyMap())
        return null
    }

    private fun cleanText(s: String) = s.trim().removeSuffix(" zu").trim()

    /** "um 7", "um 6:30", "um 18 uhr 15", "auf halb acht", "um viertel nach sieben" → (Stunde, Minute). */
    fun parseClock(t: String): Pair<Int, Int>? {
        Regex("(\\d{1,2})[:.](\\d{2})").find(t)?.let {
            val h = it.groupValues[1].toInt(); val m = it.groupValues[2].toInt()
            if (h in 0..23 && m in 0..59) return h to m
        }
        Regex("(\\d{1,2}) uhr( (\\d{1,2}))?").find(t)?.let {
            val h = it.groupValues[1].toInt(); val m = it.groupValues[3].toIntOrNull() ?: 0
            if (h in 0..23 && m in 0..59) return h to m
        }
        Regex("halb (\\d{1,2}|[a-zäöüß]+)").find(t)?.let {
            val h = num(it.groupValues[1]) ?: return@let
            return ((h - 1 + 24) % 24) to 30
        }
        Regex("viertel nach (\\d{1,2}|[a-zäöüß]+)").find(t)?.let { val h = num(it.groupValues[1]) ?: return@let; return h % 24 to 15 }
        Regex("viertel vor (\\d{1,2}|[a-zäöüß]+)").find(t)?.let { val h = num(it.groupValues[1]) ?: return@let; return ((h - 1 + 24) % 24) to 45 }
        Regex("(um|auf) (\\d{1,2}|[a-zäöüß]+)\\b").find(t)?.let {
            val h = num(it.groupValues[2]) ?: return@let
            if (h in 0..23) return h to 0
        }
        return null
    }
}
