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
- **Kein Schreiben, kein Anlegen, kein Löschen**: `openDocument` erlaubt nur `r`, keine Flags zum Schreiben.
- **Nur mit Zugriff auf alle Dateien** (ab Android 11 `isExternalStorageManager`, davor Leserecht); ohne ihn und ohne
  vorhandenen Lesezeichen-Ordner meldet Ordo keinen Ort.
- **Nie App-eigene Daten, nie der Tresor**: `/data`, `Android/data`, `Android/obb` und `Android/media` sind ausgeschlossen,
  auch wenn ein Lesezeichen dorthin zeigt. Der Tresor speichert verschlüsselt in Ordos privatem Ordner und ist über den
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

Offen: Ändern sich die Lesezeichen, während die Auswahl offen ist, sieht sie das erst beim nächsten Öffnen
(`notifyChange` für die Wurzeln fehlt noch). Vorschaubilder liefert Ordo noch nicht.
