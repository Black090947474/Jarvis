# Jarvis – Anleitung

Sag „Hey Jarvis“, das Handy wacht auf, und du redest mit Claude. Funktioniert auch bei gesperrtem Bildschirm.

---

## 1. Schlüssel besorgen

Das Weckwort „Hey Jarvis“ wird komplett offline auf dem Handy erkannt und braucht kein Konto.

**Groq (das Gehirn) – kostenlos**
1. Auf https://console.groq.com/keys gehen und mit GitHub oder Google anmelden.
2. **Create API Key** antippen, Namen eingeben und den Schlüssel kopieren (beginnt mit `gsk_`, wird nur einmal angezeigt).
3. Kostenlos sind ca. 1.000 Anfragen pro Tag.

**Gemini – optional, kostenlos, als Ersatz**
Falls Groq am Limit ist, übernimmt Gemini, wenn du auch dafür einen Schlüssel einträgst.
1. Auf https://aistudio.google.com/apikey mit deinem Google-Konto anmelden.
2. Auf **„API-Schlüssel erstellen“** tippen und den Schlüssel kopieren (beginnt mit `AIza`).
3. Im kostenlosen Tarif gibt es ein Tageslimit, und Google darf die Anfragen zur Verbesserung seiner Modelle nutzen.

**Claude – optional, als Ersatz (kostet Guthaben)**
Sind die kostenlosen Dienste am Limit oder gestört, übernimmt automatisch Claude, falls du auch dafür einen Schlüssel einträgst.
1. Auf https://console.anthropic.com/ ein Konto anlegen und unter *Billing* Guthaben aufladen.
2. Unter *API Keys* einen Schlüssel erstellen (beginnt mit `sk-ant-`).

> Gib die Schlüssel niemandem und lade sie nicht ins Internet hoch. Du trägst sie erst in der fertigen App ein.

---

## 2. Die App bauen

### Weg A: Über GitHub (ohne Programme, geht sogar am Handy)
1. Konto auf https://github.com anlegen.
2. Oben rechts **+ → New repository**, Name `jarvis`, auf **Private** stellen, *Create*.
3. Auf der leeren Seite **„uploading an existing file“** antippen.
4. Den **Inhalt** des entpackten Ordners `jarvis` hochladen (alle Dateien und Ordner, nicht den Ordner selbst).
   - Wichtig: Der Ordner `.github` muss mit hoch. Wird er auf deinem Gerät versteckt, leg die Datei
     auf GitHub über *Add file → Create new file* mit dem Namen `.github/workflows/build.yml` an und kopier den Inhalt rein.
5. *Commit changes* drücken.
6. Oben auf **Actions** gehen. Der Bau startet automatisch (dauert ca. 3–5 Minuten).
7. Wenn ein grüner Haken erscheint: Auf den Durchlauf tippen, unten bei **Artifacts** „Jarvis-APK“ herunterladen und entpacken.

### Weg B: Mit Android Studio am PC
1. Android Studio installieren: https://developer.android.com/studio
2. *Open* → den Ordner `jarvis` wählen, warten bis alles geladen ist.
3. *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.
4. Die Datei liegt dann unter `app/build/outputs/apk/debug/app-debug.apk`.

---

## 3. Auf dem Handy installieren
1. Die `app-debug.apk` aufs Handy bringen und antippen.
2. Android fragt, ob du „Apps aus unbekannten Quellen“ erlauben willst → für diesen Vorgang erlauben.
3. Falls *Play Protect* warnt: „Trotzdem installieren“ (die App ist ja von dir selbst gebaut).

---

## 4. Einrichten (in der Jarvis-App)
1. Den Groq-Schlüssel eintragen (Gemini und Claude nur, wenn du willst), optional deinen Namen → **Speichern**.
2. Bei **Berechtigungen** alles antippen, bis überall ein grüner Haken ist:
   - Mikrofon, Benachrichtigungen
   - **Über anderen Apps einblenden** – damit Jarvis von selbst aufgehen darf
   - **Vollbild-Benachrichtigungen** – damit es auch bei gesperrtem Handy klappt
   - **Akku-Optimierung aus** – sonst schläft Jarvis ein
   - **Kontakte** und **Direkt anrufen** – damit „Ruf Mama an“ funktioniert (optional)
3. **Jarvis einschalten** drücken. Oben erscheint eine dauerhafte Benachrichtigung „Jarvis ist bereit“.

