# Recherche: Open-Source-Dateimanager für Android (Stand 14.11.2026)

Quelle: GitHub-API (`gh api repos/...`, `search/repositories`), README-Feature-Listen, LICENSE-Dateien.

## Apps

| Repo | Lizenz | Sterne | Letzter Push | Stärke (laut README) |
|---|---|---|---|---|
| zhanghai/MaterialFiles | GPL-3.0 | 9476 | 2026-10-15 | NIO2-Dateisystem-API, Linux-Syscalls statt `ls`-Parser, Root, Archive (lesen/entpacken/erstellen), FTP/SFTP/SMB/WebDAV, Symlinks/Rechte/SELinux, Themes |
| TeamAmaze/AmazeFileManager | GPL-3.0 | 6467 | 2026-11-12 | Tabs, App-Manager (Backup/Deinstallation), Verlauf/Lesezeichen, Root, AES-Verschlüsselung, Cloud (Plugin), DB-/ZIP-/RAR-/APK-/Text-Reader |
| FossifyOrg/File-Manager | GPL-3.0 | 1874 | 2026-11-09 | App-/Ordner-Sperre (PIN/Muster/Fingerabdruck), Speicheranalyse, Homescreen-Verknüpfungen, Favoriten, SD/USB, Root, offline |
| Raival-e/Prism-File-Explorer | GPL-3.0 | 1388 | 2026-10-31 | Jetpack Compose + M3, Tabs, Bild/Video/Audio/PDF-Viewer, Texteditor mit Syntax-Highlighting, ZIP |
| d4rken-org/butler | GPL-3.0 (Icons/Assets/Doku **nicht** frei) | 142 | 2026-11-13 | Browser-artige Tabs, Kopiertempo-Graph, Papierkorb, Regex-Suche in Dateien, Editor für riesige Dateien, App-Manager, Root + Shizuku, Tablet-Mehrspalten |
| CluvexStudio/Strata | **AGPL-3.0** | 164 | 2026-10-16 | Zwei Spalten + Drag&Drop, 7z/RAR/ZST, APK-Signieren/Klonen, Hex-Editor, FTP-Server/WLAN-Teilen, Tresor mit Fingerabdruck |
| 1hakr/AnExplorer | Apache-2.0 | 2056 | 2026-06-02 | Ordnergrößen, Android TV, RTL, Barrierefreiheit, als Dateiauswahl für andere Apps |
| SimpleMobileTools/Simple-File-Manager | GPL-3.0 | 1584 | 2024-06-11 | tot, Nachfolger = Fossify |
| AbdurazaaqMohammed/MP-Manager | GPL-3.0 | 562 | 2026-11-12 | Zwei Spalten, APK-Fokus |
| pgp/XFiles | LGPL-3.0 | 112 | 2025-09-12 | Root-Explorer |
| plainhub/plain-app | AGPL-3.0 | 7194 | 2026-11-14 | Handy-Verwaltung per Browser (Idee für Web-Zugriff) |

## Bibliotheken

| Repo | Lizenz | Zweck |
|---|---|---|
| RikkaApps/Shizuku | Apache-2.0 | `Android/data`, `obb` ohne Root |
| topjohnwu/libsu | Apache-2.0 | Root-Shell |
| hierynomus/smbj | Apache-2.0 (LICENSE-Text; API meldet NOASSERTION) | SMB 2/3 |
| hierynomus/sshj | Apache-2.0 | SFTP |
| apache/commons-compress | Apache-2.0 | ZIP/TAR/GZ/XZ/7z |
| junrar/junrar | unRAR-Lizenz | RAR nur **entpacken**, Erstellen verboten |
| omicronapps/7-Zip-JBinding-4Android | LGPL-2.1 (+ unRAR) | 7z/RAR nativ |

## Lizenz-Folge

- Alle starken Apps sind **GPL-3.0**. Wer Code übernimmt, muss die eigene App unter GPL-3.0 veröffentlichen
  (Quellcode offen, Play-Store-Verkauf erlaubt, Hinweise auf Original-Autoren Pflicht).
- **AGPL (Strata, PlainApp) nur als Ideenquelle**, kein Code: würde die Lizenz verschärfen.
- Butler: Code GPL, aber Icons/Grafiken/Texte nicht übernehmen.
- Apache-Bibliotheken sind mit GPL-3.0 verträglich.

## Samsung „Eigene Dateien" – Pflichtumfang

Kategorien (Bilder, Videos, Audio, Dokumente, Downloads, APKs), direktes Öffnen unter „Zuletzt verwendet“ mit
Hinweis bei nicht mehr vorhandenen Dateien, Speicher analysieren
(große / doppelte / ungenutzte Dateien), Papierkorb, Cloud (OneDrive/Google Drive), Netzwerkspeicher (FTP/SFTP/SMB),
USB/SD, ZIP packen/entpacken, Favoriten, Homescreen-Verknüpfungen, Suche, versteckte Dateien, Liste/Raster/Sortierung,
Details, Teilen, „Öffnen mit".

## Mehr als Samsung (aus den Repos)

Root + Shizuku, 7z/RAR/TAR/ZST, WebDAV, Tabs + Zwei-Spalten mit Drag&Drop, Tresor/App-Sperre mit Fingerabdruck,
AES-Verschlüsselung, App-Manager inkl. APK-Export/Split-APK-Installation, Regex-/Inhaltssuche, Texteditor für
große Dateien + Hex-Editor, Viewer (Bild/Video/Audio/PDF), FTP-Server/WLAN-Teilen, Kopiertempo-Anzeige,
Ordnergrößen, Symlinks/Rechte, Tablet-Mehrspalten.

## Entscheidung

Basis = **Fork von MaterialFiles** (GPL-3.0). Grund: technisch sauberster Kern (NIO2-Provider, Syscalls, NAS,
Archive, Root) – darauf lassen sich die Funktionen der anderen Apps als weitere Provider/Screens aufsetzen.
Ergänzungen: Papierkorb/Regex-Suche/Shizuku (Butler), App-Sperre/Speicheranalyse/Shortcuts (Fossify),
App-Manager/AES (Amaze), Viewer/Editor (Prism), Zwei-Spalten/FTP-Server/Tresor nur als Idee (Strata).
Annahme: eigene App wird Open Source unter GPL-3.0.
