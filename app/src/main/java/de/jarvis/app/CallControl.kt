package de.jarvis.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import java.util.Locale

/** Anrufe annehmen/beenden per Stimme und Anrufer ansagen. Alles nur auf deinen Befehl bzw. mit deiner Freigabe. */
object CallControl {
    @Volatile var ringing = false; private set
    @Volatile var inCall = false; private set
    @Volatile var caller: String? = null; private set

    private val ANSWER = Regex("""\b(nimm (ab|an|den anruf an)|annehmen|abnehmen|geh ran|rangehen|anruf annehmen)\b""")
    private val END = Regex("""\b(leg auf|auflegen|ablehnen|anruf (beenden|ablehnen)|wegdrücken|drück weg)\b""")

    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION", "MissingPermission")
    fun answer(ctx: Context): String {
        if (!has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return "BERECHTIGUNG FEHLT: Erlaube „Anrufe annehmen & ansagen“ im Berechtigungszentrum."
        return try { ctx.getSystemService(TelecomManager::class.java)!!.acceptRingingCall(); "OK: Anruf angenommen." }
        catch (e: Exception) { "FEHLER: Konnte den Anruf nicht annehmen." }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    fun end(ctx: Context): String {
        if (!has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return "BERECHTIGUNG FEHLT: Erlaube „Anrufe annehmen & ansagen“ im Berechtigungszentrum."
        return try { if (ctx.getSystemService(TelecomManager::class.java)!!.endCall()) "OK: Aufgelegt." else "OK: Es läuft gerade kein Anruf." }
        catch (e: Exception) { "FEHLER: Konnte nicht auflegen." }
    }

    /** Sprachbefehl direkt erledigen (ohne KI, damit es schnell geht). Gibt null zurück, wenn es kein Anruf-Befehl war. */
    fun handleLocal(ctx: Context, text: String): String? {
        val t = text.lowercase(Locale.GERMANY)
        if ((ringing) && ANSWER.containsMatchIn(t)) return answer(ctx)
        if ((ringing || inCall) && END.containsMatchIn(t)) return end(ctx)
        return null
    }

    fun nameFor(ctx: Context, number: String?): String {
        if (number.isNullOrBlank()) return "unbekannte Nummer"
        if (has(ctx, Manifest.permission.READ_CONTACTS)) try {
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        } catch (_: Exception) {}
        return number.chunked(3).joinToString(" ")
    }

    internal fun onState(ctx: Context, state: String?, number: String?) {
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val first = !ringing
                ringing = true; inCall = false
                if (number != null) caller = nameFor(ctx, number)
                if (first || number != null) if (Prefs(ctx).announceCalls) Announcer.say(ctx, "Anruf von ${caller ?: "unbekannt"}.", delayMs = 1500)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> { ringing = false; inCall = true }
            else -> { ringing = false; inCall = false; caller = null }
        }
    }
}

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        @Suppress("DEPRECATION")
        CallControl.onState(ctx.applicationContext, i.getStringExtra(TelephonyManager.EXTRA_STATE), i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER))
    }
}

/** Kurze Ansagen im Hintergrund (z. B. Anrufer), unabhängig vom Gesprächsbildschirm. */
object Announcer {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<String>()
    private val main = Handler(Looper.getMainLooper())
    private var lastText = ""; private var lastAt = 0L

    fun say(ctx: Context, text: String, delayMs: Long = 0) = main.postDelayed({
        val now = System.currentTimeMillis()
        if (text == lastText && now - lastAt < 8000) return@postDelayed   // nicht doppelt ansagen
        lastText = text; lastAt = now
        val t = tts
        if (t == null) {
            pending += text
            tts = TextToSpeech(ctx.applicationContext) { st ->
                ready = st == TextToSpeech.SUCCESS
                tts?.language = Locale.GERMANY
                tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                if (ready) pending.forEach { p -> tts?.speak(p, TextToSpeech.QUEUE_ADD, null, "ann") }
                pending.clear()
            }
        } else if (ready) t.speak(text, TextToSpeech.QUEUE_ADD, null, "ann")
    }, delayMs)
}
