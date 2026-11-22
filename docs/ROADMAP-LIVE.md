# Ordo – lebende Roadmap

<!-- Erzeugt von utils/roadmap.sh. Nicht von Hand ändern: Ziele stehen im Skript, erledigt ist, was sein Prüfbefehl bestätigt. -->

Stand: 22.11.2026, letzte Version: v0.18.1. Als Nächstes: **Version 1.0.0 im Play Store**

Jedes Ziel ist eine Zelle mit einem Prüfbefehl ([autopoiesis](https://github.com/BEKO2210/autopoiesis)).
Besteht der Befehl, ist das Ziel erledigt. Nach jeder veröffentlichten Version entstehen die Zellen
für die nächste – vor 1.0.0 und danach.

**v0.18.1** (22.11.2026) ist ein Stabilitäts-Update: #155, #156, #160, #161, #162, dazu die
Geräte-Tests #154, #158, #159. Bis **1.0.0** gilt ein Feature-Freeze. Der Produktionszugang bei
Google Play ist um den 01.12.2026 geplant. Danach kommt Ordo Bilder
(„Bildbetrachter auf Spitzenniveau“).

## Offen

- [ ] KI-Tempo auf echten Geräten gemessen
- [ ] Zwei Spalten auf Tablets
- [ ] Geschlossener Test bei Google Play bestanden
- [ ] Version 1.0.0 im Play Store
- [ ] KI-Vorschau mit eigenen Sätzen aus dem Alltag geprüft
- [ ] Streaming: Musik an Fernseher und Receiver per DLNA
- [ ] Streaming: Videos und Bilder an den Fernseher
- [ ] PDF-Leser: suchen, markieren, Nachtmodus
- [ ] Markdown lesen und schreiben
- [ ] Bildbetrachter auf Spitzenniveau
- [ ] PDF bearbeiten: markieren, kommentieren, Formulare, Seiten ordnen
- [ ] Jede gängige Datei öffnen, lesen und schreiben – und es sieht gut aus
- [ ] Goldener Schnitt als Maßsystem in ganz Ordo
- [ ] Dokumente bearbeiten: Entscheidung für Office-Formate
- [ ] KI: Musik per Satz abspielen (Datei-Manager und Ordo Musik)
- [ ] Offline-Chatbot, verbunden mit allen eingebauten Apps
- [ ] Nach v0.18.1: nächste Version hat eine eigene Nummer
- [ ] Nach v0.18.1: beide READMEs nennen die neue Version
- [ ] Nach v0.18.1: neue Version veröffentlicht

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
- [x] Klang: eigene Effekte mit lebenden Bildern
- [x] Musik-Player als eigenes App-Symbol zum Einschalten
- [x] Ordo Musik: eigene Musiksammlung mit Titeln, Alben, Interpreten und Ordnern
- [x] Ordo Musik: Infos bearbeiten (Tag-Editor)
- [x] Ordo Musik: Auswahl, Wiedergabelisten und Favoriten
- [x] Video-Player: Filme direkt in Ordo ansehen
- [x] Abspielen auf: Bluetooth, Kabel und Cast-Geräte wählen
- [x] Streaming: Musik an Chromecast und Google TV
- [x] Eingebaute Apps entkoppeln: jede als eigenes App-Symbol, einzeln schaltbar
- [x] Icon-Farbe für Ordo und jede eingebaute App selbst wählen
- [x] Ordo als Ort in Androids Dateiauswahl (DocumentsProvider)
- [x] Nach v0.17.0: nächste Version hat eine eigene Nummer
- [x] Nach v0.17.0: beide READMEs nennen die neue Version
- [x] Nach v0.17.0: neue Version veröffentlicht
- [x] Nach v0.18.0: nächste Version hat eine eigene Nummer
- [x] Nach v0.18.0: beide READMEs nennen die neue Version
- [x] Nach v0.18.0: neue Version veröffentlicht

## Graph

```mermaid
flowchart TD
  nc_209dce["Analyse neu: Belegung, Rangliste, Seiten statt Reiter [done]"]
  nc_e05552["Analyse: Diagramme zurück, fehlerfrei und antippbar [done]"]
  nc_de6be2["Analyse: Scan-Animation statt Ladekreisel [done]"]
  nc_b701d6["KI wählt das Modell passend zum Gerät [done]"]
  nc_b9cf3a["KI-Tempo auf echten Geräten gemessen [active]"]
  nc_0bf28e["Datenmüll finden: leere Ordner und Vorschau-Caches [done]"]
  nc_0abcc8["Sicherung wiederherstellen per Knopf [done]"]
  nc_a2b340["Zwei Spalten auf Tablets [active]"]
  nc_263ab2["Name endgültig [done]"]
  nc_52e09b["Store-Texte Deutsch und Englisch [done]"]
  nc_e4ab31["Store-Bilder im Repo [done]"]
  nc_fad15d["Berechtigungen für Google Play begründet [done]"]
  nc_17f4f5["Vordergrunddienst-Typ für Dateiaufträge geklärt [done]"]
  nc_95badd["Native Bibliothek für 16-KB-Seiten [done]"]
  nc_87038f["Geschlossener Test bei Google Play bestanden [active]"]
  nc_cc42ea["Version 1.0.0 im Play Store [active]"]
  nc_62b09d["KI: neue Befehle als Vorschau zum Einschalten [done]"]
  nc_12690a["KI-Vorschau mit eigenen Sätzen aus dem Alltag geprüft [active]"]
  nc_c4bc30["KI-Blatt lesbar ohne Unschärfe-Effekt [done]"]
  nc_ea10a5["Einstellungen bildlich: Symbol an jedem Eintrag, Auswahl mit Bildern [done]"]
  nc_76944b["Bewegung aus einem Guss: Menüs, Dialoge, Piktogramme [done]"]
  nc_cd136d["Stand der Technik für Medien belegt [done]"]
  nc_54fc2b["Musik-Player: Audio direkt in Ordo abspielen [done]"]
  nc_74d46f["Klang: eigene Effekte mit lebenden Bildern [done]"]
  nc_7eceae["Musik-Player als eigenes App-Symbol zum Einschalten [done]"]
  nc_e13fc7["Ordo Musik: eigene Musiksammlung mit Titeln, Alben, Interpreten und Ordnern [done]"]
  nc_af9fd4["Ordo Musik: Infos bearbeiten (Tag-Editor) [done]"]
  nc_8fb7da["Ordo Musik: Auswahl, Wiedergabelisten und Favoriten [done]"]
  nc_25562e["Video-Player: Filme direkt in Ordo ansehen [done]"]
  nc_950509["Abspielen auf: Bluetooth, Kabel und Cast-Geräte wählen [done]"]
  nc_a82c16["Streaming: Musik an Chromecast und Google TV [done]"]
  nc_62697b["Streaming: Musik an Fernseher und Receiver per DLNA [active]"]
  nc_e475cb["Streaming: Videos und Bilder an den Fernseher [active]"]
  nc_435cec["PDF-Leser: suchen, markieren, Nachtmodus [active]"]
  nc_4bf816["Markdown lesen und schreiben [active]"]
  nc_e75356["Bildbetrachter auf Spitzenniveau [active]"]
  nc_5efc6e["PDF bearbeiten: markieren, kommentieren, Formulare, Seiten ordnen [active]"]
  nc_53fd38["Jede gängige Datei öffnen, lesen und schreiben – und es sieht gut aus [active]"]
  nc_4a37d3["Eingebaute Apps entkoppeln: jede als eigenes App-Symbol, einzeln schaltbar [done]"]
  nc_46ef99["Icon-Farbe für Ordo und jede eingebaute App selbst wählen [done]"]
  nc_3d2ea4["Ordo als Ort in Androids Dateiauswahl (DocumentsProvider) [done]"]
  nc_c5d7ce["Goldener Schnitt als Maßsystem in ganz Ordo [active]"]
  nc_ca8d13["Dokumente bearbeiten: Entscheidung für Office-Formate [active]"]
  nc_2215e6["KI: Musik per Satz abspielen (Datei-Manager und Ordo Musik) [active]"]
  nc_e1e884["Offline-Chatbot, verbunden mit allen eingebauten Apps [active]"]
  nc_a18101["Nach v0.18.1: nächste Version hat eine eigene Nummer [active]"]
  nc_a18102["Nach v0.18.1: beide READMEs nennen die neue Version [active]"]
  nc_a18103["Nach v0.18.1: neue Version veröffentlicht [active]"]
  nc_17f4f5 --> nc_87038f
  nc_52e09b --> nc_87038f
  nc_95badd --> nc_87038f
  nc_e4ab31 --> nc_87038f
  nc_fad15d --> nc_87038f
  nc_263ab2 --> nc_cc42ea
  nc_87038f --> nc_cc42ea
  nc_62b09d --> nc_12690a
  nc_cd136d --> nc_54fc2b
  nc_54fc2b --> nc_74d46f
  nc_54fc2b --> nc_7eceae
  nc_7eceae --> nc_e13fc7
  nc_e13fc7 --> nc_af9fd4
  nc_e13fc7 --> nc_8fb7da
  nc_7eceae --> nc_25562e
  nc_cd136d --> nc_25562e
  nc_54fc2b --> nc_950509
  nc_950509 --> nc_a82c16
  nc_a82c16 --> nc_62697b
  nc_25562e --> nc_e475cb
  nc_62697b --> nc_e475cb
  nc_cd136d --> nc_435cec
  nc_cd136d --> nc_4bf816
  nc_cd136d --> nc_e75356
  nc_435cec --> nc_5efc6e
  nc_4bf816 --> nc_53fd38
  nc_25562e --> nc_4a37d3
  nc_7eceae --> nc_4a37d3
  nc_7eceae --> nc_46ef99
  nc_4bf816 --> nc_ca8d13
  nc_8fb7da --> nc_2215e6
  nc_2215e6 --> nc_e1e884
  nc_25562e --> nc_e1e884
  nc_5efc6e --> nc_e1e884
  nc_a18101 --> nc_a18102
  nc_a18102 --> nc_a18103
```
