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

### Neu in Version 27 – Großes Design-Update & besseres Hologramm
- **Neuer Look:** sanftes „Polarlicht“ im Hintergrund (Cyan, Blau, Violett), runde Glas-Kacheln, große Begrüßung mit Farbverlauf.
- **Startseite als Kachel-Raster:** „Frag Jarvis“-Karte mit leuchtender Kugel und Schnellknöpfen (Briefing, Scan, Schreiben), große Wetterkachel, „Als Nächstes“, Akku-Ring, Nachrichten, „Für dich“-Vorschläge.
- **Schwebende Leiste unten** mit großem **Mikrofon-Knopf in der Mitte**.
- **Chat:** Blasen mit Farbverlauf, Polarlicht-Hintergrund.
- **Hologramm 2.0:** durchsichtig (man sieht die Rückseite durchschimmern), leuchtende Leiterbahnen mit wandernden Datenpulsen, Farbverlauf von Cyan zu Violett, kreisende Datenringe um den Kopf, aufsteigende Funken, kurzes „Glitch“-Flackern, höhere Auflösung.

### Neu in Version 26 – Nachrichten in allen Apps + Design
- **Nachrichten über Snapchat, Instagram, WhatsApp, Telegram, Discord, Signal, Messenger, TikTok:** „Jarvis, schreib Lena auf Snapchat: Bin gleich da.“ Jarvis fragt einmal nach („An Lena auf Snapchat: … senden?“), nach deinem Ja öffnet er die App, sucht die Person, öffnet den Chat, schreibt und sendet. Braucht die Bildschirmsteuerung (Profi-Modus → Auto-Einrichtung). Der Name muss so heißen wie in der App.
- **Tools-Seite neu:** Suchfeld, Kategorien (Highlights, Kommunikation, Alltag, Schule & Lernen, Spaß, Jarvis & System), Glas-Kacheln mit Symbol-Hintergrund.
- Knöpfe einheitlicher und nicht mehr abgeschnitten.

### Neu in Version 25.1 – KI-Schlüssel ganz einfach
Tools → **KI-Schlüssel** (oder ⚙ → „🔑 KI-Schlüssel“): alle Anbieter auf einer Seite, empfohlene oben. Pro Anbieter nur zwei Knöpfe: **① Schlüssel holen** (Seite öffnet sich, anmelden, Schlüssel kopieren) und **② Einfügen** – Jarvis speichert und testet sofort. Kommst du mit kopiertem Schlüssel zurück, fragt Jarvis sogar von selbst. Oben siehst du, wie viele aktiv sind und wie oft noch Pausen drohen.

### Neu in Version 25 – das große Update
- **Schwebender Jarvis:** leuchtende Kugel über allen Apps (Tools → „Schwebender Jarvis“). Antippen = reden (Jarvis merkt sich dabei den Bildschirm für „Was steht hier?“), lang drücken = Chat, ziehen = verschieben.
- **Kamera „Was ist das?“** (Tools → „Was ist das?“): Kamera draufhalten, Knopf tippen – Jarvis erkennt Pflanzen, Tiere, Autos, Schilder, erklärt Matheaufgaben Schritt für Schritt, übersetzt Texte, schätzt Kalorien. Passt ein Hologramm, kannst du es antippen.
- **„Jarvis, wo bist du?“:** Handy klingelt laut (auch auf lautlos), blinkt mit der Taschenlampe und vibriert, bis du tippst.
- **Beim Rausgehen:** Verlässt du dein Zuhause, erinnert Jarvis an Schirm (bei Regen), die Schulfächer von heute und Fälliges. Abschaltbar unter Personalisieren.
- **Spiele:** „Wer bin ich?“, Rätsel, Abenteuer (du entscheidest die Geschichte).
- **Klassenarbeit-Coach:** Fach, Datum und Thema sagen – Jarvis plant tägliche Lerneinheiten, Erinnerungen, Lernkarten und eine Probe-Arbeit am Vortag.

