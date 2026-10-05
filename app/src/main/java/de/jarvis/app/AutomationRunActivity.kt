package de.jarvis.app

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Unsichtbarer Ausführer für Automationen: führt die Schritte nacheinander aus und schließt sich wieder. */
class AutomationRunActivity : Activity() {
    private var speaker: Speaker? = null
    private val speakDone = mutableMapOf<String, CountDownLatch>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val a = try { JSONObject(intent.getStringExtra("automation") ?: "{}") } catch (_: Exception) { JSONObject() }
        val steps = a.optJSONArray("steps")
        if (steps == null || steps.length() == 0) { finish(); return }
        Toast.makeText(this, "Jarvis: ${a.optString("name", "Automation")}", Toast.LENGTH_SHORT).show()
        val ready = CountDownLatch(1)
        speaker = Speaker(this, onDone = { id -> id?.let { speakDone.remove(it)?.countDown() } }, onReady = { ready.countDown() })
        val tools = PhoneTools(this)
        thread {
            ready.await(5, TimeUnit.SECONDS)
            val results = mutableListOf<String>()
            for (i in 0 until minOf(steps.length(), 10)) {
                val s = steps.getJSONObject(i)
                val tool = s.optString("tool")
                val args = JSONObject(s.optJSONObject("args")?.toString() ?: "{}").apply { remove("confirmed") }
                val r = when (tool) {
                    "notify" -> { Notifier.post(this, Notifier.CH_REMINDER, 51_000 + i, a.optString("name", "Jarvis"), args.optString("text")); "OK" }
                    "speak" -> say(args.optString("text"))
                    "briefing" -> say(de.jarvis.app.tools.BriefingTool.build(applicationContext))
                    "greet" -> say(SmartHints.greeting(applicationContext))
                    else -> tools.execute(tool, args)
                }
                results += "${Automations.describeStep(s)}: ${r.take(60)}"
            }
            ActionLog.add(this, "automation", "Automation „${a.optString("name")}“", JSONObject().put("text", results.joinToString(" | ").take(200)), "OK", null)
            runOnUiThread { finish() }
        }
    }

    private fun say(text: String): String {
        val sp = speaker ?: return "FEHLER"
        if (text.isBlank()) return "OK"
        val id = "auto${System.nanoTime()}"
        val l = CountDownLatch(1); speakDone[id] = l
        runOnUiThread { sp.speak(text, id) }
        l.await(60, TimeUnit.SECONDS)
        return "OK"
    }

    override fun onDestroy() { speaker?.shutdown(); super.onDestroy() }
}
