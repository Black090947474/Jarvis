package de.jarvis.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Aktionsverlauf: was Jarvis wann getan hat, mit welcher Berechtigung und ob bestätigt wurde. Nur lokal. */
object ActionLog {
    private const val MAX = 300

    data class Entry(val at: Long, val tool: String, val label: String, val detail: String, val status: String,
                     val confirmed: Boolean, val feature: String?)

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("jarvis_log", Context.MODE_PRIVATE)

    @Synchronized fun add(ctx: Context, tool: String, label: String, input: JSONObject, result: String, feature: String?) {
        if (tool == "show_card") return
        val status = when {
            result.startsWith("OK") -> "OK"
            result.startsWith("BESTÄTIGUNG") -> "Rückfrage"
            result.startsWith("BERECHTIGUNG") -> "Berechtigung fehlt"
            result.startsWith("FEHLER") || result.startsWith("Fehler") -> "Fehler"
            else -> "OK"
        }
        val args = JSONObject(input.toString()).apply { remove("html"); remove("confirmed") }
        val detail = listOf("app", "name", "who", "query", "title", "text", "action", "what", "range", "destination")
            .mapNotNull { k -> args.optString(k).takeIf { it.isNotBlank() }?.let { "$k: ${it.take(60)}" } }.joinToString(", ")
        val a = load(ctx)
        a.put(JSONObject().put("at", System.currentTimeMillis()).put("tool", tool).put("label", label.removeSuffix(" …"))
            .put("detail", detail).put("status", status).put("confirmed", input.optBoolean("confirmed")).put("feature", feature ?: ""))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - MAX) until a.length()) keep.put(a.get(i))
        sp(ctx).edit().putString("log", keep.toString()).apply()
    }

    private fun load(ctx: Context) = try { JSONArray(sp(ctx).getString("log", "[]")) } catch (_: Exception) { JSONArray() }

    fun entries(ctx: Context): List<Entry> {
        val a = load(ctx)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Entry(it.optLong("at"), it.optString("tool"), it.optString("label"), it.optString("detail"), it.optString("status"),
                it.optBoolean("confirmed"), it.optString("feature").ifBlank { null })
        }.reversed()
    }

    fun clear(ctx: Context) = sp(ctx).edit().remove("log").apply()
}
