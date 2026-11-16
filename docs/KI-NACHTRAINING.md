# KI-Befehle: Nachtraining (LoRA) des 4B-Modells

Stand 16.11.2026. Branch `feat/ordo-memory-phase0`, nur Labor (`tools/memory-lab/`), kein App-Code.

## Ergebnis in Kürze

- **Machbar: ja.** QLoRA auf `Qwen/Qwen3.5-4B` passt auf pop-os (RTX 3070, 8 GB). Der Adapter wurde mit dem
  llama.cpp-Stand der App (`3549c274`) umgewandelt und läuft dort mit `--lora` auf **dem Q4_0-GGUF der App**.
- **Eingefroren: run2.** Adapter `ordo-cmd-lora` (f16):
  - Pfad: pop-os `~/ordo-lora/out/FROZEN.gguf` (= `run2.gguf`)
  - Größe: 42 485 408 Byte
  - SHA-256 `30ac6052cf23a0052262f52c44dbd860533b164db33c599a76a674a2f84ede64`
  - Nicht im Git.
- Dazu gehören:
  - Prompt `rules3.SYSTEM_LORA` (75 Token)
  - Grammatik `rules3.grammar()`
  - Netz `rules3.decide(..., flags="abcd")`
- **Das Modell selbst versteht jetzt viel mehr.** Auf Blind 2 kommt es allein auf 37/50, vorher 15–20/50. Mit Netz
  sind es 35/50 bei **0 gefährlichen** Plänen, C+R2 kam auf 29/50.
- **Alle Zahlen sind Dev-Zahlen, kein Urteil.** Das Urteil kommt aus Blindtest 3, den ein anderer Agent schreibt.
  Meine Erwartung dort: 65–75 % richtig, 0 gefährlich. **Das 80-%-Ziel wird eher verfehlt.**
- run3 (Inhaltsfragen, zwei Themenwörter) war auf Dev nicht besser als run2 und auf den heutigen Befehlen einen
  Fall schlechter. Allein hatte run3 6 gefährliche Pläne statt 2. Darum ist run2 eingefroren.

## 1. Was trainiert wurde

- **Grundmodell:** `Qwen/Qwen3.5-4B` (Hugging Face, Revision `851bf6e8`, Apache-2.0). Das ist genau das Modell hinter
  dem App-GGUF: Die GGUF-Metadaten von `unsloth/Qwen3.5-4B-GGUF` Q4_0 (SHA-256 `298fcb5f…`, gleich wie in
  `CommandModel.kt`) nennen `general.base_model = Qwen/Qwen3.5-4B`, Architektur `qwen35`, 32 Blöcke, Vokabular
  248 320 Token. Die Chat-Vorlage weicht bei Unsloth leicht ab (7 816 statt 7 756 Zeichen). Das ist egal: Labor und
  App bauen den Prompt-Text selbst (`<|im_start|>…<think>\n\n</think>\n\n`).
- **Verfahren:** QLoRA. Das Grundmodell wird beim Training in 4 Bit (NF4) geladen, Rang 16, Alpha 32, Dropout 0,05.
  Gelernt wird nur auf der Antwort (JSON + `<|im_end|>`), nicht auf dem Prompt.
- **Welche Schichten:** MLP (gate/up/down) in allen 32 Blöcken und q/k/v/o in den 8 Blöcken mit voller Attention.
  Die Linear-Attention-Projektionen bleiben aus. Grund: Der Konverter von llama.cpp ordnet deren Zeilen beim
  Umwandeln um, ein Adapter darauf würde nicht korrekt übertragen. Der MTP-Kopf bleibt unberührt.
  21,2 Mio. trainierbare Parameter (0,5 %).
- **Gemessen wird mit der App-Basis:** Der Adapter wird auf das **Q4_0-GGUF der App** gelegt, nicht auf das
  fp16-Modell. Server: `llama-server`, selbst gebaut aus **genau dem llama.cpp-Stand der App** (`3549c274`), CUDA,
  greedy, mit der Grammatik unten.
- **Machbarkeit (Schritt 1) belegt:** Adapter → `convert_lora_to_gguf.py` (gleicher Stand) → 256 Tensoren →
  `llama-server --lora` lädt ihn auf Q4_0 und die Ausgabe ändert sich. Ein Zufalls-Probeadapter
  änderte „Hallo! Wie kann ich dir heute helfen?“ zu „Hallo! Wie geht's dir?“.

## 2. Entscheidung: Das Modell schreibt den ganzen Plan, der Code ist nur noch Netz

Blindtest 2 hat gezeigt: Die Regeln von 0c (`rules2.py`) lesen fast alles selbst aus dem Satz. Ihre Lücken im
Wortschatz (Feiertage, „E-Books“, „RAW“, „Präsentationen“, Tippfehler) kann das Modell nicht schließen, weil die
Regeln seinen Plan überschreiben. Ein Adapter für das alte 6-Feld-Schema hätte darum wenig gebracht.

Darum gilt jetzt **Schema L** (`rules3.py`): Das Modell schreibt nur die Felder, die der Befehl nennt, in fester
Reihenfolge: `action` (show, move, copy, delete, organize, none, **ask**), `source`, `destination`, `kinds`,
`extensions`, `name_contains`, `content_contains`, `exclude`, `time`, `size`, `sort`, `limit`, `group_by`.

