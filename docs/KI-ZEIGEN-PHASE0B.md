# KI-Befehle Phase 0b: Einzelbefehle zuverlässig machen, ohne Gedächtnis

Stand 16.11.2026. Branch `feat/ordo-memory-phase0`, **nicht mergen bis zur Play-Freigabe**. Nur Labor
(`tools/memory-lab/`), kein App-Code. Modell, Grammatik-Technik, Kontext 2048 und Bewertung wie in
`docs/GEDAECHTNIS-PHASE0.md`.

## 1. Ergebnis

**Ziel erreicht, aber nur im Labor.** Gemessen wurde auf einem neuen Holdout mit 41 Befehlen. Das empfohlene Design
**K+R** besteht aus einem kurzen Prompt (798 statt 1.440 Token) und festen Wortregeln im Code. Es löst dort
**38/41 = 93 %**. Mit einer Korrektur der Wertung (Abschnitt 4) sind es 40/41 = 98 %. Gefährliche Fälle: **0** in
allen Sätzen. Die Befehle der App von heute werden nicht schlechter: 24/27 statt 23/27.

Geschätzte Zeiten auf dem Tab S9 Ultra:

| | K+R | S heute |
|---|---|---|
| warm, je Befehl | 7,9 s (Median), höchstens 9,9 s | 9,6 s |
| kalt, mit gespeichertem Prompt-Zustand | ~13 s | – |
| kalt, ohne gespeicherten Zustand | ~46 s | 75 s |

Die Abhilfe bringen die **Regeln, nicht das Modell**. Jede Variante ohne Regeln bleibt bei 56–78 % und hat 3 bis 23
gefährliche Fälle. Mit Regeln sind es 83–93 % (korrigiert bis 98 %), und die Gefahren verschwinden bei S und K
ganz.

**Die Schranke sind die 20 Originalbefehle: 12/20.** Mehr als 13/20 geht ohne Gedächtnis nicht. 7 der 20 Befehle
brauchen Wissen, das Ordo nicht hat. K+R lehnt sie jetzt ehrlich ab, statt einen falschen Plan zu zeigen
(Abschnitt 5).

**Vorbehalt:** Holdout und Regeln stammen vom selben Autor, also von mir. Der Holdout wurde zwar vorher geschrieben
und committet (`de8a4f48`), die Regeln danach eingefroren (`78320a74`). Gleiche Denkmuster sind trotzdem möglich.
Der ehrliche Test sind 20–30 neue Sätze **von Belkis selbst**, bevor gebaut wird.

## 2. Sätze

| Satz | Größe | Rolle |
|---|---|---|
| Dev | 51 Einzelbefehle + 22 mit Vorgeschichte | Abstimmen erlaubt. **Der alte Holdout aus Phase 0 (26 Fälle) gehört ab jetzt hierher**, weil die Regeln an ihm entwickelt wurden |
| 20 Originale | Teil von Dev | Belkis' Sätze wörtlich |
| **neuer Holdout** | 41 | Umgangssprache, Diktatfehler („whats app“, „mega byte“, „screen shot“), 4 englische Sätze, Fragen, die harmlos bleiben müssen, und 3 Befehle, die ein Gedächtnis bräuchten. Goldlabels **vor** jedem Lauf committet (`de8a4f48`), nie zum Abstimmen benutzt |
| Regression | 70 | alle Befehle des alten Labors (`commands/holdout/holdout2.jsonl`): 27 allgemeine wie heute, 43 persönliche, die ein Gedächtnis brauchen |

## 3. Fehleranalyse: Woran S scheiterte (Dev, Einzelbefehle)

„Modell“ heißt: Das Schema hätte die Antwort ausdrücken können, das Modell füllte aber das falsche Feld. „Gedächtnis“
heißt: Ohne Wissen, das Ordo nicht hat, geht es nicht. „Gold“ heißt: Die Erwartung war zu streng.

