# KI-Befehle – Modellmessung (Schritt 1)

Ziel: Ein kleines Modell auf dem Gerät macht aus einem getippten oder diktierten Satz einen Plan
(`move`, `copy`, `delete`, `organize` oder `none`). Das Modell fasst nie Dateien an. Die App prüft den Plan,
zeigt eine Vorschau und führt erst nach Bestätigung aus.

## Aufbau

- Prüfstand: `utils/command-bench/bench.py` (nur Standardbibliothek, `--selftest` prüft die Bewertung).
- Befehle: `commands.jsonl` (54, DE + EN, inkl. Diktier-Stil und Nicht-Befehle) und `holdout.jsonl`
  (20 neue Befehle, erst nach der Prompt-Überarbeitung geschrieben).
- Server: llama-server (aus Ollama 0.40.2) auf pop-os, RTX 3070, Antwort per JSON-Schema erzwungen,
  Temperatur 0, Thinking aus.
- Gefährliche Fehler: Löschen, obwohl nicht Löschen gemeint war, und eine Aktion bei einem Nicht-Befehl.

## Ergebnis (14.11.2026)

Lauf 1 = erster Prompt. Lauf 2 = einmal überarbeitet: Das Modell kopiert Zahl und Einheit, die App rechnet.

| Modell (GGUF) | Datei | Lauf 1 (54) | Lauf 2 (54) | Holdout (20) | gefährlich L2 | Antwort |
|---|---|---|---|---|---|---|
| Qwen3.5-0.8B Q4_K_M | 533 MB | 20 | 21 | 9 | 0 | 0,2 s |
| **Qwen3.5-2B Q4_K_M** | 1,28 GB | 33 | **37** | **15** | 1 | 0,3 s |
| Gemma-4-E2B QAT Q2_K_XL | 2,19 GB | 14 | 11 | 6 | 7 | 2,2 s |

Alle drei Apache-2.0. Antwortzeiten gelten nur für die PC-GPU, nicht für Handys.

## Folgen für die App (Schritt 2)

- Favorit: Qwen3.5-2B. Gemma macht aus „Uninstall WhatsApp" oder „Formatiere die SD-Karte" Aktionen, ist raus.
- Auch Qwen-2B las einmal „verschiebe alles älter als ein Jahr von Download nach Alt" als Löschen.
  Daher prüft die App selbst: Ein Lösch-Plan gilt nur, wenn der Satz ein Lösch-Wort enthält
  (lösch, entfern, wirf weg, delete, remove, trash …).
- Bei Löschen fehlte öfter der Ordner. Ohne genannten Ordner gibt es keine Aktion, sondern eine Rückfrage.
- Häufige Restfehler: zusätzliche Endungen bei „Bilder“/„Songs“, Quelle und Ziel vertauscht. Die Vorschau
  zeigt deshalb immer Ordner, Anzahl und Größe vor der Bestätigung.

## Messung auf dem Gerät (Schritt 1b, 14.11.2026)

Pixel 11 Pro XL (Tensor G6, 12 GB RAM, Android 17). Qwen3.5-2B Q4_K_M (1,18 GiB), nur CPU.
Weg: llama.cpp (MIT, Commit `3549c27`) mit NDK 30 nach `docs/android.md` übersetzt, KleidiAI an
(Apache-2.0, wie in `examples/llama.android`). Beide Lizenzen passen zu GPL-3.0.
Skript: `utils/command-bench/device-run.sh` (llama-server auf dem Gerät, `adb forward`, Holdout-Lauf).

| Messung | Wert |
|---|---|
| llama-bench, 4 Threads, kühl | Prompt 99,5 t/s, Ausgabe 20,6 t/s |
| llama-bench, 8 Threads | Prompt 72 t/s, langsamer, also 4 Threads nehmen |
| Holdout auf dem Gerät (20 Befehle) | 15/20 (wie auf der pop-GPU), 0 gefährliche Fehler |
| Zeit pro Befehl, Median | 11 s; ab dem 2. Befehl ~7 s (System-Prompt im Cache) |
| Erster Befehl | 10–32 s; der System-Prompt hat 774 Token, die Spitze von 79 s fiel mit kaltem Laden zusammen |
| Ausgabe im Dauerlauf | ~8 t/s; Akku bei 40 °C, das Gerät drosselt. Mit und ohne JSON-Schema gleich schnell |
| RAM-Spitze (VmHWM, Kontext 2048) | 3,3 GB, danach 2,5 GB |

