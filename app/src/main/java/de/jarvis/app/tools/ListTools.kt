package de.jarvis.app.tools

import android.content.Context
import de.jarvis.app.logic.TimeLogic
import org.json.JSONObject

/** Einkaufsliste: hinzufügen, anzeigen, abhaken, leeren. */
object ShoppingTool : JarvisTool {
    override val name = "shopping"
    override val group = "agenda"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"shopping","description":"Einkaufsliste. add: Dinge hinzufügen (items). list: anzeigen. remove: abhaken/entfernen (items). clear: ganze Liste leeren (erst ohne confirmed, nach Ja mit confirmed=true).",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["add","list","remove","clear"]},
  "items":{"type":"array","items":{"type":"string"}},
  "confirmed":{"type":"boolean"}},"required":["action"]}}
""".trimIndent())

    fun items(ctx: Context) = JsonStore(ctx).list("shopping").map { it.optString("t") }

    fun add(ctx: Context, new: List<String>): String {
        val store = JsonStore(ctx)
        val cur = store.list("shopping")
        val have = cur.map { it.optString("t").lowercase() }.toMutableSet()
        val added = new.map { it.trim() }.filter { it.isNotBlank() && have.add(it.lowercase()) }
        store.save("shopping", cur + added.map { JSONObject().put("t", it) })
        return if (added.isEmpty()) Res.ok("Steht schon alles auf der Einkaufsliste.")
        else Res.ok("Auf die Einkaufsliste: ${added.joinToString(", ")}. Jetzt ${cur.size + added.size} Sachen.")
    }

    fun remove(ctx: Context, what: List<String>): String {
        val store = JsonStore(ctx)
        val cur = store.list("shopping")
        val gone = cur.filter { o -> what.any { w -> o.optString("t").contains(w.trim(), true) } }
        store.save("shopping", cur - gone.toSet())
        return if (gone.isEmpty()) Res.error("Das steht nicht auf der Liste.") else Res.ok("Abgehakt: ${gone.joinToString(", ") { it.optString("t") }}.")
    }

    fun list(ctx: Context): String {
        val l = items(ctx)
        return if (l.isEmpty()) Res.ok("Die Einkaufsliste ist leer.") else Res.ok("Einkaufsliste (${l.size}): ${l.joinToString(", ")}.")
    }

    override fun run(t: ToolContext, a: JSONObject): String {
        val items = a.optJSONArray("items")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
        return when (a.optString("action")) {
            "add" -> if (items.isEmpty()) Res.error("Was soll auf die Liste?") else add(t.ctx, items)
            "remove" -> remove(t.ctx, items)
            "clear" -> if (!a.optBoolean("confirmed")) Res.confirm("Die ganze Einkaufsliste (${items(t.ctx).size} Sachen) löschen.")
                       else { JsonStore(t.ctx).save("shopping", emptyList()); Res.ok("Einkaufsliste geleert.") }
            else -> list(t.ctx)
        }
    }
}

/** Notizen: schnell etwas notieren und später wiederfinden. */
object NotesTool : JarvisTool {
    override val name = "notes"
    override val group = "agenda"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"notes","description":"Notizen. add: notieren (text). list: neueste. search: suchen (query). delete: löschen (id, erst ohne confirmed, nach Ja mit confirmed=true).",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["add","list","search","delete"]},
  "text":{"type":"string"},"query":{"type":"string"},"id":{"type":"integer"},"confirmed":{"type":"boolean"}},"required":["action"]}}
""".trimIndent())

    private fun line(o: JSONObject) = "id ${o.optInt("id")} | ${TimeLogic.short(o.optLong("at"))} | ${o.optString("t")}"

    override fun run(t: ToolContext, a: JSONObject): String {
        val store = JsonStore(t.ctx)
        val notes = store.list("notes")
        return when (a.optString("action")) {
            "add" -> {
                val text = a.optString("text").trim().ifBlank { return Res.error("Was soll ich notieren?") }
                store.save("notes", notes + JSONObject().put("id", store.newId()).put("t", text).put("at", System.currentTimeMillis()))
                Res.ok("Notiert: $text")
            }
            "search" -> {
                val q = a.optString("query")
                val hits = notes.filter { it.optString("t").contains(q, true) }
                if (hits.isEmpty()) Res.ok("Keine Notiz zu „$q“.") else Res.ok(hits.takeLast(15).joinToString("\n") { line(it) })
            }
            "delete" -> {
                val n = notes.firstOrNull { it.optInt("id") == a.optInt("id", -1) } ?: return Res.error("Notiz nicht gefunden.")
                if (!a.optBoolean("confirmed")) return Res.confirm("Notiz „${n.optString("t").take(60)}“ löschen.")
                store.save("notes", notes - n); Res.ok("Notiz gelöscht.")
            }
            else -> if (notes.isEmpty()) Res.ok("Noch keine Notizen.") else Res.ok("Notizen:\n" + notes.takeLast(15).reversed().joinToString("\n") { line(it) })
        }
    }
}

