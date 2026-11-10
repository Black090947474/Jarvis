package de.jarvis.app.tools

import android.content.Intent
import de.jarvis.app.AutoPilot
import de.jarvis.app.AutoPilotActivity
import org.json.JSONObject
import java.time.LocalDate

/** Bildschirmzeit und Autopilot per Sprache. */
object ScreenTimeTool : JarvisTool {
    override val name = "autopilot"
    override val group = "info"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"autopilot","description":"Autopilot & Bildschirmzeit. screen_time: wie lange welche App heute/gestern genutzt wurde (day heute|gestern). set_limit: Tageslimit für eine App (app, minutes; minutes 0 = Limit weg). toggle: Autopilot-Funktion an/aus (feature: sleep, morning_brief, alarm_plan, evening, school, battery, cleanup, birthdays, breaks, screen_coach, goodnight, fact, weekly; on). set_times: bed und/oder wake (HH:MM). open: Autopilot-Zentrale öffnen.",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["screen_time","set_limit","toggle","set_times","open"]},
  "day":{"type":"string"},"app":{"type":"string"},"minutes":{"type":"integer"},
  "feature":{"type":"string"},"on":{"type":"boolean"},"bed":{"type":"string"},"wake":{"type":"string"}},"required":["action"]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String {
        val ctx = t.ctx
        return when (a.optString("action")) {
            "screen_time" -> {
                val d = if (a.optString("day").contains("gestern")) LocalDate.now().minusDays(1) else LocalDate.now()
                Res.ok(AutoPilot.usageText(ctx, d))
            }
            "set_limit" -> {
                val app = a.optString("app").trim().ifBlank { return Res.error("Für welche App?") }
                val min = a.optInt("minutes", -1); if (min < 0) return Res.error("Wie viele Minuten pro Tag?")
                val map = AutoPilot.limits(ctx).toMutableMap()
                if (min == 0) map.remove(app.lowercase()) else map[app.lowercase()] = min
                AutoPilot.putStr(ctx, "limits", map.entries.joinToString(", ") { "${it.key}=${it.value}" })
                if (!AutoPilot.on(ctx, "screen_coach")) AutoPilot.set(ctx, "screen_coach", true)
                Res.ok(if (min == 0) "Limit für $app entfernt." else "Limit gesetzt: $app höchstens $min Minuten am Tag. Ich sage Bescheid, wenn es erreicht ist.")
            }
            "toggle" -> {
                val f = AutoPilot.FEATURES.firstOrNull { it.key == a.optString("feature") } ?: return Res.error("Welche Funktion?")
                val on = if (a.has("on")) a.optBoolean("on") else !AutoPilot.on(ctx, f.key)
                AutoPilot.set(ctx, f.key, on)
                Res.ok("${f.title.drop(2).trim()} ist jetzt ${if (on) "an" else "aus"}.")
            }
            "set_times" -> {
                val bed = a.optString("bed"); val wake = a.optString("wake")
                if (bed.isBlank() && wake.isBlank()) return Res.error("Welche Uhrzeit?")
                if (bed.isNotBlank()) AutoPilot.putStr(ctx, "bed", AutoPilot.parse(bed, java.time.LocalTime.of(22, 0)).toString().take(5))
                if (wake.isNotBlank()) AutoPilot.putStr(ctx, "wake", AutoPilot.parse(wake, java.time.LocalTime.of(6, 30)).toString().take(5))
                Res.ok("Gespeichert: Schlafenszeit ${AutoPilot.bed(ctx).toString().take(5)}, Aufstehen ${AutoPilot.wake(ctx).toString().take(5)}.")
            }
            "open" -> t.launch(Intent(ctx, AutoPilotActivity::class.java), "Autopilot-Zentrale geöffnet.")
            else -> Res.error("Unbekannte Aktion.")
        }
    }
}

/** Noten-Tracker: Noten speichern, Durchschnitt pro Fach und gesamt, Trend. */
object GradesTool : JarvisTool {
    override val name = "grades"
    override val group = "info"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"grades","description":"Noten-Tracker (Schulnoten 1–6, auch 2+ / 2- / 2,5). add: Note speichern (subject, grade als Text, kind z. B. Klassenarbeit/Test/mündlich, weight Standard 1, Klassenarbeit 2). list: alle Noten (optional subject). average: Durchschnitt pro Fach + gesamt + Trend. delete: Note löschen (id, confirmed).",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["add","list","average","delete"]},
  "subject":{"type":"string"},"grade":{"type":"string"},"kind":{"type":"string"},"weight":{"type":"number"},
  "id":{"type":"integer"},"confirmed":{"type":"boolean"}},"required":["action"]}}
