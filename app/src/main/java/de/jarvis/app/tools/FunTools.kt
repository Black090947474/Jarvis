package de.jarvis.app.tools

import android.content.ContentUris
import android.content.Intent
import android.provider.MediaStore
import de.jarvis.app.FocusActivity
import de.jarvis.app.logic.TimeLogic
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/** Taschengeld & Ausgaben mit Sparziel – nur lokal auf dem Handy. */
object MoneyTool : JarvisTool {
    override val name = "money"
    override val group = "agenda"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"money","description":"Taschengeld/Ausgaben-Buch. add: Ausgabe (amount positiv, what, category z. B. Essen, Spiele, Kleidung) oder Einnahme (income=true, z. B. Taschengeld). month: Übersicht des Monats nach Kategorien. balance: Kontostand (alles eingenommen minus ausgegeben). goal: Sparziel setzen (name, amount). undo: letzten Eintrag löschen (confirmed).",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["add","month","balance","goal","undo"]},
  "amount":{"type":"number"},"what":{"type":"string"},"category":{"type":"string"},"income":{"type":"boolean"},
  "name":{"type":"string"},"confirmed":{"type":"boolean"}},"required":["action"]}}
""".trimIndent())

    private fun eur(v: Double) = String.format(Locale.GERMANY, "%.2f €", v)

    fun balance(store: JsonStore) = store.list("money").sumOf { if (it.optBoolean("in")) it.optDouble("a") else -it.optDouble("a") }

    override fun run(t: ToolContext, a: JSONObject): String {
        val store = JsonStore(t.ctx)
        val all = store.list("money")
        return when (a.optString("action")) {
            "add" -> {
                val amt = a.optDouble("amount", Double.NaN).takeIf { !it.isNaN() && it > 0 } ?: return Res.error("Wie viel Euro?")
                val inc = a.optBoolean("income")
                store.save("money", all + JSONObject().put("id", store.newId()).put("a", amt).put("in", inc)
                    .put("w", a.optString("what")).put("c", a.optString("category").ifBlank { if (inc) "Einnahme" else "Sonstiges" })
                    .put("at", System.currentTimeMillis()))
                val bal = balance(store)
                val goal = store.obj("money_goal")
                Res.ok((if (inc) "Eingetragen: +${eur(amt)}" else "Eingetragen: −${eur(amt)} für ${a.optString("what")}") + ". Stand: ${eur(bal)}." +
                    (if (goal.has("amount")) " Sparziel „${goal.optString("name")}“: ${(bal / goal.optDouble("amount") * 100).toInt().coerceIn(0, 100)} %." else ""))
            }
            "month" -> {
                val z = ZoneId.systemDefault(); val ym = YearMonth.now()
                val rows = all.filter { YearMonth.from(Instant.ofEpochMilli(it.optLong("at")).atZone(z)) == ym }
                val out = rows.filter { !it.optBoolean("in") }; val inc = rows.filter { it.optBoolean("in") }.sumOf { it.optDouble("a") }
                if (rows.isEmpty()) Res.ok("Diesen Monat noch nichts eingetragen.")
                else Res.ok("${ym.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.GERMANY)}: eingenommen ${eur(inc)}, ausgegeben ${eur(out.sumOf { it.optDouble("a") })}.\n" +
                    out.groupBy { it.optString("c") }.entries.sortedByDescending { e -> e.value.sumOf { it.optDouble("a") } }
                        .joinToString("\n") { (c, l) -> "• $c: ${eur(l.sumOf { it.optDouble("a") })}" } +
                    "\nLetzte: " + out.takeLast(5).reversed().joinToString(", ") { "${it.optString("w")} ${eur(it.optDouble("a"))}" })
            }
            "goal" -> {
                val amt = a.optDouble("amount", 0.0); if (amt <= 0) return Res.error("Wie viel willst du sparen?")
                store.saveObj("money_goal", JSONObject().put("name", a.optString("name").ifBlank { "Sparziel" }).put("amount", amt))
                Res.ok("Sparziel „${a.optString("name")}“ über ${eur(amt)} gesetzt. Aktuell: ${eur(balance(store))}.")
            }
            "undo" -> {
                val last = all.lastOrNull() ?: return Res.error("Nichts zum Löschen.")
                if (!a.optBoolean("confirmed")) return Res.confirm("Letzten Eintrag (${last.optString("w")} ${eur(last.optDouble("a"))}) löschen.")
                store.save("money", all.dropLast(1)); Res.ok("Gelöscht.")
            }
            else -> {
                val goal = store.obj("money_goal"); val bal = balance(store)
                Res.ok("Stand: ${eur(bal)}." + (if (goal.has("amount")) " Sparziel „${goal.optString("name")}“: ${eur(bal)} von ${eur(goal.optDouble("amount"))}." else ""))
            }
        }
    }
}

/** Quiz-Abend: Jarvis als Quizmaster, Punkte für mehrere Spieler. */
object PartyQuizTool : JarvisTool {
    override val name = "party_quiz"
    override val group = "info"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"party_quiz","description":"Quiz-Abend mit Freunden. start: Spieler (players) und optional Thema festlegen. Danach stellst DU abwechselnd Fragen (selbst ausgedacht, passend zum Thema, fair, nicht zu schwer), bewertest Antworten und gibst mit score Punkte (player, points). standings: Zwischenstand. end: Sieger verkünden.",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["start","score","standings","end"]},
  "players":{"type":"array","items":{"type":"string"}},"topic":{"type":"string"},
  "player":{"type":"string"},"points":{"type":"integer"}},"required":["action"]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String {
        val store = JsonStore(t.ctx)
        val g = store.obj("party")
        fun table(o: JSONObject) = o.optJSONObject("score")?.let { s -> s.keys().asSequence().map { it to s.optInt(it) }.sortedByDescending { it.second }.toList() } ?: emptyList()
        return when (a.optString("action")) {
            "start" -> {
                val players = a.optJSONArray("players") ?: return Res.error("Wer spielt mit?")
                val score = JSONObject(); for (i in 0 until players.length()) score.put(players.optString(i), 0)
                store.saveObj("party", JSONObject().put("topic", a.optString("topic")).put("score", score).put("round", 0))
                Res.ok("Quiz gestartet mit ${(0 until players.length()).joinToString(", ") { players.optString(it) }}" +
                    (if (a.optString("topic").isNotBlank()) ", Thema ${a.optString("topic")}" else "") + ". Stell jetzt die erste Frage.")
            }
            "score" -> {
                val s = g.optJSONObject("score") ?: return Res.error("Erst start.")
                val p = s.keys().asSequence().firstOrNull { it.equals(a.optString("player"), true) } ?: a.optString("player")
                s.put(p, s.optInt(p) + a.optInt("points", 1)); store.saveObj("party", g.put("round", g.optInt("round") + 1))
                Res.ok("Punkte: " + table(g).joinToString(", ") { "${it.first} ${it.second}" })
            }
            "end" -> {
                val tb = table(g); store.saveObj("party", JSONObject())
                if (tb.isEmpty()) Res.ok("Kein Quiz aktiv.") else Res.ok("Endstand: " + tb.joinToString(", ") { "${it.first} ${it.second}" } + ". Gewinner: ${tb.first().first}!")
            }
            else -> Res.ok(table(g).joinToString(", ") { "${it.first} ${it.second}" }.ifBlank { "Kein Quiz aktiv." })
        }
    }
}

/** Fokus-Timer (Pomodoro): Lernblöcke mit Pausen, „Nicht stören“ an, zählt die Lernzeit. */
object FocusTool : JarvisTool {
    override val name = "focus"
    override val group = "agenda"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"focus","description":"Fokus-/Lern-Timer (Pomodoro): öffnet den Fokus-Bildschirm. minutes (Standard 25), pause (Standard 5), rounds (Standard 4), subject (Fach). Schaltet währenddessen 'Nicht stören' an, wenn erlaubt.",
 "input_schema":{"type":"object","properties":{"minutes":{"type":"integer"},"pause":{"type":"integer"},"rounds":{"type":"integer"},"subject":{"type":"string"}},"required":[]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String = t.launch(Intent(t.ctx, FocusActivity::class.java)
        .putExtra("minutes", a.optInt("minutes", 25).coerceIn(1, 120)).putExtra("pause", a.optInt("pause", 5).coerceIn(1, 30))
        .putExtra("rounds", a.optInt("rounds", 4).coerceIn(1, 8)).putExtra("subject", a.optString("subject")).putExtra("autostart", true),
        "Fokus-Timer läuft: ${a.optInt("minutes", 25)} Minuten" + (a.optString("subject").takeIf { it.isNotBlank() }?.let { " $it" } ?: "") + ". Viel Erfolg!")
}

/** Fotos finden nach Zeitraum, Ordner (Kamera, Screenshots, WhatsApp) und Ort. */
object PhotosTool : JarvisTool {
    override val name = "photos"
    override val group = "device"
    override val feature = "photos"
    override val permissions = listOf("android.permission.READ_MEDIA_IMAGES")

    override val definition: JSONObject = JSONObject("""
{"name":"photos","description":"Fotos finden. find: nach Zeitraum (from/to YYYY-MM-DD, z. B. letzter Sommer = 2026-06-01 bis 2026-08-31), Ordner (folder: camera, screenshots, whatsapp, alle) und optional Ort (place, z. B. Strand-Ort oder Stadt). Liefert Liste mit id. open: Foto mit id anzeigen. Den Bildinhalt kann Jarvis erst sehen, wenn der Nutzer das Foto im Chat schickt.",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["find","open"]},"from":{"type":"string"},"to":{"type":"string"},
  "folder":{"type":"string","enum":["camera","screenshots","whatsapp","alle"]},"place":{"type":"string"},"id":{"type":"integer"}},"required":["action"]}}
