# KI-Befehle Phase 0c: Das Design verallgemeinern

Stand 16.11.2026. Branch `feat/ordo-memory-phase0`, nur Labor (`tools/memory-lab/`), kein App-Code. Modell
Qwen3.5-4B Q4_0 (App-GGUF), Kontext 2048, greedy, auf der GPU von pop-os.

## 1. Ergebnis in Kürze

- **Problem aus Phase 0b:** K+R löste den Holdout seines eigenen Autors zu 93 %, einen fremden Blindsatz aber nur zu
  22/40, mit 2 gefährlichen Plänen. Die Wortregeln waren an bekannte Sätze angepasst.
- **Neu:** sechs allgemeine Mechanismen (a–f) in `rules2.py`, jeder mit eigenen Unit-Tests aus eigenen Sätzen.
  Dazu ein schlankerer Prompt **C**: Das Modell füllt nur noch Aktion, Ordner und Suchwörter. Zeit, Größe, Typ,
  Sortierung und Anzahl liest der Code.
- **Gefährlich: 0 in allen Sätzen** (vorher 2).
- Auf Dev steigt die Trefferquote, auf allen älteren Sätzen bleibt sie gleich oder steigt (Tabelle in Abschnitt 4).
- **Blind 1 ist ab jetzt ein DEV-Satz.** Ich habe beim Bauen auf seine Fehler geschaut. Seine 39/40 sind darum
  **kein Urteil**. Das Urteil liefert Blindsatz 2, geschrieben und ausgeführt von einem anderen Agenten.
- **Eingefroren:** Prompt C und `rules2.py` im Commit, den Abschnitt 9 nennt.

## 2. Fehleranalyse: die 18 Fehler von K+R auf Blind 1

„Roh“ = Plan des Modells K vor den Regeln. „Schema“ = Das Schema aus 0b konnte die Antwort gar nicht ausdrücken.

| # | Satz (gekürzt) | Rohplan | Regel | Ursache | behoben durch |
|---|---|---|---|---|---|
| b001 | Fotos von vorgestern | older_than 2 | → newer_than 2 | **Schema**: genau ein Tag nicht ausdrückbar; Modell falsch | b |
| b003 | Handyvideos vom Sommer | name „Sommer“ | unverändert | **Schema**: Jahreszeit; Modell falsch | b, d |
| b004 | Screenshots aus 2024 | newer 1 Jahr | unverändert | **Schema**: Jahr | b |
| b005 | PDFs seit Weihnachten im down load | newer 2 Wochen (erfunden) | unverändert | **Schema**: „seit Datum“ | b, d |
| b007 | Sprachnachrichten WhatsApp > 1 MB | WhatsApp Images, image | → Elternordner | Modell falsch, **Regel verschlimmert** | d, Typ im Code |
| b012 | Lieferschein von der Küche | Ordner „Küche“ (gibt es nicht) | unverändert | Modell falsch, Regel fing es nicht | d, a |
| b014 | letzten fünf Sprachnachrichten | audio + erfundene Endungen | unverändert | Modell falsch | d, Typ im Code |
| b016 | Download außer Zip | extensions zip (umgekehrt) | unverändert | **Schema**: Ausschluss | c |
| b017 | Kamera außer Fotos | image + Inhalt „außer Fotos“ | unverändert | **Schema**: Ausschluss | c |
| b018 | PDFs this month | newer 1 Monat (rollend) | unverändert | **Schema**: Kalendermonat | b |
| b019 | whatsapp voice messages | WhatsApp Images | unverändert | Modell falsch | d |
| b020 | copy wallpapers to backup | Quelle „.“ | unverändert | Modell falsch, Regel-Lücke (nur nach „aus/die/alle“) | d |
| **b023** ⚠ | Kontoauszüge aus Download nach Bank | **ohne Namensfilter** | unverändert | Modell ließ den Filter weg, Regel fing es nicht: 146 statt 2 Dateien | a |
| b024 | drei Bewerbungen aus Dokumente | richtig (Dokumente) | → Documents/Bewerbungen | **Regel machte es kaputt** | d |
| **b025** ⚠ | Podcast-Folgen im Download löschen | **ohne Namensfilter** | unverändert | Modell ließ den Filter weg, Regel fing es nicht: 284 statt 9 | a |
| b026 | WhatsApp Bilder nach Monaten | richtig (WhatsApp Images) | → Elternordner | **Regel machte es kaputt** | d |
| b031 | Lissabon-Fotos in eine Zip | organize | unverändert | Modell falsch, keine Absage-Liste | e |
| b032 | verwackelte Fotos löschen | delete, Inhalt „verwackelt“ | → show | Modell falsch, Regel halb richtig | a (Rückfrage nur bei delete) |

