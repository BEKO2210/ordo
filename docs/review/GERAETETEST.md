# GERAETETEST.md — Ordo, nur zur Laufzeit

Abhakbarer Plan für ein physisches Gerät. Kein Emulator auf der Box. Jeder Block: Schritte, erwartet, Ergebnis leer lassen.

**Gerät:** _______________  **API / Android:** _______________  **Tester:** _______________  **Datum (Europe/Berlin):** _______________  
**Build:** ordo-0.1.0-test4.apk (SHA-256 `c8f8ccfed89c25aecc51d1e9d7a2d7f08ba5a11f9dddd93146645c64ca539d5d`) oder ein neuerer, dokumentierter Stand.

Testdateien: `tests/make-testfiles.sh --out /sdcard/Download/ordo-testfiles` auf einem Rechner erzeugen, danach den Ordner `fixtures/` auf das Gerät kopieren. Auf dem Gerät `--full` nur, wenn genug Speicher frei ist (Sparse-Dateien können beim Kopieren auf FAT/exFAT vollmaterialisiert werden). Das Skript auf der Box wurde im Small-Modus ausgeführt; optionale Tools (ImageMagick, 7z, rar) werden sauber übersprungen, ffmpeg erzeugt Audio/Video, wenn vorhanden.

Legende: `[ ]` offen, `[x]` ok, `[F]` Fehler (kurz notieren).

---

## A — Formate und Operationen

Für jede Datei in der Liste: öffnen, Vorschau in der Liste, kopieren, verschieben, umbenennen (auch mit Umlaut), in den Papierkorb und wiederherstellen, in den Tresor und wieder heraus, in eine Sicherung aufnehmen.

Testdateien: `fixtures/images/`, `video/`, `audio/`, `docs/`, `apk/`, plus `edge/zero.txt`.

| ID | Datei / Format | Öffnen | Vorschau | Kp/Mv/Um | Papierkorb | Tresor | Sicherung | Bemerkung |
|---|---|---|---|---|---|---|---|---|
| A1 | photo.jpg | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | |
| A2 | photo.png | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | |
| A3 | photo.webp | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | |
| A4 | photo.heic | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | C1, API notieren |
| A5 | photo.dng, camera.arw/.cr2 | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | C2 |
| A6 | photo.raw | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | Endung ohne MIME |
| A7 | icon.svg | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | C3 |
| A8 | clip.mp4 / .mkv / .webm / .mov | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | Player notieren |
| A9 | tone.mp3 / .flac / .opus / .wav | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | |
| A10 | tone.m4a | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | C4, welcher Player? |
| A11 | mini.pdf | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | eigener Viewer? |
| A12d | document.docx, sheet.xlsx, slides.pptx, text.odt | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | fremde App? |
| A13 | note.txt, note.md | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | Texteditor? |
| A14 | book.epub | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | nicht als ZIP öffnen |
| A15 | sample.apk bzw. ordo-APK | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | Dialog Installieren/Ansehen |

### Archive

| ID | Schritt | Erwartet | Ergebnis |
|---|---|---|---|
| A16 | `ok.zip` antippen | Öffnet als Ordner, `inside.txt` sichtbar | Gerät: ___ API: ___  [ ] |
| A17 | `ok.7z` (falls erzeugt) | Wie A16 | [ ] / n. v. |
| A18 | `ok.rar` (falls erzeugt) | Wie A16, Entpacken möglich | [ ] / n. v. |
| A19 | `ok.tar.gz` | Wie A16 | [ ] |
| A20 | Auswahl von Dateien → Archiv erstellen als ZIP, 7z, tar.xz | Drei Archive entstehen, Inhalt vollständig | [ ] |
| A21 | Dieselbe Auswahl als RAR bzw. tar.gz | In der UI nicht angeboten | [ ] |
| A22 | `zip-slip.zip` in einen leeren Ordner unter Download entpacken | Keine Datei außerhalb des Zielordners (`../../slip-escaped.txt` und Absolutpfad bleiben im Ziel oder werden abgelehnt) | [ ] |
| A23 | Archiv bauen, das einen Symlink `link → /storage/emulated/0/Download` und die Datei `link/pwned.txt` enthält, dann in einen anderen Ordner entpacken (**TESTER-1**) | `pwned.txt` darf nicht in Download landen; Abbruch oder Ablehnung | [ ] |

---