### Neu in Version 24.1 – Schlüssel automatisch erkennen
Bei „KI-Anbieter“ → Anbieter wählen → „Schlüssel holen“: Seite öffnet sich, anmelden, Schlüssel erstellen, **kopieren**, zurück zu Jarvis – Jarvis erkennt den Schlüssel in der Zwischenablage, trägt ihn nach deinem „Eintragen“ ein und testet ihn sofort. Funktioniert auch für Groq, NVIDIA, Gemini und Claude.

### Neu in Version 24 – Viele KI-Anbieter gleichzeitig
Tools → **KI-Anbieter** (oder ⚙ → „Weitere KI-Anbieter“): beliebig viele zusätzliche Anbieter eintragen – Vorlagen für Google Gemini, OpenRouter, Cloudflare Workers AI, Cohere, Hugging Face, Z.ai, SambaNova, Cerebras, Pollinations und „Eigener Anbieter“ (jede OpenAI-kompatible Adresse). Jeweils „Schlüssel holen“ → einfügen → „Testen“. Ist ein Gratis-Limit voll, nimmt Jarvis sofort den nächsten. Pro Anbieter ein Konto.

### Neu in Version 23.1 – Jarvis antwortet schneller
- Spricht **satzweise**: Der erste Satz startet, während der Rest noch berechnet wird (vor allem mit Jarvis-Klang deutlich schneller).
- Beim Sprechen nimmt er zuerst **Groq** (falls Schlüssel eingetragen, meist unter 1 Sekunde), sonst NVIDIA mit schnellen Modellen; die großen „Denker“-Modelle nur noch im Chat.
- Hört schneller auf zuzuhören, wenn du fertig bist (kürzere Pause).

### Neu in Version 23 – Updates ohne Löschen
- Jarvis hat jetzt einen **festen Schlüssel**: Neue Versionen installieren sich einfach über die alte, deine Daten bleiben. (Einmalig, beim Wechsel auf Version 23, muss die alte App noch ein letztes Mal gelöscht werden.)
- **Selbst-Update:** Jarvis schaut alle paar Stunden nach einer neuen Version und fragt dich. Oder: Tools → „Update“ bzw. ⚙ Einstellungen → „Nach Updates suchen“. Beim ersten Mal „Unbekannte Apps installieren“ für Jarvis erlauben.

### Neu in Version 22 – Profi-Modus (Shizuku)
Jarvis schaltet das System selbst: „Flugmodus an“, „mobile Daten aus“, „WLAN/Bluetooth/NFC/Standort an“, „Dunkelmodus an“, „Energiesparmodus an“, „Nicht stören an“, „Bildschirm nach 2 Minuten aus“, „Screenshot“, „nimm 20 Sekunden den Bildschirm auf“, „beende Instagram“, „deinstalliere …“, „starte neu“ (die letzten drei nur nach deinem Ja). Einfache Schalter gehen sofort und auch ohne Internet.
Einrichtung: Tools → **Profi-Modus** → Shizuku aus dem Play Store installieren, „Kabelloses Debugging“ in den Entwickleroptionen an, Shizuku koppeln und starten, „Jarvis verbinden“. Danach **„Auto-Einrichtung“** tippen: Jarvis bekommt alle Freigaben auf einmal (auch die Bildschirmsteuerung ohne die Samsung-Sperre). Nach jedem Handy-Neustart Shizuku einmal neu starten.
Sicherheit: nur eine feste Befehlsliste, keine freien Shell-Befehle; geschützte Apps (Family Link, Play-Dienste, Einstellungen, Telefon) fasst Jarvis nicht an.

### Neu in Version 21 – Jarvis darf mehr selbst
- **„Alles freigeben“-Assistent:** führt dich Schritt für Schritt durch alle Freigaben (mit Samsung-Tipp für „Eingeschränkte Einstellungen“) und zeigt am Ende, was noch fehlt. Zu finden unter Tools → „Alles freigeben“ oder ⚙ Einstellungen → Schritt 2.
- **Bildschirm verstehen:** Öffne eine App, sag „Jarvis, was steht hier?“ oder „Erklär mir das auf meinem Bildschirm“. Jarvis merkt sich beim Weckwort kurz, was auf dem Bildschirm war (nur im Arbeitsspeicher, nach 3 Minuten verworfen) und schickt es nur auf deine Frage an die KI. Braucht die Bildschirmsteuerung und Android 11+.
- **Anrufe:** „Jarvis, nimm ab“ / „leg auf“, und Jarvis sagt an, wer anruft („Anruf von Mama“). Ansage abschaltbar unter Personalisieren. Freigabe: „Anrufe annehmen & ansagen“.
- **Akku-Freigabe** im Berechtigungszentrum, damit Android Jarvis nicht schlafen legt.

