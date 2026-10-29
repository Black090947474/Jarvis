package de.jarvis.app.logic

/**
 * Welches 3D-Hologramm passt zu einer Frage oder Antwort?
 * Gibt die Szene zurück (siehe [Scene]) oder null, wenn kein eingebautes Modell passt.
 */
object HoloTopics {
    enum class Scene(val id: Int, val label: String) {
        HEAD(0, "Jarvis"), BRACHIO(1, "Brachiosaurus"), TREX(2, "T-Rex"), TRICERA(3, "Triceratops"),
        PLANET(4, "Saturn"), EARTH(5, "Erde"), ROCKET(6, "Rakete"), HEART(7, "Herz"),
        VOLCANO(8, "Vulkan"), ATOM(9, "Atom");
        companion object { fun of(id: Int) = entries.firstOrNull { it.id == id } }
    }

    /** Reihenfolge = Priorität (spezielle Begriffe vor allgemeinen). */
    private val RULES: List<Pair<Scene, Regex>> = listOf(
        Scene.TREX to Regex("""\bt[\s-]?rex\b|tyrannosaurus|raubsaurier|fleischfresser.*dino|velociraptor|\braptor"""),
        Scene.TRICERA to Regex("""triceratops|stegosaurus|ankylosaurus|horn.*dino"""),
        Scene.BRACHIO to Regex("""brachiosaurus|diplodocus|brontosaurus|apatosaurus|sauropod|langhals|\bdino|saurier|urzeit|jura\b|kreidezeit|fossil"""),
        Scene.ROCKET to Regex("""rakete|raumfahrt|raumschiff|astronaut|mondlandung|\bnasa\b|spacex|\besa\b|raumstation|apollo"""),
        Scene.EARTH to Regex("""\berde\b|\berdkugel|\bplanet erde|kontinent|weltkugel|globus|erdkern|äquator|ozean"""),
        Scene.PLANET to Regex("""planet|saturn|jupiter|\bmars\b|venus|merkur|neptun|uranus|sonnensystem|weltall|weltraum|universum|galaxie|milchstraße|schwarze[sn]? loch|\bmond\b|sterne?\b"""),
        Scene.VOLCANO to Regex("""vulkan|\blava\b|magma|ausbruch|eruption|vesuv|ätna"""),
        Scene.HEART to Regex("""\bherz\b|\bherzen\b|herzschlag|herzinfarkt|blutkreislauf|\bpuls\b|\bblut\b"""),
        Scene.ATOM to Regex("""\batom|molekül|elektron|proton|neutron|chemie|kernkraft|teilchen"""),
    )

    fun match(text: String): Scene? {
        val t = text.lowercase()
        return RULES.firstOrNull { (_, r) -> r.containsMatchIn(t) }?.first
    }

    /** Ist das eine Wissensfrage, zu der ein Bild-Hologramm (Wikipedia) passen könnte? */
    private val KNOWLEDGE = Regex("""^(erklär|erzähl|was (ist|sind|war|waren)|wer (ist|war|hat)|wie (groß|hoch|alt|schwer|lang|funktioniert|entsteht|lebt|leben)|wo (ist|liegt|lebt|leben)|zeig mir|warum (ist|sind|gibt)|welche[rs]?\b)""")

    fun isKnowledgeQuestion(text: String): Boolean {
        val t = text.lowercase().trim().removePrefix("jarvis").trim(' ', ',', '.')
        return KNOWLEDGE.containsMatchIn(t)
    }

    /** Suchbegriff für Wikipedia aus der Frage (Füllwörter weg). */
    fun searchTerm(text: String): String {
        val stop = setOf("jarvis", "erklär", "erkläre", "mir", "mal", "bitte", "was", "ist", "sind", "war", "waren", "wer", "hat", "wie",
            "groß", "hoch", "alt", "schwer", "lang", "funktioniert", "entsteht", "lebt", "leben", "wo", "liegt", "zeig", "erzähl", "erzähle",
            "über", "von", "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "eigentlich", "denn", "warum", "gibt",
            "es", "welche", "welcher", "welches", "genau", "so", "uns", "etwas", "irgendwas")
        return text.lowercase().replace(Regex("[^a-zäöüß0-9 -]"), " ").split(Regex("\\s+"))
            .filter { it.length > 1 && it !in stop }.joinToString(" ").trim()
    }
}