Summe: **7× Schema**, **2× Regel machte einen richtigen Rohplan kaputt** (+1× verschlimmert), **9× Rohplan
falsch und keine Regel fing es**. Beide Gefahren entstanden gleich: Das Modell vergaß das Bestimmungswort, und keine
Regel prüfte, ob jedes Wort des Satzes im Plan ankommt. Genau das prüft jetzt Mechanismus a.

## 3. Die Mechanismen

Harte Regel beim Bau: Keine Regel hängt an einem Wort oder einer Formulierung aus einem Testsatz. Jede Regel ist eine
sprachliche oder strukturelle Kategorie. Jede hat eigene Unit-Tests mit Sätzen, die in keinem Fallsatz stehen.
`rules2_test.py` prüft das automatisch: 120 Testsätze gegen 232 Fallsätze, keine Kopie und keine Beinahe-Kopie
(Wortüberlappung ≥ 0,8). Die Tests nutzen ein eigenes Mini-Handy und ein anderes „heute“ (Mi 10.03.2027).
Alle Tests laufen mit `python3 phase0c.py --selftest`.

| | Mechanismus | Unit-Tests | Wirkung (ohne ihn, C+R2) |
|---|---|---|---|
| **a** | **Wort-Abdeckung (Wächter):** Jedes Inhaltswort muss in einem Planfeld ankommen. Ausgenommen sind Funktionswörter, Verben, Gattungswörter und alles, was der Code schon gelesen hat. Ein übriges Wort wird bei einer Änderung nur dann zum Filter, wenn genau diese Lesart auf dem Gerät Dateien trifft. Sonst fragt Ordo nach. Wörter in verneinten Satzteilen („aber lösch keine Rechnungen“) zählen nicht. Was das Modell erfindet, wird kein Filter | 15 | **4 gefährliche Pläne**, Blind 36 statt 39 |
| **b** | **Zeit- und Größen-Grammatik im Code:** relative Tage, Wochentage, Monate, Jahreszeiten, Jahre, Daten, feste Feiertage + Ostern, „seit/bis/vor/nach X“, „zwischen X und Y“, „letzte N Tage“, „älter/jünger als“, Größen mit Vergleich, Komma, „halbes GB“, „zwischen 10 und 50 MB“. Ergebnis ist immer ein Bereich von/bis | 26 Zeit + 12 Größe | Blind 33, heutige Befehle 21 statt 26 |
| **c** | **Ausschluss als Feld:** „außer, ohne, bis auf, aber nicht die, except, excluding, other than“ → `exclude_kinds/_extensions/_terms`. Ein verneintes Verb („nicht verschieben“) ist kein Ausschluss | 14 | Blind 37 |
| **d** | **Ordner nur gegen den echten Baum:** exakt, Android-Standardordner, eindeutig unscharf. Sonst fragt Ordo nach. Präposition entscheidet die Rolle: „in den/nach/to“ = Ziel, „im/aus/from“ = Quelle. Ein vom Nutzer genannter, existierender Ordner wird nie ersetzt. Ein Ordnername, der nur Objekt ist („die Bewerbungen aus Dokumente“), wird zurückgegeben | 16 | Dev 25, heutige 8 statt 26 |
| **e** | **Geschlossene Liste, was Ordo nicht kann**, vor dem Planen: packen/entpacken, umwandeln/zusammenfügen, umbenennen, teilen/senden/hochladen, öffnen/drucken, Gelöschtes zurückholen, bearbeiten | 21 | heutige 25 statt 26 |
| **f** | **Plausibilität:** Eine Zahl im Satz („die drei …“, „beide“) wird mit den getroffenen Dateien verglichen. Nur Warnung in der Vorschau | 12 | siehe unten |
| (m) | Gedächtnis-Absage, nur noch als Kategorien: Gewohnheit („wie immer“), Rückbezug („das gleiche, die anderen, vorhin“), Nutzungslog („geöffnet, gesucht“), Anschluss an einen früheren Satz („und jetzt nur die …“, Pronomen als Objekt). Die satznahen Muster aus 0b („arbeitsdokumente“, „zu der rechnung“) sind gestrichen | 12 | – |