### Neu in Version 20 – Hightech-HUD & 3D-Hologramme
- **Neues Design „HUD“:** Startseite mit drehendem HUD-Kern (Radar, Skalen, Anzeigen für Weckwort, Akku, KI, Wetter), Befehlszeile, Sechseck-Schnellknöpfe, Zeitleiste „Heute“, Glas-Kacheln und schwebender Leiste unten. Neue Systemdiagnose mit Akku-Ring und Balken.
- **Jarvis als 3D-Hologramm-Kopf** im Sprach-Bildschirm. Er dreht sich leicht, ein Scan-Licht läuft darüber, beim Sprechen bewegt sich der Mund. Mit dem Finger drehen, antippen = zuhören.
- **Themen-Hologramme:** Redest du über Dinos, Planeten, Erde, Raketen, das Herz, Vulkane oder Atome, projiziert Jarvis ein animiertes 3D-Modell (T-Rex, Brachiosaurus, Triceratops, Saturn, Erde, Rakete, Herz, Vulkan, Atom). Bei anderen Wissensfragen („Wie hoch ist der Eiffelturm?“) holt er ein Bild aus Wikipedia und zeigt es als Hologramm.
- Läuft komplett auf dem Handy (OpenGL). Design wechseln: ⚙ Einstellungen → Design („Hologramm“ ist jetzt Standard).

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

## Autopilot (ab Version 28)
Werkzeuge → **Autopilot**: Hier schaltest du alles ein/aus, was Jarvis von selbst macht.
- 🌙 **Schlafmodus** (Schlafenszeit einstellbar): Nicht stören, dunkler Bildschirm, Dunkelmodus (mit Profi-Modus). Morgens zurück.
- ☀️ **Briefing beim ersten Entsperren** am Morgen.
- ⏰ **Wecker nach Stundenplan** und 📋 **Abend-Check** um 20 Uhr (Termine, Packliste, Hausaufgaben, Akku).
- 🏫 **Schulmodus**: im Unterricht nur Vibration (Stundenplan mit Uhrzeiten nötig, z. B. „08:00 Mathe“).
- 🔋 **Akku-Wächter** unter 20 %, 🧹 **Aufräum-Hinweis** für alte Screenshots (Löschen nur nach Android-Bestätigung).
- 🎂 **Geburtstage** mit vorbereitetem Gruß, 💡 **Fakt des Tages** mit Hologramm.
- 💧 **Pausen-Erinnerung** nach 60 Min. Bildschirm, 📱 **Bildschirmzeit-Coach** mit Limits („Jarvis, TikTok höchstens 60 Minuten am Tag“).
- 😴 **Gute-Nacht-Routine** und 📊 **Wochenrückblick** am Sonntag.
Die Bildschirmzeit bleibt nur auf deinem Handy.

**Neu in Version 29:**
- 🧠 **Gewohnheiten erkennen**: z. B. Kopfhörer rein → immer Spotify? Jarvis fragt, ob er das automatisch machen soll.
- 🚌 **Bus & Bahn**: Start- und Ziel-Haltestelle eintragen → an Schultagen morgens die nächsten Verbindungen mit Verspätung. Oder: „Jarvis, wann fährt mein Bus?“
- 📍 **Ankunft melden**: an gemerkten Orten fragt Jarvis, ob er z. B. Mama Bescheid geben soll (gesendet wird erst nach deinem Ja).
- 🚗 **Parkplatz automatisch**, wenn sich das Auto-Bluetooth trennt (Auto-Gerät in den Einstellungen festlegen).
- 📚 **Vokabeln nebenbei**: Karteikarten-Frage als Benachrichtigung, direkt dort antworten.
- 📝 **Noten-Tracker**: „Jarvis, ich hab in Mathe eine 2- in der Klassenarbeit“ → „Wie ist mein Schnitt?“
- 🌨 **Wetter-Überraschungen**: Schnee, Gewitter, Sturm und Sternschnuppen-Nächte.
- 🆘 **Akku-Notfall-SMS** (nur wenn du es einschaltest): bei 5 % bekommt deine Notfall-Person deinen Standort.

