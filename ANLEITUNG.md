# Jarvis – Anleitung

Sag „Jarvis“, das Handy wacht auf, und du redest mit Claude. Funktioniert auch bei gesperrtem Bildschirm.

---

## 1. Schlüssel besorgen

Das Weckwort „Jarvis“ wird komplett offline auf dem Handy erkannt und braucht kein Konto.

**NVIDIA (empfohlen, das Gehirn) – kostenlos, ohne Kreditkarte**
NVIDIA hat kein so knappes Minutenlimit wie Groq (ca. 40 Anfragen pro Minute), daher kaum Pausen.
1. Auf https://build.nvidia.com gehen und mit E-Mail anmelden (eventuell wird die Handynummer bestätigt).
2. Oben rechts auf dein Profil → **API Keys** → **Generate API Key**, Schlüssel kopieren (beginnt mit `nvapi-`).
3. In Jarvis bei „NVIDIA API-Key“ einfügen. Groq kannst du zusätzlich als Ersatz eintragen.

**Groq – kostenlos, optional als Ersatz**
1. Auf https://console.groq.com/keys gehen und mit GitHub oder Google anmelden.
2. **Create API Key** antippen, Namen eingeben und den Schlüssel kopieren (beginnt mit `gsk_`, wird nur einmal angezeigt).
3. Im Gratis-Tarif gibt es ein Minutenlimit; ist es voll, wartet Jarvis kurz („Kurze Pause“).

**Mistral – optional** (nur falls dein Mistral-Konto API-Zugriff hat)

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
Ab Version 14 öffnet sich zuerst das **Dashboard** (Uhrzeit, Wetter, Termine, Erinnerungen, Aufgaben, Nachrichten, Schnellaktionen). Die bisherigen Einstellungen erreichst du dort über **⚙ Einstellungen**, die Freigaben über **🛡 Berechtigungen**.

1. Den Groq-Schlüssel eintragen (Gemini und Claude nur, wenn du willst), optional deinen Namen → **Speichern**.
2. Bei **Berechtigungen** alles antippen, bis überall ein grüner Haken ist:
   - Mikrofon, Benachrichtigungen
   - **Über anderen Apps einblenden** – damit Jarvis von selbst aufgehen darf
   - **Vollbild-Benachrichtigungen** – damit es auch bei gesperrtem Handy klappt
   - **Akku-Optimierung aus** – sonst schläft Jarvis ein
   - **Kontakte** und **Direkt anrufen** – damit „Ruf Mama an“ funktioniert (optional)
3. **Jarvis einschalten** drücken. Oben erscheint eine dauerhafte Benachrichtigung „Jarvis ist bereit“.

### Berechtigungszentrum (Dashboard → 🛡 Berechtigungen)
Für jede Funktion siehst du: Status (✓ erlaubt / ○ fehlt), wofür Jarvis sie braucht, und einen **Schalter**, mit dem du sie für Jarvis sperren kannst. Antippen öffnet den Android-Dialog bzw. die passende Einstellung. Jarvis umgeht nie Sicherheitsfunktionen von Android – erteilen und entziehen passiert immer bei Android selbst.
- **Kalender** – Termine lesen, eintragen, verschieben, löschen (immer erst nach deinem „Ja“)
- **Erinnerungen** – brauchen „Benachrichtigungen“ und „Wecker & Erinnerungen“
- **Telefon & Anrufliste** – „Wer hat mich zuletzt angerufen?“
- **Standort im Hintergrund** – nur für „Erinnere mich, wenn ich zu Hause bin“ (Android verlangt dafür „Immer erlauben“)
- **Systemeinstellungen ändern** – für die Helligkeit
- **Hinweise**: Proaktive Hinweise sind standardmäßig **aus**. Eingeschaltet meldet Jarvis bevorstehende Termine (mit Wegzeit-Puffer), Kalender-Konflikte und morgens einen Tagesüberblick. Uhrzeit, Vorlauf und Puffer stellst du dort ein; jede Art ist auch eine eigene Benachrichtigungs-Kategorie in Android.
- **Zuhause = aktueller Ort** speichert dein Zuhause für ortsbasierte Erinnerungen.