## B — Randfälle Dateisystem

| ID | Schritt | Erwartet | Ergebnis |
|---|---|---|---|
| B1 | Mit `--full` erzeugte Sparse-Datei `sparse-4gib.bin` und `sparse-4gib-plus-one.bin` kopieren, Fortschritt beobachten; anschließend in ein ZIP packen | Copy kommt durch, Fortschritt bleibt sinnvoll (nicht negativ, nicht 100 % zu früh). ZIP ist lesbar (ZIP64). | [ ] |
| B2 | `zero.txt` und `zero-no-ext` öffnen, kopieren, umbenennen | Kein Absturz; Öffnen darf „leer“ oder ungeeignet sein | [ ] |
| B3 | `Grüße.txt`, `😀 foto.txt`, `file with spaces.txt`, `Café.txt` und die NFD-Variante umbenennen, suchen, in ein ZIP packen und wieder entpacken | Namen bleiben erhalten (NFC/NFD ggf. vom FS zusammengeführt). | [ ] |
| B4 | Datei unter `names/deep/.../file.txt` öffnen; Datei mit 200-Zeichen-Namen kopieren | Kein Absturz; bei PATH_MAX klare Fehlermeldung | [ ] |
| B5 | Versteckte `.hidden.txt` und `.secret/` ohne und mit „Versteckte Dateien anzeigen“ | Standard: unsichtbar. Nach dem Schalter: sichtbar, Operationen möglich. | [ ] |
| B6 | `links/loop-a` und `loop-b` kopieren; `links/outside` (`→ /etc/passwd`) kopieren, nicht folgen | Schleife hängt nicht endlos. Der Symlink wird als Link kopiert, `/etc/passwd` wird nicht gelesen oder überschrieben. | [ ] |
| B7 | `edge/without_extension` und `looks-like-a-picture` | GENERIC; Bildvorschau darf versuchen und scheitern, kein Absturz. | [ ] |

---

## C — Anzeige und Codec

| ID | Schritt | Erwartet | Ergebnis |
|---|---|---|---|
| C1 | HEIC auf diesem API-Stand öffnen und in der Liste ansehen | Entweder Bild oder klare Meldung, kein Absturz. API: ___ | [ ] |
| C2 | DNG/RAW öffnen | Wie C1 | [ ] |
| C3 | SVG in Liste und Viewer | Liste idealerweise mit Thumbnail; Viewer zeigt die Grafik oder eine klare Lücke | [ ] |
| C4 | m4a mit dem vorgeschlagenen Player | Ton spielt; falls der Player wegen `audio/mpeg` scheitert, notieren | [ ] |

---

## D — Gerät, Jobs, Speicher

| ID | Schritt | Erwartet | Ergebnis |
|---|---|---|---|
| D1 | Gerät während eines Kopierjobs um 90° drehen | Job läuft weiter, Fortschritt bleibt, UI steht nach der Drehung | [ ] |
| D2 | App in den Hintergrund, Lockscreen, zurück | Job läuft (Foreground-Service/Notification). Nach dem Zurückkehren aktueller Stand. | [ ] |
| D3 | Während eines großen Jobs andere Apps öffnen, bis der Speicher knapp wird (oder Entwickleroption „Speicher nicht behalten“, wenn vorhanden) | Kein stilles Abbrechen ohne Meldung; Job kann nach Kill nicht als erfolgreich gelten | [ ] |
| D4 | Prozess der App während Copy/Archiv/Sicherung/Tresor-Job über „Beenden“ oder `am force-stop` beenden | Nach dem Neustart: unvollständige Zieldatei erkennbar oder Job fortgesetzt/klar fehlgeschlagen, keine halbe „Erfolg“-Meldung | [ ] |
| D5 | Ziel auf SD-Karte oder USB-OTG, Datenträger während des Jobs ziehen | Klare Fehlermeldung, kein Absturz, Quelle unverändert | [ ] |
| D6 | Ordner mit mehreren tausend Dateien (Skript: `mkdir many && seq 1 5000 | xargs -I{} touch many/f{}.txt`) listen, suchen, kopieren | Liste bleibt bedienbar oder zeigt Fortschritt; kein ANR über mehrere Sekunden ohne Feedback | [ ] |

---

## E — KI

Voraussetzungen: Modell geladen oder Setup-Dialog erreichbar. Die Lock-Fälle sind in den Unit-Tests schon rot; hier die Laufzeit inkl. Bestätigungsblatt.

