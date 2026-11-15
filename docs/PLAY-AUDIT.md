# Play-Prüfung für Ordo 0.7.0

Stand: 15. November 2026. Geprüft: Branch `feat/restore` (Ordo 0.7.0, `versionCode` 15, Paket `com.beko2210.ordo`).
Grundlage: `app/src/main/AndroidManifest.xml`, `app/build.gradle`, der Quelltext und das zusammengeführte Manifest des
Beta-Builds 0.7.0 auf pop-os (`processBetaManifest`, gebaut am 15.11.2026 um 16:24). Werkzeug: Skill
`play-policy-insights` (Ergebnis: keine kritischen oder wichtigen Verstöße) und Prüfung von Hand.
Die Hintergründe und Quellen zu jeder Berechtigung stehen in [PLAY-BERECHTIGUNGEN.md](PLAY-BERECHTIGUNGEN.md).
Diese Datei ist die Liste zum Abhaken.

Status: **ok** = nichts zu tun. **Play Console** = Belkis trägt etwas ein. **Änderung** = Code oder Text muss sich ändern.

## Übersicht

| # | Anforderung | Status |
| --- | --- | --- |
| 1 | Ziel-API: neue Apps und Updates brauchen ab 31.08.2026 API 36 | **ok**: `targetSdk 36` |
| 2 | `android:exported` bei jeder Komponente mit Intent-Filter | **ok**: alle gesetzt |
| 3 | `MANAGE_EXTERNAL_STORAGE` (Zugriff auf alle Dateien) | **Play Console**: Formular, Text A |
| 4 | `QUERY_ALL_PACKAGES` (installierte Apps sehen) | **Play Console**: Formular, Text B |
| 5 | `REQUEST_INSTALL_PACKAGES` (APK installieren) | **Play Console**: Formular, Text C |
| 6 | Vordergrunddienste: `dataSync` (Dateiaufträge), `specialUse` (FTP-Server) | **Play Console**: Formular + 2 Videos, Texte D und E |
| 7 | `REQUEST_DELETE_PACKAGES`, `INSTALL_SHORTCUT`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, Netz | **ok**: kein Formular |
| 8 | Unverschlüsselter Verkehr (`usesCleartextTraffic`) | **ok**: nötig für FTP und HTTP-WebDAV zu eigenen Servern |
| 9 | Datensicherheit (Data safety) | **Play Console**: „keine Daten erhoben“, Abschnitt F |
| 10 | Datenschutzerklärung stimmt mit dem Code überein | **Änderung**: Adresssuche für Foto-Orte fehlt, Abschnitt G |
| 11 | Konten und Kontolöschung | **ok**: Ordo hat keine Konten |
| 12 | App-Zugriff für Prüfer (Login-Daten) | **Play Console**: „ohne Einschränkung nutzbar“ |
| 13 | Familien-Richtlinie | **Play Console**: Zielgruppe 18+, dann gilt sie nicht |
| 14 | Werbung | **Play Console**: „Keine Werbung“ |
| 15 | Download von Code (KI-Modell) | **ok**: das Modell sind Daten, kein Code |
| 16 | 16-KB-Speicherseiten (Pflicht ab 01.02.2027) | **ok**: `-Wl,-z,max-page-size=16384` in `llm/src/main/cpp/CMakeLists.txt`, Prüfung mit `utils/check-16k.sh` |
| 17 | Käufe und Bezahlinhalte | **ok**: Ordo ist ganz kostenlos, ohne In-App-Kauf und ohne Werbung |
| 18 | Store-Eintrag nennt den Dateimanager als Kernfunktion | **Änderung**: Store-Texte im Repo sind noch die von Material Files, Abschnitt I |

## 1. Ziel-API

