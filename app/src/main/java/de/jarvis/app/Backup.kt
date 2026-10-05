package de.jarvis.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Sicherung aller Jarvis-Daten in eine JSON-Datei (ohne API-Schlüssel) und Wiederherstellung. */
object Backup {
    private val FILES = listOf("jarvis", "jarvis_agenda", "jarvis_extra", "jarvis_memory", "jarvis_convo")
    private val SECRET = setOf("groq_key", "gemini_key", "anthropic_key", "mistral_key", "nvidia_key", "github_key", "action_pin", "remote_code")

    fun export(ctx: Context): String = try {
        val root = JSONObject().put("app", "jarvis").put("version", 1).put("at", System.currentTimeMillis())
        for (f in FILES) {
            val o = JSONObject()
            for ((k, v) in ctx.getSharedPreferences(f, Context.MODE_PRIVATE).all) {
                if (k in SECRET) continue
                o.put(k, JSONObject().put("t", when (v) { is Int -> "i"; is Long -> "l"; is Float -> "f"; is Boolean -> "b"; is Set<*> -> "s"; else -> "str" })
                    .put("v", if (v is Set<*>) JSONArray(v.toList()) else v))
            }
            root.put(f, o)
        }
        val name = "jarvis-sicherung-${java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.GERMANY).format(java.util.Date())}.json"
        val bytes = root.toString(1).toByteArray()
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Jarvis")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Kein Speicher")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        } else {
            val dir = File(ctx.getExternalFilesDir(null), "Sicherung").apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
        }
        "Sicherung gespeichert: Downloads/Jarvis/$name"
    } catch (e: Exception) { "Sicherung fehlgeschlagen: ${e.message}" }

    fun import(ctx: Context, uri: Uri): String = try {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: throw IllegalStateException("Datei leer")
        val root = JSONObject(text)
        if (root.optString("app") != "jarvis") throw IllegalArgumentException("Das ist keine Jarvis-Sicherung")
        for (f in FILES) {
            val o = root.optJSONObject(f) ?: continue
            val ed = ctx.getSharedPreferences(f, Context.MODE_PRIVATE).edit()
            for (k in o.keys()) {
                if (k in SECRET) continue
                val e = o.getJSONObject(k)
                when (e.optString("t")) {
                    "i" -> ed.putInt(k, e.optInt("v")); "l" -> ed.putLong(k, e.optLong("v"))
                    "f" -> ed.putFloat(k, e.optDouble("v").toFloat()); "b" -> ed.putBoolean(k, e.optBoolean("v"))
                    "s" -> ed.putStringSet(k, e.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet())
                    else -> ed.putString(k, e.optString("v"))
                }
            }
            ed.commit()
        }
        "Sicherung eingespielt."
    } catch (e: Exception) { "Einspielen fehlgeschlagen: ${e.message}" }
}