- **Zeit und Größe (Mechanismus b):** Das Modell rechnet keine Daten aus. Es schreibt die Bedingung in kurzer,
  normierter Form, z. B. „Anfang des Jahres“ → `"time":"im Januar"`, „vor zwei Wochen und älter“ →
  `"älter als 2 Wochen"`. Die erprobte Zeit-Grammatik aus `rules2` liest diese Form. Die Ausgabe bleibt kurz:
  Median 16 Token.
- **Netz a+b+c+d** (`rules3.decide`, Flags `abcd`): Es füllt nur Lücken und legt ein Veto ein. Was das Modell
  geschrieben hat, überschreibt es nie.
  - a: Bei Änderungen muss jedes Inhaltswort in einem Filterfeld landen, sonst fragt Ordo nach.
  - b: Die Zeit-/Größenangabe des Modells liest der Code. Fehlt sie, liest er den Satz. Bei einer Änderung mit
    unlesbarer oder vager Angabe fragt Ordo nach.
  - c: Fehlt der Ausschluss im Plan, liest der Code ihn aus dem Satz.
  - d: Ordner werden gegen den echten Baum geprüft. Gibt es bei einer Änderung keinen Ordner, mehrere oder kein Ziel,
    fragt Ordo nach.
  - Immer aktiv wie in 0c: Gedächtnis-Absage und Aktions-Sicherheit je Satzteil. Der Code erzeugt nie selbst ein
    Löschen, und eine Frage bleibt eine Anzeige.
- **Prompt:** Mit Adapter reicht ein kurzer Prompt (`rules3.SYSTEM_LORA`, 75 Token statt 464 bei C). Das
  macht das Training etwa 4× schneller und den Kaltstart auf dem Tab kürzer.
- **Grammatik:** `rules3.grammar()` (GBNF): feste Reihenfolge, jedes Feld außer `action` optional, keine leeren
  Werte.

**Netz-Korrekturen, offen gelegt:** Beim Bau sah ich die Fehler des Netzes auf den **Trainingsdaten** an. Daraus
entstanden drei allgemeine Korrekturen: Füllwörter („now“, „rüber“, „Unterordner“), „neuen Ordner“ ist kein vages
Zeitwort, und „aufräumen in Unterordner“ wird kein Verschieben. Dazu kam eine Korrektur an einem **Dev-Fall**
(h023, der einzige gefährliche Fall der alten Rohpläne unter `rules3`): Ein Ordnername, der nur Objekt ist, zählt
wieder als Wort. Diesen Mechanismus hatte `rules2` schon. Keine Regel hängt an einem Testsatz.

## 3. Trainingsdaten

Bau: `lora_data.py` (Handys + Pläne), `lora_words.py` (Wortlaut), `lora_build.py` (Prüfung, Leck-Prüfung,
Aufteilung). Zahlen des eingefrorenen Laufs: `tools/memory-lab/lora/build_run2.md` (run3: `build.md`).

- **Synthetische Handys:** 120 eigene Handys plus 40 für die Aufräum-Pläne (Seeds 101, 202, 303, 404), **nicht**
  das Laborhandy. Sie haben andere Ordner (z. B. Verträge, Kita, Hund, Künstlerordner unter Music), andere
  Dateinamen (Marken, Personen, Orte, Themen, Ereignisse), Datum 2023 bis heute, Textinhalt in einem Teil der PDFs.
- **Pläne programmatisch gezogen** aus Schema L: Aktion, Ordner, Ziel (vorhanden oder neu), Dateiart (19
  Arten von „Fotos“ bis „E-Books“, „Sprachnachrichten“, „Präsentationen“), Name, Inhalt, Zeit (37 Formen), Größe,
  Sortierung/Anzahl, Ausschluss, Gruppierung. Jeder Plan trifft auf seinem Handy mindestens eine Datei. Ein
  Änderungsplan trifft höchstens 150.
- **Wortlaut von stärkeren LLMs:** Sie sehen nur Fakten, z. B. „Aktion: löschen; Ordner: Screenshots; Zeit:
  vorgestern“. Sie sehen nie das Ziel-JSON und nie einen Testsatz. Je Batch eine andere Persona: diktierende ältere
  Person, tippender Teenager, knapp, höflich-indirekt, Deutsch als Zweitsprache, leichter Dialekt, gestresst,
  Problem statt Befehl, dazu 4 englische Personas. Generatoren: codex 3 116 Sätze, Cursor Composer 2.5 1 099,
  Composer 2.5 fast 742, Grok 4.6 609, Grok 4.7 495.
- **Negative Klassen und Beinahe-Paare:**
  - Chat → `none`.
  - Kann-Ordo-nicht (18 + 4 Themen) → `none`.
  - Unklar → `ask`.
  - Löschen/Verschieben als bloße Frage („Kann ich … löschen?“) → `show` mit denselben Filtern (270 Sätze).
  - Lauf 2 dazu: Speicher-Fragen („was kann weg“ → `show` nach Größe, „wie viel frei“ → `none`) und Doppelte → `show`.
  - Die Unterschiede innerhalb eines Paars (Name da/weg, „außer“, „nur“, Zeitwort) entstehen aus den gezogenen
    Plänen.
