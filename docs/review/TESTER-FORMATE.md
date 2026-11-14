# TESTER-FORMATE.md — Dateiformate im Code

Stand: Ordo @ `5cae538`, 14.11.2026, Europe/Berlin. Bewertung aus statischer Codeverfolgung. Zellen: **S** unterstützt, **N** nicht unterstützt, **U** unklar / nur auf dem Gerät, **F** fehlerhaft oder lückenhaft. Jede Zelle hat einen Beleg.

## Erkennung

MIME kommt fast nur aus der Dateiendung: `AndroidFileTypeDetector.getMimeType` (`AndroidFileTypeDetector.kt:25-33`) ruft `MimeType.guessFromPath` (`MimeTypeConversionExtensions.kt:14-17`) auf, das `guessFromExtension` und `MimeTypeMapCompat` nutzt. Es gibt keine Magic-Byte-Erkennung (TESTER-10). Content-Provider können einen MIME liefern.

Wichtige Zuordnungen in `mime/MimeTypeMapCompat.kt`:

| Endung | MIME | Anmerkung |
|---|---|---|
| jpg, jpeg | image/jpeg | |
| png | image/png | |
| webp | image/webp | |
| heic, heif | image/heic, image/heif | |
| dng | image/x-adobe-dng | |
| arw, cr2, nef, orf, rw2 | image/x-sony-arw usw. | typische RAW-Endungen |
| raw | — | kein Eintrag, fällt auf GENERIC (TESTER-9) |
| svg | image/svg+xml | |
| mp4 | video/mp4 | |
| mkv | video/x-matroska | |
| webm | video/webm | |
| mov | video/quicktime | |
| mp3 | audio/mpeg | |
| m4a | audio/mpeg | eher audio/mp4 (TESTER-8) |
| flac | audio/flac | |
| opus | audio/ogg | |
| wav | audio/x-wav | |
| pdf | application/pdf | |
| docx, xlsx, pptx | Office Open XML | |
| odt | application/vnd.oasis.opendocument.text | |
| txt | text/plain | |
| md, mkd | text/markdown | |
| epub | application/epub+zip | Icon EBOOK, nicht in der Archiv-Liste |
| apk | application/vnd.android.package-archive | zugleich unterstütztes Archiv |
| zip, 7z, rar, tar, tgz, gz | application/zip, x-7z-compressed, vnd.rar, x-tar, x-gtar-compressed, gzip | |

`CommandFiles.KINDS` (`CommandFiles.kt:20-26`) kennt dieselben Familien plus `gif`/`bmp`/`heif` bei Bildern; `.raw` fehlt auch dort. KI-Aktionen sehen nur die oberste Ebene des Quellordners, keine versteckten Dateien.

## Öffnen und Vorschau

Öffnen: `FileListFragment.openFile` (`FileListFragment.kt:1426-1448`). APK zuerst (`openApk`, Installieren oder als Archiv betrachten, Einstellung `OPEN_APK_DEFAULT_ACTION`). Unterstützte Archive werden als Ordner geöffnet (`isListable` / `createArchiveRootPath`). Sonst `ACTION_VIEW` über `FileProvider` (`openFileWithIntent`, `FileListFragment.kt:1486-1514`). Eingebaute Viewer:

- Bilder: `ImageViewerActivity`, Intent-Filter `image/*` (`AndroidManifest.xml:386-393`); die aktuelle Liste wird mitgegeben (`maybeAddImageViewerActivityExtras`, `FileListFragment.kt:1518-1542`).
- PDF: fest auf `PdfViewerActivity` (`FileListFragment.kt:1497-1500`, Manifest 398-405).
- Text inkl. `text/*` und damit `txt`/`md`: `TextEditorActivity` (Manifest 366-381).
- Video/Audio/Office/EPUB: keine eigene Activity, fremde App über `ACTION_VIEW`.

Vorschau (Liste): `FileItem.supportsThumbnail` (`FileItemExtensions.kt:61-81`) und `PathAttributesFetcher` (`PathAttributesFetcher.kt` ab 72). Ja für APK (Package-Icon), Bilder, Video/Audio über `MediaMetadataRetriever` (Audio faktisch oft ohne Frame), PDF ab API 28 bzw. Einstellung. Coil: `ImageDecoderDecoder` ab API 28, sonst `GifDecoder`, plus `SvgDecoder.Factory(false)` (`CoilInitializer.kt:17-37`). Office, EPUB, Text: kein Thumbnail.

## Operationen, die vom Format unabhängig sind

Kopieren, Verschieben, Umbenennen, Papierkorb, Tresor und Sicherung arbeiten auf Bytes bzw. Pfaden, nicht auf dem MIME:

