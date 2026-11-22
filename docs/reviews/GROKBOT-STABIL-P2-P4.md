# Review der Fix-Branches stabil-p2 bis stabil-p4

**Basis:** `integrate/0.18.1` (Stand `a8e4d119`). Alle drei Branches zweigen von `f1631d98` ab, also vor dem Merge von PR #154.

| Branch | Geprüfter Head | Thema |
|---|---|---|
| `fix/stabil-p2` | `f0ab7ea4` | Tresor-Viewer absichern, Bildbetrachter härten |
| `fix/stabil-p3` | `3089c3f1` | Datei-Jobs und Sicherung (Backup/Restore, Texteditor) |
| `fix/stabil-p4` | `b304cca5` | FTP-Defaults, Ersetzen über Temp-Datei, TOFU-Pins, Timeouts |

**Methode:** Ich habe für jeden Branch `git diff integrate/0.18.1...<branch>` gelesen und den umgebenden Code nachgeprüft,
bei Bibliotheken auch deren Quelltext in der eingebundenen Version (Coil 2.7.0, smbj 0.11.5, sshj 0.40.0).
Ich habe nichts gebaut und nichts auf einem Gerät laufen lassen. Zeilenangaben beziehen sich auf den jeweiligen Branch.
Hier stehen nur Befunde, die sich im Code belegen lassen. Stilfragen fehlen bewusst.
Mit „#n“ sind die Funde aus PR #153 gemeint (`docs/review/GROKBOT-STABIL-1.0.md`).

## Kurzfassung

| Branch | Hoch | Mittel | Niedrig | Urteil |
|---|---|---|---|---|
| `fix/stabil-p2` | 0 | 1 | 2 | **mergebar nach Fix P2-1** |
| `fix/stabil-p3` | 1 | 0 | 2 | **nicht mergebar** |
| `fix/stabil-p4` | 0 | 2 | 2 | **mergebar nach Fix P4-1 und P4-2** |

---

## fix/stabil-p2 (`f0ab7ea4`)

### Befunde

| # | Schwere | Datei:Zeile | Ablauf (Eingabe → falsches Ergebnis) | Fix-Vorschlag |
|---|---|---|---|---|
| P2-1 | mittel | `viewer/image/ImageViewerActivity.kt:28-35`, `:145`, `:79`, `:103-108`; `filelist/FileListFragment.kt:1605-1609` | Der Schutz für #12 lässt sich auf drei Wegen umgehen. **(a)** Eine fremde App startet den exportierten Betrachter mit `data = content://<paket>.file_provider/file%3A%2F%2F%2Fdata%2Fuser%2F0%2F<paket>%2Ffiles%2F…`. `fileUriTargetsAppData` prüft nur `file:`. Der Pfad wird über Ordos eigenen, nicht exportierten `FileProvider` geöffnet, und den darf Ordo selbst lesen. Folge: Die private Datei wird angezeigt, und über „Teilen“ geht sie mit URI-Grant an jede App. **(b)** `file:///proc/self/root/data/data/<paket>/…`: `normalizeAbsolutePath` löst keine Symlinks auf, die Präfixprüfung greift nicht, und der Linux-Provider öffnet die Datei. **(c)** Der Token aus `ImageViewerLaunches.register` hängt am *impliziten* `ACTION_VIEW`-Intent. Bei Bildern setzt `FileListFragment` keine Klasse, bei „Öffnen mit“ geht der Intent sogar über den Chooser. Wählt der Nutzer eine fremde Bild-App, erhält sie den Token. Der Token wird nur in `onDestroy` des Ordo-Betrachters freigegeben, bleibt in diesem Fall also bis zum Prozessende gültig. Die fremde App kann damit `EXTRA_PATH_URI_LIST` mit beliebigen privaten Pfaden einschleusen. | Ein externer Start (Token fehlt) nimmt nur `content://`-URIs fremder Authorities an. Eigene Authorities und `file:` werden abgelehnt, oder der Pfad wird nach `realpath` geprüft. Den Token erst nach `setClass(…, ImageViewerActivity)` anhängen, bei implizitem Intent und Chooser nie. |
| P2-2 | niedrig | `viewer/image/ImageViewerAdapter.kt:119`, `:197`, `:213` | Ein kleines SVG öffnen (z. B. ein 24×24-Icon). `coilEdgeCapPx` setzt für jedes SVG `Size(4096, 4096)`. Coils `SvgDecoder` (2.7.0) rechnet mit `computeSizeMultiplier` und **vergrößert** auf die Zielgröße. Jede SVG-Seite wird so zu einem 4096×4096-ARGB-Bitmap (64 MiB). Mit `offscreenPageLimit = 1` sind bis zu drei solcher Seiten gleichzeitig im Speicher. Auf Android 7.x (Bitmaps im Java-Heap) droht OutOfMemory, auf neueren Versionen starker Speicherdruck. Vorher wurde das Icon in Originalgröße gerendert. | Nur verkleinern: Die SVG-Maße vorab lesen (z. B. AndroidSVG `documentWidth`) und nur bei mehr als 4096 px begrenzen. Alternativ die Bildschirmgröße als Ziel nehmen. |
| P2-3 | niedrig | `viewer/image/ImageViewerFragment.kt:210-226`, `:176` | Bild auf SMB löschen und während des Löschens das Gerät drehen. `viewLifecycleOwner.lifecycleScope` wird abgebrochen. Der IO-Block läuft zu Ende, die Datei ist gelöscht, doch danach wirft `withContext` eine `CancellationException`. `paths.removeAll` läuft nicht. `onSaveInstanceState` hat die Liste vorher nicht gespeichert, weil sie unverändert war. Der neue Fragment-Stand zeigt die gelöschte Datei weiter an, mit Ladefehler, und ein zweites Löschen endet mit Fehler-Toast. | Das Ergebnis des Löschens unabhängig vom View-Lifecycle übernehmen, z. B. im ViewModel oder mit `lifecycleScope` des Fragments plus `NonCancellable` für das Aktualisieren der Liste. |