- **Mechanische Prüfung jedes Satzes:**
  - Das Ziel muss als Schema L gültig sein (gleiche Regeln wie die Grammatik).
  - Mit `rules3` ohne Netz ausgeführt, muss das Ziel auf seinem Handy **genau** die Gold-Dateien treffen.
  - Der Satz muss jede Tatsache tragen: Name unscharf, Ordner, Ziel, Zeit (liest der Code eine Zeit, muss es dieselbe
    sein), Größe, Anzahl, Ausschluss.
  - Eine Frage darf kein Änderungsverb enthalten.
- **Leck-Prüfung:** gegen alle 284 vorhandenen Fallsätze (p0 inkl. der 20 Originale, p0b-Holdout + heutige 27 +
  persönliche, Blind 1, Blind 2, Laborsätze). Maß: max(Wort-Jaccard, Zeichen-Ähnlichkeit) nach Faltung. **Ab 0,70
  verworfen: 127 Sätze**, z. B. „zeig mir mal die größten videos“ (~ „zeig mir die 5 größten videos“, 0,90) oder
  „Wie spät ist es.“ (1,00). Der nächste behaltene Satz liegt unter 0,70. Liste in `lora/build_run2.md`.
- **Ergebnis (Lauf 2):** 6 530 Sätze → **6 061 behalten**. Training 5 464 + 374 Wiederholungen der kleinen
  Klassen = 5 838, Validierung 597 (10 %). Getrennt wird nach Plan, Umformulierungen landen also nicht auf beiden
  Seiten. Etwa 14 % Englisch.
- **Klassen:** show 2 067, organize 810, delete 661, move 537, Ablehnung 589, chat 384, copy 275, Frage→show 270,
  ask 216, Speicher→show 129, Doppelte 123.
- **Verworfen:** nahe an Testsatz 127, Zeit falsch 105, doppelt 52, Ausschluss fehlt 37, Zeit fehlt 34, Ordner fehlt
  30, Ziel trifft Gold nicht 21, sonst 82.
- **Kostenlose OmniRoute-Anbieter:** Gemini, OVH, Kilo und Mistral lieferten 0 Sätze (503/429/leere Antworten).

**Wie viel das Modell ausgibt:** Nur die genannten Felder, Zeit und Größe als kurze Normform (Mechanismus b liest
sie). Ausgabe im Median 17 Token, höchstens 33 (Lauf 1).

## 4. Training

Skript `lora_train.py` auf pop-os (RTX 3070, 8 GB). Beide Sperren (`build.lock`, dann `gpu.lock`) waren während
jedes Laufs gehalten.

- Software: torch 2.11 (cu128), transformers 5.23, peft 0.21, bitsandbytes.
- Einstellungen: Mikro-Batch 4 mit Linksauffüllung, Gradienten-Akkumulation 4 (effektiv 16), AdamW, LR 2e-4,
  Kosinus mit Aufwärmen, 1 Epoche, Gradient Checkpointing.
- Logits nur für das Antwortende: Bei 248k Vokabular passen volle Logits nicht in 8 GB. Der erste Probelauf ist
  genau daran gescheitert (Out of Memory).
- Tempo: ~0,26 s je Beispiel, weil der Prompt kurz ist. Mit dem langen Prompt waren es ~1 s, damit wäre das Training
  nicht in 40 Minuten fertig geworden.

| Lauf | Daten | Schritte | Zeit | Val-Loss vorher → 100 → 200 → Ende | interne Validierung (exakt / Aktion) |
|---|---|---|---|---|---|
| run1 | 4 666 Training / 490 Val | 291 | 20,7 min | 2,054 → 0,053 → 0,026 → **0,024** | 439/490 / 481/490 |
| run2 | 5 838 / 597 | 365 | 26,1 min | 2,060 → 0,039 → 0,026 → **0,017** | 563/597 / 589/597 |
| run3 | 6 363 / 620 (+ Inhaltsfragen, zwei Themenwörter) | 388 | 27,7 min | 2,128 → 0,044 → 0,025 → **0,015** | 584/620 / 613/620 |

- Die Logs liegen auf pop-os unter `~/ordo-lora/out/runN.log.jsonl`: Train-Loss alle 10 Schritte, Val-Loss alle 100.
- Kein Zeichen von Overfitting: Der Val-Loss fällt bis zum Ende.
- Die Validierung ist dieselbe Verteilung wie das Training. Darum ist der Wert eine Obergrenze, kein Urteil.
- Reproduzierbar:
  - run2 = `lora_build.py` über die ersten 160 Zeilen von `lora/words.jsonl`, ohne `specs_505/606`. Ergibt
    md5 `738f2123…`, nachgeprüft.
  - run3 = alle 177 Zeilen.

## 5. Ergebnisse (alles Dev-Sätze, KEIN Urteil)

Gleiche Wertung für alle Zeilen: `eval3.py` mit der Bewertung von `blind2_run.score`.
- Kopieren zählt als Änderung.
- Bei Rückfrage-Gold zählt nur eine Rückfrage.
- Bei Ablehnungs-Gold zählt kein Plan als richtig.

