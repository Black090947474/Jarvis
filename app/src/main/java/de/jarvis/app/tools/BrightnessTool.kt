package de.jarvis.app.tools

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.json.JSONObject

/** Bildschirmhelligkeit. Android verlangt dafür die Sondererlaubnis „Systemeinstellungen ändern“. */
object BrightnessTool : JarvisTool {
    override val name = "brightness"
    override val group = "device"
    override val feature = "settings"
    override val permissions = listOf("android.permission.WRITE_SETTINGS")

    override val definition: JSONObject = JSONObject("""
{"name":"brightness","description":"Stellt die Bildschirmhelligkeit (0-100 %) ein oder schaltet automatische Helligkeit an/aus.",
 "input_schema":{"type":"object","properties":{"percent":{"type":"integer"},"auto":{"type":"boolean"}},"required":[]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String {
        if (!Settings.System.canWrite(t.ctx)) {
            t.launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + t.ctx.packageName)), "")
            return Res.noPermission("die Helligkeit", "Ich habe die Seite „Systemeinstellungen ändern“ geöffnet – bitte Jarvis dort erlauben und es dann nochmal sagen.")
        }
        val cr = t.ctx.contentResolver
        if (a.has("auto")) {
            val on = a.optBoolean("auto")
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            if (!a.has("percent")) return Res.ok("Automatische Helligkeit ${if (on) "an" else "aus"}.")
        }
        if (!a.has("percent")) return Res.error("Welche Helligkeit (0–100 %)?")
        val pct = a.optInt("percent").coerceIn(0, 100)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (pct * 255 / 100).coerceIn(1, 255))
        return Res.ok("Helligkeit auf $pct % gestellt.")
    }
}
