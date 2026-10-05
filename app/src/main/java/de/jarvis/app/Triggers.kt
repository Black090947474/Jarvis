package de.jarvis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import org.json.JSONObject

/**
 * Auslöser, die Android nur an laufende Apps meldet (Akku, Ladekabel, Kabel-Kopfhörer, WLAN).
 * Läuft im Benachrichtigungs-Dienst mit (der ist dauerhaft aktiv, wenn „Benachrichtigungen lesen“ erlaubt ist).
 */
object Triggers {
    private var receiver: BroadcastReceiver? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var lastLevel = 100
    private var wifiOn = false

    fun start(ctx: Context) {
        stop(ctx)
        val app = ctx.applicationContext
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                        if (isInitialStickyBroadcast) { if (lvl >= 0) lastLevel = lvl; return }
                        if (lvl in 0 until lastLevel) {
                            val prev = lastLevel
                            Thread { Automations.fire(app, "battery", JSONObject().put("level", lvl).put("prev", prev))
                                if (lvl <= 15 && lastLevel > 15) SmartHints.batteryLow(app, lvl) }.start()
                        }
                        if (lvl >= 0) lastLevel = lvl
                    }
                    Intent.ACTION_POWER_CONNECTED -> Thread { Automations.fire(app, "charging") }.start()
                    Intent.ACTION_HEADSET_PLUG -> if (i.getIntExtra("state", 0) == 1 && !isInitialStickyBroadcast)
                        Thread { Automations.fire(app, "headphones", JSONObject().put("device", "Kabel")) }.start()
                }
            }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_HEADSET_PLUG) }
        try { app.registerReceiver(receiver, f) } catch (_: Exception) {}
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        netCb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if (wifi && !wifiOn) {
                    @Suppress("DEPRECATION")
                    val ssid = try { (app.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo?.ssid?.trim('"').orEmpty() } catch (_: Exception) { "" }
                    Thread { Automations.fire(app, "wifi", JSONObject().put("ssid", ssid)) }.start()
                }
                wifiOn = wifi
            }
            override fun onLost(n: Network) { wifiOn = false }
        }
        try { cm.registerDefaultNetworkCallback(netCb!!) } catch (_: Exception) {}
    }

    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        receiver?.let { try { app.unregisterReceiver(it) } catch (_: Exception) {} }
        netCb?.let { try { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        receiver = null; netCb = null
    }
}
