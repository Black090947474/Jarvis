package de.jarvis.app

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import kotlin.concurrent.thread

/**
 * Deine eigene KI: ein offenes Sprachmodell (Qwen 2.5, 1,5 Mrd. Parameter, Apache-2.0-Lizenz), das komplett auf dem Handy läuft.
 * Kein Schlüssel, kein Konto, kein Internet nötig – was du sagst, bleibt auf dem Handy.
 */
object LocalAi {
    const val MODEL_NAME = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    const val MODEL_URL = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/$MODEL_NAME"
    const val MODEL_BYTES = 1_625_493_432L

    fun file(ctx: Context): File = File(ctx.getExternalFilesDir(null), MODEL_NAME)
    fun installed(ctx: Context): Boolean {
        val f = file(ctx); if (f.exists() && f.length() > 100_000_000L) return true
        return File(ctx.getExternalFilesDir(null), "eigenes-modell.task").let { it.exists() && it.length() > 50_000_000L }
    }
    private fun modelPath(ctx: Context): String = file(ctx).takeIf { it.exists() && it.length() > 100_000_000L }?.absolutePath
        ?: File(ctx.getExternalFilesDir(null), "eigenes-modell.task").absolutePath

    @Volatile private var engine: LlmInference? = null
    private val lock = Any()

    /** Modell laden (dauert beim ersten Mal ein paar Sekunden). */
    fun engine(ctx: Context): LlmInference = synchronized(lock) {
        engine ?: LlmInference.createFromOptions(ctx.applicationContext,
            LlmInference.LlmInferenceOptions.builder().setModelPath(modelPath(ctx)).setMaxTokens(1280).build()).also { engine = it }
    }

    fun release() = synchronized(lock) { try { engine?.close() } catch (_: Throwable) {}; engine = null }

    fun generate(ctx: Context, prompt: String): String = synchronized(lock) { engine(ctx).generateResponse(prompt) }

    // ---------- Download (über WLAN, mit Fortschritt in der Benachrichtigung)

    fun startDownload(ctx: Context, wifiOnly: Boolean): Long {
        file(ctx).delete()
        val req = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("Jarvis: eigene KI wird geladen")
            .setDescription("ca. 1,6 GB – einmalig")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, null, MODEL_NAME)
            .setAllowedOverMetered(!wifiOnly).setAllowedOverRoaming(false)
        val id = ctx.getSystemService(DownloadManager::class.java).enqueue(req)
        ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putLong("own_ai_dl", id).apply()
        return id
    }

    /** (Status-Text, Fortschritt 0..100 oder -1). */
    fun downloadState(ctx: Context): Pair<String, Int>? {
        val id = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getLong("own_ai_dl", -1L)
        if (id < 0) return null
        val c = ctx.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(id)) ?: return null
        c.use {
            if (!it.moveToFirst()) return null
            val st = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { t -> t > 0 } ?: MODEL_BYTES
            val pct = (done * 100 / total).toInt()
            return when (st) {
                DownloadManager.STATUS_SUCCESSFUL -> "Fertig geladen." to 100
                DownloadManager.STATUS_FAILED -> "Download fehlgeschlagen (Code ${it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))}). Nochmal versuchen." to -1
                DownloadManager.STATUS_PAUSED -> "Pausiert – wartet auf WLAN … ($pct %)" to pct
                else -> "Lädt … $pct % (${done / 1_048_576} von ${total / 1_048_576} MB)" to pct
            }
        }
    }
}

/** Jarvis' eigenes Gehirn auf dem Handy. Kann reden, erklären, helfen – Aktionen (Wecker, Timer …) macht Jarvis vorher selbst. */
class LocalBrain(private val ctx: Context, private val p: Prefs, private val mem: Memory, private val chat: Boolean) : Brain {
    private val history = ArrayList<Pair<String, String>>()

    override fun seed(history: List<Pair<String, String>>) { this.history.clear(); this.history.addAll(history.takeLast(6)) }

    private fun system(): String {
        val name = p.userName.ifBlank { "Sir" }
        val facts = mem.all().takeLast(15)
        val now = java.text.SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", java.util.Locale.GERMANY).format(java.util.Date())
        return "Du bist Jarvis, der persönliche KI-Assistent von $name. Du läufst komplett offline auf seinem Handy. Heute ist $now. " +
            "Antworte immer auf Deutsch. " + (if (chat) "Antworte klar und hilfreich. " else "Deine Antwort wird vorgelesen: höchstens 2–3 kurze Sätze, keine Listen, keine Emojis. ") +
            "Stil: " + Personality.styleText(p) + " " +
            (if (facts.isNotEmpty()) "Das weißt du über $name: " + facts.joinToString("; ") + ". " else "") +
            "Wenn du etwas nicht sicher weißt (aktuelle Ereignisse, Preise, Wetter), sag ehrlich, dass du offline bist und es nicht genau weißt. Erfinde nichts."
    }

