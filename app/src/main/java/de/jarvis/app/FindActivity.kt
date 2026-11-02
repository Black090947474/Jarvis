package de.jarvis.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** „Jarvis, wo bist du?“ – Handy klingelt laut (auch auf lautlos), blinkt und vibriert, bis du tippst (max. 45 s). */
class FindActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var oldVol = -1
    private var torch = false
    private var camId: String? = null
    private var running = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.rgb(2, 7, 14))
            setOnClickListener { stopAll(); finish() }
        }
        col.addView(OrbView(this), LinearLayout.LayoutParams(dp(220), dp(220)))
        col.addView(TextView(this).apply { text = "Hier bin ich!"; textSize = 34f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
        col.addView(TextView(this).apply { text = "Irgendwo antippen zum Stoppen"; textSize = 15f; setTextColor(Color.rgb(100, 200, 255)); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        setContentView(col)
        start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun start() {
        val am = getSystemService(AudioManager::class.java)!!
        try { oldVol = am.getStreamVolume(AudioManager.STREAM_ALARM); am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) } catch (_: Exception) {}
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@FindActivity, uri); isLooping = true; prepare(); start()
            }
        } catch (_: Exception) {}
        try {
            val v = getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
        } catch (_: Exception) {}
        try {
            val cm = getSystemService(CameraManager::class.java)!!
            camId = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        } catch (_: Exception) {}
        blink()
        h.postDelayed({ stopAll(); finish() }, 45_000)
    }

    private fun blink() {
        if (!running) return
        val id = camId ?: return
        try { torch = !torch; getSystemService(CameraManager::class.java)!!.setTorchMode(id, torch) } catch (_: Exception) { return }
        h.postDelayed({ blink() }, 350)
    }

    private fun stopAll() {
        if (!running) return
        running = false
        h.removeCallbacksAndMessages(null)
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
        try { getSystemService(Vibrator::class.java)?.cancel() } catch (_: Exception) {}
        try { camId?.let { getSystemService(CameraManager::class.java)!!.setTorchMode(it, false) } } catch (_: Exception) {}
        if (oldVol >= 0) try { getSystemService(AudioManager::class.java)!!.setStreamVolume(AudioManager.STREAM_ALARM, oldVol, 0) } catch (_: Exception) {}
    }

    override fun onDestroy() { stopAll(); super.onDestroy() }

    companion object {
        private val FIND = Regex("""\b(wo bist du|wo ist mein handy|wo ist das handy|finde? mein handy|ich finde (mein|das) handy nicht|handy suchen|such mein handy)\b""")
        fun matches(text: String) = FIND.containsMatchIn(text.lowercase())
        fun start(ctx: Context) = ctx.startActivity(android.content.Intent(ctx, FindActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
