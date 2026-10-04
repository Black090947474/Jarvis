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

/** Lern-Modus: Vokabeln/Karteikarten abfragen, schwierige Karten kommen öfter. */
object QuizTool : JarvisTool {
    override val name = "learn"
    override val group = "info"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"learn","description":"Lern-Modus mit Karteikarten. add: Karten in ein Thema (deck) speichern, cards = Liste 'Frage = Antwort'. next: nächste Karte zum Abfragen holen (stell nur die Frage, verrate die Antwort nicht). answer: Ergebnis speichern (deck, id, correct). decks: Themen mit Lernstand. delete_deck: Thema löschen (mit confirmed). Beim Abfragen: Frage stellen, Antwort des Nutzers großzügig bewerten (Tippfehler ok), answer aufrufen, dann nächste Karte.",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["add","next","answer","decks","delete_deck"]},
  "deck":{"type":"string"},"cards":{"type":"array","items":{"type":"string"}},
  "id":{"type":"integer"},"correct":{"type":"boolean"},"confirmed":{"type":"boolean"}},"required":["action"]}}
""".trimIndent())

    private fun key(deck: String) = "deck_" + deck.lowercase().trim().replace(Regex("[^a-z0-9äöüß]+"), "_")

    override fun run(t: ToolContext, a: JSONObject): String {
        val store = JsonStore(t.ctx)
        val deck = a.optString("deck").trim()
        val index = store.obj("decks")
        return when (a.optString("action")) {
            "add" -> {
                if (deck.isBlank()) return Res.error("Wie heißt das Thema?")
                val arr = a.optJSONArray("cards") ?: return Res.error("Keine Karten.")
                val cards = store.list(key(deck))
                var n = 0
                for (i in 0 until arr.length()) {
                    val raw = arr.optString(i)
                    val parts = if (raw.contains("=")) raw.split("=", limit = 2).map { it.trim() }
                                else raw.split(Regex("\\s+(–|-|:)\\s+|\\s*:\\s*"), limit = 2)
                    if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) continue
                    cards += JSONObject().put("id", store.newId()).put("q", parts[0]).put("a", parts[1]).put("ok", 0).put("bad", 0); n++
                }
                store.save(key(deck), cards); store.saveObj("decks", index.put(key(deck), deck))
                Res.ok("$n Karten in „$deck“ gespeichert (jetzt ${cards.size}).")
            }
            "next" -> {
                val cards = store.list(key(deck))
                if (cards.isEmpty()) return Res.error("Im Thema „$deck“ gibt es keine Karten. Erst mit add anlegen.")
                // Schwierige und neue Karten bevorzugen, etwas Zufall
                val pick = cards.sortedBy { it.optInt("ok") * 2 - it.optInt("bad") * 3 + Math.random() * 3 }.first()
                Res.ok("Karte id ${pick.optInt("id")}: Frage: ${pick.optString("q")} | (Lösung, nicht verraten: ${pick.optString("a")})")
            }
            "answer" -> {
                val cards = store.list(key(deck))
                val c = cards.firstOrNull { it.optInt("id") == a.optInt("id", -1) } ?: return Res.error("Karte nicht gefunden.")
                if (a.optBoolean("correct")) c.put("ok", c.optInt("ok") + 1) else c.put("bad", c.optInt("bad") + 1)
                store.save(key(deck), cards)
                val known = cards.count { it.optInt("ok") > it.optInt("bad") }
                Res.ok("Gespeichert. Lernstand „$deck“: $known von ${cards.size} sitzen.")
            }
            "delete_deck" -> {
                if (!a.optBoolean("confirmed")) return Res.confirm("Thema „$deck“ mit allen Karten löschen.")
                store.save(key(deck), emptyList()); index.remove(key(deck)); store.saveObj("decks", index)
                Res.ok("Thema „$deck“ gelöscht.")
            }
            else -> {
                val names = index.keys().asSequence().toList()
                if (names.isEmpty()) Res.ok("Noch keine Lernthemen. Sag z. B. „Speicher Vokabeln: dog = Hund, cat = Katze“.")
                else Res.ok("Lernthemen:\n" + names.joinToString("\n") { k ->
                    val cs = store.list(k); "${index.optString(k)}: ${cs.size} Karten, ${cs.count { it.optInt("ok") > it.optInt("bad") }} sitzen" })
            }
        }
    }
}
