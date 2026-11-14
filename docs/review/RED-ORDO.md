# RED TEAM – Finales Sicherheitsreview Ordo

| | |
|---|---|
| Repo | BEKO2210/ordo, `main` @ `5cae53867b38bce494493c6b1170542afdaa6809` (lokal read-only: `review/repo`) |
| APK | `ordo-0.1.0-test4.apk`, SHA-256 geprüft: `c8f8ccfed89c25aecc51d1e9d7a2d7f08ba5a11f9dddd93146645c64ca539d5d` (stimmt mit BRIEF überein) – Paket `com.beko2210.ordo`, versionName 0.1.0, minSdk 23, targetSdk 36 |
| Datum | 2026-11-14 (Europe/Berlin) |
| Werkzeuge | `sha256sum`, `aapt2 dump xmltree/badging` (Android SDK build-tools 36.0.0), `rg/grep/nl`, JDK 21 (`<tmp>`), kotlinc 2.1.0 (offizielles JetBrains-Release, nur in `<tmp>`), org.json 20240303 (Maven Central) für eine isolierte Harness der reinen Logikklassen. Kein Gradle-Build, keine Geräte, keine echten Samples. |
| Repro-Material | `review/workdir/red/` |
| Rolle | RED TEAM (Angriff auf KI-Sperren und Angriffsfläche) |

Statuslegende: **bestätigt** = Datei:Zeile plus von mir ausgeführter, reproduzierbarer Befehl/Test. **Verdacht** = statisch hergeleitet, nicht ausgeführt. **Gerätetest nötig** = nur zur Laufzeit auf Android prüfbar, Schritte angegeben. **widerlegt** = Angriff geprüft, greift nicht.

Hinweis zum Kontext: Der Submodul-Checkout `llm/src/main/cpp/llama.cpp` (Pin `3549c274dc22001d9118713fa56a10085b85de3b`) ist lokal **nicht** ausgecheckt; nativer llama.cpp-Code wurde daher nicht geprüft.

## Findings

### RED-01 – Exportierte `OpenFileActivity` vergibt fremden Apps Lese- **und Schreib**rechte auf beliebige Pfade (Confused Deputy über eigenen FileProvider)

- **Schweregrad: Hoch.** Jede installierte App kann ohne Berechtigung und ohne Nutzeraktion über Ordo private App-Daten von Ordo lesen und überschreiben (z. B. Einstellungen mit gespeicherten Server-Zugangsdaten, `vault.keyset` und die verschlüsselten Tresor-Dateien) sowie den gesamten geteilten Speicher, für den Ordo `MANAGE_EXTERNAL_STORAGE` hat. Nicht Kritisch, weil eine bösartige App installiert sein muss. Das Muster stammt aus Material Files (Upstream), wiegt in Ordo aber schwerer, weil der Tresor dazugekommen ist.
- **Dateien:**
  - `app/src/main/AndroidManifest.xml:155-165`: `OpenFileActivity` mit `exported="true"`, Intent-Filter `me.zhanghai.android.files.intent.action.OPEN_FILE`, `mimeType="*/*"`.
  - Im APK-Merged-Manifest dasselbe: `workdir/red/apk-manifest.txt:189-197`, `exported=true`.
  - `app/src/main/java/me/zhanghai/android/files/filelist/OpenFileActivity.kt:26-43`: Pfad und MIME-Typ kommen ungeprüft aus dem Intent, dazu `FLAG_GRANT_WRITE_URI_PERMISSION`.
  - `util/IntentPathExtensions.kt:21-31`: Der Pfad wird aus dem String-Extra `com.beko2210.ordo.extra.PATH_URI` gelesen (`file:///…`), alternativ aus `data` oder `org.openintents.extra.ABSOLUTE_PATH`.
  - `util/IntentExtensions.kt:163-167`: `ACTION_VIEW` plus `FLAG_GRANT_READ_URI_PERMISSION`.
  - `file/FileProvider.kt:188-195` und `:383-407`: Der Provider öffnet jeden kodierten `file://`-Pfad, auch im Schreibmodus. Er hat `grantUriPermissions=true` (`workdir/red/apk-manifest.txt:462`).