Zu den Zeilen:
- „Modell allein“ = nur der Plan des Modells. Ordner liest die App gegen den Baum, die Zeit-/Größenangabe liest der
  Code, sonst nichts.
- „oldC“ = die eingefrorenen Rohpläne von Prompt C (ohne Adapter), geführt durch das neue Netz.
- „base“ = Q4_0 ohne Adapter mit dem langen Prompt L (`rules3.SYSTEM`).
- Spalte „gefährlich“: über alle 274 Dev-Sätze, inklusive persönlicher und Sitzungsfälle.

| Lauf | Regeln | Blind 1 (40) | Blind 2 (50) | p0b-Holdout (41) | 20 Originale | heutige (27) | p0 Einzel (51) | gefährlich | Rückfrage/Abl. |
|---|---|---|---|---|---|---|---|---|---|
| oldC | Modell allein | 13/40 | 20/50 | 19/41 | 3/20 | 10/27 | 9/51 | 26 | 60 |
| oldC | + Netz a-d | 28/40 | 31/50 | 37/41 | 10/20 | 22/27 | 27/51 | 0 | 87 |
| base | Modell allein | 17/40 | 15/50 | 25/41 | 2/20 | 17/27 | 6/51 | 12 | 121 |
| base | + Netz a-d | 18/40 | 20/50 | 26/41 | 2/20 | 17/27 | 7/51 | 0 | 158 |
| run1 | Modell allein | 31/40 | 37/50 | 37/41 | 7/20 | 26/27 | 26/51 | 9 | 58 |
| run1 | + Netz a-d | 35/40 | 36/50 | 38/41 | 9/20 | 26/27 | 29/51 | 0 | 95 |
| run2 | Modell allein | 32/40 | 37/50 | 39/41 | 9/20 | 26/27 | 29/51 | 2 | 47 |
| run2 | + Netz a-d | 34/40 | 35/50 | 39/41 | 11/20 | 26/27 | 33/51 | 0 | 84 |
| run3 | Modell allein | 31/40 | 37/50 | 38/41 | 9/20 | 25/27 | 29/51 | 6 | 52 |
| run3 | + Netz a-d | 34/40 | 35/50 | 39/41 | 11/20 | 25/27 | 33/51 | 0 | 83 |

Zum Vergleich die eingefrorene C+R2 (0c), gezählt mit ihren eigenen Skripten: Blind 1 39/40 (dort Dev),
**Blind 2 29/50**, p0b 39/41, 20 Originale 13/20, heutige 26/27, p0 Einzel 38/51, 0 gefährlich.

- **Heutige 27:** run2 + Netz 26/27, gleich wie C+R2. Keine Verschlechterung.
- **20 Originale und p0 Einzel bleiben unter C+R2.** Viele dieser Sätze brauchen Gedächtnis oder Wissen über
  Ordnerinhalte („zu den anderen Rechnungen“, „Arbeitsdokumente“). Dort hatte `rules2` Wortregeln aus genau diesen
  Sätzen.
- **Das Netz rettet Gefahr, kostet aber Treffer:** run2 allein hat 2 gefährliche Pläne, mit Netz 0. Auf Blind 2
  fallen 2 richtige Pläne weg:
  - b2_20 „zu den anderen Rechnungen“: Gedächtnis-Absage.
  - b2_47: Das Modell fragte zurück, das Netz lehnt mit dem Gedächtnis-Satz ab.
- Restfehler run2 + Netz auf Blind 2 (Liste in `lora/results.md`):
  - erfundene Ordner aus Themenwörtern („Küche“, „Autokauf“, „Tickets“),
  - Umgangssprache in der Zeit („Anfang des Jahres“, „über die Feiertage“),
  - RAW → `raw` statt `dng`,
  - „in welcher Datei steht …“ → none,
  - Gedächtnis-Sätze.

## 6. Kosten

- Adapter-GGUF (f16): **42 485 408 Byte (40,5 MiB)**, 256 Tensoren, Rang 16. Dazu kommt der Modell-Download
  (Q4_0, ~2,6 GB).
- Training: ~21–27 min GPU je Lauf.
- Daten:
  - Wortlaut ~40 min über Abos (codex, Cursor), 0 € extra.
  - Prüfung und Bau: Sekunden.
- Auf dem Tab (geschätzt aus Token, 24 t/s lesen, 9 t/s schreiben; Last des Adapters nicht gemessen):

| | Prompt | Befehl (Median) | Ausgabe (Median/Max) | warm | kalt mit Zustand | kalt ohne |
|---|---|---|---|---|---|---|
| C (0c, ohne Adapter) | 464 | 19 | 23 / 33 | ~3,4 s | ~8,6 s | ~28 s |
| **L + Adapter** | **75** | 19 | **16 / 33** | **~2,6 s** | **~7,8 s** | **~10,7 s** |

LoRA kostet auf dem Gerät pro Token zusätzliche Rechenzeit: 21 Mio. Parameter neben 4 Mrd., also grob +1–3 %.
Gemessen ist das auf dem Tab nicht.

## 7. So würde die App den Adapter laden (llama.cpp `3549c274`)

