# Ordo – lebende Roadmap

<!-- Erzeugt von utils/roadmap.sh. Nicht von Hand ändern: Ziele stehen im Skript, erledigt ist, was sein Prüfbefehl bestätigt. -->

Stand: 17.11.2026, letzte Version: v0.12.0. Als Nächstes: **Markdown lesen und schreiben**

Jedes Ziel ist eine Zelle mit einem Prüfbefehl ([autopoiesis](https://github.com/BEKO2210/autopoiesis)).
Besteht der Befehl, ist das Ziel erledigt. Nach jeder veröffentlichten Version entstehen die Zellen
für die nächste – vor 1.0.0 und danach.

## Offen

- [ ] KI-Tempo auf echten Geräten gemessen
- [ ] Zwei Spalten auf Tablets
- [ ] Geschlossener Test bei Google Play bestanden
- [ ] Version 1.0.0 im Play Store
- [ ] KI-Vorschau mit eigenen Sätzen aus dem Alltag geprüft
- [ ] Video-Player: Filme direkt in Ordo ansehen
- [ ] PDF-Leser: suchen, markieren, Nachtmodus
- [ ] Markdown lesen und schreiben
- [ ] Bildbetrachter auf Spitzenniveau
- [ ] PDF bearbeiten: markieren, kommentieren, Formulare, Seiten ordnen
- [ ] Jede gängige Datei öffnen, lesen und schreiben – und es sieht gut aus
- [ ] Eingebaute Apps entkoppeln: jede als eigenes App-Symbol, einzeln schaltbar
- [ ] Goldener Schnitt als Maßsystem in ganz Ordo
- [ ] Dokumente bearbeiten: Entscheidung für Office-Formate
- [ ] Nach v0.12.0: neue Version veröffentlicht

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
- [x] Einstellungen bildlich: Symbol an jedem Eintrag, Auswahl mit Bildern
- [x] Bewegung aus einem Guss: Menüs, Dialoge, Piktogramme
- [x] Stand der Technik für Medien belegt
- [x] Musik-Player: Audio direkt in Ordo abspielen
- [x] Musik-Player als eigenes App-Symbol zum Einschalten
- [x] Nach v0.12.0: nächste Version hat eine eigene Nummer
- [x] Nach v0.12.0: beide READMEs nennen die neue Version

## Graph

```mermaid
flowchart TD
  nc_c79af6["Analyse neu: Belegung, Rangliste, Seiten statt Reiter [done]"]
  nc_54c130["Analyse: Diagramme zurück, fehlerfrei und antippbar [done]"]
  nc_035061["Analyse: Scan-Animation statt Ladekreisel [done]"]
  nc_c59a9e["KI wählt das Modell passend zum Gerät [done]"]
  nc_a51703["KI-Tempo auf echten Geräten gemessen [active]"]
  nc_13641f["Datenmüll finden: leere Ordner und Vorschau-Caches [done]"]
  nc_6624cb["Sicherung wiederherstellen per Knopf [done]"]
  nc_8ad8cc["Zwei Spalten auf Tablets [active]"]
  nc_7f5830["Name endgültig [done]"]
  nc_961377["Store-Texte Deutsch und Englisch [done]"]
  nc_4f3cba["Store-Bilder im Repo [done]"]
  nc_b457bb["Berechtigungen für Google Play begründet [done]"]
  nc_c3c485["Vordergrunddienst-Typ für Dateiaufträge geklärt [done]"]
  nc_a53ea1["Native Bibliothek für 16-KB-Seiten [done]"]
  nc_85c842["Geschlossener Test bei Google Play bestanden [active]"]
  nc_95e4f4["Version 1.0.0 im Play Store [active]"]
  nc_b01caf["KI: neue Befehle als Vorschau zum Einschalten [done]"]
  nc_66ea9b["KI-Vorschau mit eigenen Sätzen aus dem Alltag geprüft [active]"]
  nc_fe77a3["KI-Blatt lesbar ohne Unschärfe-Effekt [done]"]
  nc_c923cf["Einstellungen bildlich: Symbol an jedem Eintrag, Auswahl mit Bildern [done]"]
  nc_c9e7eb["Bewegung aus einem Guss: Menüs, Dialoge, Piktogramme [done]"]
  nc_d9a89b["Stand der Technik für Medien belegt [done]"]
  nc_6bae3a["Musik-Player: Audio direkt in Ordo abspielen [done]"]
  nc_524fe6["Musik-Player als eigenes App-Symbol zum Einschalten [done]"]
  nc_ddcc1c["Video-Player: Filme direkt in Ordo ansehen [active]"]
  nc_94c1d9["PDF-Leser: suchen, markieren, Nachtmodus [active]"]
  nc_9ec444["Markdown lesen und schreiben [active]"]
  nc_6fe30b["Bildbetrachter auf Spitzenniveau [active]"]
  nc_c534b8["PDF bearbeiten: markieren, kommentieren, Formulare, Seiten ordnen [active]"]
  nc_98ee42["Jede gängige Datei öffnen, lesen und schreiben – und es sieht gut aus [active]"]
  nc_4ddd3e["Eingebaute Apps entkoppeln: jede als eigenes App-Symbol, einzeln schaltbar [active]"]
  nc_4fe6d9["Goldener Schnitt als Maßsystem in ganz Ordo [active]"]
  nc_201dcc["Dokumente bearbeiten: Entscheidung für Office-Formate [active]"]
  nc_85e42e["Nach v0.12.0: neue Version veröffentlicht [active]"]
  nc_4f3cba --> nc_85c842
  nc_961377 --> nc_85c842
  nc_a53ea1 --> nc_85c842
  nc_b457bb --> nc_85c842
  nc_c3c485 --> nc_85c842
  nc_7f5830 --> nc_95e4f4
  nc_85c842 --> nc_95e4f4
  nc_b01caf --> nc_66ea9b
  nc_d9a89b --> nc_6bae3a
  nc_6bae3a --> nc_524fe6
  nc_524fe6 --> nc_ddcc1c
  nc_d9a89b --> nc_ddcc1c
  nc_d9a89b --> nc_94c1d9
  nc_d9a89b --> nc_9ec444
  nc_d9a89b --> nc_6fe30b
  nc_94c1d9 --> nc_c534b8
  nc_9ec444 --> nc_98ee42
  nc_524fe6 --> nc_4ddd3e
  nc_ddcc1c --> nc_4ddd3e
  nc_9ec444 --> nc_201dcc
```
