# Ordo – lebende Roadmap

<!-- Erzeugt von utils/roadmap.sh. Nicht von Hand ändern: Ziele stehen im Skript, erledigt ist, was sein Prüfbefehl bestätigt. -->

Stand: 17.11.2026, letzte Version: v0.13.2. Als Nächstes: **Video-Player: Filme direkt in Ordo ansehen**

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
- [ ] Streaming: Musik an Chromecast und Google TV
- [ ] Streaming: Musik an Fernseher und Receiver per DLNA
- [ ] Streaming: Videos und Bilder an den Fernseher
- [ ] PDF-Leser: suchen, markieren, Nachtmodus
- [ ] Markdown lesen und schreiben
- [ ] Bildbetrachter auf Spitzenniveau
- [ ] PDF bearbeiten: markieren, kommentieren, Formulare, Seiten ordnen
- [ ] Jede gängige Datei öffnen, lesen und schreiben – und es sieht gut aus
- [ ] Eingebaute Apps entkoppeln: jede als eigenes App-Symbol, einzeln schaltbar
- [ ] Goldener Schnitt als Maßsystem in ganz Ordo
- [ ] Dokumente bearbeiten: Entscheidung für Office-Formate
- [ ] Nach v0.13.1: neue Version veröffentlicht
- [ ] Nach v0.13.2: neue Version veröffentlicht

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
- [x] Abspielen auf: Bluetooth, Kabel und Cast-Geräte wählen
- [x] Nach v0.13.1: nächste Version hat eine eigene Nummer
- [x] Nach v0.13.1: beide READMEs nennen die neue Version
- [x] Nach v0.13.2: nächste Version hat eine eigene Nummer
- [x] Nach v0.13.2: beide READMEs nennen die neue Version

## Graph

```mermaid
flowchart TD
  nc_030d2d["Analyse neu: Belegung, Rangliste, Seiten statt Reiter [done]"]
  nc_05c18a["Analyse: Diagramme zurück, fehlerfrei und antippbar [done]"]
  nc_aed179["Analyse: Scan-Animation statt Ladekreisel [done]"]
  nc_d4e375["KI wählt das Modell passend zum Gerät [done]"]
  nc_42da5b["KI-Tempo auf echten Geräten gemessen [active]"]
  nc_d9751a["Datenmüll finden: leere Ordner und Vorschau-Caches [done]"]
  nc_87a9ce["Sicherung wiederherstellen per Knopf [done]"]
  nc_48bebd["Zwei Spalten auf Tablets [active]"]
  nc_1279f1["Name endgültig [done]"]
  nc_7d2b74["Store-Texte Deutsch und Englisch [done]"]
  nc_8a03fc["Store-Bilder im Repo [done]"]
  nc_031b14["Berechtigungen für Google Play begründet [done]"]
  nc_830016["Vordergrunddienst-Typ für Dateiaufträge geklärt [done]"]
  nc_d80a8c["Native Bibliothek für 16-KB-Seiten [done]"]
  nc_21eac6["Geschlossener Test bei Google Play bestanden [active]"]
  nc_aad7b9["Version 1.0.0 im Play Store [active]"]
  nc_858cda["KI: neue Befehle als Vorschau zum Einschalten [done]"]
  nc_a1c154["KI-Vorschau mit eigenen Sätzen aus dem Alltag geprüft [active]"]
  nc_852c8d["KI-Blatt lesbar ohne Unschärfe-Effekt [done]"]
  nc_569edf["Einstellungen bildlich: Symbol an jedem Eintrag, Auswahl mit Bildern [done]"]
  nc_509b67["Bewegung aus einem Guss: Menüs, Dialoge, Piktogramme [done]"]
  nc_64b956["Stand der Technik für Medien belegt [done]"]
  nc_05976b["Musik-Player: Audio direkt in Ordo abspielen [done]"]
  nc_3f5025["Musik-Player als eigenes App-Symbol zum Einschalten [done]"]
  nc_42c54b["Video-Player: Filme direkt in Ordo ansehen [active]"]
  nc_005f72["Abspielen auf: Bluetooth, Kabel und Cast-Geräte wählen [done]"]
  nc_de7da1["Streaming: Musik an Chromecast und Google TV [active]"]
  nc_0fa391["Streaming: Musik an Fernseher und Receiver per DLNA [active]"]
  nc_5f32e7["Streaming: Videos und Bilder an den Fernseher [active]"]
  nc_a8a3a6["PDF-Leser: suchen, markieren, Nachtmodus [active]"]
  nc_d6d9cc["Markdown lesen und schreiben [active]"]
  nc_e5c52e["Bildbetrachter auf Spitzenniveau [active]"]
  nc_742246["PDF bearbeiten: markieren, kommentieren, Formulare, Seiten ordnen [active]"]
  nc_3ee455["Jede gängige Datei öffnen, lesen und schreiben – und es sieht gut aus [active]"]
  nc_81d03b["Eingebaute Apps entkoppeln: jede als eigenes App-Symbol, einzeln schaltbar [active]"]
  nc_c9fd0b["Goldener Schnitt als Maßsystem in ganz Ordo [active]"]
  nc_2adb3d["Dokumente bearbeiten: Entscheidung für Office-Formate [active]"]
  nc_89874d["Nach v0.13.1: neue Version veröffentlicht [active]"]
  nc_aa27a9["Nach v0.13.2: neue Version veröffentlicht [active]"]
  nc_031b14 --> nc_21eac6
  nc_7d2b74 --> nc_21eac6
  nc_830016 --> nc_21eac6
  nc_8a03fc --> nc_21eac6
  nc_d80a8c --> nc_21eac6
  nc_1279f1 --> nc_aad7b9
  nc_21eac6 --> nc_aad7b9
  nc_858cda --> nc_a1c154
  nc_64b956 --> nc_05976b
  nc_05976b --> nc_3f5025
  nc_3f5025 --> nc_42c54b
  nc_64b956 --> nc_42c54b
  nc_05976b --> nc_005f72
  nc_005f72 --> nc_de7da1
  nc_de7da1 --> nc_0fa391
  nc_0fa391 --> nc_5f32e7
  nc_42c54b --> nc_5f32e7
  nc_64b956 --> nc_a8a3a6
  nc_64b956 --> nc_d6d9cc
  nc_64b956 --> nc_e5c52e
  nc_a8a3a6 --> nc_742246
  nc_d6d9cc --> nc_3ee455
  nc_3f5025 --> nc_81d03b
  nc_42c54b --> nc_81d03b
  nc_d6d9cc --> nc_2adb3d
```