- **Angriffsszenario:** Eine harmlos wirkende App (z. B. ein „Wallpaper“-Tool ohne Speicherrecht) registriert eine Activity für einen exotischen MIME-Typ, etwa `application/x-poc`. Dann startet sie `Intent("me.zhanghai.android.files.intent.action.OPEN_FILE").setClassName("com.beko2210.ordo","me.zhanghai.android.files.filelist.OpenFileActivity").setType("application/x-poc").putExtra("com.beko2210.ordo.extra.PATH_URI","file:///data/user/0/com.beko2210.ordo/shared_prefs/com.beko2210.ordo_preferences.xml")`. Ordo baut `content://com.beko2210.ordo.file_provider/<file-URI>` und schickt es mit Lese- und Schreibrecht per `ACTION_VIEW`. Nur die Angreifer-App bedient diesen MIME-Typ, also bekommt sie die URI direkt.
  - **Ergebnis lesen:** Einstellungen inklusive gespeicherter SMB-, SFTP-, FTP- und WebDAV-Zugangsdaten, `command_model.xml`, decryptete Tresor-Kopien in `cache/vault-open/<id>/<name>` (sofern Namen bekannt bzw. erratbar) und beliebige Dateien im geteilten Speicher ohne eigenes Speicherrecht.
  - **Ergebnis schreiben/zerstören:** `openFileDescriptor(uri,"wt")` auf `no_backup/vault.keyset` oder Tresor-Dateien kürzt sie. Das ist ein dauerhafter Verlust aller Tresor-Inhalte. Außerdem können Einstellungen manipuliert werden, z. B. ein abgeschalteter Papierkorb.
- **Erwartet:** Nur Ordo selbst (Shortcuts) darf `OPEN_FILE` auslösen, oder der Pfad wird auf geteilten Speicher beschränkt und nur mit Leserecht weitergegeben.
- **Tatsächlich:** Es gibt keine Prüfung des Aufrufers und keine Pfadbeschränkung, dafür ein Schreib-Grant.
- **Verifikation:** Exportstatus im APK bestätigt per `aapt2 dump xmltree --file AndroidManifest.xml ordo-0.1.0-test4.apk` (Ausgabe in `workdir/red/apk-manifest.txt`). Den Codepfad habe ich statisch nachvollzogen. **Gerätetest nötig** für den Ablauf:
  1. Eigene Test-App bauen: Activity mit `<intent-filter><action VIEW/><category DEFAULT/><data mimeType="application/x-redteam-poc"/></intent-filter>`. Sie liest `intent.data` mit `contentResolver.openInputStream` und gibt nur

### RED-01 – Lösch-Wort-Prüfung ist Substring und akzeptiert verneinte/fremde Treffer
- **Schwere:** Hoch – Ein vom Modell erzeugter `action=delete`-Plan wird freigegeben, sobald irgendwo im Befehlstext ein Listeneintrag als Substring vorkommt, auch bei klarer Verneinung oder Zufallstreffern. Die Bestätigung zeigt dann einen Lösch-Dialog; ein unachtsamer Tap löscht (in den Papierkorb).
- **Dateien:** `CommandCheck.kt:34–37`, `CommandCheck.kt:58–61`
- **Angriffsszenario:** Nutzer schreibt „Bitte nichts löschen, nur Bilder aus Download nach Backup verschieben“. Das Modell (oder ein Prompt-Injection-Versuch) liefert trotzdem `action=delete`, Quelle Download. Die Sperre sieht „lösch“ in „löschen“ und gibt Ready. Alternativ: Befehl enthält „Papierkorb“, „trash“, „remove“/„Entfernung“, „Trashmetal“ → gleicher Effekt.
- **Repro:** `workdir/red/run-harness.sh` → Abschnitt A; Auszug:
  - `[Bitte nichts löschen, …]` → `READY`
  - `[Do not delete anything, …]` → `READY`
  - `[move Download to Trashmetal]` → `READY`
  - `[Mach Download leer]` / `[wegmachen:…]` / `[clean up…]` → `REFUSED` (Synonyme fehlen – gut gegen Bypass, schlecht für UX)
  - NFD `Lösche` (U+0308), Fullwidth, Zero-Width in „lösche“ → `REFUSED`
