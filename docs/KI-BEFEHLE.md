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

## Offen

- Tempo und Speicher auf echten Geräten (Pixel 11 Pro XL, S21) mit llama.cpp für Android.
- Ordnernamen gegen echte Ordner auflösen (Download/Downloads, Musik/Music).