- Copy/Move: `CopyFileJob` / `copyOrMove` (`FileJobs.kt:767`, `1284-1371`), Symlinks mit `NOFOLLOW_LINKS`.
- Rename: `FileJobService.rename` von der Dateiliste (`FileListFragment.kt:1329-1334`).
- Papierkorb: `DeleteFileJob` legt in den Trash, sofern aktiv und `Trash.canTrash` (`FileJobs.kt:977-980`); sonst endgültig.
- Tresor: `VaultStore.add` verschlüsselt den Strom (`VaultStore.kt:31-60`), kein MIME-Filter.
- Sicherung: Backup packt gewählte Ordner als ZIP (`Backup.kt`, `FileJobs.kt:2636`, `Archive.FORMAT_ZIP`).

Für alle unten genannten Formate daher **S** bei Kopieren, Verschieben, Umbenennen, Papierkorb, Tresor, Sicherung — sofern Speicher und Rechte reichen (Gerätetest bei 0-Byte, >4 GiB, Sonderzeichen).

## Archiv packen und entpacken

Packen in der UI (`CreateArchiveDialogFragment.kt:91-93`): **ZIP**, **tar.xz**, **7z**. **RAR packen: N.** **tar.gz packen: N** (nur tar.xz). Beliebige Dateien können in ZIP/7z/tar.xz aufgenommen werden (`ArchiveFileJob`, `FileJobs.kt:643-707`, `ArchiveWriter.kt`).

Entpacken: Copy aus einem Archivpfad (`CopyFileJob`, `isExtract`, `FileJobs.kt:770`). Lesen über libarchive (`ReadArchive.kt`, `ArchiveReader.kt`). In `MimeTypeTypeExtensions.kt:20-48` stehen unter anderem zip, 7z, rar (`application/vnd.rar` und `application/rar`), tar, gzip, gtar-compressed, xz, 7z, cab, iso, apk, jar. **ZIP, 7z, RAR, tar.gz entpacken: S** (libarchive; Passwort über `ArchivePasswordRequiredException`). EPUB ist nicht in dieser Liste, wird nicht als Archiv geöffnet.

Dateinamen in Archiven: UTF-8 oder die Einstellung `ARCHIVE_FILE_NAME_ENCODING` (`ArchiveReader.kt:187-203`). ZIP64: kein explizites Flag (TESTER-12). Zip-Slip `..`: Normalisierung im Archiv-FS (`ArchiveReader.kt:40-57`). Symlink-Slip: TESTER-1.

## Matrix

Spalten: Öffnen | Vorschau | Kopieren | Verschieben | Umbenennen | Papierkorb | In ZIP/7z/tar.xz packen | RAR/tar.gz packen | Als Archiv öffnen/entpacken | Tresor | Sicherung.

