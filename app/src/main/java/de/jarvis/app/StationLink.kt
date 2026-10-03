package de.jarvis.app

import android.app.Activity
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Verbindung zwischen zwei Handys im selben WLAN.
 * Die Station (zweites Handy) öffnet einen kleinen Server; das Haupthandy schickt ihm
 * Werkzeug-Befehle ("spiel Musik", "Wecker auf 7"), geschützt durch einen 6-stelligen Code.
 */
object StationLink {
    const val PORT = 48765
    private const val TYPE = "_jarvis._tcp."
    private const val TAG = "JarvisLink"

    /** Werkzeuge, die die Station für das Haupthandy ausführen darf. */
    private val BLOCKED = setOf("second_phone", "show_card", "remember", "forget")

    // ---------------- Station (Server) ----------------

    class Server(
        private val activity: Activity,
        private val code: String,
        private val onEvent: (String) -> Unit,
    ) {
        @Volatile private var running = false
        private var server: ServerSocket? = null
        private val tools by lazy { PhoneTools(activity) }
        private var fails = 0
        private var lockedUntil = 0L
        private var nsd: NsdManager? = null
        private var reg: NsdManager.RegistrationListener? = null

        fun start() {
            if (running) return
            running = true
            thread(name = "jarvis-station") { loop() }
        }

        fun stop() {
            running = false
            try { server?.close() } catch (_: Exception) {}
            try { reg?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
            reg = null
        }

        private fun loop() {
            try {
                val ss = ServerSocket(PORT).also { server = it }
                register()
                while (running) {
                    val s = ss.accept()
                    thread { handle(s) }
                }
            } catch (e: Exception) {
                if (running) onEvent("Verbindungsfehler: ${e.message}")
            }
        }

        private fun register() {
            val m = activity.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsd = m
            val info = NsdServiceInfo().apply {
                serviceName = "Jarvis-Station ${Build.MODEL}"
                serviceType = TYPE
                port = PORT
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { Log.w(TAG, "NSD reg failed $e") }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            reg = l
            try { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { Log.w(TAG, "NSD", e) }
        }

        private fun handle(s: Socket) {
            try {
                s.use {
                    it.soTimeout = 30_000
                    val line = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8)).readLine() ?: return
                    val resp = process(JSONObject(line))
                    it.getOutputStream().write((resp.toString() + "\n").toByteArray(Charsets.UTF_8))
                    it.getOutputStream().flush()
                }
            } catch (e: Exception) { Log.w(TAG, "handle", e) }
        }

        @Synchronized
        private fun process(req: JSONObject): JSONObject {
            val now = SystemClock.elapsedRealtime()
            if (now < lockedUntil) return err("Zu viele falsche Codes – kurz gesperrt.")
            if (req.optString("code") != code) {
                if (++fails >= 5) { lockedUntil = now + 60_000; fails = 0 }
                return err("Falscher Kopplungscode.")
            }
            fails = 0
            val tool = req.optString("tool")
            if (tool == "ping") return ok("${Build.MODEL} bereit")
            if (tool.isBlank() || tool in BLOCKED) return err("Das geht auf der Station nicht.")
            val input = try { JSONObject(req.optString("args").ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
            onEvent("▶ ${tools.label(tool)}")
            val result = tools.execute(tool, input)
            onEvent(if (result.startsWith("Fehler")) "✕ $result" else "✓ $result")
            return if (result.startsWith("Fehler")) err(result) else ok(result)
        }

        private fun ok(r: String) = JSONObject().put("ok", true).put("result", r)
        private fun err(r: String) = JSONObject().put("ok", false).put("result", r)
    }

    // ---------------- Haupthandy (Client) ----------------

    data class Found(val name: String, val host: String, val port: Int)

    /** Sucht eine Station im WLAN (blockierend, nicht auf dem Hauptthread aufrufen). */
    fun find(ctx: Context, timeoutMs: Long = 5000): Found? {
        val m = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val latch = CountDownLatch(1)
        var result: Found? = null
        var resolving = false
        val disc = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                if (resolving || !i.serviceName.startsWith("Jarvis-Station")) return
                resolving = true
                @Suppress("DEPRECATION")
                m.resolveService(i, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, e: Int) { resolving = false }
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = si.host?.hostAddress
                        if (host != null) result = Found(si.serviceName.removePrefix("Jarvis-Station ").trim(), host, si.port)
                        latch.countDown()
                    }
                })
            }
        }
        try { m.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, disc) } catch (_: Exception) { return null }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        try { m.stopServiceDiscovery(disc) } catch (_: Exception) {}
        return result
    }

    /** Schickt einen Befehl an die Station. Gibt (ok, Text) zurück. Blockierend. */
    fun send(host: String, port: Int, code: String, tool: String, args: String): Pair<Boolean, String> {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 3000)
            s.soTimeout = 45_000
            val req = JSONObject().put("code", code).put("tool", tool).put("args", args)
            s.getOutputStream().write((req.toString() + "\n").toByteArray(Charsets.UTF_8))
            s.getOutputStream().flush()
            val line = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).readLine()
                ?: return false to "Keine Antwort von der Station."
            val r = JSONObject(line)
            return r.optBoolean("ok") to r.optString("result")
        }
    }

    /** Eigene IP-Adresse im WLAN (für die Anzeige auf der Station). */
    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (_: Exception) { null }
}
