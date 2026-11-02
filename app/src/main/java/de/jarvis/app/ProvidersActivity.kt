package de.jarvis.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
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

/**
 * Alle KI-Anbieter auf einer Seite – pro Anbieter nur zwei Knöpfe:
 *   ① „Schlüssel holen“ (öffnet die richtige Seite)  ② „Einfügen“ (nimmt den kopierten Schlüssel, speichert, testet).
 * Kommt man mit einem kopierten Schlüssel zurück, erkennt Jarvis ihn sogar von selbst.
 */
class ProvidersActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private val status = HashMap<String, String>()
    private var waitingFor: String? = null
    private var lastOffered = ""

    /** Ein Eintrag der Liste: eingebaut (Schlüssel in den Einstellungen) oder Vorlage (zusätzlicher Anbieter). */
    private class Entry(val id: String, val name: String, val badge: String, val note: String, val signup: String,
                        val builtin: Boolean, val testUrl: String = "", val testModel: String = "")

    private val entries = listOf(
        Entry("groq", "Groq", "⚡ AM SCHNELLSTEN", "Antwortet meist in unter 1 Sekunde. Kostenlos, ohne Karte.",
            "https://console.groq.com/keys", true, "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile"),
        Entry("nvidia", "NVIDIA", "★ EMPFOHLEN", "Viele starke Modelle, ca. 40 Anfragen pro Minute. Kostenlos.",
            "https://build.nvidia.com/settings/api-keys", true, "https://integrate.api.nvidia.com/v1/chat/completions", "meta/llama-3.3-70b-instruct"),
        Entry("gemini", "Google Gemini", "★ EMPFOHLEN", "Großes Tageslimit, kann auch Bilder. Anmeldung mit deinem Google-Konto.",
            "https://aistudio.google.com/apikey", true, "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-flash-latest, gemini-3.8-flash, gemini-3.6-flash"),
        Entry("openrouter", "OpenRouter", "", "Viele Gratis-Modelle, ca. 50 Anfragen pro Tag.", "https://openrouter.ai/keys", false),
        Entry("mistral", "Mistral", "", "Kostenloser „Experiment“-Tarif (Telefonnummer nötig).", "https://console.mistral.ai/api-keys",
            true, "https://api.mistral.ai/v1/chat/completions", "mistral-small-latest"),
        Entry("cohere", "Cohere", "", "Test-Schlüssel kostenlos, ca. 1.000 Anfragen im Monat.", "https://dashboard.cohere.com/api-keys", false),
        Entry("zai", "Z.ai (GLM)", "", "Flash-Modelle kostenlos.", "https://z.ai/manage-apikey/apikey-list", false),
        Entry("sambanova", "SambaNova", "⚡ SCHNELL", "Sehr schnell, Gratis-Kontingent.", "https://cloud.sambanova.ai/apis", false),
        Entry("huggingface", "Hugging Face", "", "Kleines Gratis-Guthaben jeden Monat.", "https://huggingface.co/settings/tokens", false),
        Entry("cloudflare", "Cloudflare Workers AI", "", "Täglich Gratis-Kontingent. Braucht zusätzlich deine Konto-ID.", "https://dash.cloudflare.com/profile/api-tokens", false),
        Entry("cerebras", "Cerebras", "⚡ SCHNELL", "Extrem schnell; verlangt evtl. eine Karte.", "https://cloud.cerebras.ai", false),
        Entry("pollinations", "Pollinations", "", "Geht sogar ohne Schlüssel – einfach „Ohne Schlüssel nutzen“.", "https://auth.pollinations.ai", false),
        Entry("claude", "Claude (Anthropic)", "€ KOSTET GELD", "Sehr klug, aber nur mit Guthaben.", "https://console.anthropic.com/settings/keys", true),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this); Ui.load(prefs); ui = Ui(this)
        window.statusBarColor = BG; window.navigationBarColor = BG; Ui.lightBars(window)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(16), ui.px(22), ui.px(16), ui.px(40)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(col) })
        build()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    private fun saveExtras(list: List<JSONObject>) { prefs.extraProviders = JSONArray(list).toString() }

    // ---------- Schlüssel lesen/schreiben ----------

    private fun keyOf(id: String): String = when (id) {
        "groq" -> prefs.groqKey; "nvidia" -> prefs.nvidiaKey; "gemini" -> prefs.geminiKey
        "mistral" -> prefs.mistralKey; "claude" -> prefs.anthropicKey
        else -> prefs.extraList().firstOrNull { it.optString("id") == id }?.let { it.optString("key").ifBlank { if (id == "pollinations") "–" else "" } }.orEmpty()
    }

    private fun setKey(id: String, key: String, account: String = ""): Boolean {
        when (id) {
            "groq" -> prefs.groqKey = key; "nvidia" -> prefs.nvidiaKey = key; "gemini" -> prefs.geminiKey = key
            "mistral" -> prefs.mistralKey = key; "claude" -> prefs.anthropicKey = key
            else -> {
                val pr = ProviderCatalog.preset(id) ?: return false
                val url = ProviderCatalog.url(pr.url, account)
                if (url.contains("{account}") || !url.startsWith("https://")) return false
                val l = prefs.extraList().filter { it.optString("id") != id }.toMutableList()
                l += JSONObject().put("id", id).put("name", pr.name).put("url", url).put("key", key)
                    .put("model", pr.models.joinToString(", ")).put("on", true).put("account", account)
                saveExtras(l)
            }
        }
        return true
    }

    private fun removeKey(id: String) {
        when (id) {
            "groq" -> prefs.groqKey = ""; "nvidia" -> prefs.nvidiaKey = ""; "gemini" -> prefs.geminiKey = ""
            "mistral" -> prefs.mistralKey = ""; "claude" -> prefs.anthropicKey = ""
            else -> saveExtras(prefs.extraList().filter { it.optString("id") != id })
        }
        status.remove(id)
    }

    // ---------- Oberfläche ----------

    private fun build() {
        col.removeAllViews()
        col.addView(ui.row().apply {
            addView(ui.text("‹", 30f).apply { setPadding(0, 0, ui.px(16), 0); setOnClickListener { finish() } })
            addView(LinearLayout(this@ProvidersActivity).apply { orientation = LinearLayout.VERTICAL
                addView(ui.text("KI-Schlüssel", 22f, Ui.FG, true))
                addView(ui.text("JE MEHR, DESTO SELTENER PAUSEN", 11f, RED).apply { letterSpacing = 0.18f; typeface = Typeface.MONOSPACE }) })
        })
        val active = entries.count { keyOf(it.id).isNotBlank() } + prefs.extraList().count { it.optString("id") == "custom" }
        col.addView(ui.card().apply {
            addView(ui.row().apply {
                addView(ui.weighted(ui.text("$active von ${entries.size} Anbietern aktiv", 16f, Ui.FG, true)))
                addView(ui.text(when { active >= 6 -> "Pausen: fast nie"; active >= 3 -> "Pausen: selten"; active >= 1 -> "Pausen: manchmal"; else -> "Noch keiner" },
                    12f, if (active >= 3) GREEN else AMBER))
            })
            addView(android.widget.ProgressBar(this@ProvidersActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = entries.size; progress = active
                progressTintList = android.content.res.ColorStateList.valueOf(RED)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.LINE)
            }, LinearLayout.LayoutParams(-1, ui.px(8)).apply { topMargin = ui.px(8) })
            addView(ui.text("So geht's: ① „Schlüssel holen“ → anmelden → Schlüssel erstellen → kopieren. ② Zurück zu Jarvis → „Einfügen“. Fertig.",
                13f, SOFT).apply { setPadding(0, ui.px(10), 0, 0) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(12) })

        for (e in entries) col.addView(card(e))

        // Eigene Anbieter
        col.addView(ui.section("Eigene Anbieter"))
        prefs.extraList().filter { it.optString("id") == "custom" }.forEach { c ->
            col.addView(ui.card().apply {
                addView(ui.row().apply {
                    addView(ui.weighted(ui.text(c.optString("name"), 15f, Ui.FG, true)))
                    addView(ui.text("Entfernen", 13f, RED).apply { setPadding(ui.px(10), ui.px(6), 0, ui.px(6)); setOnClickListener {
                        saveExtras(prefs.extraList().filter { it.optString("url") != c.optString("url") || it.optString("id") != "custom" }); build() } })
                })
                addView(ui.text(c.optString("url"), 11f, MUTED).apply { maxLines = 1 })
            })
        }
        col.addView(ui.button("+ Eigenen Anbieter hinzufügen (Profis)", false) { addCustom() })
        col.addView(ui.text("Die Schlüssel bleiben nur auf diesem Handy und kommen nicht in die Sicherung. Pro Anbieter ein eigenes Konto.",
            12f, MUTED).apply { setPadding(0, ui.px(14), 0, 0) })
    }

    private fun card(e: Entry) = ui.card().apply {
        val key = keyOf(e.id)
        val has = key.isNotBlank()
        addView(ui.row().apply {
            addView(ui.text(if (has) "✓" else "○", 20f, if (has) GREEN else MUTED).apply { width = ui.px(30) })
            addView(ui.weighted(LinearLayout(this@ProvidersActivity).apply { orientation = LinearLayout.VERTICAL
                addView(ui.text(e.name, 16f, Ui.FG, true))
                if (e.badge.isNotBlank()) addView(ui.text(e.badge, 10f, if (e.badge.startsWith("€")) AMBER else RED).apply { letterSpacing = 0.14f; typeface = Typeface.MONOSPACE })
            }))
            if (has && !e.builtin) {
                val x = prefs.extraList().firstOrNull { it.optString("id") == e.id }
                if (x != null) addView(Switch(this@ProvidersActivity).apply { isChecked = x.optBoolean("on", true)
                    setOnCheckedChangeListener { _, on -> saveExtras(prefs.extraList().map { if (it.optString("id") == e.id) it.put("on", on) else it }) } })
            }
        })
        addView(ui.text(e.note, 13f, SOFT).apply { setPadding(ui.px(30), ui.px(2), 0, 0) })
        status[e.id]?.let { addView(ui.text(it, 13f, if (it.startsWith("✓")) GREEN else if (it.startsWith("…")) MUTED else AMBER).apply { setPadding(ui.px(30), ui.px(6), 0, 0) }) }
        val row = ui.row().apply { setPadding(ui.px(30), 0, 0, 0) }
        fun btn(t: String, filled: Boolean, act: () -> Unit) = row.addView(ui.weighted(ui.button(t, filled) { act() }).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { if (row.childCount > 0) leftMargin = ui.px(8); topMargin = ui.px(10) } })
        if (!has) {
            btn("① Schlüssel holen", true) { openSignup(e) }
            btn("② Einfügen", false) { pasteFor(e) }
        } else {
            if (e.id != "claude") btn("Testen", false) { test(e.id) }
            btn("Entfernen", false) {
                AlertDialog.Builder(this@ProvidersActivity).setMessage("${e.name}-Schlüssel entfernen?")
                    .setPositiveButton("Entfernen") { _, _ -> removeKey(e.id); build() }.setNegativeButton("Abbrechen", null).show()
            }
        }
        addView(row)
        if (!has && e.id == "pollinations") addView(ui.button("Ohne Schlüssel nutzen", false) { setKey("pollinations", ""); build(); test("pollinations") })
    }

    private fun openSignup(e: Entry) {
        waitingFor = e.id
        toast("Anmelden → Schlüssel erstellen → kopieren → zurück zu Jarvis. Ich erkenne ihn dann automatisch.")
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.signup))) } catch (_: Exception) { toast("Kein Browser gefunden.") }
    }

    /** ② Einfügen: kopierten Schlüssel aus der Zwischenablage nehmen, speichern, testen. */
    private fun pasteFor(e: Entry) {
        val raw = KeySniffer.clipboard(this)
        val k = raw?.let { ProviderCatalog.cleanKey(it) }.orEmpty()
        if (k.length < 16) {
            // Nichts Passendes kopiert → zum Eintippen/Einfügen per Hand
            manual(e); return
        }
        adopt(e.id, k)
    }

    private fun manual(e: Entry) {
        val f = EditText(this).apply { hint = "Schlüssel hier einfügen (lange drücken → Einfügen)"; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
        AlertDialog.Builder(this).setTitle(e.name).setMessage("In der Zwischenablage ist kein Schlüssel. Hier einfügen:")
            .setView(LinearLayout(this).apply { setPadding(ui.px(20), 0, ui.px(20), 0); addView(f, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Speichern") { _, _ -> val k = ProviderCatalog.cleanKey(f.text.toString()); if (k.length >= 16) adopt(e.id, k) else toast("Das sieht nicht nach einem Schlüssel aus.") }
            .setNeutralButton("Schlüssel holen") { _, _ -> openSignup(e) }
            .setNegativeButton("Abbrechen", null).show()
    }

    /** Schlüssel übernehmen (bei Cloudflare noch die Konto-ID abfragen) und sofort testen. */
    private fun adopt(id: String, key: String) {
        waitingFor = null; lastOffered = key
        if (id == "cloudflare") {
            val f = EditText(this).apply { hint = "Konto-ID (Account ID, steht rechts im Cloudflare-Dashboard)"; isSingleLine = true }
            AlertDialog.Builder(this).setTitle("Cloudflare – Konto-ID").setView(LinearLayout(this).apply { setPadding(ui.px(20), 0, ui.px(20), 0); addView(f, LinearLayout.LayoutParams(-1, -2)) })
                .setPositiveButton("Speichern") { _, _ -> if (setKey(id, key, f.text.toString().trim())) { build(); test(id) } else toast("Konto-ID fehlt.") }
                .setNegativeButton("Abbrechen", null).show()
            return
        }
        setKey(id, key)
        toast("${entries.firstOrNull { it.id == id }?.name ?: id}: Schlüssel gespeichert – teste …")
        build(); if (id != "claude") test(id)
    }

    /** Zurück aus dem Browser mit kopiertem Schlüssel → automatisch anbieten. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val clip = KeySniffer.clipboard(this) ?: return
        val hit = KeySniffer.detect(clip, waitingFor) ?: return
        val id = if (hit.target == "gemini_oa") "gemini" else hit.target
        if (hit.key == lastOffered || keyOf(id) == hit.key) return
        lastOffered = hit.key
        AlertDialog.Builder(this).setTitle("Schlüssel erkannt 🔑")
            .setMessage("Du hast einen Schlüssel für ${entries.firstOrNull { it.id == id }?.name ?: hit.name} kopiert. Eintragen?")
            .setPositiveButton("Eintragen") { _, _ -> adopt(id, hit.key) }
            .setNegativeButton("Nein", null).show()
    }

    private fun addCustom() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(20), ui.px(8), ui.px(20), 0) }
        fun f(h: String) = EditText(this).apply { hint = h; isSingleLine = true }.also { box.addView(it) }
        val name = f("Name"); val url = f("Adresse …/v1/chat/completions"); val model = f("Modell"); val key = f("Schlüssel")
        AlertDialog.Builder(this).setTitle("Eigener Anbieter").setView(box).setPositiveButton("Speichern") { _, _ ->
            val u = url.text.toString().trim()
            if (!u.startsWith("https://") || model.text.isBlank()) { toast("Adresse (https://…) und Modell nötig."); return@setPositiveButton }
            saveExtras(prefs.extraList() + JSONObject().put("id", "custom").put("name", name.text.toString().ifBlank { "Eigener Anbieter" })
                .put("url", u).put("key", ProviderCatalog.cleanKey(key.text.toString())).put("model", model.text.toString().trim()).put("on", true))
            build()
        }.setNegativeButton("Abbrechen", null).show()
    }

    // ---------- Test ----------

    private fun test(id: String) {
        val e = entries.firstOrNull { it.id == id }
        val x = prefs.extraList().firstOrNull { it.optString("id") == id }
        val url = x?.optString("url") ?: e?.testUrl.orEmpty()
        val models = (x?.optString("model") ?: e?.testModel).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val model = models.firstOrNull().orEmpty()
        val key = keyOf(id).takeIf { it != "–" }.orEmpty()
        if (url.isBlank() || model.isBlank()) return
        status[id] = "… teste"; build()
        thread {
            var res = "✗ Modell nicht gefunden (schick mir die Meldung)"
            for (m in models) {
                res = probe(url, key, m)
                if (!res.contains("Modell nicht gefunden")) break
            }
            runOnUiThread { status[id] = res; if (!isFinishing) build() }
        }
    }

    private fun probe(url: String, key: String, model: String): String {
        return try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 10000; readTimeout = 25000
                    setRequestProperty("content-type", "application/json")
                    if (key.isNotBlank()) setRequestProperty("authorization", "Bearer $key")
                }
                val body = JSONObject().put("model", model).put("max_tokens", 20)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Antworte nur mit: OK")))
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                when {
                    code in 200..299 -> "✓ Funktioniert!"
                    code == 401 || code == 403 -> "✗ Schlüssel stimmt nicht – nochmal kopieren und „Entfernen“ → „Einfügen“"
                    code == 404 -> "✗ Modell nicht gefunden (schick mir die Meldung)"
                    code == 429 -> "✓ Schlüssel ok (gerade am Limit)"
                    code == 402 -> "✗ Anbieter verlangt Guthaben oder Karte"
                    else -> "✗ Fehler $code: " + txt.take(90)
                }
            } catch (ex: Exception) { "✗ Nicht erreichbar (Internet?)" }
    }
}