## Aufsteh-Wecker (ab Version 35.1)
Werkzeuge → **Aufsteh-Wecker** (oder „Jarvis, öffne den Aufsteh-Wecker“): ein Wecker, der erst aufhört, wenn du ein Foto vom eingestellten Ort machst (z. B. Waschbecken im Bad) und einen Satz abschreibst. Uhrzeit, Tage, Strenge und Foto-Ort stellst du dort ein. Klingelt auch nach Neustart.

**Abschreiben (ab 35.3):** Satz abtippen und **„Fertig – prüfen“** drücken. Jarvis prüft dann einmal: ganz richtig oder nur 1 Tippfehler → sofort okay; viele Fehler oder fehlende Wörter → nicht okay; dazwischen schaut die KI (eigene KI auf dem Handy, sonst die kostenlose Online-KI) drüber. Ist keine KI erreichbar, entscheidet eine feste Regel – der Wecker hängt nie am Internet.

## Demo-Trading (ab Version 34)
Werkzeuge → **Demo-Trading**: Jarvis-Depot mit 10.000 € **Spielgeld** und echten Kursen (Yahoo Finance, Binance). Nie echtes Geld.
- „Jarvis, schau, welche Aktien gerade steigen könnten“ → Signale (Trend, RSI, Schwung) für ~25 Werte (Apple, NVIDIA, SAP, Rheinmetall, Bitcoin …).
- „Kauf für 500 € Apple“, „Verkauf Tesla“, „Wie steht mein Depot?“, „Zeig den Chart“ (öffnet TradingView).
- 🤖 Demo-Bot an/aus (großer Schalter oben oder „Jarvis, Bot an“): handelt dann ganz allein im Hintergrund – alle 30 Min., wenn die Börse offen ist (Krypto auch nachts). Stop-Loss, -8 %, +15 %, max. 3 Käufe am Tag. Jeder Trade kommt als Benachrichtigung. Aus = nichts passiert automatisch.
- Signale sind Statistik, keine Vorhersage und keine Anlageberatung. TradingView bietet keine Schnittstelle für Demo-Trades, deshalb hat Jarvis sein eigenes Demo-Depot.
- **Neu in 35:** Trailing-Stop (zieht Gewinne mit), Risiko-Stufen (Vorsichtig/Normal/Mutig), eigene Beobachtungsliste, Leistungs-Kurve mit Vergleich zum S&P 500, Trefferquote.
- **Alpaca-Demo-Konto** (optional): echtes Paper-Trading-Konto bei einem Broker (100.000 $ Spielgeld, US-Aktien, ETFs, Krypto). Auf der Demo-Trading-Seite → „Konto“ → Konto anlegen, zwei Schlüssel einfügen. Jarvis ist fest auf das Demo-System eingestellt – echtes Geld ist ausgeschlossen. Bei Brokern muss man meist 18 sein.

## Ohne API-Schlüssel (ab Version 33)
- Jarvis funktioniert jetzt **sofort ohne Schlüssel**: ohne eigene Schlüssel nutzt er automatisch die kostenlose Online-KI Pollinations (langsamer, begrenzt).
- 🧠 **Eigene KI** (Werkzeuge → Eigene KI): einmal per WLAN laden (ca. 1,6 GB), danach läuft deine KI komplett auf dem Handy – ohne Schlüssel, ohne Konto, ohne Internet, privat. Kleiner als die großen Online-KIs, dafür deine. Schalter „Eigene KI zuerst“ = immer privat.
- Schlüssel (Groq, Gemini …) sind freiwillig und machen Jarvis schneller und schlauer.

## AirPods (ab Version 32.1)
Verbindest du AirPods oder Beats, öffnet Jarvis ein Menü wie beim iPhone: Akkustand links/rechts/Case (auch „im Ohr“ und Laden), Musik, Lautstärke, Spotify, Bluetooth.
Geräuschunterdrückung/Transparenz: Stiel gedrückt halten. Per App geht das auf Samsung erst ab Android 17 / One UI 9 ohne Root (App „LibrePods“) – ist sie installiert, öffnet Jarvis sie direkt.

