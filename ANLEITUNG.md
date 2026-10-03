# Jarvis – Anleitung

Sag „Jarvis“, das Handy wacht auf, und du redest mit Claude. Funktioniert auch bei gesperrtem Bildschirm.

---

## 1. Zwei Schlüssel besorgen

**Picovoice (für das Wort „Jarvis“) – kostenlos**
1. Auf https://console.picovoice.ai/ ein Konto anlegen.
2. Auf der Startseite den **AccessKey** kopieren.

**Claude (das Gehirn) – bezahlt nach Nutzung**
1. Auf https://console.anthropic.com/ ein Konto anlegen.
2. Unter *Billing* ein paar Euro Guthaben aufladen (5 € reichen lange).
3. Unter *API Keys* einen Schlüssel erstellen und kopieren (beginnt mit `sk-ant-`).

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
1. Beide Schlüssel eintragen, optional deinen Namen → **Speichern**.
2. Bei **Berechtigungen** alles antippen, bis überall ein grüner Haken ist:
   - Mikrofon, Benachrichtigungen
   - **Über anderen Apps einblenden** – damit Jarvis von selbst aufgehen darf
   - **Vollbild-Benachrichtigungen** – damit es auch bei gesperrtem Handy klappt
   - **Akku-Optimierung aus** – sonst schläft Jarvis ein
   - **Kontakte** und **Direkt anrufen** – damit „Ruf Mama an“ funktioniert (optional)
3. **Jarvis einschalten** drücken. Oben erscheint eine dauerhafte Benachrichtigung „Jarvis ist bereit“.

### Extra für Samsung (wichtig!)
Samsung beendet Hintergrund-Apps besonders gern:
- *Einstellungen → Akku → Hintergrund-Nutzungslimits → Apps, die nie in den Standby-Modus wechseln* → **Jarvis** hinzufügen.
- *Einstellungen → Apps → Jarvis → Akku* → **Nicht eingeschränkt**.

---

## 5. Benutzen
- **„Jarvis“** sagen und direkt weiterreden, z. B. „Jarvis, stell den Wecker auf 7“. Ein kurzer Ton zeigt, dass er zuhört.
- Nach seiner Antwort hört er automatisch weiter zu. Du musst „Jarvis“ also nicht vor jedem Befehl sagen, erst wieder, wenn du ein paar Sekunden still warst.
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

Was Android **nicht** erlaubt, egal welche App: WLAN, Bluetooth oder mobile Daten selbst umschalten (Jarvis öffnet dir dann die passende Einstellung), Nachrichten ohne dein Antippen abschicken, Wecker löschen.

Ist das Handy gesperrt, kann Jarvis Wecker, Timer, Taschenlampe, Lautstärke und Musiksteuerung trotzdem sofort ausführen. Für Apps, Anrufe und Maps musst du kurz entsperren.

## Gut zu wissen
- **Nach einem Neustart** des Handys einmal die Jarvis-App öffnen. Android erlaubt aus Datenschutzgründen nicht, dass das Mikrofon von selbst wieder angeht.
- Das Wort „Jarvis“ wird **offline auf dem Handy** erkannt. Erst nach dem Wake Word geht etwas ins Internet.
- Spracherkennung und Stimme kommen von Google. Klingt die Stimme komisch: *Einstellungen → Allgemeine Verwaltung → Text-zu-Sprache* → „Google Sprachausgabe“ wählen und die deutsche Stimme laden.
- Reagiert Jarvis zu oft oder zu selten, lässt sich die Empfindlichkeit im Code (`Prefs.kt`, `sensitivity`) anpassen.
- Kosten: Mit dem voreingestellten Modell (Claude Haiku) kostet eine typische Frage weniger als einen Cent. Eine Websuche kostet zusätzlich etwa einen Cent.
- Für schlauere (aber langsamere und teurere) Antworten kannst du in der App das Modell auf `claude-sonnet-5-5` ändern.
- Deine Schlüssel liegen nur auf deinem Handy. Gib die App-Datei deshalb nicht an andere weiter, nachdem du sie eingerichtet hast.