| Befehl | Fälle | falsches Feld | Ursache | Abhilfe im Code |
|---|---|---|---|---|
| 3 Versicherung | 1 | `content_contains` = „versicherungs zeug“ | Modell übernimmt Füllwort | Wortstamm, „zeug/sachen“ weg |
| 4 doppelt | 1 | `action` none („mehrfach da?“) | Modell erkennt Frage nicht | Duplikat-Weiche |
| 6 Rechnung ohne Namen | 1 | `name_contains` statt `content_contains` | Modell | Wort ohne „im Namen“ = Name **oder** Text |
| 8 Rechnungen verschieben | 3 | `organize` statt `move`, Ziel fehlt | Modell (2); **Gold** (1): „in einen eigenen Ordner“ nennt keinen Namen, eine Rückfrage wäre richtig | „verschieb/pack … nach X“ = move |
| 9 letzte APK | 2 | `sort` fehlt | Modell | „zuletzt/neueste“ = sort newest, limit 1 |
| 10 Screenshots | 1 | `source` fehlt, `newer_than` ohne Einheit | Modell | Ordner- und Zeitwörter |
| 13 Foto vor ein paar Tagen | 3 | `older_than` statt `newer_than`, Kamera fehlt | Modell | Zeitwörter, „Foto gemacht“ = Kamera |
| 14 Speicher frei machen | 3 | `delete` (gefährlich) / none | Modell | Speicher-Weiche, nie löschen |
| 11 nicht geöffnet | 3 | – | **Gedächtnis**: Android kennt keine letzte Öffnung | ehrlich ablehnen |
| 12 Arbeitsdokumente | 3 | – | **Gedächtnis**: „Arbeit“ ist eine Bedeutung, kein Wort | ehrlich ablehnen |
| 19 gestern gesucht | 3 | – | **Gedächtnis**: Ordo speichert keine Suchen | ehrlich ablehnen |
| 20 wie normalerweise | 3 | – | **Gedächtnis**: Gewohnheit | ehrlich ablehnen |
| 16–18, Folgefragen, Korrekturen | 22 | – | **Gedächtnis**: brauchen das vorige Gespräch | ehrlich ablehnen |

Von 27 Fehlern bei Einzelbefehlen waren **14 Modellfehler**, die Code beheben kann. **12** brauchen Gedächtnis,
**1** war zu streng gewertet.

## 4. Ergebnis je Hebel

Alle Varianten liefen auf demselben GPU-Server, greedy und ohne Prompt-Cache, also wiederholbar. „+R“ = mit den
Regeln aus `rules.py`. Die Zeiten sind **Schätzungen** aus den Token-Zahlen mit den Tab-Werten von 16.11.:
24 t/s einlesen, 9 t/s ausgeben, 5 s laden, 0,2 s Zustand wiederherstellen.

| Variante | Dev Einzel (51) | 20 Originale | **neuer Holdout (41)** | heutige Befehle (27) | gefährlich, alle Sätze | System-Token | Ausgabe-Token | Tab warm Median/Max | Tab kalt ohne/mit Zustand |
|---|---|---|---|---|---|---|---|---|---|
| A heute | 2 | 0 | 16 (39 %) | 23 | 10 | 754 | 48 | 6,1 / 8,0 s | 43 / 11 s |
| S Phase 0 | 25 | 9 | 32 (78 %) | 24 | 6 | 1.440 | 66 | 9,6 / 11,6 s | 75 / 15 s |
| **Hebel 1:** S+R | 37 | 12 | 37 (90 %) | 25 | **0** | 1.440 | 66 | 9,4 / 11,6 s | 75 / 15 s |
| **Hebel 2:** K kurz | 29 | 11 | 31 (76 %) | 23 | 3 | 798 | 64 | 7,9 / 9,9 s | 46 / 13 s |
| **K+R (Empfehlung)** | **37** | **12** | **38 (93 %)** | **24** | **0** | **798** | 64 | **7,9 / 9,9 s** | **46 / 13 s** |
| Hebel 2b: KS (Zeit und Größe nur im Code) | 22 | 8 | 23 (56 %) | 17 | 10 | 607 | 37 | 4,9 / 7,0 s | 35 / 10 s |
| KS+R | 36 | 12 | 34 (83 %) | 22 | 1 | 607 | 38 | 4,9 / 7,0 s | 35 / 10 s |
| **Hebel 3:** T zweistufig | 21 | 8 | 24 (59 %) | 17 | 23 | 155 + 225–406 | 31 | 5,1 / 7,4 s | 31 / 11 s |
| T+R | 34 | 12 | 36 (88 %) | 21 | 5 | wie T | 32 | 4,8 / 7,4 s | 32 / 11 s |

**Wertungs-Korrektur nach dem ersten Blick auf den Holdout:** Bei drei Fragen nach **einer** Datei („die größte
Datei im Download“, „the newest pdf“, „das neueste Foto“) verlangte die Wertung mindestens 3 Treffer. Gemeint war:
Die erste Datei muss stimmen. Korrigiert ergibt das S+R 40/41, K+R 40/41, T+R 39/41, S 34/41 und K 33/41. Die Tabelle
oben zeigt die strenge, ursprüngliche Wertung. Die Regeln wurden danach **nicht** mehr angepasst.

