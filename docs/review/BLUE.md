# Ordo – BLUE TEAM: finaler defensiver Security-Review

| | |
|---|---|
| **Datum** | 2026-11-14 (Europe/Berlin) |
| **Rolle** | BLUE TEAM (Präfix `BLUE-`) |
| **Scope** | Tresor (Vault), Netzwerk-Zugänge FTP/FTPS/SFTP/SMB/WebDAV + eingebauter FTP-Server, Datenlecks (Logs, Clipboard, Screenshots), Berechtigungen/Komponenten, Modell-Download/GGUF |
| **Repo** | `BEKO2210/ordo`, main @ `5cae53867b38bce494493c6b1170542afdaa6809` (lokal read-only, `git status` sauber, nicht verändert) |
| **APK** | `ordo-0.1.0-test4.apk` – `sha256sum` = `c8f8ccfed89c25aecc51d1e9d7a2d7f08ba5a11f9dddd93146645c64ca539d5d` → **stimmt mit dem Brief überein** |
| **Methode** | Statische Code-Analyse (`rg`, `sed`), Manifest/Ressourcen aus der APK mit `aapt2 dump badging/permissions/xmltree` (build-tools 36.0.0), Dex-Prüfung mit `dexdump` (kein jadx nötig), `strings` auf native Libs, Abgleich mit Original-Quellen der Bibliotheken (commons-net 3.13.0, smbj 0.11.5, tink-android 1.24.0 Sources von Maven Central; libarchive `archive_write_set_format_zip.c`). Kein Gradle-Build, keine Unit-Tests ausgeführt, kein Gerätetest. Arbeitsdateien nur unter `<tmp>`. |

Statuswerte: **bestaetigt** = Datei:Zeile + reproduzierbarer Befehl mit Ausgabe; **Verdacht** = plausibel, aber nicht vollständig belegt; Laufzeitverhalten ist als „Gerätetest nötig“ markiert.

---

## Kurzfazit

Der Tresor ist kryptografisch sauber gebaut: Tink **StreamingAead `AES256_GCM_HKDF_1MB`**, Keyset mit einem AndroidKeystore-AES-256-GCM-Masterkey verschlüsselt, Associated Data bindet jeden Ciphertext an seinen Dateinamen, Nonces/Salts erzeugt Tink, Ablage in `noBackupFilesDir`, atomare Schreibreihenfolge mit `fsync`. Der Modell-Download ist auf eine HTTPS-URL mit fester Revision und einen im Code gepinnten SHA-256 festgelegt. Es gibt keinen Crash-Reporter, kein Analytics, kein Tracking.

Die Schwachstellen liegen **um** den Tresor herum und in den geerbten Netzwerk-Clients:

1. **Test-APK ist ein Debug-Build** (`debuggable=true`, `BUILD_TYPE="debug"`, `DEBUG=true`, Debug-Zertifikat, kein R8). Damit ist auch `FLAG_SECURE` im Tresor abgeschaltet und der App-Prozess per Debugger zugänglich (BLUE-001, Hoch).
2. **SFTP ohne Host-Key-Prüfung** (`PromiscuousVerifier`) und **FTPS ohne Zertifikatsketten- und Hostnamen-Prüfung** (commons-net-Default) → MITM mit Passwortdiebstahl möglich (BLUE-007/008, Hoch).
3. Server-Passwörter und SFTP-Private-Keys liegen **unverschlüsselt** (Base64-Parcel) in den SharedPreferences und gehen über `allowBackup=true` in Android-Backups (BLUE-009, Mittel).
4. Entschlüsselte Tresor-Dateien verlassen den Schutz (externe Apps per URI-Grant, interne Viewer ohne `FLAG_SECURE`) und das ZIP-Backup des Tresors schwächt die Krypto auf WinZip-AES mit PBKDF2-SHA1 × 1000 und Klartext-Dateinamen (BLUE-003/004, Mittel).
5. Das Modell wird nur **einmal** gehasht; danach reicht „Flag + richtige Größe“ in einem extern erreichbaren Verzeichnis, bevor llama.cpp die GGUF nativ parst (BLUE-018, Mittel).

**Findings: 19** — Kritisch 0 · Hoch 3 · Mittel 7 · Niedrig 7 · Info 2 (bestaetigt 17, Verdacht 2, widerlegt 0).

---

## Positive Controls (mit Belegen)

