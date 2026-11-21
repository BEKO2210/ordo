# Play Console: Datensicherheit und Altersfreigabe

Stand: 15. November 2026, Code von Ordo 0.7.1 (`main`). Antworten zum Abtippen in die Play Console, jede
mit Beleg im Code. Hintergrund zu den Berechtigungen steht in [PLAY-BERECHTIGUNGEN.md](PLAY-BERECHTIGUNGEN.md).

Regeln von Google, gelesen am 15. November 2026 in
[Data safety](https://support.google.com/googleplay/android-developer/answer/10787469):

- **Erheben** heißt: Daten verlassen über die App das Gerät. Was nur auf dem Gerät verarbeitet wird, gibt man nicht an.
- **Teilen** heißt: erhobene Daten gehen an Dritte. Keine Weitergabe ist eine Aktion, die der Nutzer selbst auslöst
  und erwartet.
- Lädt der Nutzer Daten in sein eigenes Konto (Beispiel Drive, Dropbox) und die App liest sie dabei nicht selbst
  aus, ist das keine Erhebung durch die App.
- IP-Adressen gibt man an, je nachdem, wofür die App sie nutzt (Beispiel: Standort bestimmen).
- Auch eine App, die nichts erhebt, füllt das Formular aus und verlinkt eine Datenschutzerklärung.

## 1. Datensicherheit: Antworten

| Frage im Formular | Antwort |
| --- | --- |
| Erhebt oder teilt deine App erforderliche Nutzerdatentypen? | **Nein** |
| Datenschutzerklärung | `https://beko2210.github.io/ordo/privacy.html` (deutsch: `…/datenschutz.html`) |
| Konten | Ordo legt keine Konten an. Kein Löschweg für Konten nötig |

Mit „Nein“ entfallen die Fragen zu Verschlüsselung beim Transport und zu Löschanfragen; Google stellt sie nur nach
einem „Ja“.

## 2. Warum „Nein“: jeder Netzwerkweg im Code

Ordo hat `INTERNET`, `ACCESS_NETWORK_STATE` und `ACCESS_WIFI_STATE` (`app/src/main/AndroidManifest.xml`). Diese
Wege nutzen das Netz:

| Vorgang | Wer löst aus | Wohin | Im Code | Einordnung |
| --- | --- | --- | --- | --- |
| KI-Modell laden | Nutzer richtet KI-Befehle ein | Hugging Face, feste Adresse | `ai/CommandModel.kt`: feste URL, `DownloadManager`, SHA-256-Prüfung | Keine Erhebung, siehe unten |
| FTP-, SFTP-, SMB-, WebDAV-Client | Nutzer trägt Server ein | Server des Nutzers | `provider/ftp`, `sftp`, `smb`, `webdav` | Eigenes Ziel des Nutzers, keine Erhebung |
| Eingebauter FTP-Server | Nutzer startet ihn | Geräte im eigenen Netz | `ftpserver/FtpServerService.kt` | Vom Nutzer ausgelöst, keine Erhebung |
| Teilen, Öffnen mit | Nutzer | andere App auf dem Gerät | `Intent.ACTION_SEND`, `ACTION_VIEW` | Vom Nutzer ausgelöst, keine Weitergabe |
| Sicherung | Nutzer wählt Ziel | USB-Stick oder Speicherort im System-Dialog | `backup/BackupFragment.kt` (`ACTION_CREATE_DOCUMENT`) | Die gewählte App überträgt, Ordo liest nichts aus |
| Links in „Über“ | Nutzer tippt | Browser | `about/AboutFragment.kt` | Verlässt die App |

**Modell-Download.** Die Anfrage enthält nur die feste Adresse des Modells, keine Befehle, Dateinamen oder
Gerätedaten. Hugging Face sieht technisch die IP-Adresse, Ordo liest, speichert und nutzt sie nicht. Nach der
IP-Regel oben ist deshalb nichts anzugeben. Die Datenschutzerklärung nennt den Download und Hugging Face trotzdem.

**Keine Analyse, keine Werbung, kein Absturzbericht.** In `app/build.gradle`, `llm/build.gradle` und
`build.gradle` steht keine Abhängigkeit mit `firebase`, `crashlytics`, `sentry`, `bugsnag`, `analytics`, `admob`,
`appcenter` oder `acra`. Einen `FirebaseInitProvider` entfernt das Manifest (`tools:node="remove"`). Die Lizenzanzeige zeigt lokales HTML (`ui/LicensesDialogExtensions.kt`,
`loadDataWithBaseURL(null, …)`).

**KI-Befehle.** Das Sprachmodell läuft auf dem Gerät (`llm/`, llama.cpp). Befehle und Dateinamen werden nur dort
verarbeitet und fallen damit nicht unter „Erheben“.

**Adresse zu Foto-Koordinaten.** Ordo fragt den Android-Geocoder nur nach dem Tipp auf „Adresse anzeigen“ in den Eigenschaften
(`fileproperties/FilePropertiesTabFragment.kt`). Den Versand übernimmt der Systemdienst, nicht Ordo; die Antworten oben ändern sich nicht.

## 3. Was Ordo auf dem Gerät speichert

Nicht anzugeben, hier nur zur Kontrolle:

- Einstellungen, Lesezeichen und eingetragene Server samt Passwörtern (SharedPreferences)
- Tresor: Dateien mit AES-256-GCM verschlüsselt, Schlüssel im Android Keystore; die Bildschirmsperre prüft Android,
  Ordo erfährt nur das Ergebnis. Geöffnete Tresor-Dateien werden beim Lesen im Speicher entschlüsselt
  (`vault/VaultProvider`), nie als Klartext-Kopie abgelegt; Sperren schließt jeden noch offenen Zugriff
- Papierkorb (30 Tage) und das KI-Modell (`CommandModel.file`, im App-Ordner)

Nichts davon geht in Cloud-Backups oder den Geräteumzug: `res/xml/data_extraction_rules.xml` (ab Android 12) und
`res/xml/backup_rules.xml` (bis Android 11) schließen Einstellungen, App-Dateien, Datenbanken und den externen App-Speicher aus.

## 4. Keine Käufe

Ordo ist ganz kostenlos: kein In-App-Kauf, kein Abo, keine Werbung. Im Code steht keine Abrechnungs-Bibliothek und
keine Berechtigung `com.android.vending.BILLING`. Zahlungs- und Kaufdaten gibt es darum nicht anzugeben.

## 5. Altersfreigabe (IARC-Fragebogen)

Google nennt die einzelnen Fragen nicht öffentlich ([Hilfe](https://support.google.com/googleplay/android-developer/answer/9859655)).
Die Antworten stehen deshalb je Themenbereich; den genauen Wortlaut zeigt erst die Console. Kategorie: die
allgemeine für Dienstprogramme und andere Apps (kein Spiel, keine soziale App).

| Thema | Antwort | Grund |
| --- | --- | --- |
| Gewalt, Angst, Horror | Nein | Ordo enthält keine solchen Inhalte |
| Sexualität, Nacktheit | Nein | Ordo zeigt nur die eigenen Dateien des Nutzers, keine eigenen Inhalte |
| Schimpfwörter, derbe Sprache | Nein | Alle Texte stehen in `res/values*/` |
| Drogen, Alkohol, Tabak | Nein | – |
| Glücksspiel, echt oder simuliert | Nein | – |
| Nutzer können miteinander kommunizieren oder Inhalte austauschen | Nein | Kein Chat, keine Konten, keine Profile. Server und FTP-Server verbinden nur Geräte, die der Nutzer selbst wählt |
| Standort wird geteilt | Nein | Keine Standort-Berechtigung im Manifest |
| Digitale Käufe | Nein | Ordo ist ganz kostenlos, ohne In-App-Kauf |
| Uneingeschränkter Internetzugang (Browser) | Nein | Kein Browser; die einzige WebView zeigt lokales HTML |

Erwartet: niedrigste Stufe (zum Beispiel USK 0, PEGI 3). Das vergibt erst IARC.

## 6. Weitere Angaben unter „App-Inhalte“

- Werbung: Nein.
- Konten, Gesundheit, Finanzfunktionen, Nachrichten, Behörde: trifft nicht zu.
- Berechtigungen und Vordergrunddienste: Texte in [PLAY-BERECHTIGUNGEN.md](PLAY-BERECHTIGUNGEN.md).

Diese Antworten sind aus dem Code abgeleitet, nicht von Google freigegeben. Vor dem Einreichen gegen den Code des
Release-Stands halten.