- **Erwartet:** Verneinte Lösch-Worte und Zufallstreffer blocken; echte Löschabsicht erkennt Wortgrenzen/Absicht.
- **Ist:** Substring `it in lower` ohne Negation/Word-Boundary.
- **Verifikation:** Build-/Unit-Test (Harness) · **Status: bestätigt**

### RED-02 – Kamera-Sperre fällt bei jedem Vorkommen von kamera/camera/dcim im Text
- **Schwere:** Hoch – `DCIM/Camera` ist nur geschützt, wenn keines der Wörter im Befehl vorkommt. Ein Satz, der die Kamera nur erwähnt („nicht die Kamera“, „nicht DCIM“), hebt die Sperre für einen Plan mit Quelle `Camera`/`DCIM/Camera` auf.
- **Dateien:** `CommandCheck.kt:39`, `CommandCheck.kt:64–66`, `CommandCheck.kt:104–117`
- **Angriffsszenario:** Modell liefert `action=delete`, `source=Camera` (Alias → `DCIM/Camera`). Nutzerbefehl: „Lösche alle Bilder in Screenshots, aber nicht in der Kamera“ oder „Lösche Screenshots (nicht DCIM)“. `cameraNamed=true` → `isProtected` überspringt die DCIM/Camera-Regel → Preview mit Kamerafotos → Bestätigung löscht sie in den Papierkorb.
- **Repro:** Harness Abschnitt D:
  - `[Lösche … Screenshots, aber nicht in der Kamera] plan.source=Camera` → `READY src=/DCIM/Camera`
  - `[Lösche Screenshots (nicht DCIM)]` → `READY`
  - ohne Kamera-Wort → `REFUSED PROTECTED_FOLDER`
- **Erwartet:** Kamera bleibt gesperrt, außer der Nutzer benennt sie klar als Ziel.
- **Ist:** Jede Erwähnung der Wörter schaltet den Schutz aus, unabhängig vom Plan-Pfad.
- **Verifikation:** Build-/Unit-Test · **Status: bestätigt**

### RED-03 – Ziel „/“ wird zum gesamten freigegebenen Speicher (root); root selbst ist nicht geschützt
- **Schwere:** Hoch – `File(root, "/")` ergibt kanonisch `root`; `isProtected(root)` ist false. Move/Copy nach „/“ legt Dateien direkt in den Speicher-Root. Zusätzlich gilt `destination == source` nur per `File.equals` ohne Kanonisierung (`Download/../Download` → Ready).
- **Dateien:** `CommandCheck.kt:74–79`, `CommandCheck.kt:104–110`
- **Angriffsszenario:** Modell setzt `destination="/"`. Check liefert Ready mit Ziel = Externer Speicher. Nutzer bestätigt „Verschieben“ → Dateien landen im Root (Unordnung, mögliche Überschreibungen je nach FileJob-Konfliktlogik).
- **Repro:** Harness C `dest=[/] → READY … dst=` (leer nach removePrefix); Extra-Check: `File(root,"/")` equals `root`, `isProtected(root)=false`; `Download/../Download` → Ready.
- **Erwartet:** Root und „Ziel == Quelle“ (kanonisch) abgelehnt.
- **Verifikation:** Build-/Unit-Test · **Status: bestätigt**

