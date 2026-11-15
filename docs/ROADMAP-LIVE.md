# Ordo – lebende Roadmap

<!-- Erzeugt von utils/roadmap.sh. Nicht von Hand ändern: Ziele stehen im Skript, erledigt ist, was sein Prüfbefehl bestätigt. -->

Stand: 15.11.2026, letzte Version: v0.4.0. Als Nächstes: **Store-Texte Deutsch und Englisch**

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
- [ ] Nach v0.4.0: nächste Version hat eine eigene Nummer
- [ ] Nach v0.4.0: beide READMEs nennen die neue Version
- [ ] Nach v0.4.0: neue Version veröffentlicht

## Erledigt

- [x] Analyse neu: Belegung, Rangliste, Seiten statt Reiter
- [x] Berechtigungen für Google Play begründet

## Graph

```mermaid
flowchart TD
  nc_07ff71["Analyse neu: Belegung, Rangliste, Seiten statt Reiter [done]"]
  nc_0ba2e5["KI wählt das Modell passend zum Gerät [active]"]
  nc_456143["KI-Tempo auf echten Geräten gemessen [active]"]
  nc_2d8680["Datenmüll finden: leere Ordner und Vorschau-Caches [active]"]
  nc_1dfb10["Sicherung wiederherstellen per Knopf [active]"]
  nc_5eb187["Zwei Spalten auf Tablets [active]"]
  nc_3ad07c["Name endgültig [active]"]
  nc_9351e1["Store-Texte Deutsch und Englisch [active]"]
  nc_ee0916["Store-Bilder im Repo [active]"]
  nc_3aa124["Berechtigungen für Google Play begründet [done]"]
  nc_59facc["Vordergrunddienst-Typ für Dateiaufträge geklärt [active]"]
  nc_ee3fb4["Native Bibliothek für 16-KB-Seiten [active]"]
  nc_4f34c0["Geschlossener Test bei Google Play bestanden [active]"]
  nc_043174["Version 1.0.0 im Play Store [active]"]
  nc_75e2e9["Nach v0.4.0: nächste Version hat eine eigene Nummer [active]"]
  nc_942485["Nach v0.4.0: beide READMEs nennen die neue Version [active]"]
  nc_5548d1["Nach v0.4.0: neue Version veröffentlicht [active]"]
  nc_3aa124 --> nc_4f34c0
  nc_59facc --> nc_4f34c0
  nc_9351e1 --> nc_4f34c0
  nc_ee0916 --> nc_4f34c0
  nc_ee3fb4 --> nc_4f34c0
  nc_3ad07c --> nc_043174
  nc_4f34c0 --> nc_043174
  nc_75e2e9 --> nc_942485
  nc_942485 --> nc_5548d1
```
