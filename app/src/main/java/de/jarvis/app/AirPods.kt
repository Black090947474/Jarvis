package de.jarvis.app

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView

/**
 * AirPods-Erkennung: Beim Verbinden erscheint ein Menü wie beim iPhone – mit Akkustand (links, rechts, Case),
 * Musik, Lautstärke und Geräuschkontrolle. Den Akkustand liest Jarvis aus dem Bluetooth-Signal, das AirPods
 * ohnehin aussenden (wie Apps „CAPod“/„OpenPods“). Geräuschunterdrückung umschalten geht auf Samsung-Handys
 * derzeit nur mit Root (Android-Bluetooth-Fehler, behoben ab Android 17 / One UI 9) – dafür gibt es LibrePods.
 */
object AirPods {
    fun isAirPods(name: String) = Regex("airpods|beats|powerbeats", RegexOption.IGNORE_CASE).containsMatchIn(name)

    /** Vom Bluetooth-Empfänger aufgerufen, sobald sich ein Gerät verbindet. */
    fun connected(ctx: Context, name: String) {
        if (!isAirPods(name) || !AutoPilot.on(ctx, "airpods")) return
        val i = Intent(ctx, AirPodsActivity::class.java).putExtra("name", name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        AutoPilot.post(ctx, 88_500, "🎧 $name verbunden", "Tippen für Akkustand, Musik und Geräuschkontrolle.", emptyList(),
            PendingIntent.getActivity(ctx, 88_501, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        // Mit „Über anderen Apps einblenden“ geht das Menü direkt auf
        if (Settings.canDrawOverlays(ctx)) try { ctx.startActivity(i) } catch (_: Exception) {}
    }

    data class Status(val model: String, val left: Int?, val right: Int?, val case: Int?, val chargeL: Boolean, val chargeR: Boolean, val chargeCase: Boolean,
                      val inEarL: Boolean, val inEarR: Boolean)

    /** Apple „Proximity Pairing“-Daten (Hersteller 0x004C, Typ 0x07) auswerten. */
    fun parse(data: ByteArray): Status? {
        if (data.size != 27 || data[0].toInt() != 0x07) return null
        val hex = data.joinToString("") { "%02X".format(it) }
        fun nib(i: Int) = hex[i].digitToInt(16)
        val flip = ((nib(10) + 0x10).toString(2)[3]) == '0'
        fun pct(v: Int) = if (v in 0..10) v * 10 else null
        val l = pct(nib(if (flip) 12 else 13)); val r = pct(nib(if (flip) 13 else 12)); val c = pct(nib(15))
        val ch = nib(14)
        val model = when (hex.substring(6, 10)) {
            "0220" -> "AirPods (1. Gen.)"; "0F20" -> "AirPods (2. Gen.)"; "1320" -> "AirPods (3. Gen.)"
            "0E20" -> "AirPods Pro"; "1420", "2420" -> "AirPods Pro 2"; "1920", "1B20" -> "AirPods 4"; "0A20" -> "AirPods Max"
            else -> "AirPods"
        }
        val ear = nib(11)
        return Status(model, l, r, c, (ch and (if (flip) 0b10 else 0b01)) != 0, (ch and (if (flip) 0b01 else 0b10)) != 0, (ch and 0b100) != 0,
            (ear and (if (flip) 0b1000 else 0b10)) != 0, (ear and (if (flip) 0b10 else 0b1000)) != 0)
    }

    const val LIBREPODS = "me.kavishdevar.librepods"
}

/** Das AirPods-Menü (kleines Fenster unten, wie beim iPhone). */
class AirPodsActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var ui: Ui
    private lateinit var title: TextView
    private lateinit var bat: TextView
    private var scanning = false
    private var best = -200

    private val cb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val d = r.scanRecord?.getManufacturerSpecificData(0x004C) ?: return
            if (r.rssi < -75 || r.rssi < best - 5) return   // nur das nächste Paar (deins)
            val s = AirPods.parse(d) ?: return
            best = maxOf(best, r.rssi)
            runOnUiThread { show(s) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.load(Prefs(this)); ui = Ui(this)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(22), ui.px(22), ui.px(22), ui.px(26))
            background = ui.round(Ui.CARD, 28, Color.argb(26, 255, 255, 255)) }
        title = ui.text("🎧 " + (intent.getStringExtra("name") ?: "AirPods"), 20f, Ui.FG, true)
        col.addView(title)
        bat = ui.text("Lese Akkustand … (AirPods-Case kurz öffnen hilft)", 15f, Ui.SOFT).apply { setPadding(0, ui.px(10), 0, ui.px(4)) }
        col.addView(bat)
        val am = getSystemService(AudioManager::class.java)
        fun media(code: Int) { am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) }
        val row1 = ui.row()
        fun small(t: String, act: () -> Unit) = ui.button(t, false, act).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = ui.px(10); rightMargin = ui.px(6) } }
        row1.addView(small("⏯ Musik") { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) })
        row1.addView(small("⏭ Weiter") { media(KeyEvent.KEYCODE_MEDIA_NEXT) })
        row1.addView(small("🔊 Lauter") { am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) })
        col.addView(row1)
        col.addView(ui.button("🎧 Geräuschunterdrückung / Transparenz") { noise() })
        val row2 = ui.row()
        row2.addView(small("⚙ Bluetooth") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
        row2.addView(small("🎵 Spotify") { packageManager.getLaunchIntentForPackage("com.spotify.music")?.let { startActivity(it) } ?: Toast("Spotify ist nicht installiert.") })
        row2.addView(small("✕ Zu") { finish() })
        col.addView(row2)
        setContentView(col)
        setFinishOnTouchOutside(true)
        startScan()
    }

    private fun Toast(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show()

    private fun show(s: AirPods.Status) {
        title.text = "🎧 " + s.model
        fun p(v: Int?, c: Boolean) = (v?.let { "$it %" } ?: "–") + (if (c) " ⚡" else "")
        bat.text = "Links ${p(s.left, s.chargeL)}   ·   Rechts ${p(s.right, s.chargeR)}   ·   Case ${p(s.case, s.chargeCase)}" +
            (if (s.inEarL || s.inEarR) "\n" + listOfNotNull("links".takeIf { s.inEarL }, "rechts".takeIf { s.inEarR }).joinToString(" & ") + " im Ohr" else "")
        val low = listOfNotNull(s.left, s.right).minOrNull()
        bat.setTextColor(if (low != null && low <= 20) Ui.AMBER else Ui.FG)
    }

    private fun noise() {
        val libre = packageManager.getLaunchIntentForPackage(AirPods.LIBREPODS)
        if (libre != null) { startActivity(libre); return }
        android.app.AlertDialog.Builder(this).setTitle("Geräuschkontrolle")
            .setMessage("Schnell am Ohr: Den Stiel einer AirPod kurz gedrückt halten – dann wechselt sie zwischen Geräuschunterdrückung und Transparenz (bei AirPods Pro, Pro 2 und AirPods 4 mit ANC).\n\n" +
                "Per App umschalten geht auf Android nur über das geheime Apple-Protokoll. Die kostenlose App „LibrePods“ kann das – auf Samsung-Handys aber erst ab Android 17 / One UI 9 ohne Root. " +
                "Sobald LibrePods installiert ist, öffnet dieser Knopf es direkt.")
            .setPositiveButton("OK", null)
            .setNeutralButton("LibrePods ansehen") { _, _ -> try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/kavishdevar/librepods"))) } catch (_: Exception) {} }
            .show()
    }

    private fun startScan() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN), 61); return
        }
        try {
            val sc = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
            sc.startScan(listOf(ScanFilter.Builder().setManufacturerData(0x004C, byteArrayOf(0x07), byteArrayOf(0xFF.toByte())).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            scanning = true
            h.postDelayed({ stopScan(); if (bat.text.startsWith("Lese")) bat.text = "Akkustand nicht empfangen – öffne kurz das Case neben dem Handy." }, 25_000)
        } catch (_: SecurityException) {}
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) { if (g.firstOrNull() == PackageManager.PERMISSION_GRANTED) startScan() else bat.text = "Für den Akkustand bitte „Geräte in der Nähe“ erlauben." }

    private fun stopScan() {
        if (!scanning) return
        try { getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: SecurityException) {}
        scanning = false
    }

    override fun onDestroy() { stopScan(); h.removeCallbacksAndMessages(null); super.onDestroy() }
}