### RED-04 – Ausführung ohne erneuten Lock-Check (TOCTOU Preview→Execute); Undo-Race dokumentiert
- **Schwere:** Mittel – `execute()` vertraut dem gespeicherten `ready`/`files` und ruft `CommandCheck` nicht erneut auf. Zwischen Preview und Tap kann sich der Ordnerinhalt ändern (neue Dateien, Symlink-Austausch). Undo startet parallel zum noch laufenden FileJob (Code-Kommentar Zeile 536–537).
- **Dateien:** `CommandSheetFragment.kt:487–528`, `CommandSheetFragment.kt:536–541`
- **Angriffsszenario:** Angreifer (andere App mit Speicherzugriff / ADB) ersetzt nach der Preview Dateien in der Quelle durch andere oder Symlinks; Nutzer bestätigt den alten Preview-Text. Oder Nutzer tippt sofort Undo, während Move noch läuft → inkonsistenter Zustand.
- **Repro:** Statischer Pfad: `confirmButton` → `execute()` ohne `CommandCheck.check`. Gerätetest nötig für Live-Symlink-Swap.
- **Verifikation:** statisch (+ Gerätetest nötig für Race) · **Status: bestätigt** (fehlender Re-Check); Race Undo · **Verdacht** / Gerätetest

### RED-05 – Modell-Integrität nur einmal; später reicht Dateilänge
- **Schwere:** Hoch – Nach erfolgreicher SHA-256-Prüfung setzt `KEY_VERIFIED=true`. Danach: `verified && file.length() == SIZE` → Ready **ohne** erneuten Hash. Die Datei liegt unter `getExternalFilesDir("models")`. Ordo selbst hält `MANAGE_EXTERNAL_STORAGE`; jede andere App mit derselben Berechtigung (oder Root) kann die ~2,7‑GB-Datei bei gleicher Länge ersetzen → manipuliertes GGUF wird geladen.
- **Dateien:** `CommandModel.kt:64–68`, `CommandModel.kt:103–112`, `CommandModel.kt:50`; Manifest-Permission `MANAGE_EXTERNAL_STORAGE`
- **Angriffsszenario:** Schadhafte App mit MES ersetzt `qwen3.5-4b-q4_k_m.gguf` durch ein gleich großes präpariertes Modell. Ordo plant Befehle nach Angreifer-Logik; Locks in CommandCheck bleiben die letzte Linie – aber ein manipuliertes Modell erhöht die Chance auf gefährliche Pläne (vgl. RED-01/02).
- **Repro:** Statisch am State-Machine-Code. Gerätetest: Datei nach Verify ersetzen, App neu öffnen, prüfen ob ohne Re-Hash geladen wird.
- **Verifikation:** statisch · **Status: bestätigt** (Logik); Ersetzen auf Gerät · Gerätetest nötig

### RED-06 – Unicode-/Lookalike-Ziele umgehen die Namensprüfung (neue Ordner, nicht die echten)
- **Schwere:** Mittel – Relative Ziele mit Zero-Width (`Andr‌oid`), Fullwidth (`Ａndroid`), kyrillischem „А“/`а` in Camera werden **nicht** als geschützt erkannt, weil `isProtected` erst nach Auflösung auf dem echten Pfad läuft und `File(root, lookalike)` einen **neuen** Ordner anlegt (`new=true`). Kein Zugriff auf echtes `Android/data`, aber Verwechslung/Phishing-Ordner neben den echten.
- **Dateien:** `CommandCheck.kt:74–79`, `CommandCheck.kt:111–116`
- **Repro:** Harness C: `Andr\u200Coid/data`, `\uFF21ndroid/data`, `\u0410ndroid/data`, `DCIM/C\u0430mera` → jeweils `READY … new=true`.
- **Verifikation:** Build-/Unit-Test · **Status: bestätigt**

