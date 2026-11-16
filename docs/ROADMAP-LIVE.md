# Ordo – lebende Roadmap

<!-- Erzeugt von utils/roadmap.sh. Nicht von Hand ändern: Ziele stehen im Skript, erledigt ist, was sein Prüfbefehl bestätigt. -->

Stand: 16.11.2026, letzte Version: v0.11.0. Als Nächstes: **Stand der Technik für Medien belegt**

Jedes Ziel ist eine Zelle mit einem Prüfbefehl ([autopoiesis](https://github.com/BEKO2210/autopoiesis)).
Besteht der Befehl, ist das Ziel erledigt. Nach jeder veröffentlichten Version entstehen die Zellen
für die nächste – vor 1.0.0 und danach.

## Offen

- [ ] KI-Tempo auf echten Geräten gemessen
- [ ] Zwei Spalten auf Tablets
- [ ] Geschlossener Test bei Google Play bestanden
- [ ] Version 1.0.0 im Play Store
- [ ] KI-Vorschau mit eigenen Sätzen aus dem Alltag geprüft
- [ ] Einstellungen bildlich: Symbol an jedem Eintrag, Auswahl mit Bildern
- [ ] Bewegung aus einem Guss: Menüs, Dialoge, Piktogramme
- [ ] Stand der Technik für Medien belegt
- [ ] Musik-Player: Audio direkt in Ordo abspielen
- [ ] Musik-Player als eigenes App-Symbol zum Einschalten
- [ ] Video-Player: Filme direkt in Ordo ansehen
- [ ] PDF-Leser: suchen, markieren, Nachtmodus
- [ ] Markdown lesen und schreiben
- [ ] Dokumente bearbeiten: Entscheidung für Office-Formate
- [ ] Nach v0.11.0: neue Version veröffentlicht
- [ ] Bildbetrachter auf Spitzenniveau
- [ ] PDF bearbeiten: markieren, kommentieren, Formulare, Seiten ordnen
- [ ] Jede gängige Datei öffnen, lesen und schreiben – und es sieht gut aus

## Erledigt

- [x] Analyse neu: Belegung, Rangliste, Seiten statt Reiter
- [x] Analyse: Diagramme zurück, fehlerfrei und antippbar
- [x] Analyse: Scan-Animation statt Ladekreisel
- [x] KI wählt das Modell passend zum Gerät
- [x] Datenmüll finden: leere Ordner und Vorschau-Caches
- [x] Sicherung wiederherstellen per Knopf
- [x] Name endgültig
- [x] Store-Texte Deutsch und Englisch
- [x] Store-Bilder im Repo
- [x] Berechtigungen für Google Play begründet
- [x] Vordergrunddienst-Typ für Dateiaufträge geklärt
- [x] Native Bibliothek für 16-KB-Seiten
- [x] KI: neue Befehle als Vorschau zum Einschalten
- [x] KI-Blatt lesbar ohne Unschärfe-Effekt
- [x] Nach v0.11.0: nächste Version hat eine eigene Nummer
- [x] Nach v0.11.0: beide READMEs nennen die neue Version

## Graph

```mermaid
flowchart TD
  nc_f4263e["Analyse neu: Belegung, Rangliste, Seiten statt Reiter [done]"]
  nc_31dc6c["Analyse: Diagramme zurück, fehlerfrei und antippbar [done]"]
  nc_cf4513["Analyse: Scan-Animation statt Ladekreisel [done]"]
  nc_2c7646["KI wählt das Modell passend zum Gerät [done]"]
  nc_af8b44["KI-Tempo auf echten Geräten gemessen [active]"]
  nc_59339e["Datenmüll finden: leere Ordner und Vorschau-Caches [done]"]
  nc_60d9cf["Sicherung wiederherstellen per Knopf [done]"]
  nc_a291b5["Zwei Spalten auf Tablets [active]"]
  nc_463bb4["Name endgültig [done]"]
  nc_a0a8b8["Store-Texte Deutsch und Englisch [done]"]
  nc_a15b2e["Store-Bilder im Repo [done]"]
  nc_eb3d9c["Berechtigungen für Google Play begründet [done]"]
  nc_27e99c["Vordergrunddienst-Typ für Dateiaufträge geklärt [done]"]
  nc_0f8ddc["Native Bibliothek für 16-KB-Seiten [done]"]
  nc_69a218["Geschlossener Test bei Google Play bestanden [active]"]
  nc_f8e0ee["Version 1.0.0 im Play Store [active]"]
  nc_407e86["KI: neue Befehle als Vorschau zum Einschalten [done]"]
  nc_86a148["KI-Vorschau mit eigenen Sätzen aus dem Alltag geprüft [active]"]
  nc_77d5cb["KI-Blatt lesbar ohne Unschärfe-Effekt [done]"]
  nc_c59371["Einstellungen bildlich: Symbol an jedem Eintrag, Auswahl mit Bildern [active]"]
  nc_fb0cfe["Bewegung aus einem Guss: Menüs, Dialoge, Piktogramme [active]"]
  nc_b6ad07["Stand der Technik für Medien belegt [active]"]
  nc_3c314b["Musik-Player: Audio direkt in Ordo abspielen [active]"]
  nc_4aecf0["Musik-Player als eigenes App-Symbol zum Einschalten [active]"]
  nc_770465["Video-Player: Filme direkt in Ordo ansehen [active]"]
  nc_323570["PDF-Leser: suchen, markieren, Nachtmodus [active]"]
  nc_1a4306["Markdown lesen und schreiben [active]"]
  nc_68ddf4["Dokumente bearbeiten: Entscheidung für Office-Formate [active]"]
  nc_c4674b["Nach v0.11.0: neue Version veröffentlicht [active]"]
  nc_b21f77["Bildbetrachter auf Spitzenniveau [active]"]
  nc_cb28c1["PDF bearbeiten: markieren, kommentieren, Formulare, Seiten ordnen [active]"]
  nc_b6159e["Jede gängige Datei öffnen, lesen und schreiben – und es sieht gut aus [active]"]
  nc_0f8ddc --> nc_69a218
  nc_27e99c --> nc_69a218
  nc_a0a8b8 --> nc_69a218
  nc_a15b2e --> nc_69a218
  nc_eb3d9c --> nc_69a218
  nc_463bb4 --> nc_f8e0ee
  nc_69a218 --> nc_f8e0ee
  nc_407e86 --> nc_86a148
  nc_b6ad07 --> nc_3c314b
  nc_3c314b --> nc_4aecf0
  nc_4aecf0 --> nc_770465
  nc_b6ad07 --> nc_770465
  nc_b6ad07 --> nc_323570
  nc_b6ad07 --> nc_1a4306
  nc_1a4306 --> nc_68ddf4
  nc_b6ad07 --> nc_b21f77
  nc_323570 --> nc_cb28c1
  nc_1a4306 --> nc_b6159e
```