| # | Kontrolle | Beleg |
|---|---|---|
| P1 | Tink StreamingAead `AES256_GCM_HKDF_1MB` (segmentiert, 1 MB) | `vault/Vault.kt:60` – `KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB)`; APK: `dexdump -d classes15.dex \| rg AES256_GCM_HKDF_1MB` → `sget-object … AES256_GCM_HKDF_1MB` |
| P2 | Keyset verschlüsselt mit AndroidKeystore-Masterkey (AES-256-GCM, Alias `ordo_vault`) + AD `ordo_vault_keyset`, atomar via `AtomicFile` | `vault/Vault.kt:27-28,45,52-54,57-73`; Tink-Quelle `AndroidKeystore.java:52-58` (`setKeySize(256)`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`) |
| P3 | Nonces/Salts nicht selbst gewählt (Tink-intern pro Stream) | `vault/VaultStore.kt:103,114` – nur `newEncryptingStream/newDecryptingStream`, kein eigener IV |
| P4 | Associated Data bindet Ciphertext an Dateinamen (Inhalt ↔ `id`, Info ↔ `id.info`) → Vertauschen von Dateien schlägt fehl | `vault/VaultStore.kt:44,46,62,75,98-103` |
| P5 | Klartext-Metadaten (Originalpfad, Größe, Datum) nur verschlüsselt in `.info` | `vault/VaultStore.kt:46,121-126` |
| P6 | Tresor + Keyset in `noBackupFilesDir` (von Auto-Backup/D2D ausgenommen) | `vault/Vault.kt:32,45`; APK `classes15.dex`: `getNoBackupFilesDir` |
| P7 | Absturzsicherheit beim Hinzufügen: Inhalt als `.tmp`, `fsync` im `close()`, erst danach Rename; Original wird erst nach erfolgreichem `add()` gelöscht; Reste nach 24 h aufgeräumt | `vault/VaultStore.kt:40-58,86-96,136-146`; `filejob/FileJobs.kt:2487-2494`; `app/AppInitializers.kt:106-107` |
| P8 | Wiederherstellen: Ziel `CREATE_NEW`, `force(true)` vor dem Löschen der Tresor-Kopie | `filejob/FileJobs.kt:2541-2557` |
| P9 | `FLAG_SECURE` in `VaultActivity` (nur Release, s. BLUE-001) | `vault/VaultActivity.kt:20-24` |
| P10 | Geöffnete Tresor-Dateien werden beim Sperren/`onStop`/App-Start gelöscht | `vault/Vault.kt:86-120`, `vault/VaultViewModel.kt:46-48,55-71,154-160` |
| P11 | Backup mit Tresor erzwingt Verschlüsselung + Geräteauthentifizierung | `backup/BackupFragment.kt:384-401,436-451` |
| P12 | Modell-URL HTTPS, an Commit-Revision gepinnt; SHA-256 und Größe im Code gepinnt; falsche Datei wird gelöscht | `ai/CommandModel.kt:23-26,104-113`; APK `classes14.dex` enthält identische URL und Hash |
| P13 | Keine Crash-Reporter/Analytics; `FirebaseInitProvider` per `tools:node="remove"` entfernt | `rg -i "acra\|sentry\|crashlytics\|bugsnag\|timber\|analytics"` → keine Treffer in Code/Gradle; `AndroidManifest.xml:460-463` |
| P14 | Native Bridge loggt nur feste Strings (keine Prompts/Dateinamen) | `llm/src/main/cpp/ordo_llm.cpp:20,58,77,88,128,139` |
| P15 | WebDAV nutzt OkHttp-Standard (Plattform-TLS + Hostname-Verifier), kein „trust all“ | `app/SystemServices.kt:40`, `provider/webdav/client/Client.kt:62-75` |
| P16 | FileProvider/AppProvider nicht exportiert, Grants nur pro URI | APK-Manifest: `FileProvider exported=false grantUriPermissions=true` |

---

## Findings

### Thema 1 – Tresor

### BLUE-001 — Test-APK ist ein Debug-Build: `debuggable`, `FLAG_SECURE` aus, Debug-Signatur, kein R8
- **Schweregrad:** Hoch — Auf einem echten Gerät mit Tresor-Inhalt erlaubt `debuggable=true` das Anhängen eines Debuggers (JDWP) und `run-as` mit der App-UID. Damit kann Code im App-Kontext laufen, der den Keystore-Schlüssel benutzen und den gesamten Tresor entschlüsseln kann; die UI-Sperre (BLUE-002) bietet dann keinen Schutz. Zusätzlich ist `FLAG_SECURE` im Tresor aus, weil es an `BuildConfig.DEBUG` hängt.
- **Betroffene Dateien:** APK `AndroidManifest.xml` (`application@debuggable=true`); APK `classes4.dex` `me.zhanghai.android.files.BuildConfig` (`BUILD_TYPE="debug"`, `DEBUG=parseBoolean("true")`); APK `META-INF/CERT.RSA`; Repo `vault/VaultActivity.kt:22-24`, `app/build.gradle:56-62` (R8 nur für `release`)
- **Status:** bestaetigt
- **Reproduktion / Codepfad:**
  ```
  $ aapt2 dump xmltree --file AndroidManifest.xml ordo-0.1.0-test4.apk | rg debuggable
  A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=true
  $ dexdump classes4.dex   # BuildConfig
  name: 'BUILD_TYPE' … value: "debug"
  $ dexdump -d classes4.dex   # BuildConfig.<clinit>
  const-string v0, "true" … Boolean.parseBoolean … sput-boolean v0, BuildConfig.DEBUG
  $ dexdump -d classes15.dex   # VaultActivity.onCreate
  sget-boolean v0, BuildConfig.DEBUG … const/16 v1, #int 8192 … Window.addFlags
  $ openssl pkcs7 -inform DER -in META-INF/CERT.RSA -print_certs -noout
  subject=CN=Android Debug, O=Android, C=US
  ```
  23 nicht minifizierte `classes*.dex` (Klassennamen im Klartext) → kein R8/Shrinking.
- **Erwartet vs. tatsächlich:** Erwartet: Testbuild entspricht dem Release (R8, nicht debuggable, `FLAG_SECURE` aktiv, Release-Signatur). Tatsächlich: Debug-Build; Repo-Release-Konfiguration (`minifyEnabled true`) wurde für diese APK nicht verwendet.
- **Verifiziert:** statisch (APK). Gerätetest nötig für den Exploit-Nachweis (nur auf einem Testgerät, nicht auf Belkis' Telefonen): (1) APK installieren, Tresor mit Testbild füllen; (2) `adb shell run-as com.beko2210.ordo ls -la no_backup/ no_backup/vault` → Zugriff erwartet; (3) Screenshot im entsperrten Tresor → bei Debug-Build möglich, bei Release blockiert; (4) Recents-Vorschau prüfen.
- **Empfehlung:** Testgeräte ausschließlich mit `assembleRelease` (oder einem `releaseTest`-Buildtyp mit `debuggable false`, R8 an, eigener Testsignatur) versorgen. `FLAG_SECURE` nicht an `BuildConfig.DEBUG` koppeln, sondern an einen expliziten Schalter (z. B. `BuildConfig.SECURE_SCREENS`), der nur für lokale Screenshot-Builds aus ist. CI-Check: `aapt2 dump badging` darf kein `application-debuggable` enthalten.

### BLUE-002 — Tresor-Sperre ist nur UI-Logik; Schlüssel nicht an Benutzer-Authentifizierung gebunden, `BIOMETRIC_WEAK` zulässig
- **Schweregrad:** Mittel — Wer Code im App-Kontext ausführen kann (Root, Debug-Build, Exploit in einer der vielen Parser-Bibliotheken, Shizuku/ADB-Zugriff auf die App-UID), entschlüsselt den Tresor ohne Gerätesperre. Klasse-2-Biometrie (z. B. 2D-Gesichtserkennung) genügt zum Entsperren. Im Code bewusst dokumentiert („shortcut“).
- **Betroffene Dateien:** `vault/Vault.kt:38-40,57-59`; `vault/VaultFragment.kt:120-127,184-191`; `backup/BackupFragment.kt:441-447`; Tink `AndroidKeystore.java:52-58`
- **Status:** bestaetigt
- **Reproduktion / Codepfad:**
  ```
  $ rg -n "BIOMETRIC_WEAK or DEVICE_CREDENTIAL" app/src/main/java
  backup/BackupFragment.kt:446:  .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
  vault/VaultFragment.kt:189:    .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
  $ rg -n "setUserAuthentication|StrongBox" tink/…/AndroidKeystore.java   → keine Treffer
  ```
  `onAuthenticationSucceeded` setzt nur `viewModel.unlock()` (`VaultFragment.kt:123-127`); kein `CryptoObject`. `Vault.store` (lazy) ist danach prozessweit ohne Prüfung nutzbar.
- **Erwartet vs. tatsächlich:** Erwartet für einen „sicheren“ Tresor: Schlüsselnutzung kryptografisch an Authentifizierung gekoppelt (`setUserAuthenticationRequired(true)` + Zeitfenster oder `CryptoObject`), mindestens `BIOMETRIC_STRONG`, StrongBox wenn vorhanden. Tatsächlich: reine UI-Prüfung, TEE-Key ohne Auth-Bindung, kein StrongBox.
- **Verifiziert:** statisch. Gerätetest nötig: Testgerät mit Klasse-2-Gesichtsentsperrung → Tresor öffnet per Gesicht (erwartet ja).
- **Empfehlung:** Für 1.0: `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`. Mittelfristig (mit Export/Recovery-Pfad, wie im Code-Kommentar geplant): eigener Master-Key via `KeyGenParameterSpec` mit `setUserAuthenticationRequired(true)`, `setUserAuthenticationParameters(30, AUTH_DEVICE_CREDENTIAL or AUTH_BIOMETRIC_STRONG)`, `setIsStrongBoxBacked(true)` mit Fallback, `setInvalidatedByBiometricEnrollment(false)`; Keyset bei jedem Entsperren neu entschlüsseln und beim Sperren die Primitive verwerfen.

### BLUE-003 — Entschlüsselte Tresor-Dateien verlassen den Schutz (externe Apps, Viewer ohne `FLAG_SECURE`, Klartext bleibt im Cache)
- **Schweregrad:** Mittel — Bilder/PDFs werden in Ordos eigenen Viewern angezeigt, die **kein** `FLAG_SECURE` setzen (Screenshots, Recents-Vorschau, Bildschirmaufnahme). Alle anderen Typen gehen per implizitem `ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION` an Fremd-Apps, die Klartext cachen/indexieren können. Der Klartext liegt in `cacheDir/vault-open/<id>/<Originalname>`; verlässt der Nutzer Ordo aus dem Viewer heraus, wird erst bei Rückkehr (Timeout 2 min) oder beim nächsten App-Start gelöscht.
- **Betroffene Dateien:** `vault/VaultFragment.kt:293-307`; `vault/Vault.kt:35-36,86-103`; `vault/VaultViewModel.kt:65-80,132-135,163`; `viewer/image/ImageViewerActivity.kt:17-20` und `viewer/pdf/PdfViewerActivity.kt:35-36` (kein `FLAG_SECURE`); `util/IntentExtensions.kt:163-167`
- **Status:** bestaetigt
- **Reproduktion / Codepfad:**
  ```
  $ rg -n "FLAG_SECURE" app/src/main/java
  vault/VaultActivity.kt:23:  window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)   # einziger Treffer
  $ rg -n "createViewIntent\(mimeType\)|setClass\(|startActivitySafe\(intent\)" vault/VaultFragment.kt
  296: val intent = path.fileProviderUri.createViewIntent(mimeType).apply {
  300:     setClass(requireContext(), ImageViewerActivity::class.java)
  303:     setClass(requireContext(), PdfViewerActivity::class.java)
  306: startActivitySafe(intent)          # sonst implizit an Fremd-App
  ```
  `VaultViewModel.onStop()` sperrt nicht, solange `viewerStartedAt != 0` (`:67-71`).
- **Erwartet vs. tatsächlich:** Erwartet: Tresor-Inhalte nur in geschützten Ansichten, Klartext so kurz wie möglich. Tatsächlich: Viewer ungeschützt, Weitergabe an Fremd-Apps ohne Warnung, Klartext-Kopie unbegrenzt bis Rückkehr/Neustart.
- **Verifiziert:** statisch. Gerätetest nötig: (1) Release-Build, Bild in Tresor, öffnen → Screenshot versuchen, Recents öffnen (erwartet: Bild sichtbar = Lücke); (2) eine `.mp4`/`.docx` öffnen → App-Wahl erscheint; (3) im Viewer Home drücken, 10 min warten, `adb shell run-as … ls cache/vault-open` (nur Debug-Testgerät) → Datei noch da; (4) im Bild-Viewer Teilen/„Öffnen mit“ testen, ob der Klartext weitergegeben werden kann.
- **Empfehlung:** `FLAG_SECURE` setzen, wenn ein Viewer aus dem Tresor gestartet wird (Intent-Extra `secure=true` → `window.addFlags`), zusätzlich `setRecentsScreenshotEnabled(false)` (API 33+). Teilen/„Öffnen mit“ in diesem Modus ausblenden. Für Fremd-Apps einen Warndialog („Datei verlässt den Tresor“) und Revoke (`revokeUriPermission`) beim Sperren. Klartext-Kopie beim `onStop` des Viewers bzw. per `WorkManager`-Timer löschen; besser: Bilder/PDF direkt aus dem entschlüsselnden Stream rendern statt Datei.

### BLUE-004 — Tresor-Backup: WinZip-AES mit PBKDF2-HMAC-SHA1 × 1000, Mindestlänge 8, Dateinamen und Ordnerstruktur im Klartext
- **Schweregrad:** Mittel — Das Backup ist der einzige Weg, Tresor-Dateien zu sichern; es landet auf USB/Cloud. WinZip-AE-2 leitet den Schlüssel mit nur 1000 PBKDF2-SHA1-Iterationen ab (Offline-Bruteforce auf GPU sehr schnell), und ZIP-Einträge (Name, Pfad, Größe, mtime) sind unverschlüsselt. So werden u. a. die Originalpfade der Tresor-Dateien (`<Tresor>/Download/Urlaub/…`) sichtbar.
- **Betroffene Dateien:** `filejob/FileJobs.kt:2633,2743-2752`; `provider/archive/archiver/WriteArchive.kt:39-46`; `backup/BackupFragment.kt:384-391,423,593`; libarchive `archive_write_set_format_zip.c:2460-2461`
- **Status:** bestaetigt
- **Reproduktion / Codepfad:**
  ```
  $ rg -n "aes256|writeSetPassphrase" provider/archive/archiver/WriteArchive.kt
  41: Archive.writeSetPassphrase(archive, password.toByteArray())
  45: archive, null, "encryption".toByteArray(), "aes256".toByteArray()
  $ rg -n "PASSWORD_MIN_LENGTH =" backup/BackupFragment.kt
  593: private const val PASSWORD_MIN_LENGTH = 8
  $ sed -n 2460,2461p archive_write_set_format_zip.c
  ret = archive_pbkdf2_sha1(passphrase, strlen(passphrase), salt, salt_len, 1000, derived_key, …
  $ rg -n 'backupEntryName\(entry.originalPath' filejob/FileJobs.kt
  2747: "$vaultFolder/${backupEntryName(entry.originalPath, storageRoot)}", usedNames
  $ strings lib/arm64-v8a/libarchive-jni.so | rg mbedtls_pkcs5_pbkdf2_hmac   → vorhanden (Krypto aktiv)
  ```
- **Erwartet vs. tatsächlich:** Erwartet: Tresor-Inhalte außerhalb des Geräts mindestens so stark geschützt wie im Tresor, Metadaten verborgen. Tatsächlich: schwacher KDF, Klartext-Namen/Struktur/Zeitstempel. Datenschutztext („AES-256“) ist formal korrekt, verschweigt aber beides.
- **Verifiziert:** statisch. Gerätetest nötig: Backup mit Tresor erstellen, auf PC `unzip -l backup.zip` → Dateinamen ohne Passwort lesbar (erwartet ja); `zipinfo -v` zeigt AE-2.
- **Empfehlung:** Tresor-Anteil nicht als ZIP-Einträge, sondern als ein einzelner Tink-Container (StreamingAead) mit passwortbasiertem Schlüssel (Argon2id/scrypt, z. B. via BouncyCastle, die bereits im Projekt ist) in das ZIP legen; Dateinamen nur innerhalb. Mindestens: Passwort ≥ 12 Zeichen + Stärke-Anzeige, Hinweis „Dateinamen sind sichtbar“. Restore-Pfad zurück in den Tresor anbieten.

### BLUE-005 — Metadaten auf dem Datenträger: Zeitstempel im Dateinamen, Größe ableitbar, keine Integrität der Eintragsmenge
- **Schweregrad:** Info — Nur mit Zugriff auf App-Daten (Root/Debug) lesbar; Inhalte und Namen bleiben geschützt.
- **Betroffene Dateien:** `vault/VaultStore.kt:39,64-81,83-84`
- **Status:** bestaetigt
- **Reproduktion / Codepfad:** `rg -n 'val id = ' vault/VaultStore.kt` → `39: val id = "$now-${UUID.randomUUID().toString().take(8)}"` → Hinzufügezeit im Klartext-Dateinamen; Ciphertext-Größe ≈ Klartextgröße + Header + 16 B pro 1-MB-Segment; mtime der Datei. Es gibt kein signiertes Verzeichnis/Manifest: Löschen oder Zurückspielen eines alten (gültigen) Eintragspaars bleibt unbemerkt.
- **Erwartet vs. tatsächlich:** Erwartet (optional): zufällige IDs, Padding. Tatsächlich: geringe Metadaten-Lecks.
- **Verifiziert:** statisch.
- **Empfehlung:** ID nur zufällig (`UUID`), Zeit nur in `.info`; optional Größen-Padding auf Segmentgrenzen; optional verschlüsseltes Index-File mit Eintragsliste + Zähler.

### BLUE-006 — Rename des Tresor-Eintrags wird nicht per Verzeichnis-`fsync` dauerhaft gemacht, bevor das Original gelöscht wird
- **Schweregrad:** Niedrig — Nur bei Stromausfall/Kernel-Crash im engen Zeitfenster; Folge wäre aber Datenverlust: Eintrag bleibt als `.tmp`, `purgeLeftovers()` löscht ihn nach 24 h, Original ist bereits gelöscht.
- **Betroffene Dateien:** `vault/VaultStore.kt:47-52,86-96`; `filejob/FileJobs.kt:2487-2489`
- **Status:** Verdacht
- **Reproduktion / Codepfad:** `add()` synchronisiert Dateiinhalte (`SyncingFileOutputStream.close()` → `fd.sync()`), aber nach `tempFile.renameTo(File(directory, id))` (`:48`) wird das Verzeichnis nicht synchronisiert. Unmittelbar danach `path.delete()` auf einem anderen Dateisystem (FUSE/Shared Storage) – keine Ordnungsgarantie zwischen beiden.
- **Erwartet vs. tatsächlich:** Erwartet: Rename dauerhaft, bevor das Original gelöscht wird; `.tmp`-Leichen mit gültigem Inhalt nicht automatisch löschen. Tatsächlich: beides nicht gegeben.
- **Verifiziert:** statisch; Gerätetest nötig (schwer reproduzierbar): Testgerät, großes File in Tresor verschieben, direkt nach Abschluss Akku ziehen/`adb reboot -f` (nur Testgerät) → prüfen, ob Eintrag vorhanden und Original gelöscht.
- **Empfehlung:** Nach dem Rename `Os.fsync(Os.open(directory.path, O_RDONLY, 0))`. `purgeLeftovers()` sollte `.tmp`-Dateien mit vollständigem, entschlüsselbarem Inhalt nicht löschen, sondern als Eintrag retten (das zugehörige `.info` existiert dann ja).

### Thema 2 – FTP / SFTP / SMB / WebDAV

### BLUE-007 — SFTP akzeptiert jeden Host-Key (`PromiscuousVerifier`)
- **Schweregrad:** Hoch — Jeder im selben Netz (öffentliches WLAN, kompromittierter Router, ARP/DNS-Spoofing) kann sich als SFTP-Server ausgeben; bei Passwort-Auth erhält er das Klartext-Passwort, außerdem kann er Dateien lesen/manipulieren. Kein TOFU, keine Warnung bei geändertem Key.
- **Betroffene Dateien:** `provider/sftp/client/Client.kt:21,240`
- **Status:** bestaetigt
- **Reproduktion / Codepfad:**
  ```
  $ rg -n "PromiscuousVerifier" app/src/main/java/me/zhanghai/android/files/provider
  sftp/client/Client.kt:21:  import net.schmizz.sshj.transport.verification.PromiscuousVerifier
  sftp/client/Client.kt:240: val sshClient = SSHClient().apply { addHostKeyVerifier(PromiscuousVerifier()) }
  $ dexdump -d classes7.dex | rg PromiscuousVerifier
  new-instance v7, Lnet/schmizz/sshj/transport/verification/PromiscuousVerifier;   # identisch in der APK
  ```
- **Erwartet vs. tatsächlich:** Erwartet: Host-Key beim ersten Verbinden anzeigen (Fingerprint SHA-256) und bestätigen lassen, danach pinnen; Abweichung = harter Fehler. Tatsächlich: keine Prüfung.
- **Verifiziert:** statisch (Repo + APK). Gerätetest nötig: Testgerät + Laptop im selben Netz; SFTP-Server A einrichten und verbinden; dann gleiche IP mit Server B (anderer Host-Key) → Ordo verbindet ohne Warnung (erwartet: ja = Lücke).
- **Empfehlung:** Eigenen `HostKeyVerifier` mit TOFU: Fingerprint im Dialog bestätigen, pro Storage (`host:port`) speichern (in den Storage-Daten), bei Mismatch abbrechen mit klarer Warnung. sshj bietet `OpenSSHKnownHosts` als Basis.

### BLUE-008 — FTPS/FTPES prüft weder Zertifikatskette noch Hostnamen
- **Schweregrad:** Hoch — `FTPSClient` wird ohne `setTrustManager`/`setHostnameVerifier` benutzt. Der commons-net-Default prüft nur das Gültigkeitsdatum jedes Zertifikats; jedes selbstsignierte Zertifikat mit gültigem Datum wird akzeptiert → MITM mit Passwortdiebstahl trotz „verschlüsselter“ Verbindung (falsches Sicherheitsgefühl).
- **Betroffene Dateien:** `provider/ftp/client/Protocol.kt:13-14`; `provider/ftp/client/Client.kt:104-107`; commons-net 3.13.0 `FTPSClient.java:154,160,1118`, `TrustManagerUtils.java:52-58,106-108`
- **Status:** bestaetigt
- **Reproduktion / Codepfad:**
  ```
  $ rg -n "setTrustManager|setHostnameVerifier|TrustManager|HostnameVerifier" app/src/main/java/me/zhanghai/android/files/provider/ftp   → keine Treffer
  $ rg -n "private TrustManager trustManager =|private HostnameVerifier" cn/org/apache/commons/net/ftp/FTPSClient.java
  154: private TrustManager trustManager = TrustManagerUtils.getValidateServerCertificateTrustManager();
  160: private HostnameVerifier hostnameVerifier;
  $ sed -n 52,58p cn/org/apache/commons/net/util/TrustManagerUtils.java
  public void checkServerTrusted(...) { if (checkServerValidity) { for (cert : certificates) { cert.checkValidity(); } } }
  ```
- **Erwartet vs. tatsächlich:** Erwartet: Plattform-Trust (System-CAs) + Hostnamenprüfung, oder TOFU-Pinning für selbstsignierte NAS-Zertifikate. Tatsächlich: praktisch „trust all“.
- **Verifiziert:** statisch. Gerätetest nötig: FTPS-Server mit selbstsigniertem Zertifikat für einen falschen Hostnamen (z. B. `openssl req -x509 -subj /CN=evil`) → Ordo verbindet ohne Warnung (erwartet: ja = Lücke).
- **Empfehlung:** `setTrustManager(TrustManagerUtils.getDefaultTrustManager(null))` bzw. Plattform-`X509TrustManager` + `setHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier())` (oder OkHttp-Verifier). Für selbstsignierte NAS: Zertifikats-Fingerprint per TOFU-Dialog pinnen.

### BLUE-009 — Server-Passwörter, SFTP-Private-Keys und FTP-Server-Passwort unverschlüsselt in SharedPreferences und im Android-Backup
- **Schweregrad:** Mittel — Klartext-äquivalent (Base64 eines `Parcel`) in den Default-SharedPreferences. `allowBackup=true` + `fullBackupContent="true"` ohne Regeln, kein `dataExtractionRules` → Zugangsdaten gehen in Google-Cloud-Backup und Gerät-zu-Gerät-Übertragung. Mit BLUE-001 zusätzlich per `run-as` lesbar.
- **Betroffene Dateien:** `settings/Settings.kt:29-33,129-132`; `settings/SettingLiveDatas.kt:296,303`; `storage/SmbServer.kt:25`, `storage/FtpServer.kt:25`, `provider/sftp/client/Authentication.kt:25-36`, `provider/webdav/client/Authentication.kt:36-39`; `storage/Storages.kt:14-22`; `AndroidManifest.xml:49,52`
- **Status:** bestaetigt
- **Reproduktion / Codepfad:**
  ```
  $ rg -n "asBase64\(\)\?.toParcelValue|toParcelBase64" settings/SettingLiveDatas.kt
  296: sharedPreferences.getString(key, null)?.asBase64()?.toParcelValue()
  303: sharedPreferences.edit { putString(key, value?.toParcelBase64()?.value) }
  $ rg -n "val privateKey: String|val privateKeyPassword" provider/sftp/client/Authentication.kt
  34: val privateKey: String,
  35: val privateKeyPassword: String?
  $ aapt2 dump xmltree --file AndroidManifest.xml ordo-0.1.0-test4.apk | rg "allowBackup|fullBackupContent|dataExtractionRules"
  allowBackup(0x01010280)=true
  fullBackupContent(0x010104eb)=true          # kein dataExtractionRules
  ```
  Datenschutztext erwähnt Backup der „Einstellungen“, aber nicht ausdrücklich Passwörter/Schlüssel (`res/values/legal.xml:47`).
- **Erwartet vs. tatsächlich:** Erwartet: Secrets mit Keystore-gebundenem Schlüssel verschlüsselt (Tink `Aead` ist schon im Projekt) und von Backups ausgeschlossen. Tatsächlich: Klartext-äquivalent, im Backup.
- **Verifiziert:** statisch. Gerätetest nötig (Testgerät, Debug-Build): SMB-Server mit Testpasswort anlegen → `adb shell run-as com.beko2210.ordo cat shared_prefs/com.beko2210.ordo_preferences.xml` → Base64 dekodieren, Passwort sichtbar; `adb shell bmgr backupnow com.beko2210.ordo` + Prüfen per `adb shell dumpsys backup` bzw. D2D-Test, dass die Prefs-Datei enthalten ist.
- **Empfehlung:** Secrets getrennt speichern: Tink-`Aead` mit eigenem AndroidKeystore-Key (wie Tresor-Keyset, in `noBackupFilesDir`); `res/xml/data_extraction_rules.xml` (API 31+) und `full_backup_content.xml` (≤30) anlegen, die die Prefs mit Zugangsdaten ausschließen (oder `allowBackup=false`). Datenschutztext präzisieren.

### BLUE-010 — Network-Security-Config vertraut Benutzer-CAs und erlaubt Klartext global
- **Schweregrad:** Niedrig — Ein vom Nutzer (oder per MDM/Social Engineering) installiertes CA-Zertifikat kann WebDAV-HTTPS mitlesen; `dav://` und `ftp://` übertragen Passwörter im Klartext. Klartext-Protokolle sind bewusst wählbar (NAS im LAN), daher Niedrig.
- **Betroffene Dateien:** `res/xml/netework_security_config.xml:11-15`; `AndroidManifest.xml:55,62`; `provider/webdav/client/Protocol.kt` (`DAV("dav","http",80)`)
- **Status:** bestaetigt
- **Reproduktion / Codepfad:**
  ```
  $ aapt2 dump xmltree --file res/xml/netework_security_config.xml ordo-0.1.0-test4.apk
  E: base-config  A: cleartextTrafficPermitted=true
    E: trust-anchors  E: certificates A: src="system"  E: certificates A: src="user"
  ```
- **Erwartet vs. tatsächlich:** Erwartet: nur System-CAs; Klartext nur nach expliziter Nutzerbestätigung. Tatsächlich: beides global offen, ohne UI-Warnung.
- **Verifiziert:** statisch. Gerätetest nötig: Benutzer-CA installieren, mitmproxy, WebDAV-HTTPS-Storage öffnen → Verkehr lesbar (erwartet ja).
- **Empfehlung:** `src="user"` entfernen (oder nur in `debug-overrides`). Beim Anlegen von `ftp://`/`dav://` mit Passwort einen deutlichen Hinweis „Passwort wird unverschlüsselt übertragen“ anzeigen.

### BLUE-011 — SMB: Signing nicht verlangt, keine SMB3-Verschlüsselung
- **Schweregrad:** Mittel — `SMBClient()` mit smbj-Defaults: `withSigningRequired(false)`, `withEncryptData(false)`. Ein MITM im LAN kann Dateiinhalte mitlesen und – sofern der Server Signing nicht erzwingt – manipulieren.
- **Betroffene Dateien:** `provider/smb/client/Client.kt:56,614-616`; smbj 0.11.5 `SmbConfig.java:95,106`
- **Status:** bestaetigt
- **Reproduktion / Codepfad:**
  ```
  $ rg -n "SMBClient\(|SmbConfig|withSigning|withEncrypt" provider/smb
  client/Client.kt:56: private val client = SMBClient()
  $ sed -n 95,106p smbj/com/hierynomus/smbj/SmbConfig.java
  .withSigningRequired(false) … .withEncryptData(false);
  ```
- **Erwartet vs. tatsächlich:** Erwartet: Signing verlangt, Verschlüsselung bevorzugt (SMB 3.x). Tatsächlich: beides aus.
- **Verifiziert:** statisch. Gerätetest nötig: Samba mit `server signing = disabled`, `smb encrypt = off` → Ordo verbindet; Wireshark zeigt Dateiinhalte im Klartext.
- **Empfehlung:** `SMBClient(SmbConfig.builder().withSigningRequired(true).withEncryptData(true).build())`, optional pro Server abschaltbar mit Warnung (für alte NAS).

### BLUE-012 — Eingebauter FTP-Server: standardmäßig anonym und beschreibbar, Klartext, gesamter interner Speicher
- **Schweregrad:** Mittel — Wenn der Nutzer den Server startet (auch per Quick-Settings-Kachel), kann jeder im selben WLAN ohne Passwort den gesamten internen Speicher (inkl. DCIM, Downloads, `.OrdoTrash`) lesen und **schreiben/löschen**. Nicht automatisch aktiv, daher nicht Hoch.
- **Betroffene Dateien:** `res/values/donottranslate_prefs.xml:28,36`; `settings/Settings.kt:139-144`; `ftpserver/FtpServer.kt:30-44`; `ftpserver/FtpServerService.kt:97-107`
- **Status:** bestaetigt
- **Reproduktion / Codepfad:**
  ```
  $ rg -n "pref_default_value_ftp_server_(anonymous_login|writable)" res/values/donottranslate_prefs.xml
  28: <bool name="pref_default_value_ftp_server_anonymous_login">true</bool>
  36: <bool name="pref_default_value_ftp_server_writable">true</bool>
  ```
  `FtpServer.kt:43` setzt `isAnonymousLoginEnabled = true` unabhängig von der Einstellung; Listener ohne Bind-Adresse und ohne TLS (`:30-32`).
- **Erwartet vs. tatsächlich:** Erwartet (sicherer Default): Login mit zufällig generiertem Passwort, nur lesend, Home = ein gewählter Ordner. Tatsächlich: anonym + schreibend + `/storage/emulated/0`.
- **Verifiziert:** statisch. Gerätetest nötig: Server mit Default-Einstellungen starten, von einem Laptop im WLAN `ftp <ip> 2121` als `anonymous` → `put`/`delete` testen.
- **Empfehlung:** Defaults umkehren (anonym aus, nur lesend, zufälliges Passwort beim ersten Start), `isAnonymousLoginEnabled` an die Einstellung koppeln, Hinweis „unverschlüsselt“, Auto-Stopp nach Inaktivität, optional FTPS.

### Thema 3 – Datenlecks

### BLUE-013 — Viele `printStackTrace()` mit Pfaden/Dateinamen, Bibliotheks-Logs via slf4j-android, kein Log-Strip im Release
- **Schweregrad:** Niedrig — Logcat ist für normale Apps nicht lesbar, landet aber in Bugreports/ADB-Sitzungen. Exception-Texte enthalten Pfade und Tresor-Dateinamen (z. B. Fehlerdialog `e.toString()`).
- **Betroffene Dateien:** z. B. `vault/VaultStore.kt:37,49,78`; `vault/Vault.kt:91,117`; `vault/VaultViewModel.kt:92,117,147`; `filejob/FileJobs.kt:2499,2561`; `ai/CommandSheetFragment.kt:266,299`; `app/proguard-rules.pro` (kein `-assumenosideeffects`); `app/build.gradle:207` (`slf4j-android`)
- **Status:** bestaetigt
- **Reproduktion / Codepfad:**
  ```
  $ rg -n "printStackTrace" app/src/main/java | wc -l            → 159
  $ rg -n "printStackTrace" app/src/main/java/me/zhanghai/android/files/{ai,vault,backup} filejob/FileJobs.kt | wc -l   → 35
  $ rg -n "assumenosideeffects" app/proguard-rules.pro            → keine Treffer
  ```
  Native: `ordo_llm.cpp` loggt nur feste Strings; llama.cpp nutzt seinen Default-Logger (`llama_log_callback_default` in `libordo_llm.so` vorhanden), der auf `stderr` schreibt – auf Android normalerweise nicht im Logcat (Gerätetest).
- **Erwartet vs. tatsächlich:** Erwartet: keine personenbezogenen Pfade in Release-Logs. Tatsächlich: ungefiltert.
- **Verifiziert:** statisch. Gerätetest nötig: `adb logcat -c`; Datei in Tresor verschieben, Fehler provozieren (z. B. schreibgeschützte Quelle), KI-Befehl ausführen und Modell laden; `adb logcat -d | rg -i "vault|gguf|<Dateiname>|<Befehlstext>"`.
- **Empfehlung:** R8-Regeln `-assumenosideeffects class android.util.Log { v(...); d(...); i(...); }` und `java.lang.Throwable.printStackTrace()` im Release entfernen bzw. durch einen eigenen Logger mit Release-No-Op ersetzen; `llama_log_set` auf eine No-Op/Fehler-only-Callback setzen.

### BLUE-014 — FTP-Server-Passwort in die Zwischenablage ohne `EXTRA_IS_SENSITIVE` und im Toast angezeigt
- **Schweregrad:** Niedrig — Android 13+ zeigt Clipboard-Inhalte in der Vorschau-Overlay an; Tastatur-/Clipboard-Manager-Apps speichern sie. Zusätzlich zeigt `copyText()` den kopierten Text (bis 40 Zeichen) im Toast.
- **Betroffene Dateien:** `ftpserver/FtpServerUrlPreference.kt:93-101`; `util/ClipboardManagerExtensions.kt:14-18,22-41`
- **Status:** bestaetigt
- **Reproduktion / Codepfad:** `rg -n "copyText\(password" ftpserver/FtpServerUrlPreference.kt` → `99: clipboardManager.copyText(password, context)`; `rg -n "EXTRA_IS_SENSITIVE" app/src/main/java` → keine Treffer; `ClipboardManagerExtensions.kt:41` → `showToast(getString(R.string.copied_to_clipboard_format, copiedText))`.
- **Erwartet vs. tatsächlich:** Erwartet: sensible Clips markiert, kein Klartext im Toast. Tatsächlich: beides fehlt.
- **Verifiziert:** statisch. Gerätetest nötig: Android 13+, FTP-Server mit Passwort, „Passwort kopieren“ → Clipboard-Overlay und Toast zeigen Passwort.
- **Empfehlung:** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33+, bzw. `"android.content.extra.IS_SENSITIVE"` davor) setzen, Toast ohne Inhalt; optional Clip nach 60 s löschen.