### RED-07 – Nullbyte im Zielpfad → IOException in isProtected (Abbruch statt Refuse)
- **Schwere:** Niedrig – `File.canonicalPath` wirft bei `Android\0/data` `IOException: Invalid file path`; ungecatcht in `check()` → Crash der Auswertung statt `Refused`.
- **Dateien:** `CommandCheck.kt:106` (Aufrufkette `check` → `isProtected`)
- **Repro:** Harness C: `dest=[Android\u0000/data] → EXCEPTION java.io.IOException`
- **Verifikation:** Build-/Unit-Test · **Status: bestätigt**

### RED-08 – OpenFileActivity exportiert; nimmt Pfad-Extras von Dritt-Apps
- **Schwere:** Mittel – Exportierte Activity mit Action `me.zhanghai.android.files.intent.action.OPEN_FILE`. Liest `com.beko2210.ordo.extra.PATH_URI`, Intent-Data oder `org.openintents.extra.ABSOLUTE_PATH` und startet VIEW mit `FileProvider`-URI plus `FLAG_GRANT_WRITE_URI_PERMISSION`. Confused-Deputy: Dritt-App triggert Öffnen beliebiger Pfade; Schreibrecht geht an die gewählte Viewer-App.
- **Dateien:** `OpenFileActivity.kt:22–44`; `IntentPathExtensions.kt:19–31`; APK-Manifest `exported=true`
- **Angriffsszenario:** Malware sendet expliziten Intent an `com.beko2210.ordo` / OpenFileActivity mit Pfad zu sensibler Datei und MIME-Type; Nutzer bestätigt ggf. App-Chooser → Viewer erhält content-URI mit Schreibgrant.
- **Verifikation:** statisch (Manifest + Code) · **Status: bestätigt** (exportiert + Pfadlogik); Ausnutzen am Gerät · Gerätetest nötig

### RED-09 – Cleartext + User-CAs in Network-Security-Config; Privacy-Text veraltet
- **Schwere:** Mittel (Privacy/Supply-Chain) – `usesCleartextTraffic=true`, `network_security_config` erlaubt Cleartext und **User-Zertifikate**. Modell-URL ist HTTPS+Hash (gut), aber FTP/HTTP-Server und künftige Calls sind MITM-anfällig. `PRIVACY.md` und Setup-String behaupten weiterhin „keine Internetverbindung“ / „Befehle verlassen nie das Gerät“, obwohl der Setup-Flow ~2,7 GB von Hugging Face lädt.
- **Dateien:** `AndroidManifest.xml` (`usesCleartextTraffic`, `networkSecurityConfig`); `res/xml/netework_security_config.xml`; `CommandModel.kt:23–26`; `PRIVACY.md:3–4`; `ai_strings.xml` `ai_setup_privacy`
- **Verifikation:** statisch · **Status: bestätigt**

### RED-10 – allowBackup=true trotz Vault/Passwörtern in Settings
- **Schwere:** Niedrig – `android:allowBackup="true"`. Vault-Keyset liegt in `noBackupFilesDir` (gut). Server-Passwörter (FTP/SFTP/SMB/WebDAV) und Settings inkl. `command_model`-Prefs können über ADB-Backup/Cloud-Backup abfließen.
- **Dateien:** `AndroidManifest.xml` allowBackup; `vault/Vault.kt:31–32` (positiv: noBackup)
- **Verifikation:** statisch · **Status: bestätigt**

### RED-11 – Prompt-Injection über Dateinamen erreicht das Modell nicht; Grammar begrenzt Output
- **Schwere:** Info – Dateinamen werden dem LLM nicht übergeben (`CommandEngine.plan` nur `suffix(text)`). Ausgabe ist Grammar-beschränkt auf das JSON-Schema. Injection über Dateinamen ist damit wirkungslos; Injection über den Befehlstext kann höchstens Felder im Schema füllen, die `CommandCheck` danach filtert.
- **Dateien:** `CommandEngine.kt:43–46`; `CommandPlanner.kt:17–28`, `CommandPlanner.kt:14–15` (`<|` escaped)
- **Verifikation:** statisch · **Status: widerlegt** (als Angriff); Defense hält – als Info dokumentiert