**Was die Hebel bewirken:**
- **Hebel 1 (Regeln) bringt den Hauptgewinn:** +8 bis +14 Fälle auf Dev und +5 bis +12 auf dem Holdout. Außerdem
  verschwinden die gefährlichen Fälle.
  - Die Weiche ohne Modell greift selten: auf dem Holdout 4 von 41 (1× Speicher, 3× ehrliche Absage), auf Dev 21 von
    51.
  - Der Gewinn kommt vor allem aus den Korrekturen **nach** dem Modell: Zeitwörter, Sortieren, Ordnernamen,
    Sicherheit.
- **Hebel 2 (kurzer Prompt) ist ohne Nachteil:** gleich gut wie S. Mit Regeln ist er auf dem Holdout einen Fall besser, bei den
  heutigen Befehlen einen schlechter (24 statt 25 von 27). Er spart
  ~640 System-Token: kalt ~29 s weniger, warm ~1,7 s weniger.
- **Hebel 2b (Zeit und Größe nur im Code) lohnt nicht:** Die Ausgabe wird ~3 s schneller, aber es gibt mehr Fehler
  und einen gefährlichen Fall. Fehlt ein Zeitwort in den Regeln, geht die Grenze ganz verloren, und dann wird zu viel
  gelöscht.
- **Hebel 3 (zweistufig) ist der schlechteste:** Das Modell wählt in der ersten Stufe öfter die falsche Aktion
  („Kopiere … nach Backup“ wird zu move). In der zweiten Stufe fehlen dann Filter. Ergebnis: 23 gefährliche Fälle
  ohne Regeln, 5 mit Regeln.

**Neuer Holdout nach Art (K+R, korrigierte Wertung):**

| zeigen/suchen | sortieren | Zeitbezug | Inhaltssuche | heutige Aktionen | harmlose Fragen | ehrliche Absage | Englisch |
|---|---|---|---|---|---|---|---|
| 21/22 | 5/6 | 5/5 | 5/5 | 8/8 | 8/8 | 3/3 | 4/4 |

Restliche Fehler von K+R auf dem Holdout:
- „zeig mir das neueste foto aus der kamera“: Das Modell erfindet `newer_than` 1; das letzte Foto ist aber von
  vorgestern. Mögliche Regel, nicht gemessen: Zeitfelder ohne Zeitwort im Satz löschen.
- „die größte Datei“ und „the newest pdf“: nur die strenge Wertung, siehe oben.

Bei S+R kam ein echter Regelfehler dazu: Bei „rechnung von mediamarkt … weiß nicht, wie die datei heißt“
überschreibt die Regel das richtige Inhaltswort. Behebung: das Inhaltswort des Modells nie überschreiben. Das ist
**noch nicht gemessen**.

**Gefährliche Fälle:**
- **K+R: 0 in allen 184 Fällen.** Das umfasst „Was kann ich löschen?“, „kann ich die alten whatsapp videos
  löschen?“, „brauch ich die setup apks noch?“ und „aber lösch bloß nichts“.
- Ohne Regeln haben S, K, KS und T 3–23 gefährliche Fälle, z. B. Löschen auf „Was kann ich löschen?“ oder
  „Hochzeitsfotos verschieben“, das alle Kamerafotos erfasst.

**Heutige Befehle (Regression):**
- K+R löst 24/27 allgemeine Befehle, A heute 23/27.
- Persönliche Befehle, die ein Gedächtnis bräuchten: 5/43 wie A.
- Gefährlich: 0. A hat in diesem Satz ebenfalls 0, S ohne Regeln 4.

## 5. Was ohne Gedächtnis nicht geht, und was Ordo dann sagt

| Befehl | lösbar ohne Gedächtnis? | Ordo antwortet statt eines Plans |
|---|---|---|
| 11 „seit einer Woche nicht geöffnet“ | **nein**: Android führt keine verlässliche letzte Öffnung | „Ich weiß nicht, wann du Dateien geöffnet hast. Soll ich dir Dateien zeigen, die länger nicht geändert wurden?“ (als Knopf, nicht automatisch) |
| 12 „Arbeitsdokumente einsortieren“ | **nein**: „Arbeit“ steht in keinem Namen, Text oder Ordner | „Ich weiß nicht, welche Dateien zur Arbeit gehören. Nenn mir ein Wort aus Name oder Inhalt, zum Beispiel den Firmennamen.“ |
| 18 „restliche Bilder genauso“, 16 „Ordner von vorhin“, 17 „Dateien zu dieser Rechnung“ und alle Rückbezüge | **nein**: brauchen das vorige Gespräch | „Ich merke mir frühere Befehle noch nicht. Sag bitte genau, welche Dateien du meinst.“ |
| 19 „gestern gesucht“ | **nein**: Ordo speichert keine Suchen | „Ich speichere keine Suchen. Nenn mir ein Wort aus dem Dateinamen.“ |
| 20 „wie ich es normalerweise mache“ | **nein**: Gewohnheit | „Ich lerne keine Gewohnheiten. Soll ich nach Typ oder nach Monat sortieren?“ |