### Neu in Version 19
- **Nur noch „Jarvis“** als Weckwort (statt „Hey Jarvis“). Für beste Erkennung: ⚙ Einstellungen → Weckwort → **„Weckwort auf meine Stimme trainieren“** (6× „Jarvis“ sagen, dann einen normalen Satz). Es werden nur Zahlen-Merkmale gespeichert, keine Aufnahmen. „Hey Jarvis“ geht weiterhin.
- **Neues Design:** Startseite im Lila-„Aivio“-Stil (Begrüßung, Suchfeld, Karten, Verlauf), neuer Kern **„Sphäre“**, neuer Sprach-Bildschirm, **heller Modus** (Personalisieren → Hintergrund → Hell).
- **Fokus-Timer (Pomodoro):** Lernblöcke + Pausen, großer Ring, auf Wunsch „Nicht stören“ (einmal im Berechtigungszentrum erlauben). Fokuszeit erscheint in der Statistik. „Jarvis, 25 Minuten Mathe lernen.“
- **Packliste:** „Was muss ich morgen einpacken?“ – Fächer aus dem Stundenplan, Material pro Fach, Hausaufgaben, Regenschirm bei Regen. „Für Sport brauche ich Sportzeug und Turnschuhe.“
- **Taschengeld:** „Ich habe 5 Euro für Kino ausgegeben“, „Ich habe 20 Euro bekommen“, „Wie viel hab ich diesen Monat ausgegeben?“, Sparziel.
- **Fotos finden:** nach Zeitraum, Ordner (Kamera, Screenshots, WhatsApp) und Ort (aus den Foto-Ortsdaten). Den Bildinhalt sieht Jarvis erst, wenn du das Foto im Chat schickst.
- **Hausaufgaben-Tutor:** erklärt Schritt für Schritt statt nur die Lösung zu verraten. **Foto → Karteikarten:** Foto vom Heft in den Chat + „mach Karteikarten“.
- **Dolmetscher:** „Sag auf Englisch: Wo ist der Bahnhof?“ – Jarvis spricht Englisch und hört danach auf Englisch zu.
- **Quiz-Abend** (Jarvis ist Quizmaster mit Punktestand), **Geschichten & Witze**.
- **Begrüßung beim Heimkommen** (Wetter, offene Aufgaben, neue Nachrichten) – braucht den Ort „Zuhause“; abschaltbar unter Personalisieren. Auch als Automation: „Nach Hause (WLAN) → Begrüßung“.

