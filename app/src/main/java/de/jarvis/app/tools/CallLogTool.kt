package de.jarvis.app.tools

import android.Manifest
import android.provider.CallLog
import de.jarvis.app.logic.TimeLogic
import org.json.JSONObject

/** „Wer hat mich zuletzt angerufen?“ – liest die Anrufliste (nur lesen). */
object CallLogTool : JarvisTool {
    override val name = "call_log"
    override val group = "comm"
    override val feature = "calls"
    override val permissions = listOf(Manifest.permission.READ_CALL_LOG)

    override val definition: JSONObject = JSONObject("""
{"name":"call_log","description":"Liest die letzten Anrufe (wer, wann, angenommen/verpasst/ausgehend). Zum Zurückrufen danach das Werkzeug call nutzen.",
 "input_schema":{"type":"object","properties":{
  "type":{"type":"string","enum":["alle","verpasst","eingehend","ausgehend"]},
  "limit":{"type":"integer"}},"required":[]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String {
        if (!t.has(Manifest.permission.READ_CALL_LOG))
            return Res.noPermission("die Anrufliste", "Bitte erlaube „Anrufliste“ im Jarvis-Berechtigungszentrum.")
        val type = a.optString("type", "alle")
        val limit = a.optInt("limit", 5).coerceIn(1, 20)
        val sel = when (type) {
            "verpasst" -> "${CallLog.Calls.TYPE} IN (${CallLog.Calls.MISSED_TYPE}, ${CallLog.Calls.REJECTED_TYPE})"
            "eingehend" -> "${CallLog.Calls.TYPE} = ${CallLog.Calls.INCOMING_TYPE}"
            "ausgehend" -> "${CallLog.Calls.TYPE} = ${CallLog.Calls.OUTGOING_TYPE}"
            else -> null
        }
        val rows = mutableListOf<String>()
        try {
            t.ctx.contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                sel, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
                while (c.moveToNext() && rows.size < limit) {
                    val who = c.getString(0)?.takeIf { it.isNotBlank() } ?: c.getString(1)?.takeIf { it.isNotBlank() } ?: "Unbekannte Nummer"
                    val kind = when (c.getInt(2)) {
                        CallLog.Calls.INCOMING_TYPE -> "angenommen"
                        CallLog.Calls.OUTGOING_TYPE -> "ausgehend"
                        CallLog.Calls.MISSED_TYPE -> "verpasst"
                        CallLog.Calls.REJECTED_TYPE -> "abgelehnt"
                        else -> "Anruf"
                    }
                    val dur = c.getLong(4)
                    rows += "$who | $kind | ${TimeLogic.short(c.getLong(3))}" + (if (dur > 0) " | ${dur / 60} Min. ${dur % 60} Sek." else "")
                }
            }
        } catch (_: SecurityException) {
            return Res.noPermission("die Anrufliste", "Bitte erlaube „Anrufliste“ im Jarvis-Berechtigungszentrum.")
        }
        return if (rows.isEmpty()) Res.ok("Keine Anrufe gefunden ($type).") else Res.ok("Letzte Anrufe ($type):\n" + rows.joinToString("\n"))
    }
}