Folgen:
- Brauchbar, aber spürbar langsam: Ladeanzeige mit Abbruch, keine Antwort im Sekundentakt versprechen.
- System-Prompt kürzen (774 Token): Das senkt die erste Antwort am stärksten. Danach Treffer neu messen.
- Modell und Prompt-Cache beim Öffnen der Eingabe vorladen, nach Leerlauf entladen (3,3 GB).
- Systemcheck (Schritt 3): mindestens 8 GB RAM und ~4 GB frei; 4 Threads.

## Modellwahl und Einbau (Schritte 2 und 3, 14.11.2026 abends)

Weitere Kandidaten auf pop-os gemessen (Prüfstand 54 / Holdout 20 / gefährliche Fehler):

| Modell | Größe | Prüfstand | Holdout | gefährlich |
|---|---|---|---|---|
| Qwen3.5-4B Q4_K_M | 2,74 GB | 51/54 | 20/20 | 0 |
| **Qwen3.5-4B Q4_0** (gewählt, siehe unten) | 2,58 GB | **51/54** | **19/20** | 0 |
| Granite-4.2-3B Q4_K_M | 2,24 GB | 42/54 | 17/20 | 0 |
| Qwen3.5-2B Q4_0 | 1,21 GB | 35/54 | 15/20 | 1 |