### BLUE-015 — `FLAG_SECURE` fehlt bei Passwort-Ansichten und im KI-Befehls-Sheet
- **Schweregrad:** Niedrig — Backup-Passwort (mit „Passwort anzeigen“-Toggle), Server-Editoren (Passwort/Private Key) und das KI-Sheet (Befehlstext, Dateinamen in der Vorschau) erscheinen in Screenshots/Recents/Screen-Recording.
- **Betroffene Dateien:** `backup/BackupFragment.kt` / `res/layout/backup_fragment.xml:266-279`; `storage/EditSftpServerFragment.kt`, `EditSmbServerFragment.kt`, `EditFtpServerFragment.kt`, `EditWebDavServerFragment.kt`; `ai/CommandSheetFragment.kt:118-131`
- **Status:** bestaetigt
- **Reproduktion / Codepfad:** `rg -n "FLAG_SECURE" app/src/main/java` → nur `vault/VaultActivity.kt:23`.
- **Erwartet vs. tatsächlich:** Erwartet: sensible Eingaben geschützt. Tatsächlich: ungeschützt.
- **Verifiziert:** statisch. Gerätetest nötig: Release-Build, jeweilige Ansicht öffnen, Screenshot + Recents prüfen.
- **Empfehlung:** `FLAG_SECURE` in `BackupActivity` und den `Edit*ServerActivity`s; im KI-Sheet optional (Einstellung „Bildschirmschutz für KI“).

