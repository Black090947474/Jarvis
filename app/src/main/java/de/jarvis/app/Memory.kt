package de.jarvis.app

import android.content.Context
import org.json.JSONArray
import java.util.Locale

/** Dauerhaftes Gedächtnis: Dinge, die Jarvis sich auf Wunsch merkt. Nur lokal auf dem Handy. */
class Memory(context: Context) {
    private val sp = context.getSharedPreferences("jarvis_memory", Context.MODE_PRIVATE)

    fun all(): List<String> {
        val arr = try { JSONArray(sp.getString("facts", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    fun add(fact: String) {
        val list = all().toMutableList()
        if (list.none { it.equals(fact.trim(), ignoreCase = true) }) list += fact.trim()
        save(list.takeLast(MAX))
    }

    fun removeMatching(query: String): Int {
        val q = query.lowercase(Locale.GERMANY).trim()
        if (q.isEmpty()) return 0
        val list = all()
        val keep = list.filterNot { it.lowercase(Locale.GERMANY).contains(q) }
        save(keep)
        return list.size - keep.size
    }

    fun removeAt(index: Int) {
        val list = all().toMutableList()
        if (index in list.indices) { list.removeAt(index); save(list) }
    }

    fun clear() = save(emptyList())

    private fun save(list: List<String>) {
        sp.edit().putString("facts", JSONArray(list).toString()).apply()
    }

    companion object { const val MAX = 100 }
}

/** Gesprächsverlauf, den Sprechen und Chat teilen – damit Jarvis weiß, worüber ihr zuletzt geredet habt. */
class Convo(context: Context) {
    private val sp = context.getSharedPreferences("jarvis_convo", Context.MODE_PRIVATE)

    private fun load(): JSONArray = try { JSONArray(sp.getString("log", "[]")) } catch (_: Exception) { JSONArray() }

    @Synchronized fun add(role: String, text: String) {
        if (text.isBlank()) return
        val a = load()
        a.put(org.json.JSONObject().put("r", role).put("t", text.take(1200)).put("at", System.currentTimeMillis()))
        val keep = JSONArray()
        for (i in maxOf(0, a.length() - 40) until a.length()) keep.put(a.get(i))
        sp.edit().putString("log", keep.toString()).apply()
    }

    /** Letzte Nachrichten (max. [n]) der letzten [hours] Stunden, als (rolle, text). */
    @Synchronized fun recent(n: Int = 16, hours: Int = 72): List<Pair<String, String>> {
        val a = load(); val since = System.currentTimeMillis() - hours * 3_600_000L
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optLong("at") >= since }
            .takeLast(n).map { it.optString("r") to it.optString("t") }
    }

    fun clear() = sp.edit().remove("log").apply()
}