### RED-12 – KI-Löschen geht an FileJobService.delete ohne permanently; endgültiges Löschen nur wenn Papierkorb aus oder canTrash false
- **Schwere:** Info / teilweise OK – Sheet verweigert Delete wenn `!Trash.isEnabled`. Sonst `delete(paths)` → Trash. Pfade mit `!Trash.canTrash` werden in `DeleteFileJob` **endgültig** gelöscht (Partition). AI-Collect liefert nur Dateien unter geprüfter Quelle; Risiko gering, solange Quelle trashable bleibt.
- **Dateien:** `CommandSheetFragment.kt:316–320`, `509–510`; `FileJobs.kt:977–1001`; `Trash.kt:43–62`
- **Verifikation:** statisch · **Status: bestätigt** (Design); Bypass „permanent statt Trash“ über AI · **widerlegt** solange Trash an und Quelle trashable

### RED-13 – Archiv: Zip-Slip im Archive-FS gemildert; Zip-Bomb / Symlink-in-tar nicht separat limitiert
- **Schwere:** Niedrig (Verdacht) – `ArchiveReader` normalisiert Entry-Namen und verwirft Root-only nach Normalize. Extract mappt über `resolveForeign`/`relativize` in vom Nutzer gewähltes Ziel. Keine sichtbaren Limits für Entry-Anzahl/Uncompressed-Size (Zip-Bomb). Symlink-Einträge: Copy mit NOFOLLOW – Verhalten am Gerät klären.
- **Dateien:** `ArchiveReader.kt:40–57`; `FileJobs.kt:767–816`, `1370`
- **Verifikation:** statisch · **Status: Verdacht** (Zip-Bomb/Symlink); Zip-Slip Host-Escape · eher widerlegt innerhalb Archive-FS, Gerätetest für Extract empfohlen

### RED-14 – ShizukuProvider exportiert (erwartbar); FtpServer/FileJob Receiver ohne exported=false explizit im Merge
- **Schwere:** Info – `rikka.shizuku.ShizukuProvider` exported + `INTERACT_ACROSS_USERS_FULL` (Shizuku-Standard). `FileJobReceiver`/`FtpServerReceiver` ohne `exported` im Dump – unter targetSdk 36 Default false für ohne Intent-Filter. FtpServer Tile/Activity bewusst exportiert.
- **Verifikation:** APK-Manifest · **Status: bestätigt** (keine zusätzliche Schwachstelle erkannt)

### RED-15 – Case „dcim/CAMERA“ als absoluter Pfad auf case-sensitivem FS nicht gefunden (ASK); auf Android ggf. Treffer
- **Schwere:** Niedrig – Harness (Linux, case-sensitiv): `…/dcim/CAMERA` → ASK. Auf Android Shared Storage (case-insensitive) würde `File.isDirectory` greifen und danach `isProtected` (parts lowercased) greifen → Refuse. Alias „Camera“ → Refuse korrekt.
- **Repro:** Harness B; Gerätetest für case-insensitive Bestätigung.
- **Verifikation:** Harness + Annahme Android · **Status: Verdacht** (auf Gerät vermutlich OK)

## Geprüft und in Ordnung

