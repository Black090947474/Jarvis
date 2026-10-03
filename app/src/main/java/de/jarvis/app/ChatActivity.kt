package de.jarvis.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Jarvis-Chat: schreiben, sprechen oder Bilder schicken und Fragen dazu stellen.
 * Der Verlauf bleibt auf dem Handy gespeichert.
 */
class ChatActivity : Activity() {

    // ---------- Farben (passend zum Puls/Nexus-Look) ----------
    private val BG1 = Color.rgb(6, 7, 15)
    private val BG2 = Color.rgb(12, 8, 26)
    private val FG = Color.rgb(232, 236, 255)
    private val MUTED = Color.rgb(124, 130, 168)
    private val ACCENT = Color.rgb(34, 224, 224)
    private val VIOLET = Color.rgb(106, 91, 255)
    private val CARD = Color.argb(26, 255, 255, 255)

    private data class Msg(val role: String, val text: String, val image: String? = null, val time: Long = System.currentTimeMillis())

    private val main = Handler(Looper.getMainLooper())
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()

    private lateinit var core: CoreView
    private lateinit var statusText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var preview: FrameLayout
    private lateinit var previewImg: ImageView
    private lateinit var speakBtn: TextView

    private val msgs = mutableListOf<Msg>()
    private var brain: BrainRouter? = null
    private var tools: PhoneTools? = null
    private var pendingImage: File? = null
    private var cameraUri: Uri? = null
    private var busy = false
    private var speakAnswers = false
    private var speaker: Speaker? = null

