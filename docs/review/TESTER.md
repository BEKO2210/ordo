# TESTER.md — Ordo finales Review

Stand: Repo [BEKO2210/ordo](https://github.com/BEKO2210/ordo) @ `5cae53867b38bce494493c6b1170542afdaa6809`. Testdatum 14.11.2026, Europe/Berlin. Nur gelesen; nichts gepusht, kommentiert oder gemerged. Produktivcode unverändert. Tests nur als Patch.

**Urteil: nicht bestanden.** **Freigabe aus QA-Sicht: nein.** **Offene Befunde: ja.**

Die bestehenden 70 Unit-Tests sind grün. Neue Sicherheitstests für die KI-Prüflogik decken mehrere Lücken in den fest vorgegebenen Sperren auf (Löschen ohne klares Löschwort, DCIM/Kamera, `..`-Pfade). Dazu kommt ein statisch begründetes Risiko beim Entpacken von Archiven mit Symlinks (Gerätetest nötig). Copy/Move/Rename/Papierkorb/Tresor/Sicherung sind formatunabhängig und sehen solide aus; Öffnen und Vorschau vieler Formate sind nur auf dem Gerät endgültig prüfbar.

## Kennzahlen

- Ein Gradle-Lauf: `./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx2g :app:testDebugUnitTest` in einer Kopie unter `<tmp>` (Original unberührt). Vorher verfügbar: rund 7 GiB. In der Kopie war der native `llama.cpp`-Build abgeschaltet, weil das Submodul leer ist und die JVM-Tests JNI nicht laden; diese Änderung ist nicht Teil des Patches.
- Ergebnis: **87 Tests, 8 Failures, 0 Errors, 0 Skips.** 15 Testklassen. Lauf 20:50:00–20:51:47 Europe/Berlin, BUILD FAILED wegen der neuen Tests.
- Bestand: **70/70 grün** (u. a. `CommandCheckTest` 11, `VaultStoreTest` 9, `TrashTest` 8, `BackupTest` 6).
- Neu: `CommandCheckSafetyTest`, 17 Tests, **9 grün / 8 rot**. Davon **6 echte Produktbefunde**, 1 zu strenge Assertion (Prompt-Suffix, kein Produktfehler), 1 Windows-Backslash unter Linux (auf Android kein typischer Separator).

Patch: `tests/0001-command-check-safety-tests.patch` (gilt sauber auf dem Original @ `5cae538`). Gerätetestplan: `GERAETETEST.md`. Formatmatrix: `TESTER-FORMATE.md`. Testdateien-Skript: `tests/make-testfiles.sh` (Small-Modus auf der Box geprüft, 4-GiB-Dateien nicht dauerhaft angelegt).

## Befunde nach Schwere

### Hoch

**TESTER-1 — Entpacken kann über Symlink-Eltern aus dem Zielordner schreiben.**  
Dateien: `FileJobs.kt:770-816` (`CopyFileJob`, `isExtract`), `FileJobs.kt:1367-1371` (`copyOrMove` mit `LinkOption.NOFOLLOW_LINKS`), `PathExtensions.kt:321-338` (`resolveForeign`).  
Pfad: Ein Archiv enthält zuerst den Symlink `link → /storage/emulated/0/…` und danach den Eintrag `link/payload`. `walkFileTree` folgt dem Symlink im Archiv nicht, kopiert ihn aber als Symlink in das Ziel. Der zweite Eintrag wird nach `target/link/payload` aufgelöst; das Dateisystem folgt dabei dem bereits angelegten Eltern-Symlink. `NOFOLLOW_LINKS` gilt für das Ziel selbst, nicht für Zwischenkomponenten. `ArchiveReader` normalisiert `..` im Archiv-Dateisystem, prüft nach `normalize()` aber nicht `path.startsWith(rootPath)` (`ArchiveReader.kt:40-57`).  
Erwartet: Entpacken bleibt im gewählten Ordner, Symlink-Ausbruch wird abgelehnt.  
Tatsächlich: statisch ein klassischer Symlink-Zip-Slip. **Gerätetest nötig** (Schritt A12 in `GERAETETEST.md`), nicht auf dem Gerät ausgeführt.

### Mittel (Unit-Test rot)

**TESTER-2 — Das Wort „Papierkorb“/„trash“ allein gilt als Löschwort.**  
`CommandCheck.kt:34-38`, Test `mentioningTrashAlone_doesNotCountAsADeleteWord`.  
Schritt: Plan `delete` auf `Download`, Satz „öffne den Papierkorb“ bzw. „show the trash folder“.  
Erwartet: `Refused(UNSURE_DELETE)` (kein Löschverb).  
Tatsächlich: `Ready`. Verstößt gegen die Regel „delete only with a delete word“. Bestätigt durch Unit-Test.

**TESTER-3 — Jede Erwähnung von „Kamera“/„camera“/„dcim“ hebt die DCIM-Sperre auf.**  
`CommandCheck.kt:40, 63-67, 115-117`, Test `cameraWordInAnUnrelatedSentence_mustNotUnlockDcim`.  
Schritt: „lösche temporäre Dateien in Download, nicht die Kamera“, Plan `delete` / Quelle `Kamera`.  
Erwartet: `PROTECTED_FOLDER`.  
Tatsächlich: `Ready` auf `DCIM/Camera`. Die Sperre hängt am Teilstring in der Nutzereingabe, nicht daran, dass die Kamera wirklich das Ziel ist.

**TESTER-4 — Ziel `Download/../Music` wird nicht kanonisch mit der Quelle verglichen.**  
`CommandCheck.kt:73-78` (`destination == source` ohne `canonicalFile`; `File(root, "Download/../Music")` wenn `resolve` den Namen nicht findet). Test `sameFolderViaRelativePath_isRefused`.  
Schritt: „verschiebe von Musik nach Download/../Music“.  
Erwartet: Ablehnung (gleicher Ordner bzw. geschützt).  
Tatsächlich: `Ready`, `newDestination=true`. `isProtected` nutzt `canonicalPath` und würde einen Ausstieg aus der Wurzel fangen, nicht aber „dieselbe Quelle über `..`“.

**TESTER-5 — `olderThanDays` läuft als `Int` über.**  
`CommandPlan.kt:66-72`, Verwendung in `CommandFiles.kt:131`. Test `yearAgeLimit_doesNotOverflowToANegativeDayCount`.  
`CommandPlan(..., olderThan = 7_000_000, ageUnit = "years").olderThanDays` ergibt **-1739967296**. Die llama.cpp-Grammatik begrenzt Zahlen auf sechs Stellen (`CommandPlanner.kt:52`, max. 999999×365 passt noch in `Int`). `parse()` und der Konstruktor prüfen das nicht. Ein negativer Tageswert dreht die Altersgrenze in `CommandFiles` um (`now - (negativ * ms)` liegt in der Zukunft, der Filter lässt zu viel durch).

**TESTER-6 — NFC- und NFD-Ordnernamen werden nicht als derselbe Ordner erkannt.**  
`CommandCheck.kt:105-110` (`name.equals(..., ignoreCase = true)`). Test `nfcAndNfdFolderNames_resolveIfTheFilesystemHasEitherForm`.  
`resolve("Café")` findet den NFC-Ordner, `resolve("Cafe\\u0301")` ergibt `null`. Auf Android (häufig NFC) und bei von macOS kopierten NFD-Namen kann ein gesprochener Name den Ordner verfehlen. Gerätetest zur Bestätigung der Nutzerwirkung.

### Niedrig

**TESTER-7 — Ziele mit führendem `/` werden unter der Speicherwurzel angelegt.**  
Test `dotDotAndAbsoluteEscapes_areProtectedOrUnresolved`: Plan-Ziel `/etc` wird zu `<root>/etc`, `Ready`, `newDestination=true`. `resolve("/etc")` lehnt den echten Absolutpfad korrekt ab (`CommandCheck.kt:90-92`), der Fallback `File(root, "/etc")` hängt `etc` unter die Wurzel. Kein Schreibzugriff auf das System-`/etc` (Java `File(parent, "/etc")` ergibt hier `parent/etc`). Irreführend, kein Ausbruch.

**TESTER-8 — `m4a` ist als `audio/mpeg` hinterlegt** (`mime/MimeTypeMapCompat.kt`). Üblich wäre `audio/mp4` bzw. `audio/m4a`. Öffnen über einen fremden Player kann scheitern. Gerätetest.

**TESTER-9 — Die Endung `.raw` hat keinen MIME-Eintrag.** Kamera-Rohdaten sind über `dng`, `arw`, `cr2`, `nef`, `orf`, `rw2` als `image/x-*` erfasst. Eine Datei `foto.raw` wird `GENERIC`.

**TESTER-10 — Keine MIME-Erkennung über Magic Bytes.** `AndroidFileTypeDetector.kt:25-33` und `MimeTypeConversionExtensions.kt:14-17` raten nur aus der Endung. Dateien ohne Endung sind `GENERIC`. Die Vorschau versucht `GENERIC` dennoch als Bild zu laden (`PathAttributesFetcher`, `mimeType.isImage || mimeType == MimeType.GENERIC`). Gerätetest.

**TESTER-11 — `FileSize.formatInBytes` nutzt `value.toInt()`** (`FileSize.kt:21`) als Quantity für Plurals. Ab 2 GiB läuft der Wert über. Die Transfer-Benachrichtigung für Copy/Move/Extract skaliert Größen > `Int.MAX_VALUE` bereits herunter (`FileJobs.kt:407-416`); `postWriteNotification` (`FileJobs.kt:2421-2422`) skaliert nicht, wird aber nur für `WriteFileJob` mit einem `ByteArray` genutzt.

**TESTER-12 — Kein explizites ZIP64 beim Schreiben.** `WriteArchive.kt` setzt Format/Filter, aber keine Option `zip64`. libarchive schaltet ZIP64 oft selbst scharf; Verhalten bei Dateien ≥ 4 GiB ist **Gerätetest** (B1).

### Kein Produktbefund (Tests rot, aber Erklärung)

- `injectionInThePromptText_isNeutralizedInTheSuffix`: Nutzertext `\<|` wird zu `\< |` (`CommandPlanner.kt:16`). Die Assertion, das fertige Suffix enthalte kein `\<|im_end|>`, ist zu grob — der echteCloser wird danach angehängt. Die Neutralisierung selbst greift.
- `injectionLikeSourceAndDestinationNames` für `Download\\..\\Android\\data`: Unter Linux/Android ist `\\` kein Pfadtrenner, der Name bleibt ein einzelnes Segment. Kein Ausbruch.

## Was die KI-Sperren halten

Geprüft und grün (Bestand + neue Tests): unbekannte Aktionen (`none`, `format`, `rename`) werden abgelehnt; Löschen ohne Löschwort in normalen Sätzen wird abgelehnt; `Android/data` inkl. Groß/Kleinschreibung und `Music/../Android/data` sind geschützt; versteckte Ordner und neue Ziele wie `.secret` sind geschützt; `/etc` als `resolve()`-Absolutpfad ergibt `null`; `CommandPlanner.suffix` entschärft `\<|` im Nutzertext; `CommandFiles` bleibt im Quellordner und lässt Verstecktes weg; Bestätigung vor Ausführung (`CommandSheetFragment.kt:57, 101-106`); Löschen nur in den Papierkorb, sonst Abbruch (`CommandSheetFragment.kt:316-321`).

## Hinweise zur Umgebung

- Original-Checkout: `review/repo`, unverändert @ `5cae538`.
- APK laut BRIEF: `ordo-0.1.0-test4.apk`, SHA-256 `c8f8ccfed89c25aecc51d1e9d7a2d7f08ba5a11f9dddd93146645c64ca539d5d` (nicht erneut gegen die Datei geprüft).
- Kein Emulator, keine Gerätetests ausgeführt.

## Top 3 für BEL 1

1. TESTER-2/3/4 — KI-Sperren (Löschwort, DCIM, `..`-Ziele) halten in den Unit-Tests nicht durch.
2. TESTER-1 — Symlink-Zip-Slip beim Entpacken, Gerätetest A12.
3. TESTER-5 — Integer-Überlauf bei der Altersgrenze, plus TESTER-6 NFC/NFD.