### Neu in Version 18 – Jarvis als Command Center
- **Automationen** (Tools → Automationen): WENN Uhrzeit / Ort / Kopfhörer / Bluetooth-Gerät / WLAN / Akku unter X % / Ladekabel / Termin beginnt / Benachrichtigung / App geöffnet → DANN Hinweis, sprechen, Erinnerung, Musik, Lautstärke, App, Timer, Taschenlampe, Helligkeit, Briefing, eigenes Kommando. Mit Vorlagen und **Testmodus**. Auch per Sprache: „Wenn meine Kopfhörer verbunden werden, starte Spotify und stell die Lautstärke auf 40 %.“
- **Agenten-Modus:** „Plane mir einen Lerntag für die Mathearbeit“ – Jarvis prüft Kalender, Aufgaben und Lernstoff, macht einen Plan und fragt, bevor er etwas einträgt.
- **Smart Context** (bei aktivierten Hinweisen): erkennt Prüfungen im Kalender ohne Lernzeit, warnt bei wenig Akku, begrüßt dich zu Hause mit offenen Hausaufgaben.
- **Lernassistent:** Fächer/Themen, Wiederholung nach Leitner-System, Multiple Choice, Prüfungssimulation mit Note, Fehleranalyse.
- **Datei-Assistent:** „Finde meine Mathe-Datei“, „Fasse das PDF zusammen“, „Suche in meinen Dateien nach Photosynthese“ (braucht „Zugriff auf alle Dateien“). Im Chat kannst du PDFs, Word-, Excel- und Textdateien anhängen.
- **Fahrzeit:** „Wie lange brauche ich mit dem Rad zur Schule?“, „Wann muss ich los, um 8 Uhr dort zu sein?“ (OpenStreetMap; Bus/Bahn öffnet Maps).
- **Benachrichtigungs-Zentrum:** sortiert in Wichtig, Schule, Familie, Freunde, Arbeit, Werbung, System.
- **Gedächtnis-Bereich:** Profil, Langzeit- und Kurzzeit-Gedächtnis ansehen, ändern, löschen. **Persönlichkeit:** Butler, freundlich, professionell, technisch, humorvoll, minimalistisch + Regler.
- **Aktionsverlauf, Statistik, Meine Geräte, Plugins, Personalisierung** (Hintergrund, Akzentfarbe, Schriftgröße, Startseite, Animationen), **Sicherung** (Export/Import ohne Schlüssel).
- **Notfall:** „Ich brauche Hilfe“ öffnet sofort 112/110/Notfallkontakt/Standort senden (du tippst selbst auf Anrufen).
- **Auto-Modus:** große Knöpfe; startet automatisch, wenn sich dein Auto per Bluetooth verbindet.
- **Aktions-PIN** (Berechtigungen): optional zusätzliche PIN vor Senden/Löschen/Kaufen.
- **Live-Status** auf Home inkl. welches KI-Modell gerade antwortet; komplexe Aufgaben nutzen automatisch ein stärkeres Modell.

### Neues Design (ab Version 17)
Unten gibt es jetzt vier Bereiche: **Home** (leuchtender Kern, „Frag Jarvis“, Wetter, Heute, Nachrichten, Schule), **Chat**, **Tools** (alle Werkzeuge als Kacheln: Kalender, Erinnerungen, Telefon, Musik, Apps, Schule, Einkauf, Notizen, Lernen, Kommandos …) und **System** (Akku, Netzwerk, Speicher, Standort, Musik, Bluetooth …). Farbschema Blau oder Lila unter ⚙ → Design.

### Gedächtnis
Jarvis merkt sich jetzt von selbst Wichtiges über dich (Hobbys, Verein, Schule, Vorlieben) – sichtbar und löschbar unter ⚙ → Gedächtnis. Sprechen und Chat teilen sich außerdem den Gesprächsverlauf der letzten Tage („Gesprächsverlauf vergessen“ löscht ihn).

### Bessere Spracherkennung
⚙ → Spracherkennung → **Whisper**: versteht deutlich genauer. Braucht einen (kostenlosen) Groq-Schlüssel; Whisper hat dort ein eigenes Limit, unabhängig vom Chat.

### Bessere Stimme
Die natürlichste kostenlose deutsche Stimme ist „Thorsten“ (eine freigegebene, gespendete Stimme). So geht's:
1. **SherpaTTS** installieren: https://f-droid.org/de/packages/org.woheller69.ttsengine/ (unten „APK herunterladen“).
2. SherpaTTS öffnen und als Sprache **Deutsch – thorsten** (Piper) laden.
3. In Jarvis: ⚙ → Stimme → **Sprach-Engine: SherpaTTS**, dann Stimme wählen und „Probe hören“.

### Jarvis-Klang (eigene Stimme)
⚙ → Stimme → **Jarvis-Klang**: Jarvis legt eigene Effekte über jede Stimme – **Butler** (warm, tief, Raumhall), **KI** (metallischer Schimmer, leichte Doppelung) oder **Hologramm** (Lautsprecher-Klang mit Echo). Antippen = Probe hören, mit „Stärke“ regeln. Läuft offline auf dem Handy.

### Stimme einstellen
Unter **Stimme** in der Jarvis-App kannst du zwischen allen deutschen Stimmen auf deinem Handy wählen (antippen zum Anhören), Tonhöhe und Tempo einstellen und den **KI-Hall** an- oder ausschalten. Standard: die natürlichste Stimme auf deinem Handy (meist eine Online-Stimme von Google), ohne Effekte.
Mehr Stimmen bekommst du über *Einstellungen → Allgemeine Verwaltung → Text-zu-Sprache → Google Sprachausgabe → ⚙ → Sprachdaten installieren → Deutsch*.