```c
llama_model * model = llama_model_load_from_file("Qwen3.5-4B-Q4_0.gguf", mparams);   // wie heute
llama_adapter_lora * ad = llama_adapter_lora_init(model, "ordo-cmd-lora.gguf");       // einmal nach dem Laden
llama_context * ctx = llama_init_from_model(model, cparams);
float scale = 1.0f;
llama_set_adapters_lora(ctx, &ad, 1, &scale);                                          // vor dem ersten Decode
```

- In der JNI-Schicht (`llm/src/main/cpp`) sind das zwei Aufrufe mehr. `llama_adapter_lora_free` ist optional, das
  Modell gibt den Adapter mit frei.
- Der gespeicherte Prompt-Zustand (KV-/Rekurrenz-Zustand) muss **mit** Adapter erzeugt werden. Ein alter Zustand ohne
  Adapter passt nicht mehr: Version im Dateinamen erhöhen.
- Der Download braucht eine zweite Datei mit eigener SHA-256-Prüfung, wie beim Modell.
- Prompt `rules3.SYSTEM_LORA`, Grammatik `rules3.grammar()`, danach `rules3.decide(..., flags="abcd")` als Netz.
  Das ist Laborcode in Python und muss für die App nach Kotlin übertragen werden. Die Mechanismen aus `rules2` sind
  dafür schon spezifiziert.

## 8. Lizenzen

- Qwen3.5-4B: Apache-2.0 (Hugging Face `Qwen/Qwen3.5-4B`, LICENSE-Datei). Ein Adapter ist ein abgeleitetes Werk und
  darf mit Hinweis auf die Lizenz verteilt werden.
- Unsloth-GGUF: Apache-2.0 laut Metadaten.
- llama.cpp: MIT.
- Trainingssätze: geschrieben von codex (OpenAI), Cursor Composer 2.5 und Grok 4.6/4.7 (über Cursor). Inhalt sind
  rein synthetische Befehle ohne personenbezogene Daten. Für die kostenlosen OmniRoute-Anbieter gab es keine
  Antwort (503/429), sie lieferten 0 Sätze.
  **Offen für Belkis:** ob die Nutzungsbedingungen dieser Anbieter das Training eines verteilten Modells mit ihren
  Ausgaben erlauben. Bei OpenAI-Ausgaben gibt es Klauseln gegen konkurrierende Modelle. Ein enger
  Dateimanager-Parser ist das vermutlich nicht. Geprüft habe ich das nicht.

## 9. Nicht getestet / Risiken

- **Das Urteil fehlt noch.** Alle Zahlen oben stammen aus Dev-Sätzen. Folgendes wurde **mit Blick auf Dev-Fehler**
  entschieden:
  - die Netz-Korrekturen in Abschnitt 2,
  - die neuen Klassen in run2 (Speicher, Doppelte, öffnen) und run3 (Inhalt, zwei Themenwörter),
  - die Wahl des eingefrorenen Laufs.
  Die Dev-Zahlen sind darum zu optimistisch.
- **Erwartung Blindtest 3:** Modell + Netz eher 65–75 % richtig und 0 gefährlich. Das 80-%-Ziel ist **eher nicht**
  erreicht.
  - Begründung: Blind 2 kam mit Netz auf 35/50 (70 %), Blind 1 auf 34/40 (85 %).
  - Schwach bleiben: Ordner, die das Modell aus Themenwörtern erfindet; umgangssprachliche Zeiten („Anfang des
    Jahres“, „über die Feiertage“); Dateiarten ohne Lexikon (RAW); Gedächtnis-Fälle.
- **Nicht getestet:**
  - der Adapter auf dem Tab (Tempo, RAM),
  - auf dem Gerät allgemein: Der Adapter lief nur auf der CUDA-GPU von pop-os, nicht mit dem CPU-/ARM-Backend der App.
    Ob die Q4_0-Ausgabe auf ARM gleich bleibt, ist offen.
  - der gespeicherte Prompt-Zustand mit Adapter,
  - `rules3` in Kotlin (gibt es noch nicht).
- **Zeit- und Datumsform:** Das Modell schreibt eine Normform. Was die Zeit-Grammatik nicht liest, wird bei einer
  Änderung zur Rückfrage und bei einer Suche ignoriert. Bei einer Suche kann das eine zu große Liste geben, die nicht
  gefährlich ist.
- **Daten-Schieflage:** Alle Sätze stammen von LLMs. Echte Nutzersätze (Belkis) fehlen ganz. Codex stellt die Hälfte
  der Sätze.
- **Lizenz der Generator-Ausgaben** (Abschnitt 8) ist nicht geprüft.

## 10. Den eingefrorenen Stand auf neue Sätze anwenden (Blindtest 3)

Auf pop-os, im Ordner `~/ordo-lora/lab` (Kopie von `tools/memory-lab`):

1. Die Fallliste als Python-Modul in diesen Ordner legen. Format wie `blind2_cases.py`: `cases()` und optional
   `EXTRA`-Dateien.
2. Ausführen:

```bash
cd ~/ordo-lora/lab && ./run_frozen.sh blind3_cases blind3
```

- Der Lauf hält `build.lock` und `gpu.lock` nur für sich selbst.
- Er nutzt das Q4_0-GGUF der App, den Adapter `~/ordo-lora/out/FROZEN.gguf`, `SYSTEM_LORA`, die Grammatik und
  greedy.