NPU/GPU des Tensor G6 sind für eigene Apps derzeit kein Weg: LiteRT-NPU nur mit vorab kompilierten
Gemma-Dateien und SDK-Beta, llama.cpp-Vulkan auf PowerVR liefert Müll (Issues #28214, #28502). Deshalb CPU.

Einbau in der App (`llm/`, `app/.../ai/`):
- llama.cpp als Submodul (`llm/src/main/cpp/llama.cpp`, Commit `3549c27`), statisch in `libordo_llm.so`,
  KleidiAI auf arm64. Nur arm64-v8a und x86_64.
- Prompt wie im Prüfstand, Antwort per GBNF-Grammatik (`CommandPlanner.GRAMMAR`) auf das Plan-JSON gezwungen.
  Gegen llama-server geprüft: gleicher Prompt + Grammatik = 19/20 auf pop-os-CPU.
- Zustand nach dem System-Prompt (774 Token) wird einmal berechnet und gesichert; jeder Befehl startet von
  diesem Schnappschuss (Qwen3.5 hat rekurrente Schichten, Kürzen des Caches geht nicht).
- `CommandEngine`: ein Thread, 4 Threads für die Rechnung (große Kerne), lädt beim Öffnen vor, gibt den
  Speicher nach 3 Minuten Leerlauf frei.
- Einrichtung im Blatt: Gerätecheck (≥ 6 GB RAM, Platz, 64 Bit), Download über DownloadManager von
  Hugging Face (feste Revision), SHA-256-Prüfung, danach direkt zur Eingabe.
- Prüfregeln (`CommandCheck`, Unit-Tests): Löschen nur mit Lösch-Wort ohne Verneinung im selben Teilsatz
  (auch am Satzende: „bitte nicht“, „nie“, „never“), nur in den Papierkorb; geschützt sind
  System-, versteckte und Kamera-Ordner (kanonischer Pfad, ohne Groß-/Kleinschreibung); unbekannter Ordner =
  Rückfrage; Rückgängig fasst nur Dateien an, die der Befehl angelegt hat.
- Duplikate (15.11.2026): Das 4B plante bei „Lösche die doppelten Dateien im Download“ den **ganzen** Ordner
  (258 statt 16 Dateien, `ordo-memory-lab`, pop-os), weil das Plan-Schema kein Feld dafür hat. Jetzt erkennt
  `CommandCheck` Duplikat-Wörter (doppel, Duplikat, duplicate, dupe, mehrfach) im Satz und `CommandFiles` nimmt nur
  die überzähligen Kopien (gleicher Inhalt wie in der Analyse, die älteste bleibt). Unabhängig davon braucht Löschen
  eines ganzen Hauptordners ohne Filter oder von mehr als 50 Dateien eine zweite Bestätigung mit Anzahl und Größe.
- Geprüft: Native-Brücke im Emulator (Instrumentierungs-Test `LlamaEngineTest`, 0,8B), voller Ablauf mit dem
  echten 4B im Emulator (Englisch hell, Deutsch dunkel, Ablehnung, Vorschau). Nicht geprüft: Download auf
  einem echten Handy, Tempo des 4B auf dem Pixel.

## Messungen auf echten Geräten und Wahl von Q4_0 (14.11.2026 nachts)

Tab S9 Ultra (Snapdragon 8 Gen 2, 12 GB), App-Build mit KleidiAI, 4 Threads:

| | Einlesen | Ausgabe |
|---|---|---|
| Q4_K_M | 10,8 t/s | 6,9 t/s |
| **Q4_0** | **32,2 t/s** | **10,5 t/s** |
| Q4_0, Build mit allen CPU-Varianten (`GGML_CPU_ALL_VARIANTS`) | 44,0 t/s | 11,8 t/s |

- Echter Ablauf in der App (Q4_K_M): Download 2,7 GB + Prüfung 170 s, erster Befehl 50 s (Laden + Anweisung),
  danach ~18 s pro Befehl, RAM 4,3 GB (PSS der App).
- Deshalb gilt jetzt **Q4_0**: fast gleiche Treffer, deutlich schneller (KleidiAI hat für Q4_0 eigene Kernel).
  Alte Modelldateien löscht die App selbst.
- Gemma-4-E4B (QAT Q4_0, 5,15 GB): 50/54, Holdout 16/20 – schlechter und doppelt so groß. Für die Tensor-TPU gibt
  es fertige Dateien nur für Gemma-4-E2B, das bei uns durchfiel.
- Nächste Tempo-Schritte: kürzeres Antwortformat (die ~70 Ausgabe-Token sind der größte Anteil) und der
  Varianten-Build (+12 % Ausgabe).
- Modellwahl je Gerät (`ai/ModelChoice.kt`, Test `ModelChoiceTest`): Arbeitsspeicher (`totalMem`), 64-Bit-Bibliothek
  (arm64-v8a, x86_64) und Chip entscheiden, Nutzer sehen keinen Modellnamen. Es gibt nur das 4B, gemessen nur auf
  Geräten mit 12 GB (Tab S9 Ultra: 4,3 GB PSS mit Q4_K_M). Darum bleibt die KI unter 10 GB `totalMem` aus (12-GB-Geräte liegen
  darüber, 8-GB-Handys darunter), bis ein kleineres Handy gemessen ist. Der Chip braucht Dot-Product
  (`asimddp` in `/proc/cpuinfo`, Armv8.2): Nur damit laufen KleidiAIs Q4_0-Kernel, ohne ist das Tempo ungemessen.
  x86_64 (Emulator) hat kein `asimddp` und bekommt das Modell nur für Tests per Hand. Eine kleinere Stufe (z. B.
  Qwen3.5-2B Q4_0, 35/54) braucht vorher feste Datei, Hash und Messung auf einem Handy.

## Zeigen/Suchen: Infrastruktur (16.11.2026, für Nutzer aus)

Spezifikation: `docs/KI-ZEIGEN-PHASE0B.md` (Entwurf K+R). **Der Entwurf fiel im Blindtest durch**
(`tools/memory-lab/blind/results.md`: 22/40, 2 gefährliche Pläne). Auch die Überarbeitung (rules2.py + Prompt C)
kam im Blindtest 2 nur auf 29/50, ohne Gefahr, aber mit zu vielen Absagen. Darum ist in der App nur die
Infrastruktur gebaut; Nutzer bekommen weiter den bisherigen Entwurf.

- **Austauschbar:** Prompt, Grammatik und Wortregeln stecken zusammen in `CommandDesign` (`Today` = ausgeliefert,
  `KR` = Prompt K + `CommandRules`). Alles danach (Prüfung, Vorschau, Liste, Runner) liest nur den Plan.
  `CommandDesign.current` liefert in Release-Builds immer `Today`: Der Schalter hängt an `BuildConfig.DEBUG`, einer
  Konstanten, und wird im Release wegoptimiert.
- **Regeln R als Kotlin-Port** (`CommandRules.kt`) von `tools/memory-lab/rules.py`, Zeile für Zeile. Golden-Test
  `CommandRulesGoldenTest`: alle 816 aufgezeichneten Rohpläne (S, K, KS, T über Dev, Originale, Holdout, Regression;
  S und K über die 40 Blindfälle) durch die Kotlin-Regeln, Ergebnis muss dem Python-Ergebnis gleichen. Er vergleicht
  Port gegen Referenz, nicht gegen Gold. Fixture neu erzeugen: `python3 tools/memory-lab/export_golden.py`.
  Zwei spät gefundene Regelfehler (Abschnitt 4 der Spezifikation) stehen als `@Ignore`-Tests drin, ungelöst.
  Auf dem Emulator (Android 16, Regex über ICU statt JVM) gibt `DebugAiActivity --es golden` ebenfalls 816/816
  (16.11.2026, `e2e/ai-show.sh`).
- **Vorrat für den nächsten Regelsatz:** Der Plan trägt schon die Felder von rules2.py (`exclude_kinds`,
  `exclude_extensions`, `exclude_terms`, `date_from`/`date_to` als ISO-Tag oder -Minute, `min_bytes`/`max_bytes`),
  und die Suche filtert danach; ein unlesbares Datum wählt nichts. Die heutigen Regeln setzen sie nie; Chips in der
  Vorschau und Titel der Liste zeigen sie noch nicht.
- **Plan K** hat 15 Felder: dazu `show`, `content_contains`, `newer_than`, `sort`, `limit`; `today` setzt nur der Code.
  Die gemeinsamen Filter in `CommandFiles` gelten auch für Verschieben/Löschen: „heute“, „letzte Woche“ und ein Wort
  engen ein, nie aus. Sie zählen als Filter bei der zweiten Bestätigung.
- **show** ändert nie etwas. Es sucht rekursiv unter dem Ordner (ohne versteckte Ordner und `Android/data`, mit
  `Android/media` für WhatsApp), sortiert wie verlangt (sonst neueste zuerst), kürzt auf `limit` und öffnet die
  normale Dateiliste mit dem Verstandenen als Titel. Antippen öffnet, langes Drücken wählt aus wie überall.
  Der Auftrag reist nicht im Intent (FileListActivity ist exportiert), sondern als zufälliges Token für eine
  Anfrage im selben Prozess; ein fremder Intent landet in der normalen Dateiliste. Quellen und Ordner, die über
  Links oder „..“ aus dem Speicher führen, werden nie gelesen. Das Schließen des Blatts beendet die Suche; das
  Blatt sucht nur bis zum ersten Treffer. Höchstens 2.000 Treffer und 200 MB gelesener Text je Suche, sonst sagt die
  Liste, dass sie früh aufgehört hat.
- **Weiche ohne Modell:** Speicherfragen und „die größten Dateien“ öffnen die Speicheranalyse auf „Große Dateien“,
  „doppelt“ auf „Duplikate“; Zurück führt zur Übersicht. Sätze, die ein Gedächtnis bräuchten, bekommen einen
  animierten Hinweis (Glühbirne, Antippen = neuer Befehl), bei „nicht geöffnet“ mit Knopf „Unveränderte Dateien
  zeigen“ (älter als ein Monat), nie automatisch.
- **Inhaltssuche:** `ContentSearch` liest nur reinen Text (txt, md, csv, log, json, xml, html, rtf, ics, vcf; bis
  20 MB). PDF und Office werden nur über den Namen gefunden; die Liste sagt das. Optionen für später, ungebaut:
  | Weg | Lizenz | Größe | Anmerkung |
  |---|---|---|---|
  | Android `PdfRenderer` Text (`PdfPageTextContent`) | Plattform | 0 | ab API 35 bzw. S Extensions 13, ältere Geräte nicht |
  | PdfBox-Android 2.0.27.0 | Apache-2.0 (mit GPL-3.0 vereinbar) | AAR 3,25 MB (Maven Central, gemessen) | reines Java, Schriftdaten nötig, letzter Push 03/2024 |
  | MuPDF | AGPL-3.0 | native, je ABI mehrere MB (nicht gemessen) | Lizenz strenger als die App |
  | docx/xlsx/odt | – | 0 | ZIP + XML mit `java.util.zip`, ohne Bibliothek |
- **Gleiche Datei wie in der Vorschau:** Die Vorschau merkt sich Größe und Zeit jeder Datei
  (`CommandFiles.snapshot`). Der Runner überspringt eine Datei, die seitdem eine andere Größe oder Zeit hat (2 s
  Toleranz für FUSE), und die Meldung nennt die Zahl („seit der Vorschau geändert und nicht angefasst“). Das schließt
  die Lücke aus `docs/GEDAECHTNIS-PHASE0.md`, Abschnitt 7.
- **Gespeicherter Prompt-Zustand:** Nach dem ersten Einlesen schreibt `LlamaEngine.saveState` den Zustand nach dem
  System-Prompt in `noBackupFilesDir/llm-state` (nie in Backups), atomar über eine Temp-Datei mit `fsync` von Datei
  und Ordner, mit CRC-32 der Daten im Kopf (vor dem Laden geprüft). Höchstens 256 MB (S maß 205 MB auf dem Tab, K
  geschätzt ~160 MB); lädt eine selbst geschriebene Datei nicht, wird sie für diesen Schlüssel nicht neu geschrieben. Schlüssel: SHA-256 aus
  Modell-Hash, Prompt, Grammatik, Kontextgröße und App-Version; nur eine Datei wird behalten. Gespeichert wird nur,
  wenn danach noch 1 GB frei ist. `restoreState` lehnt fehlende, gekürzte oder unpassende Dateien ab, dann liest die
  App den Prompt wie bisher. Gelöscht mit dem Modell. Gilt auch für den ausgelieferten Entwurf.
  Gemessen nur mit dem Testmodell (Qwen3.5-0.8B Q4_K_M, kurzer Test-Prompt, Emulator x86_64, 16.11.2026):
  Datei 20,4 MB, Speichern 179 ms (mit fsync), Wiederherstellen 15 ms statt 312 ms Einlesen, gleiche Antwort; ein
  gekipptes Byte (CRC) und eine halbierte
  Datei werden abgelehnt (`LlamaEngineTest`). **Für das 4B-Modell mit Prompt K ist die Größe ungemessen**
  (Labor-Schätzung ~160 MB auf dem Tab).
- **Ohne Modell testen (nur Debug):** `DebugAiActivity` liegt in `app/src/debug` und existiert im Release nicht;
  sie verlangt `android.permission.DUMP`, die nur die adb-Shell hat, keine installierte App.
  `e2e/ai-show.sh` gibt damit Modellantworten vor und prüft Liste, Frage→Liste, Hinweis, Analyse-Weiche, Vorschau
  und die Identitätsprüfung: 24/24 auf Deutsch und 24/24 auf Englisch (emulator-5580, 16.11.2026).
  `--ez show_design true` schaltet K+R für echte Modellbefehle in einem Debug-Build ein. **Der Modellpfad selbst
  lief auf keinem Gerät** (dem Emulator fehlt der Speicher).

## Zwei neue Entwürfe mit Sicherheitsnetz: „Guarded“ und „Tuned“ (16.11.2026)

Beide Entwürfe lassen das Modell nur einen Rohplan schreiben; ein festes Netz aus Wortregeln (Python im Labor,
`tools/memory-lab/rules2.py` und `rules3.py`, eingefroren) liest Zeit, Größe, Ausschlüsse und Ordner selbst, füllt
Lücken und **fragt zurück, wenn es unsicher ist**, statt zu raten. Es macht nie aus einer Frage eine Änderung und nie
selbst ein Löschen.

- **Guarded** = Prompt C (`phase0c.py`, 464 Token statt 754 beim heutigen Prompt) + `rules2.decide` (alle Regeln,
  Labor-Stand 430da086), mit „Zeigen“. Kein Zusatz-Download. Blindtests auf dem synthetischen Labor-Telefon (keine
  Sätze des Besitzers): Blindtest 2 29/50 richtig, Blindtest 3 45/60, **0 gefährliche Pläne** in beiden; der heutige
  Entwurf kam auf 16/50 (8 gefährlich) und 11/60 (15 gefährlich). **Für Nutzer eine Vorschau zum Einschalten:**
  Einstellungen › KI-Assistent › „Neue Befehle ausprobieren (Vorschau)“, standardmäßig aus (`Settings.AI_PREVIEW`).
  Aus: unverändert `Today`. An: `Guarded` ab dem nächsten Befehl, ohne Neustart; die Beispiele im Blatt zeigen dann
  zwei der neuen Befehlsarten zuerst. Es bleibt immer nur ein gespeicherter Prompt-Zustand (rund 68 MB für Prompt C,
  gemessen im Emulator): Beim Wechsel löscht der erste Befehl den alten und liest den neuen Prompt einmal ein
  (Emulator ~60 s, Handy ungemessen). Tempo auf einem echten Handy ungemessen; die Blindtest-Sätze stammen nicht
  vom Besitzer, das Test-Handy ist synthetisch.
- **Tuned** = nachtrainiertes Modell (LoRA-Adapter `ordo-commands-1.gguf`, 42.485.408 Byte, SHA-256 30ac6052…,
  Schema L, Prompt 75 Token) + `rules3.decide` (Regeln a–d, Labor-Stand 13bfbed9). **Nur im Entwickler-Build, wartet
  auf den Blindtest:** Blindtest 3 ergab 44/60, 0 gefährlich, unter den geforderten 80 %. Der Adapter ist nicht
  veröffentlicht; der Download unter `releases/download/ai-adapter-1/` antwortet 404, das Blatt sagt dann, dass der Zusatz für
  die KI-Befehle noch nicht verfügbar ist (nur beim Zusatz; das Modell behält die bisherige Meldung). Ein neu trainierter Adapter ändert nur Name, Größe und Hash in `CommandDesign.Tuned`.
- **Port statt Neuschreiben:** `CommandNet.kt` (Wortlesungen aus rules2.py) und `CommandNetDecide.kt` (die beiden
  Entscheidungen) sind Zeile für Zeile portiert, reines Kotlin. Alle Wortlisten, Muster, Prompts und Grammatiken
  erzeugt `tools/memory-lab/export_tuned_lexicon.py` als `CommandNetLexicon.kt` aus dem eingefrorenen Labor, niemand
  tippt sie ab. Golden-Tests vergleichen jede Entscheidung (Status, Grund, Text, übrige Wörter, Warnungen, jedes
  Planfeld) mit Python: `CommandGuardedGoldenTest` (Fixture `export_guarded_golden.py`: alle aufgezeichneten
  Prompt-C-Pläne aus Dev-Sätzen, Blindtest 2 und 3, dazu jeder andere aufgezeichnete Rohplan und die Trainingsziele,
  mit dem Telefon von damals, weil rules2 Dateien zählt) und `CommandTunedGoldenTest` (Fixture
  `export_tuned_golden.py`: alle Schema-L-Rohpläne der Läufe, Blindtest 3 mit dem eingefrorenen Adapter, die Trainings- und Validierungsziele).
  Ergebnisse auf der JVM (16.11.2026): Guarded 12.071/12.071 (davon 334 aufgezeichnete Prompt-C-Pläne), Tuned
  10.717/10.717 gleich. Auf dem Gerät (ICU-Regex) prüft `e2e/ai-show-guard.sh` dieselben Fixtures über
  `DebugAiActivity --es golden_guarded/golden_tuned`.
- **Zählen auf dem Telefon:** rules2 wählt bei Wörtern, die kein Feld erklärt, die Lesart, die auf dem Telefon Dateien
  findet (bis zu 2^n − 1 Wort-Teilmengen). `CommandNetCount` liest dafür jede Quelle einmal je Entscheidung, filtert im
  Speicher, liest den Text jeder Datei höchstens einmal (gleiche Grenzen wie die Suche), zählt gleiche Pläne einmal und
  bricht ab, wenn das Blatt schließt. Über 10 Wörtern sucht es keine Teilmengen mehr (eine Änderung fragt dann
  zurück); 10 ist das Größte, was die Labor-Fälle brauchen. Wie die App zählt: eine Änderung nur im Ordner selbst
  (auch das Labor zählt dort nicht rekursiv, `phase0.execute`: `tree.by_folder`), „Zeigen“ darunter, ohne versteckte
  Ordner und `Android/` außer `media`, Text nur in reinen Textdateien, Duplikate nur bei Änderungen. Gemessen mit
  dieser App-Zählweise auf dem Labor-Telefon weichen von den 334 Prompt-C-Fällen 5 ab (Dev 3/224, Blindtest 2 1/50,
  Blindtest 3 1/60), alle „Zeigen“ mit einem Inhaltswort, das im Labor nur im Text eines PDFs oder Office-Dokuments stand (die App liest dort nur den Namen); keine
  Änderung weicht ab.
  Lässt das Netz Wörter weg, steht das in der Vorschau („Mit „telekom“, „rechnung“ zusammen habe ich nichts
  gefunden …“) bzw. unter der Liste, ebenso eine Anzahl, die nicht passt („Du hast 3 genannt, ich habe 5 gefunden“).
  Gezeigt werden die Wörter so, wie der Nutzer sie getippt hat (das Netz rechnet mit gefalteten Wortstämmen);
  entfernt der Nutzer einen Filter-Chip, verschwindet der Hinweis. Bekannte Grenze: Die 200 MB Text gelten für alle
  Zählungen einer Entscheidung zusammen, die Vorschau danach bekommt neue 200 MB.
- **Rückfrage:** freundliche Frage im Blatt („Wohin sollen die Dateien? …“), der getippte Befehl bleibt stehen und
  kann ergänzt werden. **Absage:** Hinweis-Karte wie bei den Gedächtnis-Sätzen (ZIP, Umwandeln, Umbenennen, Teilen,
  Öffnen, Wiederherstellen, Bearbeiten, frühere Befehle). Ändernde Pläne gehen durch dieselbe Prüfung, Vorschau und
  Bestätigung wie bisher (Lösch-Wort, geschützte Ordner, zweite Bestätigung); Ordner kommen als Pfad aus dem Netz
  (`CommandCheck.check(paths = true)`).
- **Kalender** (Labor-Uhr Sonntag 15.11.2026): „letzte Woche“ = 02.–08.11. (Mo–So der Vorwoche), „diese Woche“ ab
  Montag, „diesen Monat“ ab dem 1., „letzten Monat“ = Oktober: wie festgelegt. Abweichungen der eingefrorenen Regeln,
  nicht geändert: „in den letzten 7 Tagen“ beginnt am 08.11. 0 Uhr (7 Tage plus heute); „letzte 7 Tage“ ohne „den“
  liest der Code gar nicht.
- **Auffälligkeiten der Python-Referenz, nicht „verbessert“:** Ein unlesbares Datum („vom 31.2.“) bricht rules2/3 mit
  einem Fehler ab; die App fragt dann zurück. Eine unlesbare Größe des Modells („über viel“) fragt mit dem Text für
  Zeitangaben nach (Grund `vague_time`).
- **Entwickler-Build:** `DebugAiActivity --es design today|kr|guarded|tuned` wählt den Entwurf für echte Befehle;
  eingegebene Modellantworten (`--es answer`) laufen durch Guarded/Tuned, wenn einer davon gewählt ist.

## Offen

- Älteres Gerät (S21/Exynos 2100) nicht gemessen: Gerät ist gerade nicht verfügbar.
- Gekürzten System-Prompt messen (Treffer + Zeit).
- Ordnernamen gegen echte Ordner auflösen (Download/Downloads, Musik/Music).