### Design
Unter **Design** wählst du, wie Jarvis aussieht, wenn er aufgeht: **Nexus** (HUD mit Ringen, Ticks und Reticle, Standard), **Puls** (Leuchtkern, Punkt-Ringe & Schallwelle), **Nexus** (HUD mit Ringen, Reticle & Radar), **Glut** (rote, atmende Kugel), **Aurora** (bunte Farbwolken), **Linie** (weißer Ring, minimal) oder **Glas** (Uhrzeit, blaue Kugel, Milchglas-Karte).

### Chat mit Bildern
Auf dem Startbildschirm gibt es jetzt ein zweites Symbol **„Jarvis Chat“** (auch über „Chat öffnen“ in der Jarvis-App).
- Schreiben, oder auf das **Mikrofon** tippen und sprechen.
- **Bild-Symbol** = Foto aus der Galerie, **Kamera-Symbol** = neues Foto. Dann eine Frage dazu stellen, z. B. „Was ist das für eine Pflanze?“ oder „Erklär mir die Aufgabe“.
- In der Galerie kannst du auch **Teilen → Jarvis Chat** wählen.
- 🔈 oben = Antworten vorlesen lassen, ＋ = neuer Chat. Lange auf eine Antwort tippen = kopieren.
- Der Verlauf bleibt auf deinem Handy gespeichert. Bilder gehen zum Beantworten an die KI (Groq).

### Zweites Handy: Station & Fernsteuerung
1. Auf dem **zweiten Handy** Jarvis installieren (gleicher Link), Groq-Schlüssel eintragen und unten bei **Zweites Handy** auf **„Dieses Handy als Station starten“** tippen. Am besten ans Ladekabel legen – der Bildschirm bleibt an.
2. Auf der Station siehst du Uhrzeit, Datum, Akku und unten einen **6-stelligen Code** und eine Zahl wie 192.168.x.x.
3. Auf dem **Haupthandy** bei **Zweites Handy** auf **„Station suchen & koppeln“** tippen und den Code eingeben.
4. Fertig. Sag zum Haupthandy z. B. „Jarvis, spiel auf dem zweiten Handy Musik“, „Mach auf der Station die Taschenlampe an“, „Stell auf dem anderen Handy einen Wecker auf 7“.