- Er schreibt `lora/raw_blind3.json` und `lora/results_ext.md`, dort mit „Modell allein“ und „+ Netz a-d“.
- Gezählt wird wie bei `blind2_run.score`.

## 11. Nachtraining Nr. 2: sauberer Datensatz (Lauf 4)

Stand 16.11.2026. Ziel: ein Adapter, dessen Trainingssätze **nur** von lokal laufenden Modellen mit freier Lizenz und von
eigenem Code stammen. Damit wäre er ohne die offene Anbieter-Frage aus Abschnitt 8 auslieferbar. Dazu kommen die
schwachen Klassen aus Abschnitt 9 und die Fehlerschwerpunkte aus Blindtest 3. Die habe ich **nur als Kategorien**
übernommen: Änderungen an einer benannten Teilmenge und Befehle mit mehreren Themenwörtern. Blind-3-Fälle, Rohpläne
und Fehlerlisten habe ich nicht angesehen.

### 11.1 Herkunft der Daten und Lizenzen

- **Kein Satz aus `lora/words.jsonl`** (codex, Cursor, Grok) ist übernommen. Kein Cloud- oder API-Modell wurde aufgerufen.
- Übernommen ist nur eigener Code: Handys, Plan-Ziehung, Prüfung (`lora_data.py`, `lora_build.py`).
- **Neue Pläne** mit eigenen Seeds liegen in `lora2/specs_*.jsonl`:
  - 111: Mischung
  - 222: Inhaltsfragen
  - 333: zwei Themenwörter
  - 444: Aufräumen
  - 555–557: Löschen, Verschieben und Kopieren mit zwei Themenwörtern
  - 565–566: Löschen und Verschieben; davon nur die Pläne mit Namensfilter
- **Wortlaut-Generatoren**, alle lokal auf pop-os (RTX 3070) über `llama-server`, ohne Denkmodus:

| Modell | Familie / Hersteller | Lizenz | Rohsätze | behalten (Lauf 4) | + Rauschvarianten |
|---|---|---|---|---|---|
| `qwen3.5:9b` (Ollama-Blob `02d45dc1…`, Q4_K_M), 4 Slots | Qwen (Alibaba) | Apache-2.0 | 2 546 | 2 140 | 529 |
| `granite4.2:8b` (Ollama-Blob `16a9369d…`), 4 Slots | Granite (IBM) | Apache-2.0 | 1 546 | 1 199 | 304 |
| `Ministral-3-14B-Instruct-2512` UD-IQ3_XXS (GGUF), 2 Slots | Mistral | Apache-2.0 | 1 064 | 824 | 199 |
| eigene Schablonen (`lora_templates.py`) | – | eigener Code | 949 | 892 | 198 |

  Tempo auf der 3070: qwen3.5-9b ≈ 13 Jobs/min (je Job 8 Pläne × 3 Sätze, ≈ 15 000 Sätze/h), granite ähnlich,
  Ministral ≈ 3,5 Jobs/min. Gelaufen ist das in Stücken von höchstens 10 Minuten, nur mit `gpu.lock`, nur bei ≥ 10 GB
  freiem RAM, mit RAM-Wächter (`gen_local.sh`, `gen_loop.sh`), weil parallel Release- und Emulator-Läufe liefen.

- **Lizenzprüfung an der Quelle:**
  - Lizenzdatei im Ollama-Manifest (qwen3.5, granite4.2, gemma4: jeweils der Apache-2.0-Text)
  - `general.license` in den GGUF-Metadaten
  - Hugging-Face-API (`cardData.license`) für `Qwen/Qwen3.5-9B`, `ibm-granite/granite-4.2-8b`,
    `mistralai/Ministral-3-14B-Instruct-2512`, `google/gemma-4-26B-A4B-it`

  Apache-2.0 setzt der Nutzung der Ausgaben keine Grenzen, also auch nicht dem Training damit.
- **Programmatisch (eigener Code, keine Fremdausgabe):**
  - `lora_templates.py`: ein Schablonensatz je Plan der schwachen Klassen (Inhaltsfrage, benannte Änderung,
    umgangssprachliche Zeit), nur Deutsch.
  - `lora_build.py noisy()`: Rauschen auf rund 40 % der behaltenen Sätze, danach **erneut voll geprüft**. Arten:
    - Kleinschreibung
    - Umlaute weg (ä→a oder ä→ae)
    - Satzzeichen weg oder diktiert („komma“, „punkt“)
    - Tippfehler (Buchstaben vertauscht, fehlend oder doppelt)
    - Füllwörter („mal“, „äh“, „halt“; englisch „um“, „pls“)
    - Wörter getrennt oder zusammengezogen
- **Personas:** 22 deutsche und 8 englische. Dazu gehören Dialekte (Bayern, Berlin, Köln/Ruhr, Norddeutschland, Schwaben),
  Senioren, Kinder, Deutsch als Zweitsprache (türkisch, russisch), Denglisch, Diktat mit gesprochenen Satzzeichen,
  einhändiges Tippen und ein Problem statt eines Befehls. Die Temperatur wechselt je Anfrage (0,7–1,1).