    override fun ask(userText: String, onStep: (String) -> Unit, image: String?): String {
        if (!LocalAi.installed(ctx)) throw Brain.Unavailable("Deine eigene KI ist noch nicht geladen. Öffne Werkzeuge → Eigene KI.")
        if (image != null) throw Brain.Unavailable("Bilder kann meine Handy-KI noch nicht ansehen – dafür brauche ich Internet.")
        val sb = StringBuilder("<|im_start|>system\n").append(system()).append("<|im_end|>\n")
        for ((r, t) in history.takeLast(6)) sb.append("<|im_start|>").append(if (r == "user") "user" else "assistant").append("\n").append(t.take(600)).append("<|im_end|>\n")
        sb.append("<|im_start|>user\n").append(userText.take(1500)).append("<|im_end|>\n<|im_start|>assistant\n")
        onStep("Deine Handy-KI denkt nach …")
        val out = try { LocalAi.generate(ctx, sb.toString()) } catch (e: Throwable) {
            LocalAi.release(); throw Brain.Unavailable("Meine Handy-KI ist gerade ausgefallen (${e.message?.take(60)}).") }
        val reply = out.substringBefore("<|im_end|>").substringBefore("<|im_start|>").trim().ifBlank { "Dazu fällt mir gerade nichts ein." }
        history += "user" to userText; history += "assistant" to reply
        while (history.size > 8) history.removeAt(0)
        ModelStatus.set("Eigene KI", "Qwen 2.5 (offline)", false, false)
        return reply
    }
}