**Kosten des Wächters a:** Er macht **0 richtige Pläne** zur Rückfrage, das heißt: in keinem Satz, den C+R2 ohne a
richtig löst. Er verhindert 4 gefährliche Pläne (b023, b025, b024, h023). Über alle Sätze steigen die Absagen und
Rückfragen von 41 auf 82. Fast alles davon sind Folgefragen und persönliche Befehle, die Gedächtnis brauchen; schon
vorher waren sie falsch. **Gegenüber K+R verliert C+R2 genau 3 Fälle:**
- n001 „last week“: jetzt die Kalenderwoche, das Gold rechnete 7 Tage rollend.
- n027: Das Modell sagt `show`, und Code erzeugt nie selbst eine Änderung.
- rc32 „das Übliche“: jetzt ehrlich abgelehnt.

**Plausibilität f: kein Nutzen messbar.** In allen Sätzen gab es 0 Warnungen. Wo eine Zahl genannt war (b024 „die
drei Bewerbungen“), stimmte die Trefferzahl. Rauschen gab es keins, Hilfe aber auch keine. Empfehlung: Die Warnung ist
billig und bleibt in der Spezifikation. Ein Beleg für ihren Nutzen fehlt.

**Kalender-Semantik (festgelegt):**
- **Genannte Zeiträume** sind Kalenderbereiche:
  - „heute“, „gestern“, „vorgestern“, „am Montag“, „am 5. März“ = genau dieser Tag. Ein Wochentag meint den letzten
    vor heute.
  - „diese Woche“ = seit Montag; „letzte Woche“ = Montag bis Sonntag davor.
  - „diesen Monat“ = seit dem 1.; „letzten Monat“ = der ganze Vormonat.
  - „dieses Jahr“ = seit 1.1.; „2024“ = das ganze Jahr.
  - Ein Monat ohne Jahr = sein letztes Vorkommen. Jahreszeit = 3 Monate (Winter = Dez–Feb), ihr letztes Vorkommen;
    „letzten Sommer“ = der letzte abgeschlossene.
  - Feiertage: Weihnachten = 25.–26.12., Heiligabend = 24.12., Silvester, Neujahr, Ostern (berechnet), Pfingsten u. a.
- **Dauern** sind feste Längen ab jetzt: „älter als N“ und „jünger als N“ mit Woche = 7, Monat = 30, Jahr = 365
  Tage, wie die bisherigen Felder `older_than`/`age_unit` im Labor.
- „letzte N Tage/Wochen“ = ab Mitternacht vor N Tagen.
- „neulich, vor ein paar Tagen, recently“ = die letzten 7 Tage.
- Vage Wörter ohne Zahl:
  - „alte“ und „große“ sortieren bei einer Suche nach Alter bzw. Größe.
  - Bei einer Änderung fragt Ordo nach: „Ab wann ist es für dich alt?“

## 4. Ergebnisse vorher/nachher

Gleiche Wertung wie in 0b. Rohpläne: K aus 0b (eingefroren), C neu gelaufen am 16.11. auf pop-os (224 Sätze).

| Variante | Dev Einzel (p0) | 20 Originale | p0b-Holdout | heutige Befehle (27) | persönlich (43) | Blind 1 (**jetzt Dev**) | gefährlich (alle Sätze) |
|---|---|---|---|---|---|---|---|
| K+R vorher (0b) | 37/51 | 12/20 | 40/41 | 24/27 | 5/43 | 22/40 | **2** |
| K+R2 (alter Prompt, neue Regeln) | 38/51 | 13/20 | 39/41 | 26/27 | 4/43 | 40/40 | **0** |
| **C+R2 (eingefroren)** | **38/51** | **13/20** | **39/41** | **26/27** | 4/43 | 39/40 | **0** |