- **Nicht genutzt:**
  - gemma4:12b: passt mit Kontext nicht in 8 GB VRAM.
  - Gemma-4-26B-A4B: legt Experten in den RAM. Der RAM war wegen der Release- und Emulator-Läufe gesperrt.
  - Qwen3.8-27B: zu langsam.
  - Ein CPU-Versuch mit granite-4.2-3b auf belkis-home: 2,5 Token/s, abgebrochen, 0 Sätze.

### 11.2 Prüfung und Leck-Check

- Jeder Satz läuft durch dieselbe mechanische Prüfung wie bei Lauf 2: Schema L gültig, Gold-Dateien auf seinem Handy
  exakt getroffen, alle Fakten im Satz, keine Änderungsverben in Fragen. Das gilt auch für jede Rauschvariante.
- **Leck-Check** gegen alle 284 Dev-Testsätze: p0 inkl. der 20 Originale, p0b inkl. der heutigen 27, Blind 1, Blind 2
  und die Laborsätze. Schwelle wie bisher 0,70.
- **Blind 3** ist zusätzlich drin, aber **nur gezählt**: `lora_build.blind3_texts()` liest nur das Feld `text`. Nichts
  davon wird ausgegeben oder geschrieben, `build.md` nennt nur die Anzahl.
- Gebremst wurde die Erzeugung von Sperre und RAM, nicht vom Tempo. 170 der 384 Wortlaut-Jobs (gemischte Pläne) blieben
  ohne Wortlaut.

### 11.3 Lauf 4 (Daten `lora2/`) – durchgefallen

- **Daten:** 6 285 Sätze behalten. Das Training ist auf 4 800 gekappt (Zufallsauswahl), damit das Sperrstück unter
  25 min bleibt; dazu 651 zur Validierung.
- **Mechanische Prüfung:** LLM-Sätze 81 % bestanden (4 163 von 5 156), Schablonen 94 %.
- **Verworfen:**
  - 89 nahe an Dev-Testsätzen
  - 14 nahe an Blind 3 (nur gezählt)
  - 935 doppelt
- **Training:** Rezept wie run2 (QLoRA r16, LR 2e-4, 1 Epoche).
  - Bei Schritt 297 von 300 griff der Zeitdeckel von 22 min.
  - Val-Loss: 1,699 → 0,082 (100) → 0,059 (200) → 0,053.
  - Diese Werte sind nicht mit run2 vergleichbar: Die Validierung ist eine andere, verrauschte Verteilung.
- **Adapter:** pop-os `~/ordo-lora/out/run4.gguf`, 42 485 408 Byte, SHA-256 `9186a6bc9374f2e2…`.
- **Dev-Ergebnis:** `lora2/results_run4.md`. Zwei harte Bedingungen sind verletzt: Rückschritt bei den heutigen
  Befehlen und 1 gefährlicher Plan mit Netz. **Nicht eingefroren.**
- **Ursachen**, gefunden an Dev-Rohplänen (nicht an Blind 3):
  1. **Datenfehler.** Der Plan-Rückfall „Änderung ohne Filter → Art der Ankerdatei“ setzt `kinds`, ohne dass der
     Satz eine Dateiart nennt. Den gab es schon in Lauf 2, aber dort schrieben die stärkeren Modelle die Art meist mit
     hin. Folge: Bei vagen Änderungen hängt das Modell „document“ an, und „Räum Download auf“ oder „Leg … ab“ werden zum
     Löschen.
  2. **Zu wenige Negativklassen:** Ablehnung 314, Chat 85, Rückfrage 190; Lauf 2 hatte 589/384/216. Folge:
     „umbenennen“ wird zu move, „öffnen“ zu show.

### 11.4 Lauf 5 (Daten `lora3/`) – durchgefallen

**Was sich gegenüber Lauf 4 ändert** (`LORA_STRICT=1`, gleicher Wortlaut ohne neue Fremdsätze):

- Neue Prüfung „Dateiart muss im Satz stehen“: 264 Sätze verworfen.
- Ablehnung, Chat und Rückfrage im Training doppelt (Überabtastung).
- Eigene Schablonen für 240 Ablehnungen (umbenennen, öffnen, teilen, zippen, drucken, …) und 120 Rückfragen
  (Änderung ohne Objekt oder Ziel).
- Prompt, Grammatik und Netz sind **unverändert** (`rules3` wie 13bfbed9).

**Daten:**
- 6 335 Sätze behalten, Training 4 800 (gekappt), Validierung 672.
- Klassen im Training: show 1 182, move 831, delete 807, Ablehnung 721, Rückfrage 349, organize 333, copy 250,
  chat 115, Frage→show 113, Rest 99.

**Training:**
- 300 Schritte, 23,7 min.
- Val-Loss 0,090 (100) → 0,066 (200) → 0,061.

**Adapter:** pop-os `~/ordo-lora/out/run5.gguf`, 42 485 408 Byte, SHA-256 `2653db00138e9aca…`.

**Dev-Ergebnis** (`lora3/results_run5.md`, Wertung `eval3.py` wie bisher):

