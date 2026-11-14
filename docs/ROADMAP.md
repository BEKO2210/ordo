# Ordo – Roadmap

Ziel: kostenloser Dateimanager im Play Store, alles von Samsung „Eigene Dateien" plus mehr. Open Source (GPL-3.0),
keine Werbung, kein Tracking. Basis: MaterialFiles (Kotlin, Views/ViewBinding, LiveData, NIO2-Provider).

Regeln für alle Bauaufträge: bestehende Muster von MaterialFiles nutzen (ViewModel + LiveData, `java8.nio.file.Path`,
`FileJob` für Dateioperationen, Settings über `Settings.kt`). Neue Strings in `values/strings.xml` (Englisch) und
`values-de/strings.xml` (Deutsch). Kein neues UI-Framework. Jeder Schritt baut mit `~/ordo-team/build.sh`.

## Schon vorhanden (MaterialFiles)
Ordner-Navigation mit Brotkrumen, Lesezeichen, Root + Shizuku, ZIP/TAR/7z (lesen, entpacken, erstellen),
FTP/SFTP/SMB/WebDAV, FTP-Server inkl. Schnellkachel, SAF-Ordner, Suche nach Namen, Sortierung, Liste/Raster,
versteckte Dateien, Homescreen-Verknüpfungen, Bildbetrachter, Texteditor, APK-Infos, Eigenschaften, Rechte/Symlinks,
Themes inkl. Schwarz.

## Phase 1 – Samsung-Gleichstand
- [x] P1.1 Startseite: Kategorien (Bilder, Videos, Audio, Dokumente, Downloads, APKs, Archive) über MediaStore mit
      Anzahl und Größe, „Zuletzt verwendet", Speicher-Übersicht je Laufwerk, Schnellzugriff auf Lesezeichen.
- [x] P1.2 Papierkorb: Löschen verschiebt in `.Trash` des Laufwerks (eigene Metadaten), Wiederherstellen,
      endgültig löschen, automatisch leeren nach 30 Tagen, Einstellung zum Abschalten.
- [x] P1.3 Speicheranalyse: große Dateien, Duplikate (Größe → Hash), lange ungenutzte Dateien, alte APKs;
      Auswahl und Löschen in den Papierkorb.

## Phase 2 – Mehr als Samsung
- [ ] P2.1 Tabs + Zwei-Spalten-Ansicht (Tablet/Querformat) mit Kopieren/Verschieben zwischen den Spalten.
- [x] P2.2 Inhaltssuche (Text in Dateien, Regex), Filter nach Typ/Größe/Datum.
- [x] P2.3 Tresor: AES-256-GCM-Verschlüsselung von Dateien (Tink), Entsperren per Fingerabdruck/Bildschirmsperre.
      Offen: App-Sperre, Schlüssel an Entsperrung binden (braucht erst Sicherung/Export).
- [x] P2.4 App-Manager: installierte Apps, APK sichern, Split-APK/XAPK installieren, Deinstallieren, App-Infos.
- [x] P2.5 Kopiertempo + Restzeit (Ordnergrößen stehen in der Speicheranalyse).
- [ ] P2.6 Medien: eingebauter Audio-/Video-Player und PDF-Ansicht (Plattform-APIs, keine großen Bibliotheken).

## Phase 2b – aus dem Wettbewerbsvergleich (docs/WETTBEWERB.md)
- [x] Beschriftete Auswahlleiste unten, „Kopieren/Verschieben nach…", Umbenennen in der Auswahl.
- [x] Leere Systemordner im Schnellzugriff ausblenden.
- [x] Mehrere Dateien umbenennen (Muster mit Nummer).
- [x] PDF-Ansicht eingebaut (PdfRenderer).
- [x] Startseite: Suchfeld, Speicherkarte mit Prozent, Glas-Leiste oben; Auswahlleiste als Glas (BlurView).
- [x] Sicherung auf USB-Stick oder anderen Ort (Drive über die Systemauswahl) als ZIP, optional AES-256,
      inkl. Tresor. Offen: automatische Sicherung nach Zeitplan, Wiederherstellen per Knopf.
- [ ] Datenmüll: leere Ordner, Thumbnail-Caches, Restdateien in der Speicheranalyse.
- [ ] Ungenutzte Apps (Nutzungsdaten-Erlaubnis), Erinnerung „Speicher fast voll".
- [ ] Teilen in der Nähe (Quick Share über Android-Teilen).
- [ ] Cloud (Drive/OneDrive) – braucht eigene OAuth-Zugänge (Belkis).

## Phase 3 – Play Store
- [ ] P3.1 Eigenes Icon, Farben, Name final; About-Seite: Ordo + Dank an MaterialFiles; Datenschutz-Link eigener Text.
- [ ] P3.2 Datenschutzerklärung (keine Daten erhoben), Store-Texte DE/EN, Screenshots, Feature-Grafik.
- [ ] P3.3 Begründungen für `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES`,
      Foreground-Service `specialUse` (Dateimanager = erlaubter Kernzweck).
- [ ] P3.4 Release-Signatur, `bundleRelease`, 16-KB-Seiten-Prüfung der nativen Bibliothek, CI nur bei PR.
- [ ] P3.5 Geschlossener Test (12 Tester, 14 Tage) – Belkis; danach Produktion. Repo öffentlich machen (GPL).
