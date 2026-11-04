package de.jarvis.app.tools

import de.jarvis.app.JarvisCall
import de.jarvis.app.logic.TimeLogic
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** „Ruf mich um 7 an und erinner mich an …“ – Jarvis klingelt mit Anruf-Bildschirm. */
object CallMeTool : JarvisTool {
    override val name = "jarvis_call"
    override val group = "core"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"jarvis_call","description":"Jarvis ruft den Nutzer an (Anruf-Bildschirm mit Klingelton, klingelt auch bei lautlos; nach dem Annehmen liest Jarvis text vor). Für wichtige Erinnerungen, Weck-Anrufe oder wenn der Nutzer sagt 'ruf mich an'. schedule: at = HH:MM (heute, sonst morgen) oder YYYY-MM-DDTHH:MM, oder minutes = in X Minuten; text = worum es geht. now: sofort anrufen (z. B. in Automationen). list, cancel (id).",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["schedule","now","list","cancel"]},
  "at":{"type":"string"},"minutes":{"type":"integer"},"text":{"type":"string"},"id":{"type":"integer"}},"required":["action"]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String {
        val ctx = t.ctx
        val text = a.optString("text").trim().ifBlank { "Du wolltest, dass ich dich anrufe." }
        return when (a.optString("action")) {
            "now" -> { JarvisCall.ring(ctx, text); Res.ok("Ich rufe dich jetzt an.") }
            "schedule" -> {
                val at: Long = when {
                    a.optInt("minutes", 0) > 0 -> System.currentTimeMillis() + a.optInt("minutes") * 60_000L
                    else -> {
                        val s = a.optString("at").trim()
                        val hm = Regex("^(\\d{1,2})[:.](\\d{2})$").find(s)
                        if (hm != null) {
                            val lt = LocalTime.of(hm.groupValues[1].toInt().coerceIn(0, 23), hm.groupValues[2].toInt().coerceIn(0, 59))
                            var d = LocalDateTime.of(LocalDate.now(), lt); if (!d.isAfter(LocalDateTime.now())) d = d.plusDays(1)
                            d.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        } else TimeLogic.parseLocal(s) ?: return Res.error("Wann soll ich anrufen?")
                    }
                }
                if (at < System.currentTimeMillis()) return Res.error("Der Zeitpunkt ist schon vorbei.")
                val id = JarvisCall.schedule(ctx, at, text)
                var warn = ""
                if (!JarvisCall.canFullScreen(ctx) && !android.provider.Settings.canDrawOverlays(ctx))
                    warn = " Tipp: Damit der Anruf-Bildschirm auch bei gesperrtem Handy erscheint, in den Einstellungen „Vollbild-Benachrichtigungen“ für Jarvis erlauben (Autopilot-Zentrale)."
                Res.ok("Ich rufe dich ${TimeLogic.short(at)} an (id $id): „$text“.$warn")
            }
            "list" -> {
                val l = JarvisCall.list(ctx)
                if (l.isEmpty()) Res.ok("Keine Anrufe geplant.") else Res.ok(l.joinToString("\n") { "id ${it.optInt("id")}: ${TimeLogic.short(it.optLong("at"))} – ${it.optString("text")}" })
            }
            "cancel" -> if (JarvisCall.cancel(ctx, a.optInt("id", -1))) Res.ok("Anruf gestrichen.") else Res.error("Diesen Anruf finde ich nicht.")
            else -> Res.error("Unbekannte Aktion.")
        }
    }
}
