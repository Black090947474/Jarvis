package de.jarvis.app.wecker;

import de.jarvis.app.R;

import java.util.Locale;
import java.util.Random;

final class Sentences {
    private Sentences() {}

    private static final String[] POOL = {
            "Der frühe Vogel fängt den Wurm, also steh endlich auf.",
            "Heute ist ein guter Tag, um pünktlich zu sein.",
            "Mein Bett ist warm, aber der Tag wartet auf mich.",
            "Ich trinke jetzt ein großes Glas Wasser.",
            "Wer liegen bleibt, verpasst das Beste vom Morgen.",
            "Die Sonne geht auf und ich gehe mit.",
            "Fünf Minuten länger schlafen bringt mir gar nichts.",
            "Ich öffne das Fenster und atme frische Luft.",
            "Kaltes Wasser im Gesicht macht sofort wach.",
            "Heute erledige ich zuerst die wichtigste Aufgabe.",
            "Mein Wecker meint es nur gut mit mir.",
            "Aufstehen ist der erste Sieg des Tages.",
            "Ich ziehe mich an und mache das Bett.",
            "Ein ruhiger Morgen macht einen entspannten Tag.",
            "Der Kaffee kocht sich leider nicht von allein.",
            "Ich habe heute Pläne und die warten nicht.",
            "Wer früh aufsteht, hat mehr Zeit für sich.",
            "Meine Augen sind offen und mein Kopf wird klar.",
            "Ich strecke mich einmal kräftig und lächle.",
            "Gestern habe ich mir vorgenommen, heute früh zu starten.",
            "Das Kissen bleibt hier, ich gehe ins Bad.",
            "Ein gutes Frühstück gibt mir Energie für den Tag.",
            "Ich schaffe heute mehr, als ich denke.",
            "Jede Minute am Morgen ist wertvoll.",
            "Die Schlummertaste ist nicht mein Freund.",
            "Ich putze mir die Zähne und wasche mein Gesicht.",
            "Der Tag beginnt genau jetzt und nicht später.",
            "Draußen zwitschern schon die Vögel.",
            "Ich bin stärker als meine Müdigkeit.",
            "Heute lasse ich mich nicht vom Bett besiegen.",
            "Ein kurzer Spaziergang am Morgen weckt den Körper.",
            "Wer schreibt, der wird langsam wach.",
            "Meine Ziele erreiche ich nicht im Schlaf.",
            "Ich freue mich auf etwas Schönes heute.",
            "Pünktlichkeit fängt beim Aufstehen an.",
            "Die Decke ist gemütlich, doch die Welt ist spannender.",
            "Ich lege das Handy weg und stehe auf.",
            "Der Wecker klingelt, weil ich es so wollte.",
            "Jetzt noch ein paar Sätze und dann ist Ruhe.",
            "Ein klarer Kopf beginnt mit einem frühen Start.",
            "Ich habe genug geschlafen und fühle mich bereit.",
            "Langsam werden meine Finger schneller.",
            "Heute ist der beste Tag der Woche.",
            "Ich mache heute etwas, worauf ich stolz bin.",
            "Mein Körper ist wach, mein Kopf zieht gleich nach.",
            "Die Uhr tickt und ich bin schon auf den Beinen.",
            "Ich gieße die Pflanzen und lüfte mein Zimmer.",
            "Auf dem Weg in die Küche wird alles besser.",
            "Wer abschreibt, kann auch gleich aufstehen.",
            "Ein neuer Morgen bringt neue Möglichkeiten.",
            "Ich bin froh, dass ich heute früh dran bin.",
            "Mein Frühstück wartet schon auf mich.",
            "Licht an, Vorhang auf und los geht es.",
            "Ich zähle bis drei und stehe dann auf.",
            "Der Morgen gehört denen, die wach sind.",
            "Heute trinke ich mehr Wasser als gestern.",
            "Ich plane meinen Tag mit einer kurzen Liste.",
            "Meine Hausschuhe stehen schon bereit.",
            "Nur noch wenige Sätze trennen mich vom Frieden.",
            "Gleich ist es geschafft und der Tag kann kommen."
    };

    private static final Random RND = new Random();

    static String random(String avoid) {
        String s;
        do {
            s = POOL[RND.nextInt(POOL.length)];
        } while (s.equals(avoid));
        return s;
    }

    static String normalize(String s) {
        String t = s.toLowerCase(Locale.GERMAN)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replace('’', '\'').replace('´', '\'')
                .replaceAll("\\s+", " ")
                .trim();
        return t;
    }
}
