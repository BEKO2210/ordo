# Ordo – lebende Roadmap

<!-- Erzeugt von utils/roadmap.sh. Nicht von Hand ändern: Ziele stehen im Skript, erledigt ist, was sein Prüfbefehl bestätigt. -->

Stand: 15.11.2026, letzte Version: v0.6.0. Als Nächstes: **Store-Texte Deutsch und Englisch**

Jedes Ziel ist eine Zelle mit einem Prüfbefehl ([autopoiesis](https://github.com/BEKO2210/autopoiesis)).
Besteht der Befehl, ist das Ziel erledigt. Nach jeder veröffentlichten Version entstehen die Zellen
für die nächste – vor 1.0.0 und danach.

## Offen

- [ ] KI wählt das Modell passend zum Gerät
- [ ] KI-Tempo auf echten Geräten gemessen
- [ ] Datenmüll finden: leere Ordner und Vorschau-Caches
- [ ] Sicherung wiederherstellen per Knopf
- [ ] Zwei Spalten auf Tablets
- [ ] Name endgültig
- [ ] Store-Texte Deutsch und Englisch
- [ ] Store-Bilder im Repo
- [ ] Vordergrunddienst-Typ für Dateiaufträge geklärt
- [ ] Native Bibliothek für 16-KB-Seiten
- [ ] Geschlossener Test bei Google Play bestanden
- [ ] Version 1.0.0 im Play Store
- [ ] Nach v0.6.0: nächste Version hat eine eigene Nummer
- [ ] Nach v0.6.0: beide READMEs nennen die neue Version
- [ ] Nach v0.6.0: neue Version veröffentlicht

## Erledigt

- [x] Analyse neu: Belegung, Rangliste, Seiten statt Reiter
- [x] Analyse: Diagramme zurück, fehlerfrei und antippbar
- [x] Analyse: Scan-Animation statt Ladekreisel
- [x] Berechtigungen für Google Play begründet
- [x] Nach v0.4.1: nächste Version hat eine eigene Nummer
- [x] Nach v0.4.1: beide READMEs nennen die neue Version
- [x] Nach v0.4.1: neue Version veröffentlicht

## Graph

```mermaid
flowchart TD
  nc_91ce76["Analyse neu: Belegung, Rangliste, Seiten statt Reiter [done]"]
  nc_8e0b4b["Analyse: Diagramme zurück, fehlerfrei und antippbar [done]"]
  nc_b77df9["Analyse: Scan-Animation statt Ladekreisel [done]"]
  nc_183eca["KI wählt das Modell passend zum Gerät [active]"]
  nc_211125["KI-Tempo auf echten Geräten gemessen [active]"]
  nc_a2e939["Datenmüll finden: leere Ordner und Vorschau-Caches [active]"]
  nc_dfebc0["Sicherung wiederherstellen per Knopf [active]"]
  nc_09bf2a["Zwei Spalten auf Tablets [active]"]
  nc_25b570["Name endgültig [active]"]
  nc_a5c796["Store-Texte Deutsch und Englisch [active]"]
  nc_65e45e["Store-Bilder im Repo [active]"]
  nc_3e8772["Berechtigungen für Google Play begründet [done]"]
  nc_203ade["Vordergrunddienst-Typ für Dateiaufträge geklärt [active]"]
  nc_abc284["Native Bibliothek für 16-KB-Seiten [active]"]
  nc_24fadc["Geschlossener Test bei Google Play bestanden [active]"]
  nc_5c8a5a["Version 1.0.0 im Play Store [active]"]
  nc_b44309["Nach v0.6.0: nächste Version hat eine eigene Nummer [active]"]
  nc_5eec97["Nach v0.6.0: beide READMEs nennen die neue Version [active]"]
  nc_7b759b["Nach v0.6.0: neue Version veröffentlicht [active]"]
  nc_203ade --> nc_24fadc
  nc_3e8772 --> nc_24fadc
  nc_65e45e --> nc_24fadc
  nc_a5c796 --> nc_24fadc
  nc_abc284 --> nc_24fadc
  nc_24fadc --> nc_5c8a5a
  nc_25b570 --> nc_5c8a5a
  nc_b44309 --> nc_5eec97
  nc_5eec97 --> nc_7b759b
```