### Thema 4 – Berechtigungen und Komponenten

Berechtigungen laut APK (`aapt2 dump permissions ordo-0.1.0-test4.apk`), inkl. Library-Merges:

| Berechtigung | Herkunft | Zweck im Code | Nötig? |
|---|---|---|---|
| `MANAGE_EXTERNAL_STORAGE` | App | Dateimanager-Kern | ja (Play-Policy-Begründung nötig) |
| `WRITE_EXTERNAL_STORAGE` (maxSdk 32) | App | Speicherzugriff ≤ Android 12 | ja |
| `INTERNET` | App | Netzwerk-Storages, FTP-Server, Modell-Download (DownloadManager) | ja |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | App | FTP-Server-IP, LAN-SMB-Suche | ja |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | App | `FileJobService`, `FtpServerService` | ja (specialUse begründet im Manifest) |
| `POST_NOTIFICATIONS`, `WAKE_LOCK` | App | Fortschritt, lange Jobs | ja |
| `QUERY_ALL_PACKAGES` | App | App-Übersicht, APK-Infos | fraglich – für „Öffnen mit“ reichen `<queries>`; Play verlangt Begründung |
| `REQUEST_INSTALL_PACKAGES` | App | APK installieren | optional – nur wenn Feature behalten |
| `REQUEST_DELETE_PACKAGES` | App | App deinstallieren | optional |
| `com.android.launcher.permission.INSTALL_SHORTCUT` | App | Verknüpfungen (Legacy < API 26) | gering, ok |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | androidx.biometric | Tresor/Backup-Prompt | ja |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | androidx.core | interne Receiver-Absicherung | ja (signature) |
| `moe.shizuku.manager.permission.API_V23` | Shizuku | optionaler Root/ADB-Zugriff | optional |