- **Die neuen Regeln tragen auch mit dem alten Prompt K:** K+R2 ≈ C+R2. Sie hängen also nicht an Prompt C.
- Die 20 Originale bleiben durch das Gedächtnis begrenzt: 7 brauchen Wissen, das Ordo nicht hat (siehe 0b).
- Persönliche Befehle: Sie brauchen Gedächtnis. Statt zu raten, lehnt Ordo ab oder fragt nach.

Ablation, Zeitbudget und alle Einzelfehler stehen in `tools/memory-lab/results-0c.md`.

## 5. Zeitbudget auf dem Tab (geschätzt aus Token-Zahlen)

Rechnung: 24 t/s einlesen (gemessen 22–26), 9 t/s schreiben, 5 s laden, 0,2 s Zustand wiederherstellen.

| | System-Prompt | Befehl (Median) | Ausgabe (Median/Max) | warm | kalt mit gespeichertem Zustand | kalt ohne |
|---|---|---|---|---|---|---|
| K (0b) | 798 Token | ~19 | 64 | 7,9 s | ~13 s | ~46 s |
| **C (0c)** | **464 Token** | 19 | **23 / 33** | **~3,4 s** (max ~4,5) | **~8,6 s** | **~28 s** |

Der Prompt schrumpft um 42 %, die Ausgabe um zwei Drittel. Das Modell schreibt keine Zeit-, Größen-, Typ- und
Sortierfelder mehr. Absagen der Liste e und Gedächtnis-Absagen brauchen kein Modell (< 1 ms).

## 6. Sind Regeln das falsche Werkzeug? (geschätzt, nicht gelaufen)

- **Regeln sind richtig, wo es eine feste Wahrheit gibt:** Kalender, Größen, Ordnerbaum, Sicherheit (Frage →
  nichts ändern, Code macht nie ein delete, Wächter), Liste der unmöglichen Aktionen. Das ist prüfbar, deterministisch
  und in < 1 ms erledigt.
- **Regeln sind das falsche Werkzeug für Sprachverständnis:** welche Aktion gemeint ist, welches Wort ein Filter ist.
  Dort sitzen die Restfehler (n027, m022, b032), und dort würde jede neue Wortregel wieder überanpassen.
- **Dynamische Few-Shot-Bank:**
  - Ablauf: 4 ähnliche Beispiele, gesucht per Wortsuche ohne Zusatzmodell, hinter den gespeicherten Prompt gehängt.
  - Kosten: ~200 Token mehr je Befehl, also **+7–9 s warm** auf dem Tab. Das macht den Gewinn aus 5 zunichte.
  - Lohnt nur, wenn die Bank sehr klein bleibt, oder auf schnellerer Hardware.
- **LoRA-Feintuning des 4B** auf ~3.000 synthetischen Befehl→Plan-Paaren:
  - Erzeugung: Paare per Vorlagen + Umformulierung durch qwen27 lokal, ~1–2 h, ohne GPU-Exklusivität.
  - Training: QLoRA auf der RTX 3070, Sequenz ≤ 256, 2 Epochen ≈ **20–40 min**. Das ist kein Mehrstundenlauf.
  - Ergebnis:
    - Ein **Adapter von ~30–60 MB** (llama.cpp lädt LoRA-GGUF zur Laufzeit). Das Basismodell und sein SHA256
      bleiben gleich, die App lädt den Adapter zusätzlich.
    - Alternative: mergen und neu quantisieren. Das ergibt eine **neue 2,5-GB-Datei mit neuem Hash**: neuer
      Download für alle und Änderung von `CommandModel.SHA256`.
  - Gewinn: Der Prompt könnte auf ~100 Token schrumpfen, kalt also ~10 s.
  - Risiko: Synthetische Daten tragen die Denkmuster ihres Autors. Das ist dieselbe Überanpassung eine Ebene tiefer.
    Darum braucht es einen eigenen Blindsatz.
  - **Wann besser:** Wenn Blindsatz 2 zeigt, dass die Restfehler beim Modell liegen (Aktion, Suchwort) und nicht im
    Code. Dann ersetzt der Adapter die Beispiele im Prompt, und die Code-Mechanismen bleiben, wie sie sind.

## 7. Bauvorgabe (Stand eingefroren)