| Format | Öffnen | Vorschau | Kp/Mv/Um/PK | Packen ZIP/7z/txz | Packen RAR/tgz | Archiv öffnen | Tresor | Sicherung |
|---|---|---|---|---|---|---|---|---|
| jpg, png, webp | **S** ImageViewer, `image/*` (Fragment 1518, Manifest 391-393) | **S** Coil ImageDecoder/Gif, `isImage` (`FileItemExtensions.kt:75`, `CoilInitializer.kt:28-34`) | **S** | **S** | **N** | — | **S** | **S** |
| heic/heif | **U** ImageViewer `image/*`; Decode typisch ab API 28 (ImageDecoder). Gerätetest C1 | **U** derselbe Decoder. Gerätetest C1 | **S** | **S** | **N** | — | **S** | **S** |
| dng und Kamera-RAW (arw/cr2/nef/orf/rw2) | **U** MIME `image/x-*` → ImageViewer; Decode oft ohne Codec. Gerätetest C2 | **U** | **S** | **S** | **N** | — | **S** | **S** |
| raw (Endung `.raw`) | **F/N** kein MIME, GENERIC (TESTER-9); Öffnen als */* | **U** GENERIC wird als Bild versucht (`PathAttributesFetcher`) | **S** | **S** | **N** | — | **S** | **S** |
| svg | **U** ImageViewer `image/svg+xml`; Coil hat `SvgDecoder` (`CoilInitializer.kt:34`). Gerätetest C3 | **S/U** SvgDecoder in der Liste wahrscheinlich | **S** | **S** | **N** | — | **S** | **S** |
| mp4, mkv, webm, mov | **S** fremde App, `ACTION_VIEW` + video-MIME (Fragment 1486-1514) | **S/U** `VideoFrameFetcher` + Retriever, wenn der Pfad kompatibel ist (`FileItemExtensions.kt:76`) | **S** | **S** | **N** | — | **S** | **S** |
| mp3, flac, opus, wav | **S** fremde App, audio-MIME | **N/U** `isMedia` wahr, Fetcher aber auf `isVideo` begrenzt — typisch kein Wellenform-Thumbnail | **S** | **S** | **N** | — | **S** | **S** |
| m4a | **U** MIME `audio/mpeg` (TESTER-8); Player kann falsch wählen. Gerätetest C4 | **N/U** wie Audio | **S** | **S** | **N** | — | **S** | **S** |
| pdf | **S** eigener `PdfViewerActivity` (Fragment 1497-1500) | **S** `PdfPageFetcher`, ab API 28 bzw. Einstellung (`FileItemExtensions.kt:77-79`) | **S** | **S** | **N** | — | **S** | **S** |
| docx, xlsx, pptx, odt | **S** fremde App, Office-MIME | **N** kein Thumbnail-Zweig | **S** | **S** | **N** | — | **S** | **S** |
| txt, md | **S** `TextEditorActivity`, `text/*` (Manifest 381) | **N** kein Thumbnail | **S** | **S** | **N** | — | **S** | **S** |
| epub | **S** fremde App, `application/epub+zip` | **N** Icon EBOOK, kein Fetcher | **S** | **S** | **N** | **N** nicht in `supportedArchiveMimeTypes` | **S** | **S** |
| apk | **S** Dialog Installieren oder als Archiv (`openApk`, Fragment 1451-1458) | **S** App-Icon, `isApk` (`FileItemExtensions.kt:74`) | **S** | **S** | **N** | **S** APK steht in der Archiv-MIME-Liste (`MimeTypeTypeExtensions.kt:32`) | **S** | **S** |
| zip | **S** als Ordner (`isListable`) | Icon Archiv | **S** | — | — | **S** libarchive | **S** | **S** (Backup schreibt ZIP) |
| 7z | **S** als Ordner | Icon Archiv | **S** | — | — | **S** | **S** | **S** |
| rar | **S** als Ordner | Icon Archiv | **S** | — | **N** packen | **S** entpacken | **S** | **S** |
| tar.gz / tgz | **S** MIME gzip / gtar-compressed in der Archiv-Liste | Icon Archiv | **S** | **N** packen (UI: tar.xz) | — | **S** entpacken | **S** | **S** |

## Randfälle

| Fall | Befund | Beleg |
|---|---|---|
| Dateien > 4 GiB, Int-Überlauf Größe/Fortschritt | Copy/Move/Extract-Benachrichtigung skaliert, wenn `size > Int.MAX_VALUE` (`FileJobs.kt:407-416`). `FileSize.formatInBytes` nicht (TESTER-11). Scan/Transfer nutzen `Long`. | TESTER-11, Gerätetest B1 |
| ZIP64 | Schreiben: keine Option gesetzt (TESTER-12). Lesen: libarchive. | `WriteArchive.kt`, Gerätetest B1 |
| 0 Byte | Kein Sonderpfad; Copy/Rename sollten gehen. Vorschau/Öffnen abhängig vom Decoder. | Gerätetest B2 |
| Ohne Endung | MIME GENERIC, keine Magic Bytes (TESTER-10). | `MimeTypeConversionExtensions.kt:14-17` |
| Sonderzeichen, Emoji, Umlaute, NFC/NFD | Java/`Path` sind Unicode-fähig; KI-`resolve` unterscheidet NFC/NFD (TESTER-6). Archiv-Namen: Charset-Einstellung, UTF-8-Pfad (`ReadArchive.kt:36, 148`). | TESTER-6, Gerätetest B3 |
| Sehr lange Pfade | Keine eigene Kürzung im App-Code sichtbar; Grenze ist das Dateisystem (oft 4096). | Gerätetest B4 |
| Versteckte Dateien | Dateiliste: `FILE_LIST_SHOW_HIDDEN_FILES` (`Settings.kt:53`, `FileListFragment.kt:837`). KI: niemals (`CommandFiles.kt:133`, `CommandCheck.childDirs` überspringt `.` und `Android`). | |
| Symlinks, Schleifen, Ausbruch, Zip-Slip | Copy/Move mit `NOFOLLOW_LINKS`. `walkFileTree` folgt standardmäßig nicht. Entpacken: TESTER-1 (Eltern-Symlink). `ArchiveReader` normalisiert `..`, ohne `startsWith` nach der Normalisierung. `resolveForeign` gibt absolute `other` unverändert zurück (`PathExtensions.kt:328-329`). | TESTER-1, Gerätetest A12, B5 |

## Gerätetest nötig (nicht geraten)

HEIC-Decode und -Vorschau je API-Stand; RAW/DNG-Anzeige; SVG im ImageViewer; m4a mit typischen Playern; 4-GiB-Copy und ZIP64-Schreiben; 0-Byte-Öffnen; NFC/NFD-Umbenennen und -Suche; lange Pfade; Entpacken von `zip-slip.zip` und eines Archivs mit Symlink-Eltern; TalkBack/große Schrift (siehe `GERAETETEST.md`).