Exportierte Komponenten (APK): `HomeActivity`, `FileListActivity`, `OpenFileActivity`, `FtpServerActivity`, `SettingsActivity`, `SaveAsActivity`, `TextEditorActivity`, `ImageViewerActivity`, `PdfViewerActivity`; `FtpServerTileService` (`BIND_QUICK_SETTINGS_TILE`); `ShizukuProvider` (`INTERACT_ACROSS_USERS_FULL`); `ProfileInstallReceiver` (`DUMP`). `FileProvider`/`AppProvider` nicht exportiert; `FtpServerReceiver`, `FileJobReceiver`, `VaultActivity`, `BackupActivity` nicht exportiert.

### BLUE-016 — Breiter Berechtigungssatz über den Kern hinaus
- **Schweregrad:** Info — Alle Berechtigungen sind vom Upstream geerbt und funktional erklärbar; für das Ziel „sicherster KI-Dateimanager“ und die Play-Prüfung sind `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES`, `REQUEST_DELETE_PACKAGES` und Shizuku zusätzliche Angriffs-/Policy-Fläche. `requestLegacyExternalStorage`/`requestRawExternalStorageAccess` gesetzt.
- **Betroffene Dateien:** `AndroidManifest.xml:16-38,41,56-57`; APK `aapt2 dump permissions`
- **Status:** bestaetigt
- **Reproduktion / Codepfad:** `aapt2 dump permissions ordo-0.1.0-test4.apk` (Ausgabe siehe Tabelle oben).
- **Erwartet vs. tatsächlich:** Erwartet: Minimalprinzip. Tatsächlich: Upstream-Umfang.
- **Verifiziert:** statisch.
- **Empfehlung:** Für 1.0 prüfen, ob App-Installation/-Deinstallation und App-Liste nötig sind; sonst entfernen bzw. `QUERY_ALL_PACKAGES` durch `<queries>` ersetzen. Datenschutztext listet die Rechte bereits (`legal.xml:46`), die Tabelle oben kann übernommen werden.

