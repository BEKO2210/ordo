# Blindtest 2 – Ablation der Regeln (rules2) auf den aufgezeichneten Rohplänen von Prompt C

Kein neuer Modelllauf. Quelle: `blind2/results-0c.json` (Blind 2, 50) und `results-0c.json` (Blind 1, b000–b039, 40).
Skript: `blind2_ablation.py` (eingefrorene Dateien unverändert). Bewertung wie `blind2_run.py`: Kopieren zählt als
ändernd; bei Rückfrage-Gold zählt nur eine Rückfrage als richtig; „Rückfrage/Abl.“ schließt die Rückfrage der App
bei unbekanntem Ordner ein.

**Wichtig: Blind 2 ist ab jetzt ein Entwicklungs-Set.** Diese Analyse hat jeden Fehler auf eine Regel zurückgeführt.
Wer danach Regeln ändert, passt sie an genau diese Sätze an. Das nächste Urteil muss von Sätzen kommen, die hier
niemand gesehen hat, am besten Belkis' eigenen. Blind 1 ist schon länger Entwicklungs-Set: `phase0c.all_sets()`
nimmt die Blind-1-Fälle auf, deshalb sind die 39/40 dort kein unabhängiger Wert.

Nicht abschaltbar in `rules2.decide`, sobald überhaupt Regeln laufen: Gedächtnis-Ablehnung (m), Aktions-Sicherheit
je Satzteil, Dateiarten/Sortierung/Anzahl aus den Wörtern, vage Größen- und Zeitwörter. Mit „b aus“ wäre der
Rückfall auf `rules.time_and_size` aktiv; der ist hier stillgelegt, „b aus“ heißt also wirklich ohne Zeit/Größe.

## (1)+(3) Tabelle Blind 2 (50)

| Regeln | richtig | falsch | Rückfrage/Abl. | gefährlich |
|---|---|---|---|---|
| roh (keine Regeln) | 19 | 21 | 6 | 4 |
| nur Wächter a (Rückfrage-Modus) | 19 | 21 | 9 | 1 |
| nur Zeit/Größe b | 25 | 18 | 7 | 0 |
| nur Ordner d | 22 | 23 | 5 | 0 |
| nur Ausschluss c | 22 | 21 | 6 | 1 |
| nur Kann-nicht-Liste e | 20 | 23 | 6 | 1 |
| Wächter + Ordner a+d | 22 | 21 | 7 | 0 |
| komplett (abcdef) | 29 | 13 | 8 | 0 |

## (4) Tabelle Blind 1 (40, Rohpläne von Prompt C vorhanden)

| Regeln | richtig | falsch | Rückfrage/Abl. | gefährlich |
|---|---|---|---|---|
| roh | 13 | 20 | 4 | 3 |
| nur a | 19 | 15 | 5 | 1 |
| nur b | 23 | 10 | 4 | 3 |
| nur d | 24 | 12 | 0 | 4 |
| nur c | 20 | 13 | 4 | 3 |
| nur e | 18 | 15 | 4 | 3 |
| a+d | 26 | 12 | 1 | 1 |
| komplett | 39 | 1 | 0 | 0 |

Alle 64 Teilmengen von abcdef: Jede Teilmenge mit 0 gefährlichen Plänen in **beiden** Sets enthält a **und** b.
Kleinste ist a+b mit 25/50 und 25/40. a+b+c+d ist so gut wie komplett mit 29/50 und 39/40; e und f bringen auf beiden
Sets nichts dazu. a+b+d kommt auf 27/50 und 37/40.

## (2) Fall für Fall, Blind 2: roh → komplett

- **Roh richtig, final richtig (17):** b2_12, 13, 23, 33–35, 37–46, 49. Davon sind 15 Chat-, Ablehnungs- oder
  Rückfragefälle. Bei den Fällen mit Plan-Gold ist das Modell allein nur 4/32 richtig (b2_12, 13, 23, 28).
- **Roh richtig, Regeln machen es kaputt (2):**
  - b2_28 „lebenslauff“: Der Wächter ersetzt den richtigen Namen des Modells durch das Tippwort des Nutzers.
  - b2_36 „Speicher fast voll“: Die Speicher-Regel macht aus einer Frage eine Liste nach Größe. Das ist harmlos.
- **Roh falsch, Regeln reparieren es (12):** b2_01, 03, 05, 07, 08, 21 (Zeit/Größe), 09, 19 (Ordner), 14, 31
  (Ausschluss), 22 (Sortierung), 48 (Löschen ohne Ziel wird zur Rückfrage).
- **Roh falsch, bleibt falsch (19).** Mit Code behebbar wären 17:
  - Zeit-Lexikon und Zeit-Grammatik: 02 Feiertage, 04 „Anfang des Jahres“, 15 Bereich „1. bis 15.“, 30 „fünften märz“
  - Dateiart-Lexikon: 06 „riesig“, 10 E-Books in Download, 11 RAW→dng, 24 presentations, 27 „tabelen“
  - Gedächtnis-Regex zu breit („den anderen“): 20, 30; Antwort Ablehnung statt Rückfrage: 47
  - VAGUE_T liest „new folder“ als Zeitwort: 26
  - Messenger-Ordner (Elternordner statt WhatsApp Video): 18
  - Getrennt diktierte Wörter („arzt brief“, „bewerbungs fotos“): 29, 32
  - Wächter zu streng: 25 („invoice“ nicht abgedeckt), 17 (Name eindeutig, aber kein Ordner genannt)

  Nur zwei Fälle sind eher Sprachverständnis:
  - b2_16: kein Verb, Ziel „neuer Ordner“, Auswahl nur über das Wort „Autokauf“
  - b2_50: „mach weg“ mehrdeutig; der Code macht daraus eine harmlose Größenliste

## Befund

Die Regeln helfen deutlich: roh 19 → 29 (Blind 2) und 13 → 39 (Blind 1, Entwicklungs-Set). Die gefährlichen
Pläne fallen von 4 bzw. 3 auf 0. Die Obergrenze des Modells allein liegt bei 38 % und 33 %; bei echten Aufgaben mit
Plan schafft es nur 4/32. Prompt C liefert meist nur `{"action": "show"}`, den Inhalt erkennt fast vollständig der Code.
Deshalb liegen die verbleibenden Fehler fast alle in Code-Lexika und Grammatik. Repariert man sie an diesen Sätzen,
verbessert man ein Entwicklungs-Set, keinen Blindtest.