Beide Handys müssen im **selben WLAN** sein, und auf dem zweiten Handy muss die Station geöffnet sein. Tipp: Lass das Weckwort nur auf einem Handy eingeschaltet, sonst antworten beide gleichzeitig.

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
| „Was steht heute an?“, „Was hab ich diese Woche?“ | Termine, Erinnerungen und Aufgaben zusammengefasst |
| „Trag morgen 15 Uhr Zahnarzt ein“ | Jarvis sagt, was er eintragen würde (+ Überschneidungen) und fragt „Soll ich?“ |
| „Verschieb den Zahnarzt auf Freitag 16 Uhr“, „Lösch das Training am Mittwoch“ | Sucht den Termin, fragt nach, ändert/löscht erst nach „Ja“ (Serientermine nur in der Kalender-App) |
| „Wann hab ich morgen Nachmittag zwei Stunden frei?“ | Freie Zeiten mit Puffer zwischen Terminen |
| „Erinnere mich in 20 Minuten an die Wäsche“, „… jeden Montag um 7 an Sport“ | Jarvis-Erinnerung als Benachrichtigung, auch offline und nach Neustart |
| „Erinnere mich 30 Minuten vor dem Zahnarzt“ | Erinnerung relativ zum Termin |
| „Erinnere mich, wenn ich zu Hause ankomme, an den Müll“ | Ortsbasiert (Standort „Immer erlauben“ + Zuhause gespeichert) |
| „Neue Aufgabe: Mathe lernen bis Freitag, wichtig, Kategorie Schule“ | Aufgabe mit Priorität, Datum, Kategorie, auf Wunsch Wiederholung und Erinnerung |
| „Was muss ich noch erledigen?“, „Hake Mathe ab“ | Aufgabenliste / erledigt (auch im Dashboard antippen) |
| „Wer hat mich zuletzt angerufen?“, „Hab ich verpasste Anrufe?“ | Anrufliste |
| „Hab ich neue Mails?“, „Schreib eine Mail an …“ | Neue Mails aus den Benachrichtigungen; Entwürfe öffnen sich in der Mail-App, senden tust du |
| „Check meine E-Mails“, „Was steht in meinem Posteingang?“ | Öffnet deine Mail-App, liest den Posteingang vom Bildschirm und fasst zusammen (Bildschirmsteuerung nötig) |
| „Was hat Max mir auf WhatsApp geschrieben?“, „Schau in Instagram, was neu ist“ | Öffnet die App und liest den sichtbaren Inhalt (nur lesen) |
| „Antworte auf die Mail von Max, dass ich morgen Zeit habe“, „Schreib Lisa auf Insta zurück …“ | Jarvis schreibt die Antwort in der App, liest sie dir vor und fragt „Soll ich senden?“ – gesendet wird erst nach deinem Ja |
| „Wie wird das Wetter morgen?“ | Wetter (Open-Meteo, kostenlos) |
| „Wo ist die nächste Apotheke?“ | Karte mit Treffern in deiner Nähe |
| „Helligkeit auf 40 Prozent“ | Helligkeit (Freigabe „Systemeinstellungen ändern“) |
| „Guten Morgen“ | Tagesüberblick: Wetter, Termine, Schule, Hausaufgaben, Aufgaben, Geburtstage, Nachrichten (auch ohne KI) |
| „Was gibt's Neues?“, „Wie hat Bayern gespielt?“ | Websuche und Schlagzeilen (DuckDuckGo, Google News, Wikipedia – ohne Schlüssel) |
| „Mein Montag: 8 Uhr Mathe, 9:45 Deutsch …“ | Stundenplan speichern; „Was hab ich morgen?“ |
| „Hausaufgabe Mathe: Seite 42“ | Hausaufgabe bis zur nächsten Mathestunde, Erinnerung am Vortag 17 Uhr |
| „Speicher Vokabeln: dog = Hund, cat = Katze“, „Frag mich Englisch ab“ | Lern-Modus mit Karteikarten, schwierige kommen öfter |
| „Setz Milch und Eier auf die Einkaufsliste“, „Was steht drauf?“ | Einkaufsliste (auch im Dashboard abhaken) |
| „Notiere: …“, „Was hab ich mir zu … notiert?“ | Notizen |
| „Wer hat bald Geburtstag?“ | Geburtstage aus deinen Kontakten |
| „Merk dir, wo ich geparkt habe“ / „Wo hab ich geparkt?“ | Ort merken und zu Fuß hinnavigieren |
| „Was läuft gerade?“ | Lied erkennen (Google oder Shazam) |
| „Wenn ich Gaming-Modus sage: Nicht stören an, Lautstärke 80, Spotify Gaming“ | Eigenes Kommando – danach reicht „Gaming-Modus“ (läuft ohne KI) |

