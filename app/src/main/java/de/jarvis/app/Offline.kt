package de.jarvis.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import de.jarvis.app.logic.LocalCommands
import de.jarvis.app.tools.Res
import org.json.JSONObject

/** Grundfunktionen ohne Internet: Timer, Wecker, Taschenlampe, Erinnerungen, Tagesplan, Akku. */
object Offline {
    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Versucht den Befehl lokal auszuführen. null = nicht erkannt (dann braucht es die KI). Im Hintergrund aufrufen. */
    fun handle(tools: PhoneTools, text: String): String? {
        val low = text.lowercase()
        if (listOf("zweite", "station", "anderen handy", "und dann", " danach ").any { low.contains(it) }) return null
        // Eigene Kommandos (Routinen) direkt per Namen
        de.jarvis.app.tools.RoutineTool.find(tools.appContext, text)?.let { r ->
            return Res.spoken(tools.execute("routines", JSONObject().put("action", "run").put("name", r.optString("name"))))
        }
        val c = LocalCommands.parse(text) ?: return null
        // "Taschenlampe an und Timer 5 Minuten" ist ein Mehrfach-Befehl → das macht die KI
        if (low.contains(" und ") && c.tool in setOf("flashlight", "set_timer", "set_alarm", "battery", "agenda")) return null
        return Res.spoken(tools.execute(c.tool, JSONObject(c.args)))
    }
}