/** Bildschirm „Eigene KI“: laden, testen, einstellen. */
class OwnAiActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var col: LinearLayout
    private val h = Handler(Looper.getMainLooper())
    private var status: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.load(Prefs(this)); ui = Ui(this)
        window.statusBarColor = Ui.BG; window.navigationBarColor = Ui.BG; Ui.lightBars(window)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(20), ui.px(22), ui.px(20), ui.px(40)) }
        setContentView(ScrollView(this).apply { background = ui.aurora(); addView(col) })
    }

    override fun onResume() { super.onResume(); build(); h.post(poll) }
    override fun onPause() { h.removeCallbacks(poll); super.onPause() }

    private val poll = object : Runnable { override fun run() {
        val s = LocalAi.downloadState(this@OwnAiActivity)
        if (s != null && !LocalAi.installed(this@OwnAiActivity)) status?.text = s.first
        if (s?.second == 100 && LocalAi.installed(this@OwnAiActivity) && status?.text?.startsWith("Lädt") != false) { build(); return }
        h.postDelayed(this, 1500) } }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun build() {
        col.removeAllViews()
        val p = Prefs(this)
        col.addView(ui.row().apply {
            addView(ui.text("‹", 30f).apply { setPadding(0, 0, ui.px(16), 0); setOnClickListener { finish() } })
            addView(LinearLayout(this@OwnAiActivity).apply { orientation = LinearLayout.VERTICAL
                addView(ui.text("Eigene KI", 28f, Ui.FG, true)); addView(ui.text("Läuft auf deinem Handy – ohne Schlüssel", 13f, Ui.MUTED)) }) })
        val ok = LocalAi.installed(this)
        col.addView(ui.card().apply {
            addView(ui.text(if (ok) "● Deine KI ist bereit" else "○ Noch nicht geladen", 16f, if (ok) Ui.GREEN else Ui.AMBER, true))
            addView(ui.text("Modell: Qwen 2.5 (1,5 Mrd. Parameter) · offene Lizenz (Apache 2.0) · ca. 1,6 GB.\n" +
                "Kein Konto, kein Schlüssel, kein Internet – alles bleibt auf dem Handy. Sie ist kleiner als die großen Online-KIs: " +
                "gut für Gespräche, Erklärungen und Hilfe, langsamer und bei schwierigen Fragen weniger genau. " +
                "Wecker, Timer, Erinnerungen & Co. erledigt Jarvis weiterhin selbst.", 13f, Ui.SOFT).apply { setPadding(0, ui.px(6), 0, 0) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(16) })
        status = ui.text(LocalAi.downloadState(this)?.first?.takeIf { !ok }.orEmpty(), 13.5f, Ui.MUTED).apply { setPadding(ui.px(4), ui.px(8), 0, 0) }
        col.addView(status)
        if (!ok) {
            col.addView(ui.button("Jetzt laden (nur WLAN)") { LocalAi.startDownload(this, true); toast("Download gestartet – du kannst die App ruhig schließen."); status?.text = "Startet …" })
            col.addView(ui.button("Auch über mobile Daten laden (1,6 GB!)", false) { LocalAi.startDownload(this, false); status?.text = "Startet …" })
            col.addView(ui.button("Eigene Modell-Datei wählen (.task)", false) {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 77) })
        } else {
            col.addView(ui.section("Ausprobieren"))
            val q = EditText(this).apply { hint = "Frag deine KI etwas …"; setTextColor(Ui.FG); setHintTextColor(Ui.MUTED)
                background = ui.round(Ui.CARD2, 16); setPadding(ui.px(14), ui.px(12), ui.px(14), ui.px(12)) }
            col.addView(q)
            val ans = ui.text("", 14.5f, Ui.FG).apply { setPadding(ui.px(4), ui.px(10), 0, 0) }
            col.addView(ui.button("Fragen") {
                val text = q.text.toString().trim(); if (text.isEmpty()) return@button
                ans.text = "Denkt nach … (beim ersten Mal lädt das Modell ein paar Sekunden)"
                val t0 = System.currentTimeMillis()
                thread { val r = try { LocalBrain(this, p, Memory(this), chat = true).ask(text, {}) } catch (e: Exception) { "Fehler: ${e.message}" }
                    runOnUiThread { ans.text = r + "\n\n(${(System.currentTimeMillis() - t0) / 1000} s, komplett offline)" } }
            })
            col.addView(ans)
        }
        col.addView(ui.section("Einstellungen"))
        fun sw(label: String, sub: String, get: Boolean, set: (Boolean) -> Unit) = col.addView(ui.card(false).apply {
            addView(ui.weighted(LinearLayout(this@OwnAiActivity).apply { orientation = LinearLayout.VERTICAL
                addView(ui.text(label, 15f, Ui.FG, true)); addView(ui.text(sub, 12.5f, Ui.SOFT)) }))
            addView(Switch(this@OwnAiActivity).apply { isChecked = get; setOnCheckedChangeListener { _, v -> set(v) } }) })
        sw("Eigene KI zuerst", "Jarvis fragt immer zuerst deine Handy-KI (privat). Sonst nur, wenn kein Internet oder kein Schlüssel da ist.", p.ownAiFirst) { p.ownAiFirst = it }
        sw("Kostenlose Online-KI ohne Schlüssel", "Wenn du keine Schlüssel hast und Internet da ist: Pollinations (langsamer, begrenzt).", p.freeOnline) { p.freeOnline = it }
        if (ok) col.addView(ui.button("Eigene KI löschen (gibt 1,6 GB frei)", false) {
            android.app.AlertDialog.Builder(this).setTitle("Eigene KI löschen?").setMessage("Du kannst sie jederzeit neu laden.")
                .setPositiveButton("Löschen") { _, _ -> LocalAi.release(); LocalAi.file(this).delete(); File(getExternalFilesDir(null), "eigenes-modell.task").delete(); build() }
                .setNegativeButton("Abbrechen", null).show() })
        col.addView(ui.text("Modell: Qwen2.5-1.5B-Instruct von Alibaba Cloud (Apache 2.0), für Handys umgewandelt von Google LiteRT. Läuft mit Google MediaPipe.", 11f, Ui.MUTED).apply { setPadding(0, ui.px(16), 0, 0) })
    }

    @Deprecated("") override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        val uri = data?.data ?: return
        if (rc != 77 || res != RESULT_OK) return
        toast("Kopiere Modell … (kann eine Minute dauern)")
        thread {
            val ok = try { contentResolver.openInputStream(uri)?.use { i -> File(getExternalFilesDir(null), "eigenes-modell.task").outputStream().use { i.copyTo(it, 1 shl 20) } }; true } catch (_: Exception) { false }
            runOnUiThread { toast(if (ok) "Eigenes Modell übernommen." else "Kopieren fehlgeschlagen."); build() }
        }
    }
}