**Widget:** Lange auf den Startbildschirm drücken → Widgets → Jarvis. Zeigt Uhrzeit, nächsten Termin, Wetter und einen Mikrofon-Knopf.
| „Wie wird das Wetter?“, „Wer hat gestern gespielt?“ | Websuche, Antwort gesprochen |
| „Wie viel Akku hab ich?“ | Akkustand |
| „Merk dir, dass ich um 8 Uhr Schule habe“ | Wird dauerhaft gespeichert (in der App einsehbar und löschbar) |
| „Such auf YouTube nach Minecraft“, „Zeig mir Pizza in der Nähe“ | Sucht direkt in YouTube, Google, Maps, TikTok, Instagram, Play Store, Netflix, Amazon, eBay, X, Reddit, Wikipedia |
| „Mach ein Selfie“, „Starte ein Video“ | Kamera im richtigen Modus |
| „Lies meine Nachrichten vor“, „Was hat Lisa geschrieben?“ | Liest neue Nachrichten vor (Benachrichtigungs-Zugriff nötig) |
| „Antworte Lisa, dass ich gleich komme“ | Liest dir die Antwort vor, fragt „Soll ich senden?“ und schickt sie erst nach „Ja“ |
| „Öffne Instagram und geh auf mein Profil“, „Scroll runter“, „Tipp auf Folgen“ | Bedient jede App wie du (Bedienungshilfe nötig) |
| „Mach einen Screenshot“, „Geh zurück“, „Sperr das Handy“ | Sofort (Bedienungshilfe nötig) |
| „Wo bin ich?“, „Wie weit ist es nach Hause?“ | Standort (Standort-Berechtigung nötig) |
| „Schick Max mein letztes Foto“, „Wie viele Fotos hab ich?“ | Fotos (Foto-Berechtigung nötig) |
| „Mach das WLAN an“, „Schalt Bluetooth aus“, „Flugmodus an“ | Tippt in den Schnelleinstellungen selbst auf die Kachel (volle Steuerung nötig) |
| „Mach mir einen Plan, wie ich für ein Fahrrad spare“, „Vergleich iPhone und Samsung“, „Einkaufsliste für Pizza“ | Zeigt eine **Ergebnis-Karte** auf dem Bildschirm (antippen = teilen) |
| „Bau mir eine Website für meinen Gaming-Kanal“ | Baut eine einfache Website, speichert sie unter Downloads/Jarvis und öffnet sie (nur auf dem Handy, nicht online) |

Was Android **nicht** erlaubt, egal welche App: WLAN, Bluetooth oder mobile Daten direkt per Code umschalten (Jarvis tippt mit der vollen Steuerung auf die Kachel oder öffnet dir die Einstellung), SMS/WhatsApp ohne dein Antippen abschicken, Wecker löschen, alte E-Mails im Postfach lesen (dafür bräuchte es eine Anmeldung bei Google/Microsoft).

**Ohne Internet** versteht Jarvis trotzdem: Timer, Wecker, Taschenlampe, „Erinnere mich in/um …“, „Was steht heute an?“ und den Akkustand. Erinnerungen und Aufgaben liegen nur auf deinem Handy.

**Nachfragen:** Bevor Jarvis einen Termin einträgt, ändert oder löscht, eine Antwort oder Mail schickt, eine Aufgabe löscht oder Flugmodus/mobile Daten/Hotspot umschaltet, sagt er dir genau, was er tun würde, und wartet auf dein „Ja“.

Ist das Handy gesperrt, kann Jarvis Wecker, Timer, Taschenlampe, Lautstärke und Musiksteuerung trotzdem sofort ausführen. Für Apps, Anrufe und Maps musst du kurz entsperren.

## Gut zu wissen
- **Nach einem Neustart** des Handys einmal die Jarvis-App öffnen. Android erlaubt aus Datenschutzgründen nicht, dass das Mikrofon von selbst wieder angeht.
- Das Weckwort wird **offline auf dem Handy** erkannt (mit dem freien openWakeWord-Modell, Lizenz CC BY-NC-SA 4.0, also nur für private Nutzung). Erst nach dem Wake Word geht etwas ins Internet.
- Spracherkennung und Stimme kommen von Google. Klingt die Stimme komisch: *Einstellungen → Allgemeine Verwaltung → Text-zu-Sprache* → „Google Sprachausgabe“ wählen und die deutsche Stimme laden.
- Reagiert Jarvis zu oft oder zu selten, lässt sich die Schwelle im Code (`Prefs.kt`, `sensitivity`, Standard 0.5) anpassen.
- Kosten: Mit Groq und Gemini im kostenlosen Tarif nichts. Nur wenn Claude einspringt, kostet eine Frage weniger als einen Cent (Websuche ca. einen Cent extra).
- Deine Schlüssel liegen nur auf deinem Handy. Gib die App-Datei deshalb nicht an andere weiter, nachdem du sie eingerichtet hast.