### Abgleich mit #153

| Fund | Status |
|---|---|
| #1 FLAG_SECURE | behoben: Bild und PDF für Tresor-URIs, nur in Release-Builds |
| #2 Löschen auf Main-Thread | behoben, Rest siehe P2-3 |
| #4 ViewHolder-Race | behoben (Job wird abgebrochen, Tag-Prüfung) |
| #5 Tresor-Bild nach Rückkehr | behoben für Rückkehr nach mehr als 2 min. Nach einem Prozess-Tod sind die Grants weg, es entsteht kein Klartext. |
| #12 exportierter Betrachter | **teilweise**, siehe P2-1 |
| #13 große SVG | behoben, Nebenwirkung siehe P2-2 |
| #14 TransactionTooLarge | behoben (200 Pfade, kein doppelter State) |
| #15 Int-Überlauf | behoben |
| #18 extraPath beim Teilen | behoben |

### Urteil

**mergebar nach Fix P2-1.** P2-2 und P2-3 können als Folge-Fix kommen.

---

## fix/stabil-p3 (`3089c3f1`)

### Befunde

| # | Schwere | Datei:Zeile | Ablauf (Eingabe → falsches Ergebnis) | Fix-Vorschlag |
|---|---|---|---|---|
| P3-1 | **hoch** | `filejob/FileJobs.kt:2622-2683`; `filejob/FileJobService.kt:187`, `:191` | Der Commit **löscht die Klassen `MoveToVaultJob` und `RestoreVaultJob` komplett**. Gegenüber der Basis fehlen etwa 100 Zeilen, siehe Diff-Hunk `@@ -2501,110 +2634,12 @@`. Der Rumpf von `RestoreVaultJob.restore` (Teil-Datei `.ordo-part`) steht jetzt in `RestoreTrashJob.restore(entry: TrashEntry)`, und `Vault.store.open(entry)` bekommt dort einen `TrashEntry`. `FileJobService` startet weiterhin `MoveToVaultJob(paths)` und `RestoreVaultJob(entries)`. **Der Branch kompiliert nicht.** Repariert man nur den Build, fehlen „In Tresor verschieben“ und „Aus Tresor wiederherstellen“, und das Wiederherstellen aus dem Papierkorb liefe über den Tresor statt über `moveAtomically`. Das hieße Datenverlust oder einen Absturz beim Wiederherstellen aus dem Papierkorb. | Den Hunk neu aufsetzen: `RestoreTrashJob`, `MoveToVaultJob` und `RestoreVaultJob` wie auf der Basis lassen und die `.ordo-part`-Änderung nur in `RestoreVaultJob.restore` einbauen. Danach bauen, bevor der Branch erneut geprüft wird. |
| P3-2 | niedrig | `filejob/FileJobs.kt:1735-1743`, `:1761`; `provider/common/ForeignCopyMove.kt:105-118` | Aus einem ZIP eine Musik- oder PDF-Datei öffnen, die externe App läuft weiter. Danach eine zweite Datei aus einem Archiv öffnen. Beim Kopieren in `open_cache` wird das `lastModifiedTime` der Quelle übernommen, z. B. ein Archiv-Eintrag von 2019. `deleteStaleCacheEntries` vergleicht mit `lastModified()` und löscht deshalb **sofort** auch die erste Datei, die erst Sekunden alt ist. Die externe App bricht beim nächsten Öffnen der URI ab (Spulen, neu Laden). Die 1-Stunden-Regel greift praktisch nie wie gedacht. | Nach dem Kopieren `setLastModified(now)` auf der Cache-Datei setzen oder das Alter am Kopierzeitpunkt messen. |
| P3-3 | niedrig | `filejob/FileJobs.kt:2535-2560` | Im Texteditor eine Datei auf einem Linux-Pfad speichern, die ein Symlink ist oder besondere Rechte hat (z. B. per Root `/data/local/tmp/run.sh` mit 0755). Die Temp-Datei wird neu angelegt und per `rename` über den Namen gelegt. Folge: Der Symlink wird durch eine normale Datei ersetzt, das eigentliche Ziel bleibt unverändert. Modus und Besitzer gehen verloren, das Skript ist nicht mehr ausführbar. Vorher wurde in die bestehende Datei geschrieben. | Vor dem Speichern `realpath` auflösen und Modus/Besitzer der alten Datei auf die Temp-Datei übertragen. Vor dem `rename` `force(true)`, damit die Temp-Datei auf dem Datenträger liegt. |

