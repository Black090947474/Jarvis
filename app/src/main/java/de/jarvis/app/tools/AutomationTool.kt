package de.jarvis.app.tools

import de.jarvis.app.Automations
import org.json.JSONArray
import org.json.JSONObject

/** Automationen per Sprache anlegen, auflisten, testen, ein-/ausschalten, löschen. */
object AutomationTool : JarvisTool {
    override val name = "automations"
    override val group = "core"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"automations","description":"WENN-DANN-Automationen. create: trigger + steps speichern. trigger.type: time(hour,minute,days[1-7]), place(name=Zuhause,when=arrive|leave), headphones, bluetooth(device), wifi(ssid), battery(below), charging, event(minutes_before), notification(app,text), app(app). steps = [{tool,args}] mit tool aus: notify{text}, speak{text}, reminders{action:add,text,in_minutes}, play_music{query}, media_control{action}, set_volume{percent}, open_app{name}, set_timer{seconds}, flashlight{on}, brightness{percent}, briefing{}, routines{action:run,name}. Vor create dem Nutzer kurz sagen, was sie tut. test: zeigt nur an, was passieren würde. list, toggle(id,on), delete(id, confirmed).",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["create","list","test","toggle","delete"]},
  "name":{"type":"string"},"trigger":{"type":"object"},"steps":{"type":"array","items":{"type":"object"}},
  "id":{"type":"integer"},"on":{"type":"boolean"},"confirmed":{"type":"boolean"}},"required":["action"]}}
""".trimIndent())

    private val ALLOWED = Automations.ACTIONS.keys

    override fun run(t: ToolContext, a: JSONObject): String {
        val all = Automations.all(t.ctx)
        return when (a.optString("action")) {
            "create", "test" -> {
                val trig = a.optJSONObject("trigger") ?: return Res.error("Kein Auslöser (trigger).")
                if (trig.optString("type") !in Automations.TRIGGERS.keys) return Res.error("Unbekannter Auslöser ${trig.optString("type")}.")
                val steps = JSONArray()
                val raw = a.optJSONArray("steps") ?: return Res.error("Keine Aktionen (steps).")
                for (i in 0 until raw.length()) raw.optJSONObject(i)?.takeIf { it.optString("tool") in ALLOWED }?.let { steps.put(it) }
                if (steps.length() == 0) return Res.error("Keine erlaubten Aktionen. Erlaubt: ${ALLOWED.joinToString()}.")
                val auto = JSONObject().put("name", a.optString("name").ifBlank { Automations.describeTrigger(trig) }).put("trigger", trig).put("steps", steps)
                if (a.optString("action") == "test") return Res.ok("[Testmodus – nichts ausgeführt] " + Automations.describe(auto))
                val saved = Automations.put(t.ctx, auto)
                Res.ok("Automation gespeichert (id ${saved.optInt("id")}): " + Automations.describe(saved))
            }
            "toggle" -> {
                val x = all.firstOrNull { it.optInt("id") == a.optInt("id", -1) } ?: return Res.error("Automation nicht gefunden.")
                Automations.put(t.ctx, x.put("on", a.optBoolean("on", !x.optBoolean("on", true))))
                Res.ok("„${x.optString("name")}“ ist jetzt ${if (x.optBoolean("on")) "an" else "aus"}.")
            }
            "delete" -> {
                val x = all.firstOrNull { it.optInt("id") == a.optInt("id", -1) } ?: return Res.error("Automation nicht gefunden.")
                if (!a.optBoolean("confirmed")) return Res.confirm("Automation „${x.optString("name")}“ löschen.")
                Automations.remove(t.ctx, x.optInt("id")); Res.ok("Gelöscht.")
            }
            else -> if (all.isEmpty()) Res.ok("Keine Automationen.") else Res.ok(all.joinToString("\n") {
                "id ${it.optInt("id")} | ${if (it.optBoolean("on", true)) "an" else "aus"} | ${it.optString("name")}: ${Automations.describe(it)}" })
        }
    }
}