| Lauf | Regeln | Blind 1 (40) | Blind 2 (50) | p0b (41) | 20 Originale | heutige (27) | p0 Einzel (51) | gefährlich |
|---|---|---|---|---|---|---|---|---|
| run2 (eingefroren, Fremd-Wortlaut) | + Netz a-d | 34 | 35 | 39 | 11 | 26 | 33 | 0 |
| run4 (lokal) | Modell allein | 27 | 34 | 34 | 9 | 24 | 31 | 16 |
| run4 (lokal) | + Netz a-d | 30 | 34 | 38 | 11 | 25 | 34 | **1** |
| run5 (lokal) | Modell allein | 30 | 35 | 38 | 9 | 24 | 31 | 11 |
| run5 (lokal) | + Netz a-d | 33 | 35 | 38 | 11 | 24 | 34 | **3** |

**Ergebnis:** Lauf 5 behebt die Fehler aus Lauf 4. „umbenennen“ und „schicken an Mama“ werden zu `none`, vage
Änderungen nicht mehr zu einem Löschen von Dokumenten. Dafür kommt ein neuer, gefährlicher Fehler hinzu:

- Bei Löschen und Verschieben macht das Modell aus „größer als 1 GB“ / „über 3 GB“ ein `"size":"unter …"`
  (n021, rc05, rc12).
- Das Netz liest die Größe des Modells und prüft sie nicht gegen den Satz. Darum bleibt der Fehler gefährlich.
- **Ursache:** zu wenige Größen-Beispiele. Nur 114 von 4 800 Trainingszeilen haben eine Größe. Davon widersprechen
  6 dem Satz, 5 davon von granite.
- Die Prüfung prüfte bisher nur, ob Zahl und Einheit vorkommen, nicht die Richtung. Für einen Lauf 6 liegt sie bereit:
  `LORA_STRICT=2` → „size differs“, 15 Sätze.

### 11.5 Urteil und nächster Schritt

- **Nichts eingefroren.** Beide sauberen Läufe verletzen die harten Bedingungen: 0 gefährlich mit Netz und kein
  Rückschritt bei den heutigen Befehlen. Damit gibt es keinen Kandidaten für Blindtest 4.
- `run_frozen2.sh` liegt bereit: Adapter als 3. Argument, Standard `~/ordo-lora/out/FROZEN2.gguf`. Die Datei gibt es
  noch nicht.
- **Ehrliche Einschätzung:**
  - Lokale 8–14B-Modelle liefern brauchbaren, aber weniger treuen Wortlaut als codex/Cursor/Grok.
  - Die mechanische Prüfung muss deshalb strenger sein. Zwei Lücken haben erst die Läufe gezeigt: Dateiart und
    Größenrichtung.
  - Die Daten werden knapper. Bei 4 800 Zeilen haben seltene Klassen (Größe, Chat) zu wenige Beispiele.
- **Vorschlag Lauf 6** (braucht ein OK, weil höchstens 2 Läufe erlaubt waren):
  - `LORA_STRICT=2`
  - Größen-Pläne gezielt nachziehen: Seeds mit `size`, Schablonen „größer/kleiner als“.
  - Die 170 offenen Wortlaut-Jobs erzeugen, sobald RAM frei ist (≈ 15 min mit qwen/granite).
  - Chat aus den LLM-Jobs verdoppeln.
  - Training auf ~6 000 Zeilen in zwei gesperrten Stücken oder mit längerem Sperrfenster.
  - Erwartung: Dev etwa auf run2-Niveau. Ob 0 gefährlich hält, zeigt erst der Lauf.

### 11.6 Skripte (neu)

- `lora_words.py jobs4`: Jobs aus `$LORA_DIR/specs_*`, 22 + 8 Personas, Hinweise für Umgangszeit, Inhaltsfragen und
  Themenwörter ohne Ordner. Backend `local:PORT:NAME`, die Temperatur wechselt je Anfrage.
- `gen_local.sh NAME MIN THREADS -- LLAMA_ARGS` und `gen_loop.sh`: Wortlaut mit einem lokalen Modell, nur
  `gpu.lock`, RAM-Tor und RAM-Wächter.
- `lora_templates.py`: eigene Schablonen; mit `LORA_STRICT` auch Ablehnung und Rückfrage.
- `lora_build.py`:
  - `LORA_DIR`, `LORA_NOISE`, `LORA_MAX_TRAIN`, `LORA_STRICT=1|2`
  - Rauschen `noisy()` mit Selbsttest: `lora_build.py selftest`
  - Leck-Zählung gegen Blind 3
- `train4.sh RUN` (mit `LORA_DIR`): wartet, bis niemand an `build.lock` steht, trainiert unter beiden Sperren und
  wandelt in GGUF um.
- `eval4.sh TAG ADAPTER`: Dev-Auswertung nur mit `gpu.lock`, RAM-gesperrt. Ein Lauf dauert ~2 min.
- **Reproduzieren** (`LORA_NOISE=0.4 LORA_MAX_TRAIN=4800`):
  - run4: `LORA_DIR=lora2 lora_build.py` → train md5 `526c7c00…`
  - run5: `lora3` = `lora2/specs_*` + `lora2/jobs.jsonl` + `lora3/words.jsonl`, `LORA_STRICT=1` → train md5 `9a6d4ec9…`
  - Beide nachgeprüft.
