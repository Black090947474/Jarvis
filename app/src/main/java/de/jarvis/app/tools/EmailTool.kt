package de.jarvis.app.tools

import android.content.Intent
import android.net.Uri
import de.jarvis.app.JarvisNotificationListener
import org.json.JSONObject

/**
 * E-Mails. Ehrliche Grenze: Ohne Anmeldung beim Mail-Anbieter (OAuth) kann keine App dein Postfach lesen.
 * Jarvis sieht nur, was als Benachrichtigung von Gmail/Outlook/GMX usw. ankommt, und schreibt Entwürfe,
 * die du in deiner Mail-App selbst absendest.
 */
object EmailTool : JarvisTool {
    override val name = "email"
    override val group = "comm"
    override val feature = "notifications_read"
    override val permissions = emptyList<String>()

    private val MAIL_APPS = listOf("gmail", "outlook", "mail", "gmx", "web.de", "yahoo", "proton", "thunderbird", "spark", "email")

    override val definition: JSONObject = JSONObject("""
{"name":"email","description":"E-Mails. recent: neue Mails aus den Benachrichtigungen der Mail-Apps (Absender, Betreff, Vorschau) – zum Zusammenfassen/Suchen. draft: Entwurf in der Mail-App öffnen; der Nutzer sendet selbst. Vor draft den Text zeigen und fragen. Den ganzen Posteingang liest read_app mit app=mail.",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["recent","draft"]},
  "query":{"type":"string","description":"recent: optionales Suchwort (Absender/Betreff)"},
  "to":{"type":"string"},"subject":{"type":"string"},"body":{"type":"string"},
  "confirmed":{"type":"boolean"}},"required":["action"]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String = when (a.optString("action")) {
        "recent" -> recent(a.optString("query"))
        "draft" -> draft(t, a)
        else -> Res.error("Unbekannte Aktion.")
    }

    private fun recent(q: String): String {
        if (!JarvisNotificationListener.isConnected)
            return Res.noPermission("deine E-Mail-Benachrichtigungen", "Bitte erlaube „Benachrichtigungen lesen“ im Jarvis-Berechtigungszentrum.")
        val mails = JarvisNotificationListener.recent(null, 40)
            .filter { m -> MAIL_APPS.any { m.app.lowercase().contains(it) || m.pkg.lowercase().contains(it) } }
            .filter { q.isBlank() || it.title.contains(q, true) || it.text.contains(q, true) }
        if (mails.isEmpty()) return Res.ok("Keine neuen E-Mail-Benachrichtigungen" + (if (q.isNotBlank()) " zu „$q“" else "") +
            ". (Ich sehe nur Mails, die seit dem Start von Jarvis als Benachrichtigung gekommen sind.)")
        val now = System.currentTimeMillis()
        return Res.ok("${mails.size} Mail-Benachrichtigung(en):\n" + mails.take(15).joinToString("\n") {
            val min = ((now - it.time) / 60000).coerceAtLeast(0)
            "${it.app} | ${it.title} | ${it.text.take(300)} | vor ${if (min < 60) "$min Min." else "${min / 60} Std."}"
        })
    }

    private fun draft(t: ToolContext, a: JSONObject): String {
        val to = a.optString("to"); val subject = a.optString("subject"); val body = a.optString("body")
        if (body.isBlank() && subject.isBlank()) return Res.error("Was soll in der Mail stehen?")
        if (!a.optBoolean("confirmed"))
            return Res.confirm("Mail-Entwurf an ${to.ifBlank { "(Empfänger fehlt)" }}, Betreff „$subject“: „$body“ in deiner Mail-App öffnen (du tippst dort selbst auf Senden).")
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to)))
            .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body)
        if (to.isNotBlank()) i.putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
        return t.launch(i, "Entwurf ist in der Mail-App offen. Prüfen und selbst auf Senden tippen – ich sende nichts heimlich.")
    }
}