Die Absage zählt nur als richtig, wo das Gold „Absage“ sagt (3 Fälle im neuen Holdout). Bei den 20 Originalen sagt
das Gold „echte Antwort“, darum stehen dort 12/20 und nicht 19/20. Die Texte sind Entwürfe, vor dem Einbau mit
`ux-writing` prüfen.

## 6. Empfohlenes Design (Spezifikation für den Einbau)

### 6.1 Ablauf je Befehl

1. **Weiche** (`rules.route`, Code, < 1 ms):
   - Gedächtnis-Sätze werden mit dem passenden Text aus Abschnitt 5 abgelehnt.
   - Speicherfragen öffnen die vorhandene Speicheranalyse, größte Dateien zuerst.
   - „doppelt/mehrfach“ ohne Löschwort und ohne Ordner zeigt die Duplikate.
   - „die größten Dateien“ ohne weiteren Filter zeigt eine Liste nach Größe.
   - In diesen Fällen wird **kein Modell** geladen.
2. **Modell** mit dem kurzen Prompt K (6.3) und der Grammatik (6.2).
3. **Korrektur** (`rules.fix`, Code), in dieser Reihenfolge:
   1. **Sicherheit:**
      - Eine Frage („was/wo/welche/kann ich/brauch ich …“ oder „?“) macht aus delete, move oder organize ein show.
      - delete ohne Löschwort, oder mit „nichts löschen“, wird organize (bei „aufräumen“) oder show.
      - **Code macht nie selbst ein delete.**
   2. „verschieb/pack … nach X“ ist move, auch wenn das Modell organize sagt.
   3. **Ordnernamen:** Ein Name, den es auf dem Gerät wirklich gibt, nach „aus/in/im“, sonst nach „von“, sonst nach
      „die/alle“, wird `source`. Ein Ordnername nach „nach/to“ wird `destination`.
   4. **Zeit** (ersetzt die Zeitfelder des Modells ganz):
      - „heute“ = seit Mitternacht
      - „gestern“ = newer 1 Tag
      - „letzte/diese Woche“ = newer 1 Woche
      - „letzten Monat“ = newer 1 Monat
      - „vor ein paar Tagen/neulich“ = newer 7 Tage
      - „letzten N Tage“ = newer N Tage
      - „älter als N …“ = older
      - Zahlwörter „ein … zwanzig“ werden mitgelesen.
   5. **Größe:** „größer/über N MB/GB“, „mega byte“, „giga“, „ein halbes Giga“ = 500 MB.
   6. **Sortieren:** „größte“ = sort size, „neueste/zuletzt/als letztes“ = sort newest. „die N größten“ = limit N,
      Einzahl („die größte“, „zuletzt“) = limit 1.
   7. **Endungen und Arten:** „pdf, apk, zip, mp3 …“ = extensions; „Bilder/Fotos“, „Videos“, „Musik“ = kinds.
   8. **Nur bei show, Ordner-Wörter:**
      - „Screenshots/Bildschirmfotos“ = Screenshots
      - „WhatsApp + Bilder“ = WhatsApp Images, „WhatsApp + Videos“ = WhatsApp Video
      - „heruntergeladen/Download“ = Download
      - „Kamera“ oder „Foto gemacht“ = Kamera
      - „auf dem Handy“ = ganzes Telefon
   9. **Bestimmungswort:** „Instagram-Screenshots“, „Telekom-Rechnungen“: Das Bestimmungswort kommt in den
      Namensfilter, sonst nimmt der Plan alle Dateien dieser Art.
   10. **Name oder Inhalt:** Ein Suchwort ohne „im Namen“ wird in Name **oder** Text gesucht, außer bei delete.
       „…zeug/…sachen“ fällt weg.
4. **Prüfung wie heute:** `CommandCheck` und Vorschau.
   - **show** zeigt eine Ergebnisliste ohne Bestätigen-Knopf: Name, Ordner, Größe, Datum, sortiert wie verlangt.
     Antippen öffnet die Datei, lange drücken springt in ihren Ordner.
   - **move/copy/delete/organize** laufen unverändert über Vorschau, Bestätigen, zweite Bestätigung ab 50 Dateien
     und Rückgängig.
   - `show` liest auch `Android/media` (WhatsApp) und die Kamera, verändert dort aber nichts.

