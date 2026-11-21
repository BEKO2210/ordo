# Automatischer Upload zu Google Play

Nach jeder Änderung auf `main` baut GitHub Actions das signierte App-Bundle (AAB) und lädt es in den
**geschlossenen Test** auf Google Play. Tester bekommen die neue Version, ohne dass jemand etwas hochlädt.

- Workflow: [`.github/workflows/play-internal.yml`](../.github/workflows/play-internal.yml)
- Store-Texte (nur von Hand): [`.github/workflows/play-listing.yml`](../.github/workflows/play-listing.yml)
- Secrets eintragen: [`utils/play-secrets.sh`](../utils/play-secrets.sh)

## Ablauf

| Schritt | Was passiert |
|---|---|
| Auslöser | Push auf `main` (also jeder gemergte Pull Request), außer wenn er nur `docs/**`, `*.md`, `utils/**` oder `e2e/**` ändert (Website, Doku und Gerätetests ändern die App nicht; ein Upload würde Googles Prüfung neu starten), oder von Hand: *Actions › Play closed testing › Run workflow* |
| Bauen | Checkout mit Submodulen (llama.cpp), JDK 21, Gradle-Cache; NDK 30 und CMake lädt das Android-Gradle-Plugin selbst |
| Prüfen | `./gradlew testDebugUnitTest`, danach `:app:bundleRelease`, danach `utils/check-16k.sh` auf dem AAB. Schlägt etwas fehl, wird nichts hochgeladen |
| Signieren | Upload-Schlüssel aus dem Secret `ORDO_UPLOAD_KEYSTORE_BASE64`, Passwörter aus Secrets (`signing.gradle` liest `STORE_FILE`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) |
| Hochladen | [`r0adkll/upload-google-play`](https://github.com/r0adkll/upload-google-play) v1.1.5 (per Commit-SHA festgenagelt) lädt AAB, R8-Mapping und Versionshinweise in den Track `alpha` |

Ein Lauf dauert etwa 15 bis 25 Minuten (der PR-Build mit llama.cpp braucht heute rund 8 Minuten ohne Cache).
Uploads laufen nacheinander, nie gleichzeitig.

### Versionsnummer (versionCode)

Google Play nimmt jeden `versionCode` nur einmal an, und Tester bekommen nur höhere Nummern. Der Workflow setzt
deshalb **`versionCode = 100000 + Laufnummer des Workflows`** (`github.run_number`) und übergibt das an Gradle
(`-PversionCodeOverride`). Der `versionName` bleibt der aus `app/build.gradle` (z. B. `0.8.0`).

Warum so und nicht „höchste Nummer bei Play abfragen und +1“: Die Laufnummer steigt bei jedem Lauf, braucht keinen
zusätzlichen API-Aufruf und kann nicht mit dem Stand bei Play auseinanderlaufen. Der Abstand 100000 liegt weit über
den von Hand gepflegten Nummern (`versionCode 17` in `app/build.gradle`), die für lokale Builds gelten.

Zu beachten:

- Nach dem ersten automatischen Upload keine lokal gebauten Bundles mit kleiner Nummer mehr hochladen; Tester
  bekämen sie nicht. Für die Produktion eine getestete Version im Play Console vom geschlossenen Test aus
  freigeben (gleicher `versionCode`).
- Wird die Workflow-Datei umbenannt, beginnt die Laufnummer neu. Dann den Abstand im Workflow erhöhen
  (z. B. auf 200000).
- Läufe ohne Upload (Prüf-Läufe, Pull Requests) verbrauchen auch Nummern. Lücken stören Play nicht.

### Versionshinweise („Neuerungen“)

Für jede Sprache (`de-DE`, `en-US`) nimmt der Workflow die erste vorhandene Datei:

1. `fastlane/metadata/android/<sprache>/changelogs/<versionCode>.txt`
2. `fastlane/metadata/android/<sprache>/changelogs/default.txt` (gilt für jeden Upload, bis sie geändert wird)
3. sonst „Neue Testversion 0.8.0 (100042).“ bzw. „New test build 0.8.0 (100042).“

Höchstens 500 Zeichen je Sprache (Grenze von Google Play).

### Ohne Upload (Prüf-Lauf)

- *Run workflow* mit abgehaktem **Upload to Google Play** baut und prüft nur.
- Pull Requests, die diesen Workflow ändern, laufen ebenfalls nur als Prüf-Lauf.
- Fehlen die Secrets (z. B. in Forks oder bevor die Einrichtung fertig ist), signiert der Lauf mit einem
  Wegwerf-Schlüssel, lädt nichts hoch und zeigt eine Warnung statt eines Fehlers.

## Einmalige Einrichtung

### 1. Erstes Bundle von Hand hochladen

Google erlaubt Uploads über die API erst, wenn die App in der Play Console existiert und **das erste AAB von Hand**
hochgeladen wurde: *Play Console › Ordo › Testen und veröffentlichen › Testen › Geschlossener Test ›
Alpha* (oder der eigene geschlossene Track) *› Neuen Release erstellen*. Erst danach übernimmt der Workflow.

Solange die App noch nie geprüft und veröffentlicht wurde (Status „Entwurf“), nimmt die API nur Releases im
Status `draft` an. Bis dahin die Repo-Variable `PLAY_RELEASE_STATUS` auf `draft` setzen (siehe Schritt 5) und die
Releases in der Play Console selbst zur Prüfung senden. Nach der ersten Freigabe die Variable wieder löschen.

### 2. Google-Cloud-Projekt und Play-API

1. <https://console.cloud.google.com> öffnen, oben *Projekt auswählen › Neues Projekt*, z. B. `ordo-play`.
2. *APIs & Dienste › Bibliothek*, nach **Google Play Android Developer API** suchen, *Aktivieren*.

### 3. Dienstkonto und JSON-Schlüssel

1. *IAM & Verwaltung › Dienstkonten › Dienstkonto erstellen*, Name z. B. `ordo-play-upload`. Rollen im
   Cloud-Projekt braucht es keine; *Fertig*.
2. Das neue Dienstkonto öffnen › Reiter *Schlüssel* › *Schlüssel hinzufügen › Neuen Schlüssel erstellen ›
   JSON*. Die Datei wird heruntergeladen. Sie ist ein Passwort: nie ins Repo, nach Schritt 5 löschen.
3. Die E-Mail-Adresse des Dienstkontos kopieren (`ordo-play-upload@<projekt>.iam.gserviceaccount.com`).

### 4. Dienstkonto in der Play Console freischalten

1. *Play Console* (Startseite, nicht in der App) *› Nutzer und Berechtigungen › Neue Nutzer einladen*.
2. E-Mail-Adresse des Dienstkontos eintragen.
3. Reiter *App-Berechtigungen › App hinzufügen › Ordo*, dort nur **Releases für Test-Tracks veröffentlichen**
   (*Release apps to testing tracks*) anhaken. Für den Store-Text-Workflow zusätzlich **Store-Eintrag verwalten**
   (*Manage store presence*).
4. *Nutzer einladen*. Es kann einige Minuten bis Stunden dauern, bis die Berechtigung bei der API ankommt.

### 5. GitHub-Secrets und Variablen

| Secret | Inhalt |
|---|---|
| `PLAY_SERVICE_ACCOUNT_JSON` | Inhalt der JSON-Datei aus Schritt 3 |
| `ORDO_UPLOAD_KEYSTORE_BASE64` | Upload-Keystore (`upload.jks`) als Base64 |
| `ORDO_STORE_PASSWORD` | Keystore-Passwort |
| `ORDO_KEY_ALIAS` | Alias des Upload-Schlüssels |
| `ORDO_KEY_PASSWORD` | Schlüssel-Passwort (bei PKCS12 gleich dem Keystore-Passwort) |

Am einfachsten mit dem Hilfsskript; es prüft JSON und Keystore, gibt keine Werte aus und schreibt alles per
`gh secret set` (angemeldetes `gh` mit Admin-Rechten am Repo nötig):

```bash
ORDO_KEYSTORE=/pfad/zu/upload.jks ORDO_KEY_ALIAS=<alias> \
ORDO_STORE_PASSWORD="$(<passwort aus dem Passwortmanager>)" \
  utils/play-secrets.sh ~/Downloads/<dienstkonto>.json
shred -u ~/Downloads/<dienstkonto>.json
```

Statt `ORDO_KEYSTORE` geht auch `ORDO_UPLOAD_KEYSTORE_BASE64` mit dem schon kodierten Keystore. Von Hand geht es
über *GitHub › Repo › Settings › Secrets and variables › Actions › New repository secret*.

Optionale Repo-Variablen (*Settings › Secrets and variables › Actions › Reiter Variables*):

| Variable | Standard | Wofür |
|---|---|---|
| `PLAY_TRACK` | `alpha` | Name des geschlossenen Tracks. `alpha` ist der Standard-Track „Geschlossener Test“; ein selbst angelegter Track heißt so, wie er in der Play Console benannt wurde |
| `PLAY_RELEASE_STATUS` | `completed` | `draft`, solange die App noch nie veröffentlicht wurde (siehe Schritt 1) |

### 6. Testen

*Actions › Play closed testing › Run workflow* (Branch `main`, Upload angehakt). Im Log des Schritts
*Upload to Google Play* steht der hochgeladene `versionCode`; in der Play Console erscheint der Release unter
*Geschlossener Test*.

## Store-Texte hochladen

Die Texte in `fastlane/metadata/android/{de-DE,en-US}/` (Titel, Kurz- und Langbeschreibung) gehen nur von Hand zu
Play: *Actions › Play store listing › Run workflow*. Standardmäßig ist **Only validate** angehakt; dann prüft
Google nur und ändert nichts. Für den echten Upload den Haken entfernen. Der Workflow nutzt `fastlane supply`
2.241.2. Bilder und Screenshots lädt er nicht hoch (`SUPPLY_SKIP_UPLOAD_IMAGES`, `SUPPLY_SKIP_UPLOAD_SCREENSHOTS`):
Die Dateien in `fastlane/metadata/android/<sprache>/images/` kommen von Hand in die Play Console
(*Store-Präsenz › Haupteintrag im Play Store*). Sie entstehen mit `utils/store-images/`: `demo_media.py` (nur
Demo-Medien), `capture.sh` (Aufnahmen am Gerät, nur wenn Ordo vorne ist), `compose.py` (Rahmen und Überschriften),
`check.py` (Play-Regeln).

## Fehlersuche

| Meldung | Ursache |
|---|---|
| Warnung „Upload skipped: GitHub secrets … missing“ | Secrets fehlen oder sind leer, Schritt 5 |
| `The caller does not have permission` | Dienstkonto in der Play Console nicht oder noch nicht freigeschaltet, Schritt 4 |
| `Package not found: com.beko2210.ordo` | App noch ohne erstes AAB, Schritt 1 |
| `Only releases with status draft may be created on draft app` | `PLAY_RELEASE_STATUS=draft` setzen, Schritt 1 |
| `APK specifies a version code that has already been used` | Lauf wiederholt (gleiche Laufnummer); einen neuen Lauf starten statt *Re-run* |
| `The Android App Bundle was signed with the wrong key` | Falscher Keystore im Secret; es muss der in der Play Console hinterlegte Upload-Schlüssel sein |
| `16 KB: FAIL` | Eine native Bibliothek ist nicht auf 16-KB-Seiten ausgerichtet; Upload findet nicht statt |