### Abgleich mit #153

| Fund | Status |
|---|---|
| #3 Texteditor | **teilweise**: atomar nur für Linux-Pfade. SMB, SAF/SD-Karte und FTP schreiben weiter mit `TRUNCATE` in die Originaldatei, also genau die Fälle aus dem Szenario in #153. |
| #9 unlesbare Einträge | behoben (Wiederholen / Überspringen / Abbrechen, Scan läuft weiter) |
| #10 Backup überschreibt alte Sicherung | behoben (eine nicht leere Zieldatei wird abgelehnt) |
| #11 Klartext im externen Cache | behoben (interner Cache), Rest siehe P3-2 |
| #17 Name mit `/` oder `..` | behoben |
| Tresor-Wiederherstellung mit Teil-Datei | im Prinzip richtig, aber in die falsche Klasse geraten (P3-1) |

### Urteil

**nicht mergebar.** Der Build bricht (P3-1), und selbst nach einer Build-Reparatur wären die Tresor- und Papierkorb-Jobs zerstört.
Die übrigen Änderungen sind in Ordnung, wenn der Hunk neu aufgesetzt wird.

---

## fix/stabil-p4 (`b304cca5`)

### Befunde

| # | Schwere | Datei:Zeile | Ablauf (Eingabe → falsches Ergebnis) | Fix-Vorschlag |
|---|---|---|---|---|
| P4-1 | mittel | `provider/smb/client/Client.kt:60-64`; Folge in `provider/smb/SmbWatchService.kt:126` | SMB-Ordner öffnen und 15 s nichts tun, oder ein Video bzw. Musik von SMB pausieren. `withSoTimeout(15 s)` setzt `SO_TIMEOUT` auf den Socket. Der `PacketReader` von smbj 0.11.5 macht aus `SocketTimeoutException` eine `TransportException`, ruft `handleError` auf und beendet die Verbindung. Folgen: Die Live-Aktualisierung (`CHANGE_NOTIFY`, `future.get()`) endet nach 15 s Leerlauf. Offene Datei-Kanäle (Player) liefern nach der Pause einen Lesefehler. Jede Aktion nach einer Pause baut die Sitzung neu auf und meldet sich neu an. | `withSoTimeout` weglassen (smbj-Default 0) und nur `withTimeout` für Transaktionen setzen. Einen Verbindungs-Timeout, falls gewünscht, über eine eigene `SocketFactory` mit `connect(addr, timeout)`. |
| P4-2 | mittel | `res/values/donottranslate_prefs.xml:28-30`; `settings/Settings.kt:131`, `:143`; `ftpserver/FtpServer.kt:36-40` | FTP-Server mit den neuen Standardwerten starten. „Anonym“ ist aus, Benutzer `admin`, das **Passwort ist leer** (`pref_default_value_empty`), Wurzel ist der ganze interne Speicher. `BaseUser.password = ""`, und Apache FtpServer nimmt `PASS ""` an. Wer im WLAN ist, meldet sich mit `admin` und leerem Passwort an und **liest den kompletten internen Speicher**. Praktisch ist das weiter ein anonymer Lesezugriff. Die e2e-Suite `ftp-server-defaults.sh` prüft nur den Login `anonymous` und wird deshalb grün. | Ohne gesetztes Passwort nicht starten, oder beim ersten Start ein zufälliges Passwort erzeugen und anzeigen. Zusätzlich die Wurzel standardmäßig auf einen Unterordner legen (Teil von #153/#154). |
| P4-3 | niedrig | `provider/linux/LinuxCopyMove.kt:66`, `:313-318`; `provider/common/ForeignCopyMove.kt:48`, `:175-178` (ebenso p3: `filejob/FileJobs.kt:2536`) | Eine Datei kopieren, deren Name 245 bis 255 Byte lang ist (gültig, `NAME_MAX` = 255). Der Temp-Name `.<name>.ordo-part` ist 11 Byte länger. `open` schlägt mit `ENAMETOOLONG` fehl, und die Kopie bricht als „ungültiger Dateiname“ ab. Vorher ging das. | Kurzen Temp-Namen verwenden, z. B. `.ordo-part-<hash>`, oder den Namen kürzen. |
| P4-4 | niedrig | `provider/linux/LinuxCopyMove.kt:66-122`; `provider/common/ForeignCopyMove.kt:48-83` | (a) Eine 3-GB-Datei durch eine neue 3-GB-Version ersetzen, wenn 4 GB frei sind. Das alte Ziel bleibt bis zum `rename` liegen, die Kopie scheitert mit „kein Platz“. Vorher ging das. (b) Prozess-Tod während der Kopie: Die `.ordo-part`-Datei bleibt versteckt liegen und wird nur aufgeräumt, wenn später dieselbe Datei wieder ins selbe Ziel kopiert wird. Versteckte Dateien sind standardmäßig ausgeblendet, der Platz ist für den Nutzer unsichtbar weg. | (a) Bei zu wenig Platz kontrolliert auf das alte Verfahren zurückfallen, aber nur nach Rückfrage. (b) Beim Start eines Jobs liegengebliebene `*.ordo-part` im Zielordner löschen oder anzeigen. |

### Abgleich mit #153

| Fund | Status |
|---|---|
| #7 Ersetzen löscht zuerst | **teilweise**: Linux und fremde Provider gehen über eine Temp-Datei. `SmbCopyMove` (SMB→SMB) bleibt unverändert. Auf SAF-Zielen löscht `DocumentCopyMove.move` das Ziel weiterhin vor dem Umbenennen (kleines Fenster). |
| #8 halbe Datei unter echtem Namen | behoben für Linux und fremde Provider, Rest siehe P4-4 (b) |
| #16 Umbenennen nur in Groß-/Kleinschreibung | behoben (`rename` bei gleicher Inode und anderem Namen) |
| TOFU-Pins | richtig: Der CA-Pin wird erst nach bestandener Hostnamen-Prüfung geschrieben, `check` und `verify` sind synchronisiert, die Pin-Datei wird über `AtomicFile` geschrieben. Ein Downgrade von CA auf selbst signiert wird weiter abgewiesen. Das SFTP-Timeout (sshj 0.40 ignoriert `SocketTimeoutException` im Reader) ist unkritisch. |
| FTP-Defaults | anonym aus und schreibgeschützt, aber siehe P4-2 |

### Urteil

**mergebar nach Fix P4-1 und P4-2.** P4-3 und P4-4 können als Folge-Fix kommen.

---

## Wechselwirkungen und Konflikte

- **Merge-Konflikte:** `fix/stabil-p3` und `fix/stabil-p4` kollidieren mit dem aktuellen `integrate/0.18.1` in `e2e/run.sh`.
  Der Grund ist PR #154, der nach dem Abzweigpunkt gemergt wurde. p4 kollidiert zusätzlich in `e2e/README.md`.
  `fix/stabil-p2` merged sauber. App-Code überschneidet sich zwischen den drei Branches nicht.
- **p3 und p4:** Das neue Speichern in p3 (`temp.moveTo(file, ATOMIC_MOVE, REPLACE_EXISTING)`) läuft über `LinuxCopyMove.move` aus p4.
  Ein Konflikt ergibt sich daraus nicht: Bei vorhandenem Ziel und `REPLACE_EXISTING` ersetzt `rename` die Datei direkt.
  Beide Branches verwenden versteckte Temp-Namen (`.ordo-tmp`, `.ordo-part`) mit demselben Problem bei langen Namen (P4-3).
- **Empfohlene Reihenfolge:** zuerst p2 (nach P2-1), dann p4 (nach P4-1/P4-2), p3 erst nach dem Neuaufbau des Tresor-Hunks.
- **Von keinem Branch adressiert:** #6 (fsync beim Verschieben in den Tresor) und #19 (Pfade in logcat/Toasts).