**Ablauf je Befehl:**
1. Text normalisieren:
   - Bindestriche zwischen Wörtern werden Leerzeichen.
   - Diktat-Trennungen werden zusammengezogen, wenn nur die zusammengesetzte Form bekannt ist („down load“).
   - Diktierte Buchstaben werden zusammengezogen („a p k“).
   - Bekannte Kopfwörter werden aus Komposita gelöst („Bewerbungsfotos“ → „Bewerbung Fotos“).
2. **e** Liste „kann Ordo nicht“ → Absagetext; **m** Gedächtnis-Kategorien → Absagetext. Bei beiden kein Modell.
3. Modell mit Prompt C → action, source, destination, name_contains, content_contains, group_by.
4. Code liest in dieser Reihenfolge und verbraucht jeweils die Wörter:
   1. Speicher-Absicht
   2. Ausschluss (**c**)
   3. Gruppierung („nach Monat/Typ“)
   4. Ordner (**d**)
   5. Zeit und Größe (**b**)
   6. Sortierung/Anzahl („die 5 größten“, „mein letzter“)
   7. Zahl (für **f**)
   8. Dateitypen (Typnamen-Lexikon)
   9. Duplikat-Wörter
   10. vage Zeit/Größe
5. Sicherheit je Teilsatz:
   - Frage, Nebensatz („bevor/wenn“) und verneintes Verb zählen nicht als Auftrag.
   - Will das Modell ändern, aber kein Auftragssatz sagt das, gilt der einzige genannte andere Auftrag
     (move/copy/organize), sonst `show`.
   - **Code erzeugt nie delete.** „organize“ mit Verschiebe-Verb und Ziel wird move.
6. Quelle:
   - Die Reihenfolge ist: als Ort gesagt (stark) > Ordnername/Standardordner im Satz (schwach) > Ordner des Modells,
     wenn er existiert.
   - Mehrere Kandidaten bei einer Änderung → Rückfrage.
   - Bei einer Änderung ohne Quelle → Rückfrage.
   - Ziel = Quelle → Rückfrage.
7. **a** Wächter (siehe 3) → Filter `name_terms` (delete oder „im Namen“) bzw. `text_terms` (Name oder Inhalt).
   „in einen eigenen Ordner“ ohne Namen → Ordner nach dem Suchwort benennen.
8. **f** Zahl ≠ Treffer → Warnzeile in der Vorschau.
9. Vorschau, Bestätigen, Rückgängig wie heute.

**Planfelder (App):**
- vom Modell: `action, source, destination, name_contains, content_contains, group_by`
- vom Code: `kinds, extensions, date_from, date_to` (ISO, bis exklusiv), `min_bytes, max_bytes, sort`
  (`size|newest|oldest|smallest`), `limit` (nur mit sort), `name_terms, text_terms`, `exclude_kinds,
  exclude_extensions, exclude_terms`
- Namensvergleich ohne Groß/Klein und umlauttolerant (ä/ae → a usw.)

**Grammatik C:**
```
root ::= "{\"action\":" action ",\"source\":" string ",\"destination\":" string ",\"name_contains\":" string ",\"content_contains\":" string ",\"group_by\":" groupby "}"
action ::= "\"show\"" | "\"move\"" | "\"copy\"" | "\"delete\"" | "\"organize\"" | "\"none\""
(groupby, string, char wie heute)
```

**Prompt C** (464 Token): wörtlich `phase0c.system_c()`. Er besteht aus 6 Feldzeilen, dem Satz „The app reads file
types, dates, sizes, sorting and counts from the command itself; leave them out.“ und 6 Beispielen, die in keinem
Fallsatz vorkommen.

**Absage- und Rückfragetexte** (Entwürfe, vor dem Einbau mit `ux-writing` prüfen):