`targetSdk 36`, `minSdk 23`, `compileSdk 37` (`app/build.gradle`). Das zusammengeführte Manifest des Beta-Builds zeigt
`targetSdkVersion="36"`. Quelle, gelesen am 15.11.2026: [Target API level](https://developer.android.com/google/play/requirements/target-sdk),
„Starting August 31 2026: New apps and app updates must target Android 16 (API level 36) or higher“.

## 2. Exportierte Komponenten

Alle 10 Komponenten mit Intent-Filter setzen `android:exported` ausdrücklich (9 Activities, Kachel-Dienst). Die übrigen
haben keinen Filter und sind damit nicht exportiert. Von außen erreichbar ohne Filter sind nur Bibliotheksteile mit
Schutzrecht: `rikka.shizuku.ShizukuProvider` (`INTERACT_ACROSS_USERS_FULL`) und
`androidx.profileinstaller.ProfileInstallReceiver` (`DUMP`), beide nur für System und Shell. Keine Änderung nötig.

Berechtigungen, die erst Bibliotheken ins zusammengeführte Manifest bringen: `USE_BIOMETRIC`, `USE_FINGERPRINT`
(Tresor-Sperre), `moe.shizuku.manager.permission.API_V23` (Shizuku), `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (androidx).
Keine davon braucht ein Formular.

## 3. Texte für die Berechtigungsformulare

Ort: Play Console → App-Inhalte → Sensible Berechtigungen und APIs. Voraussetzung für A und B: Die Store-Beschreibung
nennt „Dateimanager“ als Kernfunktion.

**A. Zugriff auf alle Dateien (`MANAGE_EXTERNAL_STORAGE`)**
Kurz: Ordo ist ein Dateimanager und muss Ordner außerhalb des eigenen App-Ordners verwalten. Der Nutzer erteilt das in
den Android-Einstellungen und kann es ablehnen.
Kernzweck im Formular: „Dateiverwaltung“ (File management).

```
Ordo is a file manager. Its core function is to browse, open, copy, move, rename, compress and delete files and folders anywhere on shared storage, including folders outside the app's own directory. The Storage Access Framework and MediaStore cannot do this, because the user manages whole directory trees across all volumes, not single documents or media files. Ordo checks Environment.isExternalStorageManager() and sends the user to the system "All files access" screen. The user can refuse. File contents never leave the device unless the user copies them to a server they set up or shares them with another app.
```

**B. Installierte Apps sehen (`QUERY_ALL_PACKAGES`)**
Kurz: Ordo zeigt alle installierten Apps, sichert ihre APKs, startet die Deinstallation und zeigt, welcher App eine
Datei gehört. Die Liste bleibt auf dem Gerät.

```
Ordo is a file manager. It lists every installed app so the user can see each app's APK files, save those APKs to Download/Ordo APKs, uninstall an app, and see which app owns a file in the file permissions screen. The home screen shows how many apps are installed. A fixed <queries> list cannot show apps the user installs later. The app inventory stays on the device: Ordo has no server, no ads and no analytics, and the list is never sold or shared.
```

**C. Pakete installieren (`REQUEST_INSTALL_PACKAGES`)**
Kurz: Tippt der Nutzer auf eine APK-Datei, übergibt Ordo sie an den Android-Installer. Ordo aktualisiert sich nicht
selbst und lädt keine APKs herunter.

```
Ordo is a file manager. When the user taps an APK file in the file list or inside an archive, Ordo hands it to the system package installer (ACTION_INSTALL_PACKAGE). The user confirms every installation in the system dialog. Ordo never installs packages silently, never updates itself and does not download APKs.
```

## 4. Vordergrunddienste (`dataSync`, `specialUse`)

Ort: Play Console → App-Inhalte → Vordergrunddienste. Je Dienst Beschreibung, Auswirkung und ein Video.
`FileJobService` läuft als `dataSync`: ab Android 15 höchstens 6 Stunden am Tag im Hintergrund, danach bricht der
Auftrag mit Benachrichtigung ab. `FtpServerService` bleibt `specialUse`. Begründung und Risiko stehen in
[PLAY-BERECHTIGUNGEN.md](PLAY-BERECHTIGUNGEN.md), Abschnitt 7.

**D. `FileJobService`, Dateiaufträge**
Kurz: Läuft, solange ein vom Nutzer gestarteter Auftrag (Kopieren, Verschieben, Löschen, Packen, Entpacken) dauert,
mit sichtbarer Benachrichtigung.

Typ `dataSync`, Anwendungsfall „Local processing: Other“. Beschreibung:
```
FileJobService runs a file operation the user started in Ordo: copy, move, delete, archive, backup or restore, also to or from a network folder the user added. A notification shows the progress until the operation finishes or the user cancels it.
```
Auswirkung, wenn Android den Dienst stoppt:
```
The operation stops part way. Files already copied stay, the rest is missing, and the user has to start the operation again.
```
Video: Ordner lange antippen → Kopieren → Ziel wählen → Einfügen → Benachrichtigung mit Fortschritt zeigen.

**E. `FtpServerService`, FTP-Server**
Kurz: Läuft, solange der Nutzer den FTP-Server eingeschaltet hat, mit Adresse und Stopp-Knopf in der Benachrichtigung.

Beschreibung:
```
The user switches on Ordo's FTP server to share files with other devices on the local network. It runs until the user stops it. A notification shows the server address and a stop action the whole time. The user expects the server to keep running longer than the 6-hour background limit of dataSync, so the service uses specialUse.
```
Auswirkung:
```
Other devices on the network lose access to the files until the user switches the FTP server on again.
```
Video: Menü → FTP-Server → Einschalten → Benachrichtigung mit Adresse und „Stopp“ zeigen → Stopp antippen.

## 5. Weitere Berechtigungen ohne Formular

- `REQUEST_DELETE_PACKAGES`: Deinstallieren aus der App-Liste (`apps/AppsFragment.kt`, `ACTION_UNINSTALL_PACKAGE`).
  Android fragt jedes Mal nach.
- `INSTALL_SHORTCUT`: Ordner-Verknüpfung auf dem Startbildschirm. Nötig für Android 6 und 7 (`minSdk 23`), ab Android 8
  ersetzt `ShortcutManagerCompat` sie.
- `POST_NOTIFICATIONS`: ab Android 13 zur Laufzeit erfragt, mit Begründungsdialog (`FileListFragment`).
- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `WAKE_LOCK`: eigene Server, FTP-Server, KI-Modell-Download.
- `WRITE_EXTERNAL_STORAGE`: nur bis Android 12L (`maxSdkVersion 32`).

## F. Datensicherheit (Data safety)

Ort: Play Console → App-Inhalte → Datensicherheit. Antworten:

| Frage | Antwort |
| --- | --- |
| Erhebt oder teilt die App erforderliche Nutzerdaten? | **Nein** |
| Link zur Datenschutzerklärung | `https://beko2210.github.io/ordo/datenschutz.html` (am 15.11.2026 erreichbar, HTTP 200) |

Begründung, an den Definitionen von [Google](https://support.google.com/googleplay/android-developer/answer/10787469)
gemessen (gelesen am 15.11.2026):

- Kein eigener Server, kein Analyse-, Absturz- oder Werbe-SDK (`app/build.gradle`), kein Konto. Der Firebase-Startdienst
  ist im Manifest entfernt.
- Dateien, App-Liste, Einstellungen und Server-Zugänge werden nur auf dem Gerät verarbeitet. Das zählt nicht als Erhebung.
- Kopieren auf eigene Server, Teilen mit anderen Apps und das Backup über die Dateiauswahl startet der Nutzer selbst und
  rechnet damit. Google nimmt solche Übertragungen ausdrücklich aus.
- KI-Modell: Ordo lädt eine feste Datei von Hugging Face, erst wenn der Nutzer die KI einrichtet. Ordo sendet dabei keine
  Nutzerdaten und wertet die IP-Adresse nicht aus. Google verlangt eine IP-Angabe nur, wenn die App sie selbst erhebt,
  etwa für den Standort.

Englischer Kurztext, falls die Konsole eine Erklärung erlaubt:
```
Ordo has no server, no account, no ads and no analytics. Files, the app list and settings are processed only on the device. Transfers happen only when the user copies files to their own server or shares them with another app.
```

## G. Datenschutzerklärung: eine Lücke

Öffnet der Nutzer die Eigenschaften eines Fotos oder Videos mit GPS-Daten, fragt Ordo den Android-Geocoder nach der
Adresse (`fileproperties/image/FilePropertiesImageTabFragment.kt`, Zeile 92 bis 101, gleiche Stelle im Video-Tab).
Auf den meisten Geräten schickt Android die Koordinaten dafür an Google. Die Datenschutzerklärung sagt aber: „Ordo baut
von sich aus keine Verbindung ins Internet auf.“

Für das Data-safety-Formular ändert das nach unserer Lesart nichts: Ordo erhebt die Koordinaten nicht selbst, das
erledigt ein Android-Dienst. Die Erklärung sollte es aber nennen. Nicht hier geändert, weil der Rechtstext Belkis'
Entscheidung ist.

Vorschlag, neuer Absatz unter „Daten auf deinem Gerät“ in `app/src/main/res/values-de/legal.xml`:
```
Öffnest du die Eigenschaften eines Fotos oder Videos mit Ortsangabe, fragt Ordo Android nach der passenden Adresse. Dafür sendet Android die Koordinaten meist an Google. Ordo selbst speichert und sendet sie nicht.
```
Englisch in `app/src/main/res/values/legal.xml`:
```
When you open the properties of a photo or video that contains a location, Ordo asks Android for the matching address. Android usually sends the coordinates to Google for this. Ordo itself does not store or send them.
```
Danach `utils/legal-pages.py` laufen lassen (sonst schlägt `LegalTextsTest` fehl). Alternative ohne Textänderung: die
Adresssuche entfernen und nur die Koordinaten zeigen.

## Konten, App-Zugriff, Zielgruppe, Werbung

- **Konten (11):** Ordo legt keine Konten an. Zugangsdaten für eigene FTP-, SFTP-, SMB- und WebDAV-Server bleiben auf dem
  Gerät. Keine Lösch-URL nötig ([Kontolöschung](https://support.google.com/googleplay/android-developer/answer/13327111)).
- **App-Zugriff (12):** „Alle Funktionen sind ohne besonderen Zugriff verfügbar“ wählen. Keine Prüfer-Zugangsdaten.
- **Zielgruppe (13):** nur „18 und älter“ wählen. Damit gilt die Familien-Richtlinie nicht. Frage „Spricht die App Kinder
  an?“: Nein.
- **Werbung (14):** „Nein, meine App enthält keine Werbung.“
- **Einstufung:** Fragebogen als Kategorie „Dienstprogramm, Produktivität“. Keine Gewalt, kein Glücksspiel, kein
  Austausch zwischen Nutzern.

## 15. KI-Modell und Root

Das Modell (Qwen3.5-4B, 2 583 221 408 Bytes) ist eine Datendatei, festgelegt auf eine Revision und per SHA-256 geprüft
(`ai/CommandModel.kt`). Ordo lädt keinen ausführbaren Code nach. Root und Shizuku sind optional. Sie wirken nur, wenn der Nutzer sie auf dem
Gerät eingerichtet und Ordo dort freigegeben hat (Einstellung „Root“, Standard „automatisch“).

## I. Store-Texte

`fastlane/metadata/android/en-US/title.txt` heißt noch „Material Files“, Kurz- und Langbeschreibung sind der Text des
Originalprojekts. `de-DE` fehlt. So nicht hochladen: Ein fremder App-Name im Store gilt als irreführend. Die
Roadmap-Zelle „Store-Texte Deutsch und Englisch“ ist offen. Die neuen Texte müssen „Dateimanager“ / „file manager“ als
Kernfunktion nennen, sonst fehlt die Grundlage für die Formulare A und B.

## Nicht geprüft

- Ob Google `specialUse` für den FTP-Server annimmt. Das entscheidet die Prüfung.
- Das zusammengeführte Manifest des Release-Bundles (geprüft wurde der Beta-Build mit gleichem Quellstand).
- Welche Daten der Android-Geocoder genau an Google sendet.
- Die Antwort-Schalter der Data-safety-Seite nach „Nein“. Geprüft ist nur die Regel.