### BLUE-017 — Exportierte Viewer/Editor nehmen beliebige Pfade an; App-eigene Daten sind seit Ordo-Änderung ohne Root erreichbar
- **Schweregrad:** Niedrig — Eine Fremd-App kann `ImageViewerActivity`/`TextEditorActivity`/`PdfViewerActivity` mit Extra `org.openintents.extra.ABSOLUTE_PATH` oder `…extra.PATH_URI` auf Ordos private Dateien (z. B. `no_backup/vault.keyset`, `shared_prefs/*.xml` mit Zugangsdaten) starten. Ordo hat `LinuxPath.isRootRequired()` so geändert, dass `dataDir` immer direkt erreichbar ist. Ein Lesezugriff der Fremd-App selbst entsteht nur mit Nutzerinteraktion (z. B. „Teilen“ aus dem Viewer an die Fremd-App, „Löschen“ des Keysets durch Täuschung).
- **Betroffene Dateien:** `util/IntentPathExtensions.kt:21-31,66-73`; `provider/linux/LinuxPath.kt:87-92`; `AndroidManifest.xml:366-406` (exportierte Viewer)
- **Status:** Verdacht
- **Reproduktion / Codepfad:** `extraPath` liest `EXTRA_PATH_URI`, `intent.data`, `EXTRA_INITIAL_URI`, `org.openintents.extra.ABSOLUTE_PATH` ohne Prüfung, ob der Pfad im App-Datenverzeichnis liegt; `LinuxPath.kt:90` → `if (file.startsWith(application.dataDir)) return false`.
- **Erwartet vs. tatsächlich:** Erwartet: externe Intents dürfen nicht auf App-private Pfade zeigen. Tatsächlich: keine Einschränkung erkennbar.
- **Verifiziert:** statisch. Gerätetest nötig (Testgerät): `adb shell am start -n com.beko2210.ordo/me.zhanghai.android.files.viewer.text.TextEditorActivity -a android.intent.action.VIEW -t text/plain --es org.openintents.extra.ABSOLUTE_PATH /data/user/0/com.beko2210.ordo/shared_prefs/com.beko2210.ordo_preferences.xml` → wird der Inhalt angezeigt? Danach Teilen-Menü prüfen.
- **Empfehlung:** In exportierten Einstiegspunkten Pfade aus Fremd-Intents verwerfen, wenn sie unter `dataDir`/`noBackupFilesDir`/`cacheDir` liegen (außer `vault-open`, und dann nur aus eigenen Intents); interne Aufrufe über explizite, nicht exportierte Activity-Alias führen.

