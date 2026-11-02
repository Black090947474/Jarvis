package de.jarvis.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import de.jarvis.app.Ui.Companion.AMBER
import de.jarvis.app.Ui.Companion.BG
import de.jarvis.app.Ui.Companion.GREEN
import de.jarvis.app.Ui.Companion.MUTED
import de.jarvis.app.Ui.Companion.RED
import de.jarvis.app.Ui.Companion.SOFT
import de.jarvis.app.logic.ProviderCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Viele KI-Anbieter auf einmal: je ein Gratis-Konto pro Anbieter, Jarvis wechselt automatisch bei vollem Limit. */
class ProvidersActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private val status = HashMap<Int, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this); Ui.load(prefs); ui = Ui(this)
        window.statusBarColor = BG; window.navigationBarColor = BG; Ui.lightBars(window)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(18), ui.px(22), ui.px(18), ui.px(40)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(col) })
        build()
    }

    /** Für welchen Anbieter du gerade „Schlüssel holen“ getippt hast (dann erkennen wir auch Schlüssel ohne typischen Anfang). */
    private var waitingFor: String? = null
    private var lastOffered = ""

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val clip = KeySniffer.clipboard(this) ?: return
        val hit = KeySniffer.detect(clip, waitingFor) ?: return
        if (hit.key == lastOffered || KeySniffer.known(prefs, hit)) return
        lastOffered = hit.key
        AlertDialog.Builder(this).setTitle("Schlüssel erkannt 🔑")
            .setMessage("In der Zwischenablage liegt ein Schlüssel für ${hit.name} (${hit.key.take(8)}…). Eintragen und testen?")
            .setPositiveButton("Eintragen") { _, _ -> adopt(hit) }
            .setNegativeButton("Nein", null).show()
    }

    private fun adopt(h: KeySniffer.Hit) {
        waitingFor = null
        when (h.target) {
            "groq" -> prefs.groqKey = h.key
            "nvidia" -> prefs.nvidiaKey = h.key
            "gemini" -> prefs.geminiKey = h.key
            "claude" -> prefs.anthropicKey = h.key
            "mistral" -> prefs.mistralKey = h.key
            else -> {
                val pr = ProviderCatalog.preset(h.target) ?: return
                if (pr.needsAccount) { edit(-1, pr, h.key); return }   // Konto-ID muss noch rein
                val l = prefs.extraList().toMutableList()
                val existing = l.indexOfFirst { it.optString("id") == pr.id }
                val o = JSONObject().put("id", pr.id).put("name", pr.name).put("url", pr.url).put("key", h.key)
                    .put("model", pr.models.joinToString(", ")).put("on", true)
                if (existing >= 0) l[existing] = o else l += o
                save(l); build(); test(if (existing >= 0) existing else l.size - 1); return
            }
        }
        toast("${h.name}-Schlüssel eingetragen."); build()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    private fun save(list: List<JSONObject>) { prefs.extraProviders = JSONArray(list).toString() }

    private fun build() {
        col.removeAllViews()
        col.addView(ui.row().apply {
            addView(ui.text("‹", 30f).apply { setPadding(0, 0, ui.px(16), 0); setOnClickListener { finish() } })
            addView(LinearLayout(this@ProvidersActivity).apply { orientation = LinearLayout.VERTICAL
                addView(ui.text("KI-Anbieter", 21f, Ui.FG, true))
                addView(ui.text("JE MEHR, DESTO SELTENER PAUSEN", 11f, RED).apply { letterSpacing = 0.18f; typeface = Typeface.MONOSPACE }) })
        })
        val list = prefs.extraList()
        val builtin = listOf("NVIDIA" to prefs.nvidiaKey, "Groq" to prefs.groqKey, "Mistral" to prefs.mistralKey, "Gemini" to prefs.geminiKey, "Claude" to prefs.anthropicKey)
            .filter { it.second.isNotBlank() }.map { it.first }
        val active = builtin.size + list.count { it.optBoolean("on", true) }
        col.addView(ui.text("$active Anbieter aktiv. Ist bei einem das Gratis-Limit voll, nimmt Jarvis sofort den nächsten. " +
            "Pro Anbieter ein eigenes Konto (mehrere Konten beim selben Anbieter verbieten deren Regeln).", 13f, SOFT).apply { setPadding(0, ui.px(10), 0, 0) })
        if (builtin.isNotEmpty()) col.addView(ui.text("In den Einstellungen eingetragen: " + builtin.joinToString(", "), 12f, MUTED).apply { setPadding(0, ui.px(6), 0, 0) })

        col.addView(ui.section("Zusätzliche Anbieter"))
        if (list.isEmpty()) col.addView(ui.text("Noch keine. Tippe unten auf „Anbieter hinzufügen“.", 13f, MUTED))
        list.forEachIndexed { i, p ->
            col.addView(ui.card().apply {
                addView(ui.row().apply {
                    addView(ui.weighted(LinearLayout(this@ProvidersActivity).apply { orientation = LinearLayout.VERTICAL
                        addView(ui.text(p.optString("name"), 16f, Ui.FG, true))
                        addView(ui.text(p.optString("model").substringBefore(','), 12f, MUTED).apply { maxLines = 1 }) }))
                    addView(Switch(this@ProvidersActivity).apply { isChecked = p.optBoolean("on", true)
                        setOnCheckedChangeListener { _, on -> val l = prefs.extraList().toMutableList(); l[i].put("on", on); save(l) } })
                })
                status[i]?.let { addView(ui.text(it, 12f, if (it.startsWith("✓")) GREEN else AMBER).apply { setPadding(0, ui.px(6), 0, 0) }) }
                addView(ui.row().apply {
                    addView(ui.weighted(ui.button("Testen", false) { test(i) }))
                    addView(ui.weighted(ui.button("Bearbeiten", false) { edit(i, null) }).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = ui.px(8) })
                    addView(ui.weighted(ui.button("Entfernen", false) {
                        AlertDialog.Builder(this@ProvidersActivity).setMessage("${p.optString("name")} entfernen?").setPositiveButton("Entfernen") { _, _ ->
                            val l = prefs.extraList().toMutableList(); l.removeAt(i); save(l); status.clear(); build() }.setNegativeButton("Abbrechen", null).show()
                    }).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = ui.px(8) })
                })
            })
        }
        col.addView(ui.button("Anbieter hinzufügen") { pick() })
        val missingBuiltin = listOf(Triple("Groq", prefs.groqKey, "https://console.groq.com/keys"), Triple("NVIDIA", prefs.nvidiaKey, "https://build.nvidia.com/settings/api-keys"))
            .filter { it.second.isBlank() }
        for ((n, _, url) in missingBuiltin) col.addView(ui.button("$n-Schlüssel holen (kostenlos)", false) {
            toast("Schlüssel erstellen und kopieren – zurück in Jarvis trage ich ihn automatisch ein.")
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
        })
        if (list.isNotEmpty()) col.addView(ui.button("Alle testen", false) { list.indices.forEach { test(it) } })
        col.addView(ui.text("Tipp: Erst Schlüssel beim Anbieter holen (Knopf „Schlüssel holen“), kopieren, hier einfügen, dann „Testen“. " +
            "Die Schlüssel bleiben nur auf diesem Handy und kommen nicht in die Sicherung.", 12f, MUTED).apply { setPadding(0, ui.px(14), 0, 0) })
    }

    private fun pick() {
        val have = prefs.extraList().map { it.optString("id") }.toSet()
        val presets = ProviderCatalog.PRESETS.filter { it.id == "custom" || it.id !in have }
        AlertDialog.Builder(this).setTitle("Welcher Anbieter?")
            .setItems(presets.map { it.name + "\n" + it.note }.toTypedArray()) { _, w ->
                val pr = presets[w]
                if (pr.signup.isBlank()) edit(-1, pr) else AlertDialog.Builder(this).setTitle(pr.name)
                    .setMessage("Hast du schon einen Schlüssel?\n\nWenn nicht: „Schlüssel holen“ öffnet die richtige Seite. Dort anmelden, Schlüssel erstellen, kopieren – zurück in Jarvis erkenne ich ihn automatisch.")
                    .setPositiveButton("Schlüssel holen") { _, _ -> openSignup(pr) }
                    .setNeutralButton("Habe ich schon") { _, _ -> edit(-1, pr) }.show()
            }.show()
    }

    /** Anlegen (index = -1, mit Vorlage) oder bearbeiten. */
    private fun edit(index: Int, preset: ProviderCatalog.Preset?, prefill: String? = null) {
        val cur = if (index >= 0) prefs.extraList()[index] else null
        val pr = preset ?: ProviderCatalog.preset(cur?.optString("id") ?: "custom") ?: ProviderCatalog.PRESETS.last()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(20), ui.px(8), ui.px(20), 0) }
        fun field(hint: String, v: String, secret: Boolean = false) = EditText(this).apply {
            this.hint = hint; setText(v); isSingleLine = true
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }.also { box.addView(it) }
        val name = field("Name", cur?.optString("name") ?: pr.name)
        val key = field(if (pr.keyOptional) "API-Schlüssel (optional)" else "API-Schlüssel", prefill ?: cur?.optString("key").orEmpty(), true)
        val acc = if (pr.needsAccount) field("Konto-ID (Account ID)", cur?.optString("account").orEmpty()) else null
        val url = if (pr.id == "custom") field("Adresse …/v1/chat/completions", cur?.optString("url").orEmpty()) else null
        val model = field("Modell(e), mit Komma getrennt", cur?.optString("model") ?: pr.models.joinToString(", "))
        val d = AlertDialog.Builder(this).setTitle(pr.name).setView(box)
            .setPositiveButton("Speichern") { _, _ ->
                val k = ProviderCatalog.cleanKey(key.text.toString())
                if (k.isBlank() && !pr.keyOptional) { toast("Schlüssel fehlt."); return@setPositiveButton }
                val u = if (url != null) url.text.toString().trim() else ProviderCatalog.url(pr.url, acc?.text?.toString().orEmpty())
                if (!u.startsWith("https://") || u.contains("{account}")) { toast("Adresse oder Konto-ID fehlt."); return@setPositiveButton }
                val o = JSONObject().put("id", pr.id).put("name", name.text.toString().ifBlank { pr.name }).put("url", u)
                    .put("key", k).put("model", model.text.toString()).put("on", true).put("account", acc?.text?.toString().orEmpty())
                val l = prefs.extraList().toMutableList()
                if (index >= 0) l[index] = o else l += o
                save(l); build()
                test(if (index >= 0) index else l.size - 1)
            }.setNegativeButton("Abbrechen", null)
        if (pr.signup.isNotBlank()) d.setNeutralButton("Schlüssel holen") { _, _ -> openSignup(pr) }
        d.show()
    }

    private fun openSignup(pr: ProviderCatalog.Preset) {
        waitingFor = pr.id
        toast("Anmelden, Schlüssel erstellen und auf „Kopieren“ tippen – dann zurück zu Jarvis. Ich trage ihn ein.")
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pr.signup))) } catch (_: Exception) {}
    }

    /** Kurzer Probelauf: eine Mini-Frage ohne Werkzeuge. */
    private fun test(i: Int) {
        val p = prefs.extraList().getOrNull(i) ?: return
        status[i] = "… teste"; build()
        thread {
            val res = try {
                val c = (URL(p.optString("url")).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 10000; readTimeout = 25000
                    setRequestProperty("content-type", "application/json")
                    val k = p.optString("key"); if (k.isNotBlank()) setRequestProperty("authorization", "Bearer $k")
                }
                val body = JSONObject().put("model", p.optString("model").substringBefore(',').trim())
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Antworte nur mit: OK")))
                    .put("max_tokens", 20)
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                when {
                    code in 200..299 -> "✓ Funktioniert"
                    code == 401 || code == 403 -> "✗ Schlüssel falsch oder ohne Rechte ($code)"
                    code == 404 -> "✗ Modell oder Adresse nicht gefunden – Modell prüfen"
                    code == 429 -> "✓ Schlüssel ok, gerade am Limit (429)"
                    code == 402 -> "✗ Anbieter verlangt Guthaben/Karte (402)"
                    else -> "✗ Fehler $code: " + txt.take(100)
                }
            } catch (e: Exception) { "✗ Nicht erreichbar: ${e.javaClass.simpleName}" }
            runOnUiThread { status[i] = res; if (!isFinishing) build() }
        }
    }
}
