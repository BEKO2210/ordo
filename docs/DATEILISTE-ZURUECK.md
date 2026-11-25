# Dateiliste: Zurück

Wie die Zurück-Taste (und die Zurück-Geste) in der Dateiliste wirkt.

## Ordner, die von anderswo in Ordo geöffnet werden

Schnellzugriff-Kacheln (Kamera, Downloads, Bilder, Videos, Musik, Screenshots, Lesezeichen), die Speicher-Karte, die
Seitenleiste der Startseite und die Analyse öffnen die Dateiliste direkt auf einem Ordner
(`FileListActivity.createViewIntent`). Dieser Ordner ist die **Einstiegsebene**:

1. In einem Unterordner geht Zurück **eine Ebene hoch**, bis zur Einstiegsebene.
2. Zurück auf der Einstiegsebene schließt die Liste und führt dorthin zurück, wo man herkam (meist die Startseite),
   nicht zur Wurzel des Speichers.

Kategorien (Bilder, Videos, … auf der Startseite) sind keine Ordner: Ein Ordner darin öffnet einen eigenen Bildschirm,
Zurück schließt ihn.

## Umsetzung

- `FileListFragment` merkt sich die Einstiegsebene (`backStopPath`) und schaltet den Zurück-Callback im Observer der
  Brotkrumen-Leiste: `shouldBackNavigateUp(...)` in `BackNavigation.kt`.
- Dieser Observer läuft, **bevor** `currentPathLiveData` den neuen Pfad hat. `currentPath` liest darum den Verlauf
  selbst (`TrailLiveData.currentPath`), nicht das abgeleitete LiveData. Bis 0.18.1 las es das abgeleitete LiveData:
  Im ersten Unterordner galt noch die Einstiegsebene als aktuell, und Zurück schloss die Liste.

## Tests

- Unit: `BackFromEntryFolderTest` (Reihenfolge der Observer wie im Fragment, zwei Ebenen hinein und zurück),
  `BackNavigationTest` (die Entscheidung allein).
- Gerät: `e2e/back-from-quick-access.sh` (`--write`, legt nur `Pictures/ordo-e2e-back` an und löscht es wieder),
  `e2e/back-navigation.sh` (Speicher und Analyse in einem Schritt zurück zur Startseite).