### Thema 5 – Modell-Download und GGUF

### BLUE-018 — Modell-Hash wird nur einmal geprüft; danach lädt llama.cpp jede Datei mit passender Größe aus extern erreichbarem Speicher
- **Schweregrad:** Mittel — Nach der ersten erfolgreichen Prüfung gilt `State.Ready`, sobald das Prefs-Flag `verified` gesetzt ist und die Größe stimmt. `CommandEngine.ensure()` lädt die Datei ohne erneute Prüfung in den nativen GGUF-Parser. Die Datei liegt in `Android/data/com.beko2210.ordo/files/models/`: per USB/MTP, ADB, Shizuku/Root und auf Android ≤ 10 (minSdk 23, `requestLegacyExternalStorage`) von Apps mit Speicherrecht ersetzbar. Ein ausgetauschtes Modell kann (a) GGUF-Parserfehler in llama.cpp ausnutzen (native Codeausführung im App-Kontext → Tresor, BLUE-002) oder (b) manipulierte Befehlspläne erzeugen. Das Flag liegt in auto-gesicherten SharedPreferences (`command_model`).
- **Betroffene Dateien:** `ai/CommandModel.kt:50,64-73,104-113`; `ai/CommandEngine.kt:58-74`; `ai/CommandSheetFragment.kt:109-114`; `llm/src/main/cpp/ordo_llm.cpp:75`
- **Status:** bestaetigt
- **Reproduktion / Codepfad:**
  ```
  $ rg -n "getExternalFilesDir|KEY_VERIFIED, false\) && file.length\(\) == SIZE" ai/CommandModel.kt
  50: fun file(context: Context): File = File(context.getExternalFilesDir("models"), NAME)
  67: if (prefs.getBoolean(KEY_VERIFIED, false) && file.length() == SIZE) {
  $ sed -n 65,66p ai/CommandEngine.kt
  val file = CommandModel.file(context.applicationContext)
  val loaded = LlamaEngine.load(file.path, threads(), 2048)      # kein Hash-Check
  ```
  Positiv: Frisch abgelegte Datei ohne Download-ID wird gehasht (`:72` → `Verifying`); URL/Hash im Code gepinnt.
- **Erwartet vs. tatsächlich:** Erwartet: Modell an einem nur für die App schreibbaren Ort, Integrität bei jedem Laden (oder an eine nicht fälschbare Eigenschaft gebunden). Tatsächlich: einmalige Prüfung, extern erreichbarer Ort.
- **Verifiziert:** statisch. Gerätetest nötig (Testgerät): Modell herunterladen und verifizieren lassen; per `adb push` eine gleich große Datei (2 740 937 888 Byte, z. B. `truncate -s`) nach `/sdcard/Android/data/com.beko2210.ordo/files/models/qwen3.5-4b-q4_k_m.gguf` schieben; KI-Sheet öffnen → wird `Ready` angezeigt und `nativeLoad` versucht (Logcat `OrdoLlm: model load failed`)? Erwartet: ja = Lücke.
- **Empfehlung:** Nach erfolgreicher Prüfung das Modell nach `noBackupFilesDir`/`filesDir` verschieben (gleiche Partition meist `rename`, sonst Kopie) und nur von dort laden; zusätzlich günstiger Schnell-Check bei jedem Laden (Größe + mtime + Inode in Prefs, bei Abweichung neu hashen) oder voller Hash im Hintergrund vor dem ersten Laden nach App-Start. `command_model`-Prefs vom Backup ausschließen. llama.cpp-Version regelmäßig auf GGUF-Parser-Fixes aktualisieren.

### BLUE-019 — Datenschutztext nennt den Modell-Download (Hugging Face) und das Backup von Server-Zugangsdaten nicht
- **Schweregrad:** Niedrig — Transparenzlücke: Beim Download sehen Hugging Face und dessen CDN die IP-Adresse; der Text sagt „Internet … nur für Server, die du einrichtest, und für den eingebauten FTP-Server“.
- **Betroffene Dateien:** `res/values/legal.xml:44,46,47`; `res/values-de/legal.xml:44,46` (verbindliche Fassung, gleiche Aussage); `ai/CommandModel.kt:23-24`
- **Status:** bestaetigt
- **Reproduktion / Codepfad:** `rg -n "Hugging|Modell|KI" app/src/main/res/values-de/legal.xml` → keine Treffer; `rg -n "huggingface" ai/CommandModel.kt` → `23: private const val URL = "https://huggingface.co/unsloth/…`.
- **Erwartet vs. tatsächlich:** Erwartet: Download-Quelle und Datenfluss benannt. Tatsächlich: fehlt.
- **Verifiziert:** statisch.
- **Empfehlung:** Abschnitt „KI-Modell“ ergänzen (einmaliger, vom Nutzer gestarteter Download von huggingface.co über Android-DownloadManager, danach vollständig offline) und „Anmeldedaten von Servern sind Teil des Android-Backups“ bzw. nach Umsetzung von BLUE-009 „nicht Teil“.