| Fall | Text |
|---|---|
| packen/entpacken | Packen und Entpacken (ZIP) kann Ordo noch nicht. |
| umwandeln | Dateien umwandeln oder zusammenfügen kann Ordo nicht. |
| umbenennen | Umbenennen per Befehl kann Ordo noch nicht. Halte die Datei gedrückt und wähle „Umbenennen“. |
| teilen/senden | Teilen, Senden und Hochladen geht per Befehl nicht. Halte die Datei gedrückt und wähle „Teilen“. |
| öffnen/drucken | Öffnen, Abspielen und Drucken geht per Befehl nicht. Ich kann dir die Datei zeigen; tippe sie dann an. |
| zurückholen | Gelöschte Dateien kann Ordo nicht zurückholen. |
| bearbeiten | Dateien bearbeiten kann Ordo nicht. |
| Gedächtnis | Ich merke mir keine früheren Befehle, Suchen oder Gewohnheiten. Sag bitte genau, welche Dateien du meinst. |
| Wort ohne Treffer (a) | Ich weiß nicht, wie ich „X“ auf deine Dateien anwenden soll. Kommt das Wort im Dateinamen vor? |
| Ordner fehlt / unbekannt / mehrdeutig | In welchem Ordner soll ich das machen? · Den Ordner „X“ finde ich nicht. Wie heißt er genau? · Ich habe mehrere passende Ordner gefunden: X, Y. Welchen meinst du? |
| Ziel fehlt | Wohin soll ich die Dateien legen? Nenn mir bitte einen Ordner. |
| vage Zeit/Größe bei Änderung | Ab wann ist es für dich „alt“? … · Ab welcher Größe meinst du „groß“? … |

## 8. Was ich nicht lösen konnte

- **Kalender gegen Erwartung:** Bei „last week“ (n001) erwartet das Gold 7 Tage rollend, die neue Semantik nimmt die
  Kalenderwoche. Das ist eine Festlegung, kein Fehler. Belkis sollte sie bestätigen.
- **Modell wählt die Aktion falsch:**
  - „räum download auf aber lösch bloß nichts“ → `show`; „in nen extra ordner“ → `show`. Code befördert nie zu einer
    Änderung, darum bleibt es eine harmlose Fehlleistung.
  - „Welche Fotos sind verwackelt? Die kannst du gleich löschen.“: Das Modell sagt `show`, Ordo sucht „verwackelt“ und
    findet nichts. Harmlos, aber keine ehrliche Absage. Bildinhalte beurteilen steht bewusst **nicht** auf der Liste e,
    weil das Wort aus dem Testsatz stammt.
- **Lexika sind endlich:** Typnamen, Standardordner und Verben. Ein unbekanntes Verb wird bei einer Änderung zum
  übrigen Wort, und dann fragt Ordo nach. Das ist sicher, kostet aber Rückfragen.
  - Einträge, die nach einem Blind-1-Fehler dazukamen, offen benannt: Sprachnachricht/voice message → Messenger-
    Sprachordner; „Handy“ + Bild/Video → Kamera.
- **Persönliche Befehle** (4/43) brauchen Gedächtnis. Mehr als ehrliche Absagen geht ohne Gedächtnis nicht.
- `rules2.py` hat ~1.050 Zeilen, davon gut die Hälfte Lexika. Ein Kotlin-Port braucht dieselben Unit-Tests;
  `rules2_test.py` ist die Vorlage.
- Keine Messung auf dem Tab. Alle Zeiten sind Schätzungen.

## 9. Protokoll gegen Überanpassung

- **Blind 1 = Dev.** Seine Zahlen sind kein Urteil.
- Ich habe keinen künftigen Blindsatz gesehen.
- **Eingefroren:** Prompt C (`phase0c.py`) und Regeln (`rules2.py`). Den Commit-Hash nennt die Meldung an Belkis.
- Blindsatz 2 schreibt und führt ein anderer Agent aus:
  - Rohpläne: `phase0c.py run --only C` auf seinen Fällen.
  - Wertung: `rules2.decide` unverändert.
- **Meine Erwartung für Blindsatz 2:**
  - gefährlich: wahrscheinlich 0–1. Der Wächter fängt vergessene Filter allgemein; ein Rest-Risiko bleibt, wenn ein
    falsches Wort zufällig Dateien trifft.
  - Trefferquote: **ehrlich 65–80 %**, nicht 97 %.
  - Erwartete Ausfälle: neue Wörter, die im Lexikon fehlen und dann zur Rückfrage führen, falsche Aktionen des Modells
    und Zeitausdrücke außerhalb der Grammatik (z. B. „Anfang März“, „am Wochenende vor Ostern“).
