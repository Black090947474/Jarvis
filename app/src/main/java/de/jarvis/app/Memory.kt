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
