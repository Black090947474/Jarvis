package de.jarvis.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Kamera-Modus „Was ist das?“: Live-Bild, Knopf drücken → Jarvis erkennt und erklärt es (und liest es vor).
 * Funktioniert für Pflanzen, Tiere, Autos, Schilder, Matheaufgaben, Texte zum Übersetzen …
 */
class ScanActivity : Activity() {
    private lateinit var tv: TextureView
    private lateinit var answer: TextView
    private lateinit var answerBox: ScrollView
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var bgThread: HandlerThread? = null
    private var bg: Handler? = null
    private var speaker: Speaker? = null
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        tv = TextureView(this)
        root.addView(tv, FrameLayout.LayoutParams(-1, -1))
        // HUD-Rahmen in der Mitte
        root.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = 22 * dp; setStroke(px(2), Color.argb(200, 0, 230, 255)) }
        }, FrameLayout.LayoutParams(px(250), px(250), Gravity.CENTER).apply { bottomMargin = px(120) })
        root.addView(TextView(this).apply {
            text = "J.A.R.V.I.S · SCAN"; textSize = 13f; letterSpacing = 0.3f; setTextColor(Color.rgb(160, 240, 255)); typeface = Typeface.MONOSPACE
            setShadowLayer(10f, 0f, 0f, Color.CYAN); gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = px(40) })
        root.addView(TextView(this).apply { text = "✕"; textSize = 22f; setTextColor(Color.WHITE); setPadding(px(18), px(30), px(18), px(18)); setOnClickListener { finish() } },
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14), px(10), px(14), px(22))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(240, 2, 8, 16), Color.argb(0, 2, 8, 16))) }
        answer = TextView(this).apply { textSize = 15.5f; setTextColor(Color.WHITE); setLineSpacing(0f, 1.2f)
            setPadding(px(14), px(12), px(14), px(12))
            background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(Color.argb(200, 6, 22, 38)); setStroke(px(1), Color.argb(90, 80, 200, 255)) } }
        answerBox = ScrollView(this).apply { addView(answer); visibility = View.GONE }
        bottom.addView(answerBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(10) })
        val chips = LinearLayout(this)
        fun chip(label: String, prompt: String) = chips.addView(TextView(this).apply {
            text = label; textSize = 14f; setTextColor(Color.rgb(0, 16, 24)); typeface = Typeface.DEFAULT_BOLD
            setPadding(px(16), px(11), px(16), px(11))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(0, 240, 255), Color.rgb(47, 123, 255))).apply { cornerRadius = 22 * dp }
            setOnClickListener { scan(prompt) }
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(8) })
        chip("🔍 Was ist das?", "Was ist auf dem Bild zu sehen? Erkenne das Hauptobjekt (Pflanze, Tier, Auto, Gegenstand, Ort …) und erkläre es in 2–3 Sätzen.")
        chip("📐 Aufgabe erklären", "Auf dem Bild ist eine Schulaufgabe. Erkläre den Lösungsweg Schritt für Schritt, verrate das Endergebnis erst am Schluss.")
        chip("🌍 Übersetzen", "Lies den Text auf dem Bild und übersetze ihn ins Deutsche (bzw. ins Englische, falls er Deutsch ist).")
        chip("📝 Text lesen", "Lies den Text auf dem Bild vollständig vor.")
        chip("🧮 Kalorien?", "Was für ein Essen ist das ungefähr, und wie viele Kalorien hat es grob? Kurz antworten.")
        bottom.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        speaker = Speaker(this)
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.CAMERA), 3); return }
        startBg()
        if (tv.isAvailable) openCamera() else tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) = fitPreview()
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.firstOrNull() != PackageManager.PERMISSION_GRANTED) { answerBox.visibility = View.VISIBLE; answer.text = "Ohne Kamera-Erlaubnis kann ich nichts sehen." }
    }

    override fun onPause() { closeCamera(); super.onPause() }
    override fun onDestroy() { speaker?.shutdown(); super.onDestroy() }

    private fun startBg() { if (bgThread == null) bgThread = HandlerThread("scan-cam").also { it.start(); bg = Handler(it.looper) } }

    /** Bild füllt den Bildschirm (Hochformat), ohne verzerrt zu sein. */
    private fun fitPreview() {
        val vw = tv.width.toFloat(); val vh = tv.height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val content = 1080f / 1920f
        val view = vw / vh
        val m = Matrix()
        if (view > content) m.setScale(1f, view / content, vw / 2, vh / 2) else m.setScale(content / view, 1f, vw / 2, vh / 2)
        tv.setTransform(m)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            val cm = getSystemService(CameraManager::class.java)!!
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: cm.cameraIdList.first()
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; startPreview(d) }
                override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                override fun onError(d: CameraDevice, e: Int) { d.close(); device = null }
            }, bg)
        } catch (e: Exception) { runOnUiThread { answerBox.visibility = View.VISIBLE; answer.text = "Kamera geht nicht: ${e.message}" } }
    }

    @Suppress("DEPRECATION")
    private fun startPreview(d: CameraDevice) {
        try {
            val st = tv.surfaceTexture ?: return
            st.setDefaultBufferSize(1920, 1080)
            val surface = Surface(st)
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface); set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) }
            d.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try { s.setRepeatingRequest(req.build(), null, bg) } catch (_: Exception) {}
                    runOnUiThread { fitPreview() }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, bg)
        } catch (_: Exception) {}
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        session = null; device = null
        bgThread?.quitSafely(); bgThread = null; bg = null
    }

    private fun scan(prompt: String) {
        if (busy) return
        val prefs = Prefs(this)
        if (!prefs.hasAnyKey) { answerBox.visibility = View.VISIBLE; answer.text = "Mir fehlt noch ein KI-Schlüssel."; return }
        val bmp = tv.getBitmap(1080, 1920) ?: return
        busy = true
        answerBox.visibility = View.VISIBLE; answer.text = "Scanne …"
        speaker?.stop()
        thread {
            val out = ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            val text = try {
                Brains.build(prefs, PhoneTools(this), Memory(this), chat = true)
                    .ask("$prompt Antworte auf Deutsch, kurz und klar. Rufe keine Werkzeuge auf.", { s -> runOnUiThread { answer.text = s } }, b64)
            } catch (e: Exception) { "Das hat nicht geklappt: ${e.message?.take(80)}" }
            runOnUiThread {
                busy = false
                if (isFinishing) return@runOnUiThread
                answer.text = text
                speaker?.speak(Persona.clean(text), "scan")
                de.jarvis.app.logic.HoloTopics.match(text)?.let { scene ->
                    answer.append("\n\n◆ Antippen: als Hologramm zeigen (${scene.label})")
                    answer.setOnClickListener { startActivity(Intent(this, JarvisActivity::class.java).putExtra("ask", "Erklär mir kurz: ${scene.label}")) }
                }
            }
        }
    }
}
