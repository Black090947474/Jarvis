package de.jarvis.app.wecker

import android.content.Context
import de.jarvis.app.LocalAi
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Prüft nach „Fertig“, ob ein Satz richtig abgeschrieben wurde.
 * 1. Fast/ganz gleich → sofort richtig. Viel zu viele Fehler → sofort falsch (KI kann nicht ausgetrickst werden).
 * 2. Dazwischen schaut die KI einmal drüber (eigene KI auf dem Handy, sonst die kostenlose Online-KI).
 * 3. Ist keine KI erreichbar, entscheidet eine feste Regel – der Wecker hängt nie an der KI.
 */
object CopyCheck {
    class Result(@JvmField val ok: Boolean, @JvmField val message: String)

    private fun clean(s: String) = Sentences.normalize(s)
        .replace(Regex("[^\\p{L}\\p{N} ]"), "")   // Satzzeichen egal
        .replace(Regex("\\s+"), " ").trim()

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** Wird im Hintergrund-Thread aufgerufen (kann ein paar Sekunden dauern). */
    @JvmStatic
    fun check(ctx: Context, target: String, typed: String): Result {
        val t = clean(target); val y = clean(typed)
        if (y.isEmpty()) return Result(false, "Du hast noch nichts getippt.")
        if (t == y) return Result(true, "Richtig abgeschrieben ✓")
        val errors = distance(t, y)
        val sim = 1.0 - errors.toDouble() / maxOf(t.length, y.length)
        val missing = t.split(' ').size - y.split(' ').size
        if (sim < 0.85 || missing >= 2 || missing <= -2)
            return Result(false, "Das passt noch nicht – $errors Fehler" + (if (missing > 0) ", da fehlen Wörter" else "") + ". Schau nochmal genau hin.")
        if (errors <= 1) return Result(true, "Fast perfekt – ein kleiner Tippfehler ist okay ✓")

        val ai = askAi(ctx, target, typed)
        return when (ai) {
            true -> Result(true, "Die KI hat geprüft: richtig abgeschrieben ✓ (kleine Tippfehler okay)")
            false -> Result(false, "Die KI sagt: noch nicht richtig ($errors Fehler). Vergleich nochmal Wort für Wort.")
            null -> if (sim >= 0.93) Result(true, "Richtig – nur ein paar kleine Tippfehler ✓")
                    else Result(false, "Noch $errors Fehler. Schau nochmal genau hin.")
        }
    }

    private fun prompt(target: String, typed: String) =
        "Ein Wecker prüft, ob jemand einen Satz richtig abgeschrieben hat.\n" +
        "VORLAGE: \"$target\"\nABGETIPPT: \"$typed\"\n" +
        "Groß-/Kleinschreibung, Satzzeichen und 1–3 kleine Tippfehler (vertauschte oder fehlende Buchstaben) sind okay. " +
        "Fehlende, zusätzliche oder falsche Wörter sind NICHT okay. Antworte nur mit JA (richtig) oder NEIN (falsch)."

    private fun parse(answer: String?): Boolean? {
        val a = answer?.trim()?.uppercase(Locale.GERMAN) ?: return null
        val ja = a.indexOf("JA"); val nein = a.indexOf("NEIN")
        return when { nein >= 0 && (ja < 0 || nein < ja) -> false; ja >= 0 -> true; else -> null }
    }

    /** null = gerade keine KI erreichbar. Höchstens ~12 Sekunden, dann gilt die feste Regel. */
    private fun askAi(ctx: Context, target: String, typed: String): Boolean? {
        val p = de.jarvis.app.Prefs(ctx)
        val pool = Executors.newSingleThreadExecutor()
        return try {
            pool.submit(Callable {
                var r: Boolean? = null
                if (LocalAi.installed(ctx)) r = try {
                    parse(LocalAi.generate(ctx, "<|im_start|>user\n" + prompt(target, typed) + "<|im_end|>\n<|im_start|>assistant\n"))
                } catch (_: Throwable) { null }
                if (r == null && p.freeOnline) r = try { parse(online(target, typed)) } catch (_: Exception) { null }
                r
            }).get(12, TimeUnit.SECONDS)
        } catch (_: Exception) { null } finally { pool.shutdownNow() }
    }

    private fun online(target: String, typed: String): String {
        val c = URL("https://text.pollinations.ai/openai").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 6000; c.readTimeout = 9000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("model", "openai").put("max_tokens", 5)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt(target, typed))))
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) return ""
            val txt = c.inputStream.bufferedReader().readText()
            return JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        } finally { c.disconnect() }
    }
}