    private val chatFile by lazy { File(filesDir, "chat.json") }
    private val imgDir by lazy { File(filesDir, "chat_img").apply { mkdirs() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG1
        window.navigationBarColor = BG2
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildUi()
        load()
        setupBrain()
        render()
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    // ---------- Gehirn ----------

    private fun setupBrain() {
        val p = Prefs(this)
        if (!p.hasAnyKey) return
        val t = PhoneTools(this).also { tools = it }
        t.onCard = { a -> addCardBubble(a); "Karte im Chat angezeigt." }
        val mem = Memory(this)
        brain = Brains.build(p, t, mem, chat = true).also { b ->
            // Bisherigen Verlauf (nur Text) mitgeben, damit Jarvis weiß, worum es ging
            b.seed(msgs.takeLast(12).map { (if (it.role == "user") "user" else "assistant") to
                (if (it.image != null) "[Bild] " else "") + it.text.take(1500) })
        }
    }

    // ---------- Oberfläche ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG1, BG2))
        }

        // Kopfzeile
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14), px(12), px(10), px(10))
        }
        core = CoreView(this, CoreView.styleFrom(Prefs(this).design))
        header.addView(core, LinearLayout.LayoutParams(px(54), px(54)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(10), 0, 0, 0) }
        titles.addView(TextView(this).apply {
            text = "J.A.R.V.I.S"; textSize = 17f; letterSpacing = 0.3f; setTextColor(FG)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        statusText = TextView(this).apply {
            text = "● ONLINE · CHAT"; textSize = 10f; letterSpacing = 0.18f; setTextColor(ACCENT); typeface = Typeface.MONOSPACE
        }
        titles.addView(statusText)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        speakBtn = headerButton("🔈") { toggleSpeak() }
        speakBtn.contentDescription = "Antworten vorlesen"
        header.addView(speakBtn)
        header.addView(headerButton("＋") { newChat() }.apply { contentDescription = "Neuer Chat" })
        root.addView(header)
        root.addView(View(this).apply { setBackgroundColor(Color.argb(40, 34, 224, 224)) }, LinearLayout.LayoutParams(-1, px(1)))

        // Nachrichten
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(14), px(14), px(14))
        }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Bild-Vorschau über der Eingabe
        previewImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(CARD) } }
        preview = FrameLayout(this).apply {
            visibility = View.GONE
            setPadding(px(14), px(6), px(14), 0)
            addView(previewImg, FrameLayout.LayoutParams(px(84), px(84)))
            addView(TextView(this@ChatActivity).apply {
                text = "✕"; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(200, 0, 0, 0)) }
                setOnClickListener { clearPending() }
                contentDescription = "Bild entfernen"
            }, FrameLayout.LayoutParams(px(26), px(26)).apply { leftMargin = px(64); topMargin = px(-4) })
        }
        root.addView(preview)

        // Eingabeleiste
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(px(10), px(8), px(10), px(12))
        }
        val field = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 26 * dp; setColor(Color.argb(20, 255, 255, 255)); setStroke(px(1), Color.argb(60, 34, 224, 224))
            }
            setPadding(px(4), px(2), px(4), px(2))
        }
        field.addView(iconButton(R.drawable.ic_image, "Bild aus Galerie") { pickImage() })
        field.addView(iconButton(R.drawable.ic_camera, "Foto aufnehmen") { takePhoto() })
        input = EditText(this).apply {
            hint = "Frag Jarvis …"
            setHintTextColor(MUTED); setTextColor(FG); textSize = 16f
            background = null
            maxLines = 5
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(px(6), px(10), px(6), px(10))
        }
        field.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        field.addView(iconButton(R.drawable.ic_mic, "Sprechen") { dictate() })
        bar.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        val send = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send)
            setColorFilter(BG1)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT) }
            contentDescription = "Senden"
            setOnClickListener { send() }
        }
        bar.addView(send, LinearLayout.LayoutParams(px(50), px(50)).apply { leftMargin = px(8) })
        root.addView(bar)

        setContentView(root)
    }

    private fun headerButton(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 18f; setTextColor(FG); gravity = Gravity.CENTER
        minWidth = px(44); minHeight = px(44)
        setOnClickListener { onClick() }
    }

    private fun iconButton(res: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(res)
        setColorFilter(MUTED)
        background = null
        contentDescription = desc
        layoutParams = LinearLayout.LayoutParams(px(44), px(44))
        setOnClickListener { onClick() }
    }

    // ---------- Nachrichten anzeigen ----------

    private fun render() {
        list.removeAllViews()
        if (msgs.isEmpty()) addWelcome()
        msgs.forEach { addBubble(it) }
        scrollDown()
    }

    private fun addWelcome() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(10), px(40), px(10), px(10))
        }
        box.addView(TextView(this).apply {
            text = "Wie kann ich helfen?"; textSize = 24f; setTextColor(FG); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        box.addView(TextView(this).apply {
            text = "Schreib mir, sprich mit mir oder schick ein Bild und frag mich etwas dazu."
            textSize = 14f; setTextColor(MUTED); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
        val chips = listOf("Was ist auf diesem Bild?", "Erklär mir die Hausaufgabe auf dem Foto",
            "Mach mir einen Lernplan für Mathe", "Welche Pflanze ist das?")
        chips.forEach { c ->
            box.addView(TextView(this).apply {
                text = c; textSize = 14f; setTextColor(FG)
                setPadding(px(16), px(10), px(16), px(10))
                background = GradientDrawable().apply { cornerRadius = 20 * dp; setColor(CARD); setStroke(px(1), Color.argb(50, 106, 91, 255)) }
                setOnClickListener {
                    input.setText(c); input.setSelection(c.length)
                    if (c.contains("Bild") || c.contains("Foto") || c.contains("Pflanze")) pickImage()
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(10) })
        }
        list.addView(box)
    }

    private fun addBubble(m: Msg): TextView {
        val mine = m.role == "user"
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (mine) Gravity.END else Gravity.START
        }
        if (!mine) wrap.addView(TextView(this).apply {
            text = "JARVIS · " + SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(m.time))
            textSize = 9f; letterSpacing = 0.2f; typeface = Typeface.MONOSPACE; setTextColor(ACCENT)
            setPadding(px(4), 0, 0, px(4))
        })
        if (m.image != null) {
            val f = File(imgDir, m.image)
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(CARD) }
                if (f.exists()) setImageBitmap(BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }))
                setOnClickListener { openImage(f) }
            }
            wrap.addView(iv, LinearLayout.LayoutParams(px(200), px(200)).apply { bottomMargin = px(6) })
        }
        val tv = TextView(this).apply {
            text = if (mine) m.text else markdown(m.text)
            textSize = 15.5f; setTextColor(FG); setLineSpacing(0f, 1.25f)
            setTextIsSelectable(true)
            setPadding(px(14), px(10), px(14), px(10))
            background = GradientDrawable().apply {
                if (mine) {
                    cornerRadii = floatArrayOf(18 * dp, 18 * dp, 4 * dp, 4 * dp, 18 * dp, 18 * dp, 18 * dp, 18 * dp)
                    colors = intArrayOf(Color.argb(90, 106, 91, 255), Color.argb(70, 34, 224, 224))
                    orientation = GradientDrawable.Orientation.TL_BR
                } else {
                    cornerRadii = floatArrayOf(4 * dp, 4 * dp, 18 * dp, 18 * dp, 18 * dp, 18 * dp, 18 * dp, 18 * dp)
                    setColor(CARD); setStroke(px(1), Color.argb(45, 34, 224, 224))
                }
            }
            setOnLongClickListener { copy(m.text); true }
            if (m.text.isBlank()) visibility = View.GONE
        }
        wrap.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { if (mine) leftMargin = px(48) else rightMargin = px(28) })
        list.addView(wrap, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        return tv
    }

    private fun addCardBubble(a: JSONObject) {
        val items = a.optJSONArray("items") ?: JSONArray()
        val kind = a.optString("kind")
        val sb = StringBuilder("## ").append(a.optString("title")).append("\n")
        for (i in 0 until items.length()) {
            val it = items.optString(i)
            sb.append(when (kind) { "steps" -> "${i + 1}. "; "checklist" -> "- [ ] "; else -> "- " })
                .append(it.replace("|", "–")).append("\n")
        }
        a.optString("note").takeIf { it.isNotBlank() }?.let { sb.append("\n").append(it) }
        val m = Msg("jarvis", sb.toString().trim())
        msgs += m; addBubble(m); save(); scrollDown()
    }

    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    // ---------- Senden ----------

    private fun send() {
        if (busy) return
        val text = input.text.toString().trim()
        val img = pendingImage
        if (text.isEmpty() && img == null) return
        val b = brain
        if (b == null && tools == null) tools = PhoneTools(this).also { t -> t.onCard = { a -> addCardBubble(a); "Karte im Chat angezeigt." } }

        if (msgs.isEmpty()) list.removeAllViews()   // Begrüßung weg
        val userMsg = Msg("user", text, img?.name)
        msgs += userMsg; addBubble(userMsg)
        input.setText(""); clearPending(keepFile = true)
        val thinking = addBubble(Msg("jarvis", "…"))
        scrollDown()
        setBusy(true)

        val b64 = img?.let { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
        val dots = object : Runnable {
            var n = 0
            override fun run() { if (busy) { n = (n + 1) % 4; if (thinking.text.startsWith("…") || thinking.text.matches(Regex("^\\.*$"))) thinking.text = ".".repeat(n + 1); main.postDelayed(this, 400) } }
        }
        main.post(dots)
        thread {
            val answer = try {
                val offline = b == null || !Offline.isOnline(this)
                val local = if (b64 == null) tools?.let { Offline.handle(it, text) } else null
                when {
                    local != null -> local
                    b == null -> "Trag zuerst deinen Mistral- oder Groq-Schlüssel in der Jarvis-App ein. Ohne Schlüssel verstehe ich nur einfache Befehle wie „Timer 10 Minuten“, „Erinnere mich in 20 Minuten an …“ oder „Was steht heute an?“."
                    offline -> "Du bist gerade offline. Ohne Internet gehen nur einfache Befehle: Timer, Wecker, Taschenlampe, Erinnerungen, Tagesplan, Akku."
                    else -> b.ask(text, { step -> main.post { thinking.text = step } }, b64)
                }
            } catch (e: Exception) { "Da ist etwas schiefgelaufen: ${e.message}" }
            main.post {
                setBusy(false)
                val m = Msg("jarvis", answer)
                msgs += m
                (thinking.parent as? View)?.let { list.removeView(it) }
                addBubble(m); save(); scrollDown()
                if (speakAnswers) speak(answer)
            }
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        core.mode = if (b) CoreView.Mode.THINKING else CoreView.Mode.IDLE
        statusText.text = if (b) "● DENKT NACH …" else "● ONLINE · CHAT"
        statusText.setTextColor(if (b) VIOLET else ACCENT)
    }

    // ---------- Bilder ----------

    private fun pickImage() {
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
                else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        try { startActivityForResult(i, REQ_PICK) } catch (_: Exception) {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PICK)
        }
    }

    private fun takePhoto() {
        if (Build.VERSION.SDK_INT < 29) { toast("Fotos direkt aufnehmen geht erst ab Android 10 – nimm die Galerie."); return }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "jarvis_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Jarvis")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: run { toast("Kamera geht gerade nicht."); return }
        cameraUri = uri
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivityForResult(i, REQ_CAMERA) } catch (_: Exception) { toast("Keine Kamera-App gefunden.") }
    }

    private fun dictate() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Sprich mit Jarvis …")
        try { startActivityForResult(i, REQ_SPEECH) } catch (_: Exception) { toast("Spracherkennung nicht verfügbar.") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PICK -> if (resultCode == RESULT_OK) data?.data?.let { attach(it) }
            REQ_CAMERA -> {
                val u = cameraUri ?: return
                if (resultCode == RESULT_OK) attach(u) else try { contentResolver.delete(u, null, null) } catch (_: Exception) {}
                cameraUri = null
            }
            REQ_SPEECH -> if (resultCode == RESULT_OK) {
                val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
                val cur = input.text.toString()
                input.setText(if (cur.isBlank()) t else "$cur $t")
                input.setSelection(input.text.length)
            }
        }
    }

    /** "Teilen → Jarvis Chat" aus der Galerie oder anderen Apps. */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { attach(it) }
        i.getStringExtra(Intent.EXTRA_TEXT)?.let { input.setText(it) }
    }

    /** Bild verkleinern (max. 1280 px), als JPEG speichern und als Vorschau zeigen. */
    private fun attach(uri: Uri) {
        thread {
            val bmp = try { decodeScaled(uri, 1280) } catch (_: Exception) { null }
            if (bmp == null) { main.post { toast("Das Bild konnte ich nicht öffnen.") }; return@thread }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            val f = File(imgDir, "img_${System.currentTimeMillis()}.jpg")
            f.writeBytes(out.toByteArray())
            main.post {
                pendingImage?.delete()
                pendingImage = f
                previewImg.setImageBitmap(bmp)
                preview.visibility = View.VISIBLE
                input.requestFocus()
            }
        }
    }

    private fun decodeScaled(uri: Uri, max: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = android.graphics.ImageDecoder.createSource(contentResolver, uri)
            return android.graphics.ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                val w = info.size.width; val h = info.size.height
                val s = maxOf(1f, maxOf(w, h) / max.toFloat())
                dec.setTargetSize((w / s).toInt().coerceAtLeast(1), (h / s).toInt().coerceAtLeast(1))
                dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    private fun clearPending(keepFile: Boolean = false) {
        if (!keepFile) pendingImage?.delete()
        pendingImage = null
        preview.visibility = View.GONE
        previewImg.setImageDrawable(null)
    }

    private fun openImage(f: File) {
        if (!f.exists()) return
        val iv = ImageView(this).apply {
            setImageBitmap(BitmapFactory.decodeFile(f.absolutePath)); adjustViewBounds = true; setBackgroundColor(Color.BLACK)
        }
        android.app.AlertDialog.Builder(this).setView(iv).setPositiveButton("Schließen", null).show()
    }

    // ---------- Speichern ----------

    private fun save() {
        val arr = JSONArray()
        msgs.takeLast(MAX_SAVED).forEach {
            arr.put(JSONObject().put("r", it.role).put("t", it.text).put("i", it.image ?: "").put("ts", it.time))
        }
        try { chatFile.writeText(arr.toString()) } catch (_: Exception) {}
    }

    private fun load() {
        msgs.clear()
        try {
            val arr = JSONArray(chatFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                msgs += Msg(o.optString("r"), o.optString("t"), o.optString("i").ifBlank { null }, o.optLong("ts"))
            }
        } catch (_: Exception) {}
    }

    private fun newChat() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Neuer Chat?")
            .setMessage("Der bisherige Verlauf und die Bilder darin werden gelöscht.")
            .setPositiveButton("Neu starten") { _, _ ->
                msgs.clear(); chatFile.delete()
                imgDir.listFiles()?.forEach { it.delete() }
                setupBrain(); render()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ---------- Vorlesen ----------

    private fun toggleSpeak() {
        speakAnswers = !speakAnswers
        speakBtn.text = if (speakAnswers) "🔊" else "🔈"
        if (speakAnswers && speaker == null) speaker = Speaker(this)
        if (!speakAnswers) speaker?.stop()
        toast(if (speakAnswers) "Antworten werden vorgelesen" else "Vorlesen aus")
    }

    private fun speak(text: String) {
        val clean = text.replace(Regex("[*#_`>\\[\\]]"), "").replace(Regex("\\s+"), " ").trim()
        speaker?.speak(clean.take(1500), "chat")
    }

    // ---------- Helfer ----------

    private fun copy(t: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Jarvis", t))
        toast("Kopiert")
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    /** Kleiner Markdown-Darsteller: Überschriften, **fett**, `code`, Listen. */
    private fun markdown(src: String): CharSequence {
        val out = SpannableStringBuilder()
        src.trim().lines().forEachIndexed { idx, raw ->
            if (idx > 0) out.append("\n")
            var line = raw
            var heading = 0
            when {
                line.startsWith("### ") -> { heading = 3; line = line.removePrefix("### ") }
                line.startsWith("## ") -> { heading = 2; line = line.removePrefix("## ") }
                line.startsWith("# ") -> { heading = 1; line = line.removePrefix("# ") }
                Regex("^\\s*[-*] \\[ \\] ").containsMatchIn(line) -> line = "☐  " + line.replaceFirst(Regex("^\\s*[-*] \\[ \\] "), "")
                Regex("^\\s*[-*] ").containsMatchIn(line) -> line = "•  " + line.replaceFirst(Regex("^\\s*[-*] "), "")
            }
            val start = out.length
            appendInline(out, line)
            if (heading > 0) {
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(if (heading == 1) 1.25f else 1.12f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(ACCENT), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    private fun appendInline(out: SpannableStringBuilder, line: String) {
        val re = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
        var last = 0
        for (m in re.findAll(line)) {
            out.append(line.substring(last, m.range.first))
            val s = out.length
            if (m.groupValues[1].isNotEmpty()) {
                out.append(m.groupValues[1])
                out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                out.append(m.groupValues[2])
                out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(Color.argb(40, 255, 255, 255)), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            last = m.range.last + 1
        }
        out.append(line.substring(last))
    }

    override fun onDestroy() {
        speaker?.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val REQ_PICK = 11
        private const val REQ_CAMERA = 12
        private const val REQ_SPEECH = 13
        private const val MAX_SAVED = 200
    }
}