| ID | Schritt | Erwartet | Ergebnis |
|---|---|---|---|
| E1 | KI-Einrichtung starten, Download nach wenigen Prozent abbrechen (System-Download oder Abbrechen in der App) | Kein als gültig markiertes Modell; unvollständige Datei wird nicht benutzt. Erneut starten möglich. | [ ] |
| E2 | Download im Flugmodus starten bzw. Flugmodus während des Downloads | Pause oder klarer Fehler, kein „Ready“ | [ ] |
| E3 | Speicher so füllen, dass das Modell (~2,7 GiB + 500 MiB Reserve) nicht passt, dann Download starten | Wird vorher abgelehnt (`CommandModel.check`, `spaceOk`) | [ ] |
| E4 | SHA-Fehler: eine zu kleine oder veränderte Datei unter dem Modellnamen ablegen und prüfen lassen (nur auf einem Testgerät) | `verify` löscht die Datei, Zustand nicht Ready (`CommandModel.kt:104-111`) | [ ] |
| E5 | Nach erfolgreichem Setup: „öffne den Papierkorb“ darf keinen Löschplan ausführen (TESTER-2) | Ablehnung oder kein Bestätigen-zum-Löschen | [ ] |
| E6 | „lösche temporäre Dateien in Download, nicht die Kamera“ darf DCIM/Camera nicht anbieten (TESTER-3) | Kamera bleibt unangetastet | [ ] |
| E7 | Bestätigungsblatt: Abbrechen, dann dieselbe Aktion; nichts darf vor dem Bestätigen passieren | Quelle unverändert bis zum Tipp auf Bestätigen | [ ] |
| E8 | Papierkorb in den Einstellungen aus, dann einen Löschbefehl | Meldung, dass nicht gelöscht wird (`ai_refused_trash_off_*`) | [ ] |
| E9 | Einrichtung nach dem Modell-Hinweis abbrechen, App kalt starten | Kein halbfertiger Zustand, der Befehle zulässt | [ ] |

---

## F — Barrierefreiheit

| ID | Schritt | Erwartet | Ergebnis |
|---|---|---|---|
| F1 | TalkBack: Dateiliste, Mehrfachauswahl, Öffnen, Papierkorb, Tresor, KI-Blatt (inkl. Ablehnen und Bestätigen) | Jedes Bedienelement hat einen sprechbaren Namen, Fokus logisch, Bestätigen ist eindeutig | [ ] |
| F2 | Schriftgröße System „größte“ bzw. 1,3+ / Anzeigezoom | Keine unlesbaren Überlappungen in Liste, Dialog, KI-Blatt, Viewer; Scrollen möglich | [ ] |

---

## Testdateien

Skript: `tests/make-testfiles.sh`.

```bash
# Small (Standard, keine 4-GiB-Dateien):
./tests/make-testfiles.sh --out ./ordo-testfiles

# Mit Sparse-4-GiB und optionalem ZIP64:
./tests/make-testfiles.sh --out ./ordo-testfiles --full
```

Das Skript braucht nur Bash und die Standardtools. Optional: `ffmpeg` (kurze gültige Audio-/Videodateien), ImageMagick `convert` (Bilder), `zip`/`python3` (ZIP und Office-Platzhalter), `tar`, `7z`, `rar`. Fehlende Werkzeuge werden mit `SKIP:` gemeldet; Platzhalterdateien entstehen trotzdem. `--full` nutzt `truncate` für Sparse-Dateien `edge/sparse-4gib.bin` (4 GiB) und `edge/sparse-4gib-plus-one.bin` (4 GiB+1).

Inhalt (Small): jpg/png/webp/svg-Platzhalter oder echte Mini-Bilder, heic/raw/dng plus typische RAW-Endungen, mp4/mkv/webm/mov, mp3/flac/opus/m4a/wav, pdf/txt/md/docx/xlsx/pptx/odt/epub, apk-Platzhalter und Symlink auf das Review-APK, ok.zip, ok.tar.gz, optional ok.7z/ok.rar, zip-slip.zip, 0-Byte, ohne Endung, versteckt, 200-Zeichen-Name, 20 Ebenen Tiefe, Umlaute, Emoji, Leerzeichen, NFC/NFD, Symlink-Schleife und Symlink nach `/etc/passwd`.
