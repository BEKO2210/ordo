# Ordo in der System-Dateiauswahl (DocumentsProvider)

Stand 18.11.2026. Ziel: Öffnet eine andere App Androids Dateiauswahl (`ACTION_OPEN_DOCUMENT`, etwa ein Upload im
Browser), steht „Ordo“ links bei den Orten. Der bisherige Weg über „Öffnen mit“ (`GET_CONTENT`, `e2e/pick-file.sh`)
bleibt daneben bestehen.

## Quellen

- Android-Doku „Create a custom document provider“ (developer.android.com/guide/topics/providers/create-document-provider)
  und `DocumentsProvider`/`DocumentsContract` in der API-Referenz: Provider mit
  `android.content.action.DOCUMENTS_PROVIDER`, `exported`, `grantUriPermissions` und
  `android:permission="android.permission.MANAGE_DOCUMENTS"`; Pflicht sind `queryRoots`, `queryDocument`,
  `queryChildDocuments`, `openDocument`.
- Material Files (Upstream) hat keinen: Im Code steht nur `// TODO: We should actually implement a DocumentsProvider`
  (`FileProvider.kt`), die Wünsche #680 „Upgrade FileProvider to DocumentsProvider“ und #1673 „Add DocumentsProvider“
  sind offen (GitHub-Suche 18.11.2026). Es gab also keinen Code zum Übernehmen; Ordos Provider ist neu geschrieben.

## Entscheidung für den ersten Schritt

- **Ein Ort „Ordo“, nur lesen, Inhalt = deine Lesezeichen** (`Settings.BOOKMARK_DIRECTORIES`, nur lokale Ordner). Den
  internen Speicher zeigt Androids eigener Anbieter schon; Ordo bringt dort nur Mehrwert, wo es etwas Eigenes weiß:
  die Ordner, die du dir gemerkt hast.
- **Zweiter Ort „Ordo – Zuletzt verwendet in Ordo“**, damit Ordo auch ohne Lesezeichen erscheint: die Dateien, die die
  Startseite unter „Zuletzt verwendet“ zeigt (MediaStore, neueste zuerst, höchstens 50, alle 10 s neu gelesen), mit
  denselben Prüfungen; leer ist der Ort ausgeblendet.
- **Vorschaubilder** für Bilder (unterabgetastet dekodiert, nie in voller Größe) und Musik (eingebettetes Cover),
  höchstens 512 px auf der längeren Seite; keine für Dateien über 64 MB oder Bilder über 50 Megapixel, und fehlt
  Speicher, gibt es eben kein Vorschaubild.
- **Ordner-Freigaben nur für die Lesezeichen**: Der Ort „Lesezeichen“ erlaubt eine Freigabe als Ordner
  (ACTION_OPEN_DOCUMENT_TREE), denn sein Inhalt sind die Ordner, die du selbst gewählt hast. „Zuletzt verwendet“ erlaubt
  das nicht (kein `FLAG_SUPPORTS_IS_CHILD`, `isChildDocument` = falsch): Die Liste ändert sich von selbst, eine Freigabe
  darauf gäbe der fremden App jede Datei, die später zuletzt verwendet wird. Dort gibt es nur einzelne Dateien.
- **Geänderte Lesezeichen** melden die Orte neu (`notifyChange` auf die Roots-URI), eine offene Auswahl sieht sie sofort.
- **Kein Schreiben, kein Anlegen, kein Löschen**: `openDocument` erlaubt nur `r`, keine Flags zum Schreiben.
- **Nur mit Zugriff auf alle Dateien** (ab Android 11 `isExternalStorageManager`, davor Leserecht); ohne ihn und ohne
  vorhandenen Lesezeichen-Ordner meldet Ordo keinen Ort.
- **Nie App-eigene Daten, nie der Tresor**: `/data`, `Android/data`, `Android/obb` und `Android/media` sind ausgeschlossen,
  auch wenn ein Lesezeichen dorthin zeigt, und zwar in jeder Schreibweise (der geteilte Speicher unterscheidet keine
  Groß- und Kleinschreibung, „android/DATA“ ist derselbe Ordner); verglichen wird je Pfadteil. Versteckte Dateien und
  Ordner (Name mit „.“ vorn) werden weder gelistet noch über ihre Id geöffnet. Der Tresor speichert verschlüsselt in Ordos privatem Ordner und ist über den
  Provider nicht erreichbar; ihn freizugeben bräuchte eine eigene, ausdrückliche Einstellung und ist nicht Teil
  dieses Schritts.
- **Sicherheit der Ids** (`OrdoDocumentIds`): Eine Dokument-Id ist `path:` plus kanonischer Pfad. Jede Id wird erneut
  kanonisiert (löst `..` und Symlinks auf) und muss in einem der Lesezeichen-Ordner liegen; „Music2“ zählt nicht als in
  „Music“. Abfragen darf nur das System (MANAGE_DOCUMENTS); andere Apps bekommen nur die Datei, die du auswählst.

## Tests

- `OrdoDocumentIdsTest`: Id ↔ Pfad, `..`-Ausbruch, Symlink-Ausbruch, Präfix-Trick, kaputte Ids, private Ordner,
  Manifest (MANAGE_DOCUMENTS, Intent-Filter).
- `e2e/documents-provider.sh`: öffnet die System-Dateiauswahl, prüft „Ordo“ bei den Orten und die Lesezeichen darin,
  verlässt sie mit Zurück. Noch nicht auf einem Gerät gelaufen.

Die Tests decken zusätzlich ab: alle Schreibweisen von `Android/data|obb|media`, ein Lesezeichen in `Android/` (als
Ort abgelehnt), versteckte Dateien, die Liste der zuletzt verwendeten Dateien (höchstens 50, geprüft wie Lesezeichen)
und die Unterabtastung der Vorschaubilder.
