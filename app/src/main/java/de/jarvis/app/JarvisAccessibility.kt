package de.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Bedienungshilfe: Damit kann Jarvis in jeder App den Bildschirm lesen, tippen, schreiben
 * und scrollen – wie ein Mensch. Wird nur aktiv, wenn Jarvis einen Befehl dafür bekommt.
 */
class JarvisAccessibility : AccessibilityService() {

    /** Elemente der letzten Bildschirm-Abfrage, damit "tippe Nummer 5" funktioniert. */
    private var lastNodes: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { if (instance === this) instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    // ---------- Lesen ----------

    /** Wartet kurz, bis nicht mehr Jarvis selbst im Vordergrund ist. */
    private fun root(): AccessibilityNodeInfo? {
        repeat(15) {
            val r = rootInActiveWindow
            if (r != null && r.packageName != packageName) return r
            Thread.sleep(200)
        }
        return rootInActiveWindow
    }

    fun read(maxChars: Int = 2500): String {
        val root = root() ?: return "Fehler: Der Bildschirm lässt sich gerade nicht lesen."
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collect(root, nodes, 0)
        lastNodes = nodes
        val sb = StringBuilder("App: ${appName(root.packageName)}\n")
        nodes.forEachIndexed { i, n ->
            val label = label(n)
            val kind = when {
                n.isEditable -> " (Eingabefeld)"
                n.isCheckable -> if (n.isChecked) " (an)" else " (aus)"
                n.isClickable || clickableParent(n) != null -> " (antippbar)"
                else -> ""
            }
            val name = label.ifBlank { idName(n)?.let { "„$it“" } ?: "Knopf ohne Namen" } +
                if (label.isBlank()) " (${where(n)})" else ""
            val line = "[$i] $name$kind\n"
            if (sb.length + line.length > maxChars) { sb.append("… (gekürzt)\n"); return sb.toString() }
            sb.append(line)
        }
        if (nodes.isEmpty()) sb.append("(keine lesbaren Elemente)")
        return sb.toString()
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (n == null || depth > 40 || out.size >= 80) return
        val unnamedButton = n.isClickable && label(n).isBlank() && !hasLabeledChild(n, 0)
        if (n.isVisibleToUser && (label(n).isNotBlank() || n.isEditable || unnamedButton)) out += n
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }

    /** Beschriftung (inkl. Kennung) des Elements mit dieser Nummer aus dem letzten read – für Sicherheitsabfragen. */
    fun describeIndex(index: Int): String = lastNodes.getOrNull(index)?.let { label(it) + " " + (idName(it) ?: "") }.orEmpty()

    private fun label(n: AccessibilityNodeInfo): String {
        val t = n.text?.toString()?.trim().orEmpty()
        val d = n.contentDescription?.toString()?.trim().orEmpty()
        val h = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim().orEmpty() else ""
        return listOf(t, d.takeIf { it != t }.orEmpty(), if (t.isEmpty()) h else "")
            .filter { it.isNotEmpty() }.joinToString(" – ").replace('\n', ' ').take(120)
    }

    private fun hasLabeledChild(n: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 4) return false
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (label(c).isNotBlank() || hasLabeledChild(c, depth + 1)) return true
        }
        return false
    }

    private fun idName(n: AccessibilityNodeInfo): String? =
        n.viewIdResourceName?.substringAfter(":id/")?.replace('_', ' ')?.takeIf { it.isNotBlank() }

    /** Grobe Lage auf dem Bildschirm, z. B. "unten Mitte". */
    private fun where(n: AccessibilityNodeInfo): String {
        val r = Rect().also { n.getBoundsInScreen(it) }
        val dm = resources.displayMetrics
        val v = when { r.centerY() < dm.heightPixels / 3 -> "oben"; r.centerY() > dm.heightPixels * 2 / 3 -> "unten"; else -> "Mitte" }
        val h = when { r.centerX() < dm.widthPixels / 3 -> "links"; r.centerX() > dm.widthPixels * 2 / 3 -> "rechts"; else -> "Mitte" }
        return if (v == h) "Mitte" else "$v $h"
    }

    private fun appName(pkg: CharSequence?): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg.toString(), 0)).toString()
    } catch (_: Exception) { pkg?.toString().orEmpty() }

    // ---------- Handeln ----------

    fun tap(index: Int?, text: String?): String {
        val node = findTarget(index, text) ?: return "Fehler: Element nicht gefunden. Erst mit read nachsehen."
        val target = if (node.isClickable) node else clickableParent(node)
        val ok = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true || tapAt(node)
        return if (ok) after("Angetippt: ${label(node).ifBlank { "Element" }}.") else "Fehler: Antippen hat nicht geklappt."
    }

    fun type(text: String, index: Int?): String {
        val root = root() ?: return "Fehler: Bildschirm nicht lesbar."
        val field = index?.let { lastNodes.getOrNull(it) }?.takeIf { it.isEditable }
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: firstEditable(root)
            ?: return "Fehler: Kein Eingabefeld gefunden. Erst ins Feld tippen."
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) after("Geschrieben: „$text“.") else "Fehler: Schreiben hat nicht geklappt."
    }

    fun enter(): String {
        val root = root() ?: return "Fehler: Bildschirm nicht lesbar."
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstEditable(root)
        if (Build.VERSION.SDK_INT >= 30 && field != null &&
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
            return after("Eingabe bestätigt.")
        }
        return "Fehler: Bestätigen geht hier nicht. Tippe stattdessen auf den Suchen- oder Senden-Knopf."
    }

    fun scroll(down: Boolean): String {
        val root = root() ?: return "Fehler: Bildschirm nicht lesbar."
        val s = firstScrollable(root)
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val ok = s?.performAction(action) == true || swipe(down)
        return if (ok) after(if (down) "Nach unten gescrollt." else "Nach oben gescrollt.") else "Fehler: Scrollen ging nicht."
    }

    /** Öffnet die Schnelleinstellungen und tippt die passende Kachel an. */
    fun toggleTile(tile: String): String {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        Thread.sleep(800)
        // Manche Kacheln stehen erst auf der zweiten Seite – zweimal versuchen
        repeat(2) { page ->
            val root = rootInActiveWindow
            if (root != null) {
                val hit = root.findAccessibilityNodeInfosByText(tile)
                    .firstOrNull { it.isVisibleToUser }
                if (hit != null) {
                    val target = if (hit.isClickable) hit else clickableParent(hit)
                    val ok = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true || tapAt(hit)
                    if (ok) { Thread.sleep(400); performGlobalAction(GLOBAL_ACTION_HOME); return "$tile umgeschaltet." }
                }
            }
            if (page == 0) { swipeLeft(); Thread.sleep(500) }   // nächste Seite der Schnelleinstellungen
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
        return "Fehler: Kachel „$tile“ nicht in den Schnelleinstellungen gefunden."
    }

    fun global(action: String): String {
        val (id, msg) = when (action) {
            "back" -> GLOBAL_ACTION_BACK to "Zurück."
            "home" -> GLOBAL_ACTION_HOME to "Startbildschirm."
            "recents" -> GLOBAL_ACTION_RECENTS to "Letzte Apps geöffnet."
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS to "Benachrichtigungen geöffnet."
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS to "Schnelleinstellungen geöffnet."
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT to "Screenshot gemacht."
                            else return "Fehler: Screenshot geht erst ab Android 9."
            "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN to "Bildschirm gesperrt."
                      else return "Fehler: Sperren geht erst ab Android 9."
            else -> return "Fehler: Unbekannte Aktion $action."
        }
        return if (performGlobalAction(id)) msg else "Fehler: $msg hat nicht geklappt."
    }

    /** Nach einer Aktion kurz warten und den neuen Bildschirm (gekürzt) mitliefern. */
    private fun after(msg: String): String {
        Thread.sleep(900)
        return msg + "\nBildschirm jetzt:\n" + read(1500)
    }

    // ---------- Helfer ----------

    private fun findTarget(index: Int?, text: String?): AccessibilityNodeInfo? {
        if (index != null) lastNodes.getOrNull(index)?.let { return it }
        if (text.isNullOrBlank()) return null
        val root = root() ?: return null
        val hits = root.findAccessibilityNodeInfosByText(text).filter { it.isVisibleToUser }
        return hits.firstOrNull { label(it).equals(text, true) } ?: hits.firstOrNull()
    }

    private fun clickableParent(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p = n.parent
        var depth = 0
        while (p != null && depth < 6) {
            if (p.isClickable) return p
            p = p.parent; depth++
        }
        return null
    }

    private fun firstEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) firstEditable(n.getChild(i))?.let { return it }
        return null
    }

    private fun firstScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) firstScrollable(n.getChild(i))?.let { return it }
        return null
    }

    private fun tapAt(n: AccessibilityNodeInfo): Boolean {
        val r = Rect().also { n.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        val p = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        return dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null)
    }

    private fun swipeLeft(): Boolean {
        val dm = resources.displayMetrics
        val y = dm.heightPixels * 0.3f
        val p = Path().apply { moveTo(dm.widthPixels * 0.85f, y); lineTo(dm.widthPixels * 0.15f, y) }
        return dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 300)).build(), null, null)
    }

    private fun swipe(down: Boolean): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val (y1, y2) = if (down) dm.heightPixels * 0.75f to dm.heightPixels * 0.3f
                       else dm.heightPixels * 0.3f to dm.heightPixels * 0.75f
        val p = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        return dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 350)).build(), null, null)
    }

    companion object {
        @Volatile var instance: JarvisAccessibility? = null
            private set
        val isOn: Boolean get() = instance != null
    }
}