| Thema | Beleg |
|---|---|
| APK-Hash = BRIEF | `sha256sum` → `c8f8ccfed89c25aecc51d1e9d7a2d7f08ba5a11f9dddd93146645c64ca539d5d` |
| Commit Pin | `5cae53867b38bce494493c6b1170542afdaa6809` |
| Keine Ausführung vor Confirm | Nur `confirmButton` → `execute()` (`CommandSheetFragment.kt:101–106`, `487`) |
| Delete-Wort fehlt → Refuse | Harness A: „wegmachen“, „clean up“, FR/ES → `UNSURE_DELETE` |
| Android/data, obb, versteckte Segmente, Symlink-Quellen | `isProtected` via `canonicalPath`; Harness B: absolute Android-Pfade, `Music/../Android/data`, Symlinks `Musik2`/`Fotos` → `PROTECTED_FOLDER` |
| DCIM/Camera ohne Kamera-Wort | Refuse; Alias „Camera“ → Refuse |
| Nested Resolve ignoriert `.` und `Android` | `childDirs()` filtert (`CommandCheck.kt:119–121`) |
| Model-Download HTTPS + Größe + SHA-256 beim ersten Verify | `CommandModel.kt:23–26`, `103–112` |
| Grammar erzwingt Schema; `<\|` im User-Text escaped | `CommandPlanner.kt:14–15`, `17–28` |
| FileProvider nicht exportiert, grantUriPermissions nur nach Grant | Manifest + `FileProvider.attachInfo` wirft wenn exported |
| Trash-Info-Forging: `isValidOriginalPath` | `Trash.kt:64–67` |
| Vault in `noBackupFilesDir` + Android Keystore | `Vault.kt:31–64` |
| Collect: keine Dotfiles | `CommandFiles.kt:29` |
| Move/Copy/Trash nutzen NOFOLLOW_LINKS | `FileJobs.kt` / `Trash.kt:97` |

## Nicht geprüft

- Laufzeit auf echtem Android-Gerät (kein Emulator/Phone freigegeben): Intent-Angriffe, MES-Model-Replace, Case-Folding, ART-`File`-Semantik für absolute Kinderpfade, Undo-Race, FileJob-Konfliktdialoge.
- APK-Build / Signaturprüfung über `apksigner` (nur aapt2 Badging/Manifest).
- Vollständiges Entpacken mit apktool/jadx (Manifest via aapt2 ausreichend für Exported-Inventar).
- llama.cpp-Submodul (`3549c274…`) lokal **nicht** ausgecheckt – kein Review von Upstream-CVE/Prompt-Länge jenseits der JNI-Hülle `ordo_llm.cpp` (maxTokens vom Caller, Context 2048).
- FTP-Server-Runtime, SMB/WebDAV-Credential-Storage im Klartext in Settings-Dateien.
- Backup-Restore-Pfad auf Zip-Slip (Backup.kt nur Entry-Namen-Hilfen gelesen).
- Ob `DownloadManager` die Datei an einen Ort schreibt, den MES-Apps vor Verify erreichen (Race während Download).
- Geräte-UI: Chip-Filter erweitern die Dateimenge nach Preview (bewusst) – kein Lock-Bypass, aber Nutzer sollte die neue Zahl lesen.

## Kurzstatistik

| Schwere | Anzahl | IDs |
|---|---|---|
| Kritisch | 0 | — |
| Hoch | 4 | RED-01, RED-02, RED-03, RED-05 |
| Mittel | 4 | RED-04, RED-06, RED-08, RED-09 |
| Niedrig | 4 | RED-07, RED-10, RED-13, RED-15 |
| Info | 3 | RED-11, RED-12, RED-14 |

**Gesamt: 0C / 4H / 4M / 4L / 3I** (15 Findings).

## Top-Empfehlungen (kurz)

1. Lösch-Wort: Tokenisierung + Negationserkennung; keine Substring-Treffer in Verneinungen.
2. Kamera-Sperre an den **aufgelösten Pfad** binden, nicht an Text-Keywords; Keyword höchstens als zusätzliche Freigabe wenn Pfad wirklich Camera ist.
3. `isProtected`: root ablehnen; Ziele kanonisieren vor `== source`; Lookalikes/NFC normalisieren (java.text.Normalizer).
4. Vor `execute()` erneut `CommandCheck.check` + Dateiliste hashen/vergleichen; Model bei jedem Load oder mit mtime/inode erneut hashen.