""".trimIndent())

    /** „2-“ → 2.3, „2+“ → 1.7, „2,5“ → 2.5. */
    fun parse(s: String): Double? {
        val t = s.trim().replace(',', '.')
        val m = Regex("^([1-6](?:\\.\\d+)?)\\s*([+-]?)").find(t) ?: return null
        var g = m.groupValues[1].toDouble()
        if (m.groupValues[2] == "+") g -= 0.3 else if (m.groupValues[2] == "-") g += 0.3
        return g.coerceIn(0.7, 6.0)
    }

    private fun f(d: Double) = "%.2f".format(java.util.Locale.GERMANY, d)

    override fun run(t: ToolContext, a: JSONObject): String {
        val store = JsonStore(t.ctx)
        val list = store.list("grades")
        val subj = a.optString("subject").trim()
        return when (a.optString("action")) {
            "add" -> {
                if (subj.isBlank()) return Res.error("Welches Fach?")
                val raw = a.optString("grade"); val g = parse(raw) ?: return Res.error("Welche Note (1–6)?")
                val kind = a.optString("kind").ifBlank { "Note" }
                val w = if (a.has("weight")) a.optDouble("weight", 1.0) else if (kind.contains("arbeit", true) || kind.contains("klausur", true)) 2.0 else 1.0
                val id = store.newId()
                list += JSONObject().put("id", id).put("subject", subj).put("grade", g).put("raw", raw).put("kind", kind).put("w", w).put("t", System.currentTimeMillis())
                store.save("grades", list)
                val s = list.filter { it.optString("subject").equals(subj, true) }
                Res.ok("Gespeichert: $subj $raw ($kind). Schnitt in $subj jetzt ${f(avg(s))}.")
            }
            "list" -> {
                val l = list.filter { subj.isBlank() || it.optString("subject").contains(subj, true) }
                if (l.isEmpty()) Res.ok("Noch keine Noten gespeichert.")
                else Res.ok(l.joinToString("\n") { "id ${it.optInt("id")} | ${it.optString("subject")} | ${it.optString("raw")} | ${it.optString("kind")} | ${de.jarvis.app.logic.TimeLogic.day(it.optLong("t"))}" })
            }
            "average" -> {
                if (list.isEmpty()) return Res.ok("Noch keine Noten gespeichert.")
                val by = list.groupBy { it.optString("subject").replaceFirstChar { c -> c.uppercase() } }
                val lines = by.entries.sortedBy { avg(it.value) }.map { (s, l) ->
                    val sorted = l.sortedBy { it.optLong("t") }
                    val trend = if (sorted.size >= 3) {
                        val recent = avg(sorted.takeLast(2)); val before = avg(sorted.dropLast(2))
                        if (recent < before - 0.25) " ↗ besser" else if (recent > before + 0.25) " ↘ schlechter" else " → stabil"
                    } else ""
                    "• $s: ${f(avg(l))} (${l.size} Noten)$trend"
                }
                val all = by.values.map { avg(it) }.average()
                Res.ok("Deine Durchschnitte:\n" + lines.joinToString("\n") + "\nGesamt (Fächer gleich gewichtet): ${f(all)}" +
                    "\n(Hinweis: Wie deine Schule genau gewichtet, kann abweichen.)")
            }
            "delete" -> {
                val g = list.firstOrNull { it.optInt("id") == a.optInt("id", -1) } ?: return Res.error("Note nicht gefunden.")
                if (!a.optBoolean("confirmed")) return Res.confirm("Note ${g.optString("subject")} ${g.optString("raw")} löschen.")
                store.save("grades", list.filter { it.optInt("id") != g.optInt("id") }); Res.ok("Gelöscht.")
            }
            else -> Res.error("Unbekannte Aktion.")
        }
    }

    private fun avg(l: List<JSONObject>): Double {
        val w = l.sumOf { it.optDouble("w", 1.0) }
        return if (w <= 0) 0.0 else l.sumOf { it.optDouble("grade") * it.optDouble("w", 1.0) } / w
    }
}

/** Bus & Bahn per Sprache: nächste Verbindungen des Schulwegs. */
object TransitTool : JarvisTool {
    override val name = "transit"
    override val group = "info"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"transit","description":"Nächste Bus-/Bahn-Verbindungen für den gespeicherten Schulweg (Start/Ziel aus der Autopilot-Zentrale) mit Verspätungen. from/to optional zum Speichern neuer Haltestellen.",
 "input_schema":{"type":"object","properties":{"from":{"type":"string"},"to":{"type":"string"}},"required":[]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String {
        if (a.optString("from").isNotBlank()) AutoPilot.putStr(t.ctx, "tr_from", a.optString("from").trim())
        if (a.optString("to").isNotBlank()) AutoPilot.putStr(t.ctx, "tr_to", a.optString("to").trim())
        return Res.ok(de.jarvis.app.AutoPilot2.transit(t.ctx))
    }
}

/** Frühere Gespräche durchsuchen und zusammenfassen. */
object HistoryTool : JarvisTool {
    override val name = "history"
    override val group = "core"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"history","description":"Frühere Gespräche mit Jarvis. search: nach Stichworten suchen (query) – z. B. 'Was hatten wir über Mathe besprochen?'. summary: Verlauf der letzten days Tage (Standard 1) holen, um ihn zusammenzufassen.",
 "input_schema":{"type":"object","properties":{"action":{"type":"string","enum":["search","summary"]},"query":{"type":"string"},"days":{"type":"integer"}},"required":["action"]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String {
        val c = de.jarvis.app.Convo(t.ctx)
        fun fmt(l: List<Triple<Long, String, String>>) = l.joinToString("\n") { (at, r, txt) ->
            de.jarvis.app.logic.TimeLogic.short(at) + " " + (if (r == "user") "Du" else "Jarvis") + ": " + txt.take(300) }
        return when (a.optString("action")) {
            "search" -> { val r = c.search(a.optString("query")); if (r.isEmpty()) Res.ok("Dazu habe ich in unseren Gesprächen nichts gefunden.") else Res.ok("Gefunden:\n" + fmt(r)) }
            else -> { val days = a.optInt("days", 1).coerceIn(1, 30)
                val r = c.since(System.currentTimeMillis() - days * 86_400_000L).takeLast(80)
                if (r.isEmpty()) Res.ok("In dem Zeitraum haben wir nicht geredet.") else Res.ok("Verlauf (fasse ihn kurz zusammen):\n" + fmt(r)) }
        }
    }
}

/** Jarvis selbst einstellen: Flüstermodus, Auto-Lautstärke, Offline-Erkennung, Persönlichkeit, Sprechtempo. */
object JarvisSettingsTool : JarvisTool {
    override val name = "jarvis_settings"
    override val group = "core"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"jarvis_settings","description":"Jarvis einstellen. whisper (Flüstermodus, on), auto_volume (on), offline_speech (Spracherkennung offline, on), personality (value: BUTLER, FREUNDLICH, PROFESSIONELL, TECHNISCH, HUMORVOLL, MINIMAL, SARKASTISCH, EXPERTE, DISKUSSION – z. B. 'Expertenmodus', 'Diskussionsmodus', 'sei sarkastischer'), rate (value 0.6–1.6, Sprechtempo).",
 "input_schema":{"type":"object","properties":{"setting":{"type":"string","enum":["whisper","auto_volume","offline_speech","personality","rate"]},"on":{"type":"boolean"},"value":{"type":"string"}},"required":["setting"]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String {
        val p = t.prefs
        val on = a.optBoolean("on", true)
        return when (a.optString("setting")) {
            "whisper" -> { p.whisper = on; Res.ok(if (on) "Flüstermodus an." else "Flüstermodus aus.") }
            "auto_volume" -> { p.autoVolume = on; Res.ok("Automatische Lautstärke ${if (on) "an" else "aus"}.") }
            "offline_speech" -> { p.offlineSpeech = on; Res.ok(if (on) "Ich versuche ab jetzt, dich ohne Internet zu verstehen (dafür muss das deutsche Offline-Sprachpaket installiert sein)." else "Spracherkennung wieder normal.") }
            "personality" -> { val k = a.optString("value").uppercase().trim()
                if (k !in de.jarvis.app.Personality.PRESETS) return Res.error("Welche Persönlichkeit? " + de.jarvis.app.Personality.PRESETS.keys.joinToString())
                p.personality = k; Res.ok("Persönlichkeit jetzt: " + de.jarvis.app.Personality.PRESETS[k]) }
            "rate" -> { val r = a.optString("value").replace(',', '.').toFloatOrNull() ?: return Res.error("Wie schnell (0.6 bis 1.6)?")
                p.voiceRate = r; Res.ok("Sprechtempo $r.") }
            else -> Res.error("Unbekannte Einstellung.")
        }
    }
}


/** Aufsteh-Wecker öffnen (klingelt, bis Foto- und Abschreib-Aufgabe erledigt sind). */
object WeckerTool : JarvisTool {
    override val name = "aufsteh_wecker"
    override val group = "core"
    override val feature: String? = null
    override val permissions = emptyList<String>()
    override val definition: JSONObject = JSONObject("""
{"name":"aufsteh_wecker","description":"Öffnet den Aufsteh-Wecker: ein Wecker, der erst aufhört, wenn man ein Foto vom eingestellten Ort macht und einen Satz abschreibt. Für 'Aufsteh-Wecker', 'Wecker, der mich wirklich aus dem Bett holt', 'Wecker mit Aufgabe'.",
 "input_schema":{"type":"object","properties":{},"required":[]}}
""".trimIndent())
    override fun run(t: ToolContext, a: JSONObject): String =
        t.launch(android.content.Intent(t.ctx, de.jarvis.app.wecker.WeckerActivity::class.java), "Aufsteh-Wecker geöffnet – dort stellst du Uhrzeit, Tage und den Foto-Ort ein.")
}