### Stimme einstellen
Unter **Stimme** in der Jarvis-App kannst du zwischen allen deutschen Stimmen auf deinem Handy wählen (antippen zum Anhören), Tonhöhe und Tempo einstellen und den **KI-Hall** an- oder ausschalten. Standard: die natürlichste Stimme auf deinem Handy (meist eine Online-Stimme von Google), ohne Effekte.
Mehr Stimmen bekommst du über *Einstellungen → Allgemeine Verwaltung → Text-zu-Sprache → Google Sprachausgabe → ⚙ → Sprachdaten installieren → Deutsch*.

### Design
Unter **Design** wählst du, wie Jarvis aussieht, wenn er aufgeht: **Nexus** (HUD mit Ringen, Ticks und Reticle, Standard), **Puls** (Leuchtkern, Punkt-Ringe & Schallwelle), **Nexus** (HUD mit Ringen, Reticle & Radar), **Glut** (rote, atmende Kugel), **Aurora** (bunte Farbwolken), **Linie** (weißer Ring, minimal) oder **Glas** (Uhrzeit, blaue Kugel, Milchglas-Karte).

### Nachrichten & volle Steuerung freischalten
Für „Nachrichten lesen & antworten“ und „Volle Handy-Steuerung“ tippst du in der Jarvis-App auf den Eintrag und schaltest Jarvis in der Liste ein.
Ist der Schalter **ausgegraut** oder kommt „Eingeschränkte Einstellung“, sperrt Android das für Apps, die nicht aus dem Play Store kommen. So entsperrst du es:
*Einstellungen → Apps → Jarvis → ⋮ (oben rechts) → „Eingeschränkte Einstellungen zulassen“*, dann nochmal versuchen.

Wichtig: Mit diesen Rechten sieht Jarvis deine Nachrichten und den Bildschirm. Was er dafür liest, wird an die KI (z. B. Groq) geschickt. Schalte es nur ein, wenn du damit einverstanden bist.

### Extra für Samsung (wichtig!)
Samsung beendet Hintergrund-Apps besonders gern:
- *Einstellungen → Akku → Hintergrund-Nutzungslimits → Apps, die nie in den Standby-Modus wechseln* → **Jarvis** hinzufügen.
- *Einstellungen → Apps → Jarvis → Akku* → **Nicht eingeschränkt**.

---

## 5. Benutzen
- **„Hey Jarvis“** sagen und direkt weiterreden, z. B. „Hey Jarvis, stell den Wecker auf 7“. Ein kurzer Ton zeigt, dass er zuhört.
- Nach seiner Antwort hört er automatisch weiter zu. Du musst „Hey Jarvis“ also nicht vor jedem Befehl sagen, erst wieder, wenn du ein paar Sekunden still warst.
- Beenden: „Danke“, „Tschüss“ oder „Stopp“ sagen, kurz still sein oder die Zurück-Taste drücken.
- Antippen des Bildschirms unterbricht Jarvis und er hört dir wieder zu.

