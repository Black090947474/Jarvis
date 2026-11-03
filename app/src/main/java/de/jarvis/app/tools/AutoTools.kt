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
