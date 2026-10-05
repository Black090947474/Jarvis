package de.jarvis.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fragt die Aktions-PIN ab (blockiert den Hintergrund-Thread, bis eingegeben oder abgebrochen). */
object PinGate {
    fun ask(a: Activity, what: String): Boolean {
        val pin = Prefs(a).actionPin
        if (pin.isBlank()) return true
        if (Looper.myLooper() == Looper.getMainLooper()) return false // nie auf dem Hauptthread blockieren
        var ok = false
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            if (a.isFinishing) { latch.countDown(); return@post }
            val input = EditText(a).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                hint = "Aktions-PIN"
            }
            AlertDialog.Builder(a).setTitle("Wichtige Aktion bestätigen").setMessage(what).setView(input)
                .setPositiveButton("Ausführen") { _, _ -> ok = input.text.toString() == pin; latch.countDown() }
                .setNegativeButton("Abbrechen") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await(90, TimeUnit.SECONDS)
        return ok
    }
}