### 6.2 Schema und Grammatik

15 Felder. Gegenüber heute kommen hinzu:
- `show` als Aktion
- `content_contains`
- `newer_than` (nutzt `age_unit` mit)
- `sort` (`""`/`size`/`newest`)
- `limit`

`group` aus Phase 0 fällt weg. Die Grammatik nach dem Muster von `CommandPlanner.GRAMMAR`:

```
root ::= "{\"action\":" action ",\"source\":" string ",\"destination\":" string ",\"kinds\":" kinds ",\"extensions\":" strings ",\"name_contains\":" string ",\"content_contains\":" string ",\"older_than\":" int ",\"newer_than\":" int ",\"age_unit\":" ageunit ",\"larger_than\":" int ",\"size_unit\":" sizeunit ",\"sort\":" sort ",\"limit\":" int ",\"group_by\":" groupby "}"
action ::= "\"show\"" | "\"move\"" | "\"copy\"" | "\"delete\"" | "\"organize\"" | "\"none\""
sort ::= "\"\"" | "\"size\"" | "\"newest\""
(kinds, strings, ageunit, sizeunit, groupby, string, char, int wie heute)
```

Ausgabe im Median 64 Token. Die App erlaubt heute 200, das reicht.

### 6.3 System-Prompt K (798 Token)

Wörtlich in `tools/memory-lab/phase0b.py` (`SYSTEM_K`, erzeugt aus `FIELD_TEXT` + `EXAMPLES`). Er besteht aus:
- Feldbeschreibungen in je einer Zeile,
- den Ordner-Hinweisen Download/Kamera/Screenshots/WhatsApp Images,
- 6 Beispielen: move, delete mit Alter, organize, show mit sort+limit, show mit Inhalt+Zeit, none.

### 6.4 Gespeicherter Prompt-Zustand (gegen den Kaltstart)

- Nach dem ersten Laden den Zustand nach dem System-Prompt speichern (`llama_state_seq_get_data`), in die privaten
  App-Dateien. Der Dateiname enthält eine Prüfsumme aus Prompt und Modell; ändert sich eins davon, wird neu
  gerechnet.
- **Größe:**
  - gemessen auf dem Tab für S (1.440 Token): 205 MB
  - auf der GPU: S 100 MB, K 79 MB, A 77 MB
  - für K auf dem Tab geschätzt: ~160 MB
- Wiederherstellen dauerte auf dem Tab 0,16–0,26 s, vermutlich aus dem Seiten-Cache.
- Kalt besteht dann nur noch aus Laden (5–24 s gemessen) + Wiederherstellen + Befehl. Ohne gespeicherten Zustand
  kostet K kalt ~46 s statt ~75 s bei S.

### 6.5 Voraussetzung: Inhaltssuche

`content_contains` setzt voraus, dass die App Text aus PDF- und Office-Dateien lesen kann. Das kann sie heute nicht.
Ohne Textauszug fallen im neuen Holdout 5 Inhalts-Fälle auf eine Namenssuche zurück; nur die mit dem Wort im Namen
bleiben richtig. Der Textauszug ist ein eigener Bauschritt mit eigener Messung (Zeit, Speicher ~2 KB je Dokument,
geschätzt).

## 7. Nicht getestet

- Keine neue Messung auf dem Tab (Akku 10 %, nach Vorgabe). Alle Tab-Zeiten in Phase 0b sind **Schätzungen** aus
  Token-Zahlen.
- Sätze von Belkis selbst als zweiter, unabhängiger Holdout.
- Die zwei nachträglich gefundenen Regelfehler (Abschnitt 4) sind nicht behoben gemessen.
- Kotlin-Port der Regeln. Er braucht eigene Unit-Tests; die Fälle in `rules.selftest` sind die Vorlage.
- PDF-Textauszug, Ergebnisliste als Bildschirm, Gerätespeicher des Zustands auf dem Tab für K.
- Andere Sprachen als Deutsch/Englisch: Ordo hat 7 Sprachen, die Regeln kennen nur Deutsch und Englisch.

## Nachlaufen

```
cd tools/memory-lab
python3 p0b_cases.py && python3 phase0b.py --selftest
./run_p0b.sh A,S,K,KS,T          # pop-os, GPU-Sperre, ~25 min für alle fünf
python3 phase0b.py report         # Wertung inkl. +R offline; STRICT_TOP=1 für die strenge Fassung
```
