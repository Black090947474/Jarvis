package de.jarvis.app.tools

import de.jarvis.app.JarvisAccessibility
import org.json.JSONObject

/** Nachricht in einer beliebigen Chat-App senden – über die Bildschirmsteuerung, immer erst nach dem Ja des Nutzers. */
object AppMessageTool : JarvisTool {
    override val name = "app_message"
    override val group = "comm"
    override val feature = "screen"
    override val permissions = emptyList<String>()

    val APPS = linkedMapOf(
        "snapchat" to ("com.snapchat.android" to "Snapchat"),
        "instagram" to ("com.instagram.android" to "Instagram"),
        "whatsapp" to ("com.whatsapp" to "WhatsApp"),
        "telegram" to ("org.telegram.messenger" to "Telegram"),
        "discord" to ("com.discord" to "Discord"),
        "messenger" to ("com.facebook.orca" to "Messenger"),
        "signal" to ("org.thoughtcrime.securesms" to "Signal"),
        "tiktok" to ("com.zhiliaoapp.musically" to "TikTok"),
        "sms" to ("com.samsung.android.messaging" to "Nachrichten"),
    )

    override val definition: JSONObject = JSONObject("""
{"name":"app_message","description":"Schickt eine Nachricht in einer Chat-App, indem Jarvis die App selbst bedient (öffnen, Person suchen, Chat öffnen, schreiben, senden). app: snapchat, instagram, whatsapp, telegram, discord, messenger, signal, tiktok, sms. who = Name wie in der App, text = Nachricht. Erst ohne confirmed aufrufen → Nutzer fragen → nach Ja mit confirmed=true.",
 "input_schema":{"type":"object","properties":{"app":{"type":"string"},"who":{"type":"string"},"text":{"type":"string"},"confirmed":{"type":"boolean"}},"required":["app","who","text"]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String {
        val key = a.optString("app").lowercase().replace(Regex("[^a-z]"), "").let { if (it == "snap") "snapchat" else if (it == "insta") "instagram" else it }
        val (pkg, label) = APPS[key] ?: return Res.error("Diese App kenne ich noch nicht. Möglich: " + APPS.values.joinToString { it.second })
        val who = a.optString("who").trim().ifBlank { return Res.error("An wen?") }
        val text = a.optString("text").trim().ifBlank { return Res.error("Was soll ich schreiben?") }
        val acc = JarvisAccessibility.instance
            ?: return Res.noPermission("andere Apps", "Bitte „Bildschirmsteuerung“ einschalten (Profi-Modus → Auto-Einrichtung oder Berechtigungen).")
        if (!a.optBoolean("confirmed")) return Res.confirm("Auf $label an $who senden: „$text“.")
        val r = acc.chatSend(pkg, who, text) { }
        return if (r.startsWith("OK")) Res.ok(r.removePrefix("OK: ")) else Res.error(r.removePrefix("Fehler: "))
    }
}