/**
 * Lernassistent: Karteikarten in Fächern/Themen, Wiederholung nach dem Leitner-System (Fächer 1–5),
 * Multiple Choice, Prüfungssimulation, Fehleranalyse, Lernstand. Schwierige Karten kommen öfter.
 */
object QuizTool : JarvisTool {
    override val name = "learn"
    override val group = "info"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"learn","description":"Lernassistent mit Karteikarten (deck = Fach/Thema, z. B. 'Englisch Unit 3'). add: Karten speichern, cards = Liste 'Frage = Antwort' (auch aus Fotos/Notizen erstellen). next: nächste fällige Karte (Antwort NICHT verraten; mode=mc liefert zusätzlich 3 falsche Antwortmöglichkeiten für Multiple Choice). answer: Ergebnis speichern (deck,id,correct) – großzügig bewerten. exam: Prüfungssimulation, count Fragen auf einmal; am Ende Note/Ergebnis nennen und mit answer speichern. mistakes: schwierigste Karten (Fehleranalyse). decks: alle Themen mit Lernstand. delete_deck (confirmed). Diktat/offene Fragen/Zusammenfassungen machst du selbst im Gespräch.",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["add","next","answer","exam","mistakes","decks","delete_deck"]},
  "deck":{"type":"string"},"cards":{"type":"array","items":{"type":"string"}},"mode":{"type":"string","enum":["normal","mc"]},
  "count":{"type":"integer"},"id":{"type":"integer"},"correct":{"type":"boolean"},"confirmed":{"type":"boolean"}},"required":["action"]}}