## Was Jarvis auf dem Handy kann
| Sag zum Beispiel | Was passiert |
|---|---|
| „Weck mich morgen um halb sieben“ | Wecker wird direkt gestellt |
| „Wecker jeden Montag bis Freitag um 6“ | Wiederholender Wecker |
| „Timer 10 Minuten für die Pizza“ | Timer läuft |
| „Zeig mir meine Wecker“ | Uhr-App geht auf (zum Ändern oder Löschen) |
| „Taschenlampe an / aus“ | Sofort |
| „Lautstärke auf 30 Prozent“, „Wecker lauter“ | Sofort |
| „Spiel Drake auf Spotify“, „Nächstes Lied“, „Pause“ | Musik |
| „Öffne TikTok / WhatsApp / Kamera“ | App geht auf |
| „Ruf Mama an“ | Anruf startet (Kontakte-Berechtigung nötig) |
| „Schreib Lisa auf WhatsApp, dass ich später komme“ | Nachricht ist fertig, du tippst nur noch auf Senden |
| „Navigier mich zum Hauptbahnhof“ | Google Maps startet |
| „Trag morgen 15 Uhr Zahnarzt ein“ | Termin ist ausgefüllt, du tippst auf Speichern |
| „Wie wird das Wetter?“, „Wer hat gestern gespielt?“ | Websuche, Antwort gesprochen |
| „Wie viel Akku hab ich?“ | Akkustand |
| „Merk dir, dass ich um 8 Uhr Schule habe“ | Wird dauerhaft gespeichert (in der App einsehbar und löschbar) |
| „Such auf YouTube nach Minecraft“, „Zeig mir Pizza in der Nähe“ | Sucht direkt in YouTube, Google, Maps, TikTok, Instagram, Play Store, Netflix, Amazon, eBay, X, Reddit, Wikipedia |
| „Mach ein Selfie“, „Starte ein Video“ | Kamera im richtigen Modus |
| „Lies meine Nachrichten vor“, „Was hat Lisa geschrieben?“ | Liest neue Nachrichten vor (Benachrichtigungs-Zugriff nötig) |
| „Antworte Lisa, dass ich gleich komme“ | Liest dir die Antwort vor, fragt „Abschicken?“ und schickt sie direkt |
| „Öffne Instagram und geh auf mein Profil“, „Scroll runter“, „Tipp auf Folgen“ | Bedient jede App wie du (Bedienungshilfe nötig) |
| „Mach einen Screenshot“, „Geh zurück“, „Sperr das Handy“ | Sofort (Bedienungshilfe nötig) |
| „Wo bin ich?“, „Wie weit ist es nach Hause?“ | Standort (Standort-Berechtigung nötig) |
| „Schick Max mein letztes Foto“, „Wie viele Fotos hab ich?“ | Fotos (Foto-Berechtigung nötig) |
| „Mach das WLAN an“, „Schalt Bluetooth aus“, „Flugmodus an“ | Tippt in den Schnelleinstellungen selbst auf die Kachel (volle Steuerung nötig) |
| „Mach mir einen Plan, wie ich für ein Fahrrad spare“, „Vergleich iPhone und Samsung“, „Einkaufsliste für Pizza“ | Zeigt eine **Ergebnis-Karte** auf dem Bildschirm (antippen = teilen) |
| „Bau mir eine Website für meinen Gaming-Kanal“ | Baut eine einfache Website, speichert sie unter Downloads/Jarvis und öffnet sie (nur auf dem Handy, nicht online) |
| „Wo bin ich?“, „Wie wird das Wetter hier?“ | Standort (Standort-Berechtigung nötig) |
| „Schick Max mein letztes Foto“, „Wie viele Fotos hab ich?“ | Fotos (Foto-Berechtigung nötig) |
| „Mach das WLAN an“, „Schalt Bluetooth aus“, „Flugmodus an“ | Schaltet die Kachel in den Schnelleinstellungen (volle Steuerung nötig) |

Was Android **nicht** erlaubt, egal welche App: WLAN, Bluetooth oder mobile Daten selbst umschalten (Jarvis öffnet dir dann die passende Einstellung), Nachrichten ohne dein Antippen abschicken, Wecker löschen.

Ist das Handy gesperrt, kann Jarvis Wecker, Timer, Taschenlampe, Lautstärke und Musiksteuerung trotzdem sofort ausführen. Für Apps, Anrufe und Maps musst du kurz entsperren.

## Gut zu wissen
- **Nach einem Neustart** des Handys einmal die Jarvis-App öffnen. Android erlaubt aus Datenschutzgründen nicht, dass das Mikrofon von selbst wieder angeht.
- „Hey Jarvis“ wird **offline auf dem Handy** erkannt (mit dem freien openWakeWord-Modell, Lizenz CC BY-NC-SA 4.0, also nur für private Nutzung). Erst nach dem Wake Word geht etwas ins Internet.
- Spracherkennung und Stimme kommen von Google. Klingt die Stimme komisch: *Einstellungen → Allgemeine Verwaltung → Text-zu-Sprache* → „Google Sprachausgabe“ wählen und die deutsche Stimme laden.
- Reagiert Jarvis zu oft oder zu selten, lässt sich die Schwelle im Code (`Prefs.kt`, `sensitivity`, Standard 0.5) anpassen.
- Kosten: Mit Groq und Gemini im kostenlosen Tarif nichts. Nur wenn Claude einspringt, kostet eine Frage weniger als einen Cent (Websuche ca. einen Cent extra).
- Deine Schlüssel liegen nur auf deinem Handy. Gib die App-Datei deshalb nicht an andere weiter, nachdem du sie eingerichtet hast.
