package de.jarvis.app

import android.content.Context
import de.jarvis.app.logic.WakeMath
import org.json.JSONArray
import org.json.JSONObject

/** Speichert und lädt dein persönliches „Jarvis“ (nur Zahlen-Merkmale, keine Tonaufnahmen). */
object PersonalWake {
    private const val KEY = "personal_wake"

    class Model(val templates: List<List<FloatArray>>, val mean: FloatArray, val threshold: Float, val good: Boolean, val count: Int) {
        fun score(feats: Collection<FloatArray>): Float {
            if (feats.size < WakeMath.K) return 0f
            val window = feats.toList().takeLast(WakeMath.K)
            return WakeMath.best(window, templates, mean)
        }
    }

    private fun arr(v: FloatArray) = JSONArray().also { a -> v.forEach { a.put(it.toDouble()) } }
    private fun floats(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }

    fun save(ctx: Context, tpls: List<List<FloatArray>>, mean: FloatArray, c: WakeMath.Calib) {
        val o = JSONObject().put("thr", c.threshold.toDouble()).put("good", c.good)
            .put("pos", c.posMin.toDouble()).put("neg", c.negMax.toDouble())
            .put("mean", arr(mean)).put("t", System.currentTimeMillis())
            .put("tpl", JSONArray().also { all -> tpls.forEach { t -> all.put(JSONArray().also { a -> t.forEach { a.put(arr(it)) } }) } })
        ctx.getSharedPreferences("jarvis_wake", Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    fun load(ctx: Context): Model? = try {
        val s = ctx.getSharedPreferences("jarvis_wake", Context.MODE_PRIVATE).getString(KEY, null)
        if (s == null) null else {
            val o = JSONObject(s)
            val t = o.getJSONArray("tpl")
            val tpls = (0 until t.length()).map { i -> val one = t.getJSONArray(i); (0 until one.length()).map { j -> floats(one.getJSONArray(j)) } }
            if (tpls.isEmpty()) null else Model(tpls, floats(o.getJSONArray("mean")), o.getDouble("thr").toFloat(), o.optBoolean("good"), tpls.size)
        }
    } catch (_: Exception) { null }

    fun clear(ctx: Context) = ctx.getSharedPreferences("jarvis_wake", Context.MODE_PRIVATE).edit().remove(KEY).apply()
}