---

## Abgleich Repo (5cae538) vs. APK (0.1.0-test4)

| Punkt | Repo | APK | Ergebnis |
|---|---|---|---|
| Build-Typ | Release: `minifyEnabled true`, `shrinkResources true` | `BUILD_TYPE="debug"`, `DEBUG=true`, `debuggable=true`, 23 unminifizierte Dex, Debug-Zertifikat | **Abweichung** (BLUE-001) |
| `FLAG_SECURE` Tresor | nur wenn `!DEBUG` | `DEBUG=true` → aus | **Abweichung in Wirkung** |
| Manifest-Rechte | 19 Einträge App | + `USE_BIOMETRIC`, `USE_FINGERPRINT`, `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, Shizuku `API_V23` (Library-Merges) | erwartbar |
| `allowBackup`/`fullBackupContent`/NSC/Cleartext | `true`/`true`/user-CAs/`true` | identisch | gleich |
| Tresor-Krypto | `AES256_GCM_HKDF_1MB`, Keystore-Wrap, `noBackupFilesDir` | identische Aufrufe in `classes15.dex` | gleich |
| SFTP `PromiscuousVerifier` | `Client.kt:240` | `classes7.dex` | gleich |
| Modell-URL/SHA-256 | `CommandModel.kt:23-26` | identische Strings in `classes14.dex` | gleich |
| Native Libs | llama.cpp nur arm64-v8a/x86_64 (`llm/build.gradle`) | `libordo_llm.so` nur arm64-v8a, x86_64 | gleich |

---

## Gerätetests nötig (nur Testgerät, nicht Belkis' Telefone)

1. **Release-Build-Härtung (BLUE-001):** `aapt2 dump badging` des Release-APK ohne `application-debuggable`; Screenshot im Tresor muss blockiert sein; Recents zeigt leere Vorschau.
2. **Vault-Viewer (BLUE-003):** Bild/PDF aus Tresor öffnen → Screenshot/Recents; `.mp4`/`.docx` öffnen → Fremd-App bekommt Zugriff? Viewer verlassen, 10 min warten → liegt `cache/vault-open` noch da (Debug-Testgerät, `run-as`)?
3. **SFTP-MITM (BLUE-007):** Server mit anderem Host-Key unter gleicher IP → verbindet Ordo ohne Warnung?
4. **FTPS-MITM (BLUE-008):** Selbstsigniertes Zertifikat mit falschem CN → verbindet Ordo?
5. **SMB (BLUE-011):** Samba ohne Signing/Encryption, Wireshark → Klartext?
6. **Zugangsdaten im Backup (BLUE-009):** Prefs-Inhalt per `run-as`; `bmgr backupnow` bzw. D2D-Übertragung → Datei enthalten?
7. **Modell-Austausch (BLUE-018):** gleich große Datei per `adb push` → `Ready` ohne neuen Hash?
8. **Logcat (BLUE-013):** Tresor-Aktionen, Fehlerfälle, KI-Befehl + Modell laden → Pfade/Dateinamen/Befehlstexte im Logcat? llama.cpp-Ausgaben sichtbar?
9. **Clipboard (BLUE-014):** Android 13+, FTP-Passwort kopieren → Overlay/Toast zeigt Passwort?
10. **Exportierte Viewer (BLUE-017):** `am start … --es org.openintents.extra.ABSOLUTE_PATH /data/user/0/com.beko2210.ordo/shared_prefs/…` → Inhalt sichtbar/teilbar?
11. **Absturz während Tresor-Verschieben (BLUE-006):** großes File, `adb reboot -f` direkt nach Abschluss → Eintrag + Original prüfen.
12. **DownloadManager-Redirects (P12):** Download über mitmproxy beobachten (HF → CDN-Redirects bleiben HTTPS?); falscher Inhalt muss durch den Hash abgewiesen werden.
13. **Biometrie (BLUE-002):** Klasse-2-Gesichtsentsperrung öffnet den Tresor?

---

## Priorisierte Empfehlungen

1. **Sofort:** Test-/Beta-APKs nur als Release-Build (nicht debuggable, R8 an, eigene Signatur); `FLAG_SECURE` von `BuildConfig.DEBUG` entkoppeln (BLUE-001).
2. **Vor 1.0:** SFTP-Host-Key-TOFU und FTPS mit Plattform-Trust + Hostname-Verifier/Pinning (BLUE-007, BLUE-008).
3. **Vor 1.0:** Zugangsdaten mit Tink-`Aead` + Keystore verschlüsseln und per `dataExtractionRules`/`fullBackupContent` vom Backup ausschließen (BLUE-009).
4. **Vor 1.0:** Modell nach Prüfung in App-internen Speicher verschieben, bei Abweichung erneut hashen (BLUE-018).
5. **Vor 1.0:** Tresor-Viewer mit `FLAG_SECURE`, Fremd-App-Weitergabe mit Warnung, Klartext-Cache kurzlebig (BLUE-003); `BIOMETRIC_STRONG` (BLUE-002).
6. **1.0/1.1:** Tresor-Backup als Tink-Container mit Argon2id/scrypt, Dateinamen verborgen (BLUE-004); SMB-Signing/Encryption (BLUE-011); sichere FTP-Server-Defaults (BLUE-012).
7. **Laufend:** Release-Log-Strip, `llama_log_set` (BLUE-013); sensible Clipboard-Markierung (BLUE-014); `FLAG_SECURE` für Passwortansichten (BLUE-015); NSC ohne User-CAs (BLUE-010); Pfadfilter in exportierten Activities (BLUE-017); Rechte minimieren (BLUE-016); Datenschutztext ergänzen (BLUE-019); Verzeichnis-`fsync` (BLUE-006).
8. **Später:** Auth-gebundener Tresor-Schlüssel mit StrongBox, sobald ein Export-/Recovery-Pfad existiert (BLUE-002); zufällige Tresor-IDs (BLUE-005).

---

## Offene Punkte

- **llama.cpp-Version:** Submodul `llm/src/main/cpp/llama.cpp` @ `3549c274dc22001d9118713fa56a10085b85de3b` ist lokal nicht ausgecheckt; ich konnte nicht prüfen, ob bekannte GGUF-Parser-Fixes enthalten sind. Empfehlung: Abgleich mit den llama.cpp-Security-Advisories (z. B. GitHub Security Tab von ggml-org/llama.cpp).
- **Unit-Tests** (`VaultStoreTest`, `BackupTest`) wurden wegen fehlendem Gradle-Build nicht ausgeführt.
- **libarchive-Version:** KDF-Iterationen (1000) aus aktuellem libarchive-Quelltext belegt; sie sind durch die WinZip-AE-Spezifikation festgelegt, die gebündelte Version (`me.zhanghai.android.libarchive:1.1.7`, mbedTLS) habe ich nicht separat geprüft.
- **DownloadManager-Verhalten** (Redirects, Resume, Teil-Downloads) ist systemseitig und nur am Gerät prüfbar; durch den gepinnten Hash ist ein manipulierter Download bei der Erstprüfung abgedeckt.
- **KI-Regeln** (Löschwort, Sperrbereiche, Prompt-Injection über Dateinamen) sind nicht Teil dieses Blue-Team-Berichts (Red-Team-Scope).