""".trimIndent())

    override fun run(t: ToolContext, a: JSONObject): String {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"
        if (!t.has(perm)) return Res.noPermission("deine Fotos", "Bitte „Fotos“ im Berechtigungszentrum erlauben.")
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        if (a.optString("action") == "open") {
            val id = a.optLong("id", -1); if (id < 0) return Res.error("Welches Foto (id)?")
            return t.launch(Intent(Intent.ACTION_VIEW).setDataAndType(ContentUris.withAppendedId(uri, id), "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Foto ist offen.")
        }
        val z = ZoneId.systemDefault()
        val from = a.optString("from").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it.take(10)).atStartOfDay(z).toInstant().toEpochMilli() }.getOrNull() } ?: 0L
        val to = a.optString("to").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it.take(10)).plusDays(1).atStartOfDay(z).toInstant().toEpochMilli() }.getOrNull() } ?: Long.MAX_VALUE
        val folder = a.optString("folder", "alle")
        val sel = StringBuilder("${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?")
        val args = mutableListOf(from.toString(), to.toString())
        when (folder) {
            "camera" -> { sel.append(" AND ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"); args += "Camera" }
            "screenshots" -> { sel.append(" AND ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?"); args += "Screenshot%" }
            "whatsapp" -> { sel.append(" AND ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?"); args += "%WhatsApp%" }
        }
        val rows = mutableListOf<Triple<Long, Long, String>>()
        t.ctx.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            sel.toString(), args.toTypedArray(), "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
            while (c.moveToNext() && rows.size < 400) rows += Triple(c.getLong(0), c.getLong(1), c.getString(2).orEmpty())
        }
        if (rows.isEmpty()) return Res.ok("Keine Fotos in diesem Zeitraum/Ordner gefunden.")
        // Ort über die GPS-Daten im Foto (nur wenn gefragt, max. 60 Fotos prüfen)
        val place = a.optString("place").trim()
        var list = rows
        if (place.isNotBlank()) {
            val geo = android.location.Geocoder(t.ctx, Locale.GERMANY)
            list = rows.take(60).filter { (id, _, _) ->
                try {
                    t.ctx.contentResolver.openInputStream(ContentUris.withAppendedId(uri, id).let { u -> if (android.os.Build.VERSION.SDK_INT >= 29 && t.has("android.permission.ACCESS_MEDIA_LOCATION")) MediaStore.setRequireOriginal(u) else u })?.use { s ->
                        val ll = androidx_exif(s) ?: return@filter false
                        @Suppress("DEPRECATION") val addr = geo.getFromLocation(ll.first, ll.second, 1)?.firstOrNull()
                        listOfNotNull(addr?.locality, addr?.subAdminArea, addr?.featureName, addr?.thoroughfare).any { it.contains(place, true) }
                    } ?: false
                } catch (_: Exception) { false }
            }.toMutableList()
            if (list.isEmpty()) return Res.ok("Unter den neuesten 60 Fotos im Zeitraum ist keins aus „$place“ (oder die Fotos haben keine Ortsdaten).")
        }
        return Res.ok("${list.size} Foto(s):\n" + list.take(12).joinToString("\n") { (id, at, b) -> "id $id | ${TimeLogic.short(at)} | $b" } +
            "\nMit open und id zeige ich eins an.")
    }

    /** GPS-Position aus den EXIF-Daten (android.media.ExifInterface). */
    private fun androidx_exif(s: java.io.InputStream): Pair<Double, Double>? {
        val e = android.media.ExifInterface(s)
        val ll = FloatArray(2)
        @Suppress("DEPRECATION") return if (e.getLatLong(ll)) ll[0].toDouble() to ll[1].toDouble() else null
    }

    @Suppress("unused") private val keep = JSONArray::class
}