## Neu in Version 32
- 🌍 **Live-Infos:** „Was gibt's Neues?“ (tagesschau), „Wie steht die Tesla-Aktie?“, „Bundesliga-Ergebnisse“ / „Tabelle“, „Wann geht die Sonne unter?“, „Welches Flugzeug fliegt da?“
- 📱 **Handy-Check:** Temperatur, Internet-Speedtest, WLAN-Qualität, Speicherplatz, große Dateien, doppelte Bilder (Löschen nur nach Android-Bestätigung), Systeminfo. 🌡 Hitze-Warnung im Autopilot.
- 🛡 **Sicherheitszentrale** (Werkzeuge → Sicherheit): Punkte-Check (Bildschirmsperre, Updates, fremde Bedienungshilfen, Apps mit vielen Rechten, Apps aus unbekannten Quellen …), Phishing-/Fake-Link-Prüfung, Passwort-Check (bleibt auf dem Handy), Geräte im WLAN (Profi-Modus), Bericht teilen.
- 🗣 **Jarvis-Kern:** Flüstermodus („Flüstermodus an“), Lautstärke passt sich deiner Stimme an, „Wiederhol das“, Offline-Spracherkennung, frühere Gespräche durchsuchen und zusammenfassen, neue Persönlichkeiten (Sarkastisch, Experte, Diskussion), ChatGPT als zusätzlicher Anbieter (kostet Geld).
- 🙂 **3D-Kopf:** Mund bewegt sich beim Sprechen, Farben je Zustand (Denken violett).
- 📷 **Scan:** QR-Codes (mit Betrugs-Check für Links), Live-Modus, Pflanze/Tier, Handschrift, Sternenhimmel.
- 🧭 **Kontrollzentrum:** Systemdiagnose, KI-Test, alle Bereiche, Module, „Jarvis übertragen“ (aufs neue Handy).
- 🚗 **Fahrzeug:** Auto/Motorrad/Crossbike anlegen, Kilometer, Tanken, Verbrauch, Kosten, TÜV- und Wartungs-Erinnerung, Wetter-Warnung beim Einsteigen, GPS-Tacho und Rundenzeit-Stoppuhr für Motocross.

## Jarvis ruft dich an (ab Version 30.2)
- „Jarvis, ruf mich um 7 Uhr an und erinner mich an die Mathe-Arbeit“ → zur Uhrzeit erscheint ein Anruf-Bildschirm mit Klingelton (klingelt auch bei lautlos). Annehmen → Jarvis sagt dir, worum es geht. Auch: „In 5 Minuten“ oder ablehnen.
- 📞 **Verschlafen-Schutz** (Autopilot): Warst du 15 Minuten nach dem Wecker noch nicht am Handy, ruft Jarvis an.
- In Automationen gibt es die Aktion „Jarvis ruft mich an“. Testen: Autopilot → „Jarvis-Anruf testen“.
- Damit der Anruf auch bei gesperrtem Handy erscheint: in der Autopilot-Zentrale „Vollbild-Anruf“ erlauben.

## Profi-Werkzeuge (ab Version 30, braucht den Profi-Modus)
Im Profi-Modus gibt es jetzt: Handy schneller (Animationen), Cache leeren, Hintergrund-Apps beenden, Akkufresser anzeigen und im Hintergrund bremsen,
Werbeblocker fürs ganze Handy, „Wer darf mein Mikrofon/Standort …?“ und Rechte wegnehmen, vorinstallierte Extras einfrieren (umkehrbar),
Schrift- und Anzeigegröße, Bildschirm sperren, automatisch drehen, Fehlerbericht zum Teilen – und Updates ganz ohne Tippen.
Geschützte Apps (Family Link, Play-Dienste, Einstellungen, Telefon, Tastatur, Jarvis, Shizuku) fasst Jarvis nie an.

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

---
3D-Kopf: „Infinite, 3D Head Scan“ von Lee Perry-Smith (Infinite-Realities), lizenziert unter CC BY 3.0, basierend auf einer Arbeit von www.triplegangers.com.
