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
- Prüfregeln (`CommandCheck`, Unit-Tests): Löschen nur mit Lösch-Wort, nur in den Papierkorb; geschützt sind
  System-, versteckte und Kamera-Ordner (kanonischer Pfad, ohne Groß-/Kleinschreibung); unbekannter Ordner =
  Rückfrage; Rückgängig fasst nur Dateien an, die der Befehl angelegt hat.
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

## Offen

- Älteres Gerät (S21/Exynos 2100) nicht gemessen: Gerät ist gerade nicht verfügbar.
- Gekürzten System-Prompt messen (Treffer + Zeit).
- Ordnernamen gegen echte Ordner auflösen (Download/Downloads, Musik/Music).