""".trimIndent())

    /** Wiederholungsabstände je Leitner-Fach (Minuten): 1 → gleich wieder, 5 → in 2 Wochen. */
    private val INTERVAL = longArrayOf(0, 10, 24 * 60, 3 * 24 * 60, 7 * 24 * 60, 14 * 24 * 60)

    fun key(deck: String) = "deck_" + deck.lowercase().trim().replace(Regex("[^a-z0-9äöüß]+"), "_")

    private fun findDeck(store: JsonStore, deck: String): String? {
        val index = store.obj("decks")
        if (index.has(key(deck))) return key(deck)
        return index.keys().asSequence().firstOrNull { index.optString(it).contains(deck, true) || deck.contains(index.optString(it), true) }
    }

    private fun box(c: JSONObject) = c.optInt("box", 1).coerceIn(1, 5)
    private fun due(c: JSONObject) = c.optLong("due", 0)

    override fun run(t: ToolContext, a: JSONObject): String {
        val store = JsonStore(t.ctx)
        val deck = a.optString("deck").trim()
        val index = store.obj("decks")
        val now = System.currentTimeMillis()
        val k = if (deck.isBlank()) null else findDeck(store, deck)
        when (a.optString("action")) {
            "add" -> {
                if (deck.isBlank()) return Res.error("Wie heißt das Fach/Thema?")
                val arr = a.optJSONArray("cards") ?: return Res.error("Keine Karten.")
                val kk = k ?: key(deck)
                val cards = store.list(kk)
                var n = 0
                for (i in 0 until arr.length()) {
                    val raw = arr.optString(i)
                    val parts = if (raw.contains("=")) raw.split("=", limit = 2).map { it.trim() }
                                else raw.split(Regex("\\s+(–|-|:)\\s+|\\s*:\\s*"), limit = 2)
                    if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) continue
                    if (cards.any { it.optString("q").equals(parts[0], true) }) continue
                    cards += JSONObject().put("id", store.newId()).put("q", parts[0]).put("a", parts[1]).put("ok", 0).put("bad", 0).put("box", 1).put("due", 0); n++
                }
                store.save(kk, cards); if (!index.has(kk)) store.saveObj("decks", index.put(kk, deck))
                return Res.ok("$n Karten in „${index.optString(kk, deck)}“ gespeichert (jetzt ${cards.size}).")
            }
            "next" -> {
                val kk = k ?: return Res.error("Thema „$deck“ nicht gefunden. Erst mit add anlegen oder decks ansehen.")
                val cards = store.list(kk)
                if (cards.isEmpty()) return Res.error("Keine Karten im Thema.")
                val dueCards = cards.filter { due(it) <= now }
                val pick = (dueCards.ifEmpty { cards }).sortedBy { box(it) * 10 - it.optInt("bad") * 3 + Math.random() * 6 }.first()
                var extra = ""
                if (a.optString("mode") == "mc") {
                    val wrong = cards.filter { it.optInt("id") != pick.optInt("id") }.map { it.optString("a") }.distinct().shuffled().take(3)
                    val options = (wrong + pick.optString("a")).shuffled()
                    extra = " | Antwortmöglichkeiten: " + options.mapIndexed { i, o -> "${'A' + i}) $o" }.joinToString("  ")
                }
                return Res.ok("Karte id ${pick.optInt("id")} (Fach ${box(pick)}/5, ${dueCards.size} fällig): Frage: ${pick.optString("q")}$extra | (Lösung, nicht verraten: ${pick.optString("a")})")
            }
            "exam" -> {
                val kk = k ?: return Res.error("Thema nicht gefunden.")
                val cards = store.list(kk).shuffled().take(a.optInt("count", 10).coerceIn(3, 30))
                return Res.ok("Prüfungssimulation „${index.optString(kk)}“ – ${cards.size} Fragen. Stelle sie nacheinander, bewerte, speichere jede mit answer, am Ende Punkte und Note (1–6):\n" +
                    cards.joinToString("\n") { "id ${it.optInt("id")}: ${it.optString("q")} (Lösung: ${it.optString("a")})" })
            }
            "answer" -> {
                val kk = k ?: return Res.error("Thema nicht gefunden.")
                val cards = store.list(kk)
                val c = cards.firstOrNull { it.optInt("id") == a.optInt("id", -1) } ?: return Res.error("Karte nicht gefunden.")
                val ok = a.optBoolean("correct")
                val nb = if (ok) (box(c) + 1).coerceAtMost(5) else 1
                c.put(if (ok) "ok" else "bad", c.optInt(if (ok) "ok" else "bad") + 1).put("box", nb).put("due", now + INTERVAL[nb] * 60_000L)
                store.save(kk, cards)
                val stats = store.obj("learn_stats")
                val day = java.time.LocalDate.now().toString()
                store.saveObj("learn_stats", stats.put(day, stats.optInt(day) + 1).put("total", stats.optInt("total") + 1))
                return Res.ok("Gespeichert (${if (ok) "richtig → Fach $nb" else "falsch → zurück in Fach 1"}). Lernstand: ${cards.count { box(it) >= 3 }} von ${cards.size} sitzen, ${cards.count { due(it) <= now }} fällig.")
            }
            "mistakes" -> {
                val keys = if (k != null) listOf(k) else index.keys().asSequence().toList()
                val worst = keys.flatMap { kk -> store.list(kk).map { index.optString(kk) to it } }
                    .filter { it.second.optInt("bad") > 0 }.sortedByDescending { it.second.optInt("bad") - it.second.optInt("ok") }.take(10)
                return if (worst.isEmpty()) Res.ok("Noch keine Fehler gespeichert – super!")
                else Res.ok("Schwierigste Karten:\n" + worst.joinToString("\n") { (d, c) -> "$d: ${c.optString("q")} → ${c.optString("a")} (${c.optInt("bad")}× falsch, ${c.optInt("ok")}× richtig)" })
            }
            "delete_deck" -> {
                val kk = k ?: return Res.error("Thema nicht gefunden.")
                if (!a.optBoolean("confirmed")) return Res.confirm("Thema „${index.optString(kk)}“ mit allen Karten löschen.")
                store.save(kk, emptyList()); index.remove(kk); store.saveObj("decks", index)
                return Res.ok("Gelöscht.")
            }
            else -> {
                val names = index.keys().asSequence().toList()
                return if (names.isEmpty()) Res.ok("Noch keine Lernthemen. Sag z. B. „Speicher Vokabeln Englisch: dog = Hund, cat = Katze“ oder schick ein Foto deiner Vokabelliste.")
                else Res.ok("Lernthemen:\n" + names.joinToString("\n") { kk ->
                    val cs = store.list(kk)
                    "${index.optString(kk)}: ${cs.size} Karten, ${cs.count { box(it) >= 3 }} sitzen, ${cs.count { due(it) <= now }} fällig" })
            }
        }
    }
}
