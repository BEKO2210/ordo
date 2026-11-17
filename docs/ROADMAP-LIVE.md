# Ordo – lebende Roadmap

<!-- Erzeugt von utils/roadmap.sh. Nicht von Hand ändern: Ziele stehen im Skript, erledigt ist, was sein Prüfbefehl bestätigt. -->

Stand: 17.11.2026, letzte Version: v0.12.0. Als Nächstes: **Musik-Player: Audio direkt in Ordo abspielen**

Jedes Ziel ist eine Zelle mit einem Prüfbefehl ([autopoiesis](https://github.com/BEKO2210/autopoiesis)).
Besteht der Befehl, ist das Ziel erledigt. Nach jeder veröffentlichten Version entstehen die Zellen
für die nächste – vor 1.0.0 und danach.

## Offen

- [ ] KI-Tempo auf echten Geräten gemessen
- [ ] Zwei Spalten auf Tablets
- [ ] Geschlossener Test bei Google Play bestanden
- [ ] Version 1.0.0 im Play Store
- [ ] KI-Vorschau mit eigenen Sätzen aus dem Alltag geprüft
- [ ] Musik-Player: Audio direkt in Ordo abspielen
- [ ] Musik-Player als eigenes App-Symbol zum Einschalten
- [ ] Video-Player: Filme direkt in Ordo ansehen
- [ ] PDF-Leser: suchen, markieren, Nachtmodus
- [ ] Markdown lesen und schreiben
- [ ] Bildbetrachter auf Spitzenniveau
- [ ] PDF bearbeiten: markieren, kommentieren, Formulare, Seiten ordnen
- [ ] Jede gängige Datei öffnen, lesen und schreiben – und es sieht gut aus
- [ ] Eingebaute Apps entkoppeln: jede als eigenes App-Symbol, einzeln schaltbar
- [ ] Goldener Schnitt als Maßsystem in ganz Ordo
- [ ] Dokumente bearbeiten: Entscheidung für Office-Formate
- [ ] Nach v0.12.0: nächste Version hat eine eigene Nummer
- [ ] Nach v0.12.0: beide READMEs nennen die neue Version
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

## Graph

```mermaid
flowchart TD
  nc_a62499["Analyse neu: Belegung, Rangliste, Seiten statt Reiter [done]"]
  nc_56dccf["Analyse: Diagramme zurück, fehlerfrei und antippbar [done]"]
  nc_b03043["Analyse: Scan-Animation statt Ladekreisel [done]"]
  nc_d528f2["KI wählt das Modell passend zum Gerät [done]"]
  nc_9660b1["KI-Tempo auf echten Geräten gemessen [active]"]
  nc_46579c["Datenmüll finden: leere Ordner und Vorschau-Caches [done]"]
  nc_85b9b2["Sicherung wiederherstellen per Knopf [done]"]
  nc_5a62af["Zwei Spalten auf Tablets [active]"]
  nc_67ac19["Name endgültig [done]"]
  nc_f583b9["Store-Texte Deutsch und Englisch [done]"]
  nc_c47dcd["Store-Bilder im Repo [done]"]
  nc_8d9d7d["Berechtigungen für Google Play begründet [done]"]
  nc_19cab8["Vordergrunddienst-Typ für Dateiaufträge geklärt [done]"]
  nc_e248fe["Native Bibliothek für 16-KB-Seiten [done]"]
  nc_f37ed4["Geschlossener Test bei Google Play bestanden [active]"]
  nc_73e7c1["Version 1.0.0 im Play Store [active]"]
  nc_d46cba["KI: neue Befehle als Vorschau zum Einschalten [done]"]
  nc_6a35f1["KI-Vorschau mit eigenen Sätzen aus dem Alltag geprüft [active]"]
  nc_4d8663["KI-Blatt lesbar ohne Unschärfe-Effekt [done]"]
  nc_ae5ad9["Einstellungen bildlich: Symbol an jedem Eintrag, Auswahl mit Bildern [done]"]
  nc_7c120c["Bewegung aus einem Guss: Menüs, Dialoge, Piktogramme [done]"]
  nc_5f7163["Stand der Technik für Medien belegt [done]"]
  nc_28551e["Musik-Player: Audio direkt in Ordo abspielen [active]"]
  nc_c7f898["Musik-Player als eigenes App-Symbol zum Einschalten [active]"]
  nc_f6b841["Video-Player: Filme direkt in Ordo ansehen [active]"]
  nc_4f7784["PDF-Leser: suchen, markieren, Nachtmodus [active]"]
  nc_f618e3["Markdown lesen und schreiben [active]"]
  nc_0bfd42["Bildbetrachter auf Spitzenniveau [active]"]
  nc_8793c9["PDF bearbeiten: markieren, kommentieren, Formulare, Seiten ordnen [active]"]
  nc_ee6840["Jede gängige Datei öffnen, lesen und schreiben – und es sieht gut aus [active]"]
  nc_e54e06["Eingebaute Apps entkoppeln: jede als eigenes App-Symbol, einzeln schaltbar [active]"]
  nc_ce7679["Goldener Schnitt als Maßsystem in ganz Ordo [active]"]
  nc_cfb6d6["Dokumente bearbeiten: Entscheidung für Office-Formate [active]"]
  nc_f792c0["Nach v0.12.0: nächste Version hat eine eigene Nummer [active]"]
  nc_5dd72c["Nach v0.12.0: beide READMEs nennen die neue Version [active]"]
  nc_ff9c8d["Nach v0.12.0: neue Version veröffentlicht [active]"]
  nc_19cab8 --> nc_f37ed4
  nc_8d9d7d --> nc_f37ed4
  nc_c47dcd --> nc_f37ed4
  nc_e248fe --> nc_f37ed4
  nc_f583b9 --> nc_f37ed4
  nc_67ac19 --> nc_73e7c1
  nc_f37ed4 --> nc_73e7c1
  nc_d46cba --> nc_6a35f1
  nc_5f7163 --> nc_28551e
  nc_28551e --> nc_c7f898
  nc_5f7163 --> nc_f6b841
  nc_c7f898 --> nc_f6b841
  nc_5f7163 --> nc_4f7784
  nc_5f7163 --> nc_f618e3
  nc_5f7163 --> nc_0bfd42
  nc_4f7784 --> nc_8793c9
  nc_f618e3 --> nc_ee6840
  nc_c7f898 --> nc_e54e06
  nc_f6b841 --> nc_e54e06
  nc_f618e3 --> nc_cfb6d6
  nc_f792c0 --> nc_5dd72c
  nc_5dd72c --> nc_ff9c8d
```
