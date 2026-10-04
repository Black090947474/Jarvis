package de.jarvis.app.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Kleiner lokaler Speicher für Listen (Einkaufsliste, Notizen, Routinen, Vokabeln …). Bleibt nur auf dem Handy. */
class JsonStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("jarvis_extra", Context.MODE_PRIVATE)

    @Synchronized fun list(key: String): MutableList<JSONObject> {
        val a = try { JSONArray(sp.getString(key, "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.toMutableList()
    }

    @Synchronized fun save(key: String, items: List<JSONObject>) {
        val a = JSONArray(); items.forEach { a.put(it) }
        sp.edit().putString(key, a.toString()).apply()
    }

    @Synchronized fun obj(key: String): JSONObject = try { JSONObject(sp.getString(key, "{}")) } catch (_: Exception) { JSONObject() }
    @Synchronized fun saveObj(key: String, o: JSONObject) = sp.edit().putString(key, o.toString()).apply()

    @Synchronized fun newId(): Int { val n = sp.getInt("next_id", 1); sp.edit().putInt("next_id", n + 1).apply(); return n }
}
