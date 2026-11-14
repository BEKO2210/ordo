# REVIEWER – Code-Review KI (ai/ und llm/) – Ordo

- **Geprüfter SHA:** `5cae53867b38bce494493c6b1170542afdaa6809` (main, laut BRIEF), lokaler Checkout `review/repo` (unverändert, `git status` sauber)
- **CI an diesem SHA:** Run 43633183443, Jobs build/deploy/report-build-status = success (GitHub-API, Beleg `ci-checkruns.txt`)
- **Test-APK:** `ordo-0.1.0-test4.apk`, SHA-256 `c8f8ccfe…539d5d` (stimmt mit BRIEF), `debuggable=true`, minSdk 23, `libordo_llm.so` nur arm64-v8a und x86_64
- **Belege:** `<tmp>`
- **Methode:** nur statisch (Lesen, Nachrechnen, Python-Nachbildung einzelner Prüfregeln, HTTP-HEAD auf die Modell-URL ohne Download, aapt2 auf die APK). Kein Build (kein JDK auf der Box), kein Gerät, kein Modell-Download.
- **Schwere:** K = Kritisch, H = Hoch, M = Mittel, N = Niedrig/Info
- **Teil 2 (Optik/Barrierefreiheit):** siehe `REVIEWER-A11Y.md`

## Umfang

| Datei | Zeilen |
|---|---|
| `app/src/main/java/me/zhanghai/android/files/ai/CommandSheetFragment.kt` | 626 |
| `ai/CommandModel.kt`, `CommandEngine.kt`, `CommandCheck.kt`, `CommandPlan.kt`, `CommandPlanner.kt`, `CommandFiles.kt`, `AiOrbView.kt` | 575 |
| `llm/src/main/java/.../llm/LlamaEngine.kt`, `llm/src/main/cpp/ordo_llm.cpp`, `CMakeLists.txt`, `llm/build.gradle` | 321 |
| Mitgelesen: `filejob/FileJobs.kt` (DeleteFileJob), `filejob/FileJobService.kt`, `trash/Trash.kt`, `home/HomeFragment.kt`, `CommandCheckTest.kt`, `LlamaEngineTest.kt`, `docs/KI-BEFEHLE.md` | – |
| llama.cpp-Submodul `3549c27`: nur `include/llama.h` von GitHub zum Abgleich der API (Rückgabewerte von `llama_decode`, Grammatik) | – |

## Ergebnis

**Freigabe für `5cae538`: NEIN.** Zählung Teil 1: **K 0 · H 0 · M 4 · N 13**

**Gibt es einen Pfad, auf dem eine Dateiänderung ohne Bestätigung passiert?** Nein. Dateien ändern sich nur in `execute()` (`CommandSheetFragment.kt:487–543`), und das wird nur vom Bestätigen-Knopf aufgerufen (`:101–107`). Fehler, Abbruch, Drehen des Bildschirms und Prozesstod zwischen Vorschau und Bestätigung verwerfen den Plan (`ready` wird nicht gespeichert, `:66`, `:378`, `:558`). Nach einem Neustart läuft also nichts an. Die Ausnahme sind die Rückgängig-Aktionen in der Snackbar. Sie laufen bewusst ohne zweite Rückfrage (`:503`, `:506`, `:512–516`, `:527`).

**Gibt es einen Pfad mit endgültigem Löschen?** Ja, zwei:
1. **REV-C-01 (M):** Ein bestätigter KI-Löschbefehl wird endgültig gelöscht statt in den Papierkorb gelegt, wenn der Papierkorb beim Lauf des Jobs aus ist oder `Trash.canTrash()` den Pfad ablehnt. Der Ablauf: `CommandSheetFragment.kt:510`, dann `FileJobService.kt:139–141`, dann `FileJobs.kt:983–986` und `:996–1002`.
2. **REV-C-08 (N):** „Rückgängig“ nach einem Kopierbefehl löscht die Kopien endgültig, wenn der Papierkorb aus ist (`CommandSheetFragment.kt:506`, dann `FileJobs.kt:983`). Es betrifft nur Dateien, die der Befehl selbst angelegt hat.

## Positiv bestätigt

- **Modell-Hash:** Der fest hinterlegte SHA-256 `00fe7986…ef11a4` und die Größe 2 740 937 888 Byte (`CommandModel.kt:25–26`) stimmen mit den Hugging-Face-Metadaten der festen Revision `e87f1764…` überein (`x-linked-etag`, `x-linked-size`, LFS-oid; Belege `hf-head.txt`, `hf-tree-q4km.txt`). Die URL ist HTTPS, und die Weiterleitung geht per HTTPS auf das HF-CDN.
- **Hash-Prüfung vor Freigabe:** Das Modell wird erst nach Größen- und Hash-Prüfung freigegeben (`CommandModel.kt:104–113`). Eine falsche Datei wird gelöscht. Den Download selbst übernimmt der DownloadManager des Systems. Fortsetzen (Range), Warten auf Netz und Weiterlaufen nach einem Prozesstod sind damit Sache des Systems, die App fragt nur den Status ab (`:64–88`).
- **Grammatik-Zwang:** Die GBNF-Grammatik (`CommandPlanner.kt:18–28`) erzwingt das Plan-JSON. Danach validiert `CommandCheck` den Plan noch einmal, unabhängig vom Modell. Unbekannte Aktionen werden abgelehnt (`CommandCheck.kt:55–57`).
- **Geschützte Ordner:** Die Prüfung arbeitet auf kanonischen Pfaden und ignoriert Groß- und Kleinschreibung (`CommandCheck.kt:104–117`). `..` und Pfade außerhalb des Speichers werden abgelehnt, ebenso `Android/…`, versteckte Ordner und `DCIM/Camera` ohne das Wort „Kamera“. Unit-Tests decken das ab (`CommandCheckTest.kt:84–104`).
- **Keine Injektion über Dateinamen:** Dateinamen gelangen nie in den Prompt. Ins Modell geht nur der Befehlstext (`CommandEngine.kt:44`, `CommandPlanner.kt:14–15`). Ordner-Chips setzen Pfade direkt in den Plan, nicht in den Prompt.
- **Vorschau = Ausführung:** Ausgeführt wird genau die Dateiliste der Vorschau (`files`, `:492`). Es gibt keine neue Suche zwischen Vorschau und Ausführung.
- **Überlauf und Abbruch:** Läuft der Kontext über, liefert `llama_decode` den Wert 1 (`llama.h@3549c27`, Zeilen 977–991). Der Fall wird als Fehler behandelt (`ordo_llm.cpp:57–60`, `:161–163`), und die App zeigt „KI konnte nicht starten“. Ein Abbruch wirkt beim nächsten Token (`ordo_llm.cpp:53`, `:148`).
- **Ein Thread für native Aufrufe:** Alle nativen Aufrufe außer `cancel` laufen auf einem eigenen Thread (`CommandEngine.kt:25–27`). Das Modell wird nach 3 Minuten Leerlauf freigegeben (`:58–63`, `:76–79`).

## Befunde

### REV-C-01 – KI-Löschen nicht hart auf Papierkorb festgelegt; endgültiges Löschen möglich
- **Schwere:** M · **Status:** Codepfad statisch bestätigt. Wie leicht er sich auslösen lässt, ist ein Verdacht.
- **Dateien:**
  - `ai/CommandSheetFragment.kt:316` (Papierkorb nur bei der Vorschau geprüft), `:421–423` (Vorschau verspricht den Papierkorb), `:510` (Ausführung ohne neue Prüfung und ohne „nur Papierkorb“)
  - `filejob/FileJobService.kt:139–141`
  - `filejob/FileJobs.kt:983–986`, `:996–1002` (Beleg `deletefilejob-excerpt.txt`)
  - `trash/Trash.kt:45–69`
- **Codepfad:** `DeleteFileJob.run()` liest `Trash.isEnabled` erst beim Lauf. Ist der Papierkorb aus, landen alle Pfade in `deletePaths`, und `deleteRecursively()` löscht sie endgültig. Ist er an, landen Pfade mit `Trash.canTrash()==false` ebenfalls in `deletePaths`. Das trifft zum Beispiel zu, wenn `Environment.getExternalStorageDirectory()` nicht auf `^/storage/emulated/N/` oder `^/storage/<vol>/` passt. Diese Pfade werden ohne Hinweis endgültig gelöscht.
- **Auslöser:**
  - (a) Der Papierkorb wird zwischen Vorschau und Jobstart ausgeschaltet, etwa über eine zweite App-Instanz im Mehrfenstermodus.
  - (b) Geräte mit abweichendem Speicherpfad. Das ist ein Verdacht und braucht einen Gerätetest.
- **Erwartet:** Die KI legt nur in den Papierkorb, wie es der BRIEF und `ai_refused_trash_off_text` verlangen. Geht das nicht, wird abgebrochen.
- **Tatsächlich:** Es wird endgültig gelöscht, obwohl die Vorschau „lässt sich N Tage lang wiederherstellen“ zeigt.
- **Nachweis:** statisch.
- **Gerätetest nötig für (b):** Auf dem Testgerät `Environment.getExternalStorageDirectory()` loggen und gegen `Trash.ROOT_REGEX` prüfen.

### REV-C-02 – Absturz auf Android 6–7.1: `ValueAnimator.areAnimatorsEnabled()` ohne API-Prüfung
- **Schwere:** M · **Status:** bestätigt (statisch, API-Stand) · **Laufzeit:** Gerätetest nötig
- **Dateien:** `ai/AiOrbView.kt:93`, `:106`; `ai/CommandSheetFragment.kt:592`; `app/build.gradle:20` (minSdk 23)
- **Nachweis:**
  - `rg -A8 'class name="android/animation/ValueAnimator"' $SDK/platforms/android-36/data/api-versions.xml | rg areAnimatorsEnabled` ergibt `since="26"` (Beleg `api-areAnimatorsEnabled.txt`).
  - `BackupFragment.kt:316` im selben Repo setzt die nötige Prüfung `SDK_INT < O ||` richtig.
  - CI führt nur `lintVitalRelease` aus (`.github/workflows/android.yml:23`). `NewApi` ist dort kein fataler Check und fällt deshalb nicht auf.
- **Erwartet:** Das Blatt öffnet sich auf API 23–25.
- **Tatsächlich:** Auf API 23–25 ist ein `NoSuchMethodError` zu erwarten, sobald `AiOrbView` ans Fenster kommt, also beim Öffnen des KI-Blatts.
- **Gerätetest:** Test-APK auf einem Emulator mit API 24 öffnen und auf das Zauberstab-Symbol auf Home tippen.

### REV-C-03 – Modell nach der Prüfung nur noch an der Größe erkannt; Austausch auf Android ≤ 10 möglich
- **Schwere:** M · **Status:** Verdacht
- **Dateien:** `ai/CommandModel.kt:50` (Ablage in `getExternalFilesDir("models")`), `:67` (später nur `verified && length == SIZE`), `ai/CommandEngine.kt:65–66`, `llm/src/main/cpp/ordo_llm.cpp:75`
- **Codepfad:**
  - Nach dem einmaligen Hash wird bei jedem Öffnen nur die Größe geprüft.
  - Auf API 23–29 ist `Android/data/<pkg>/files` für jede App mit Speicherrecht beschreibbar.
  - Eine fremde Datei gleicher Größe würde ungeprüft in den nativen GGUF-Parser von llama.cpp geladen. Der Prozess hat Allzugriff auf den Speicher (`MANAGE_EXTERNAL_STORAGE`).
- **Erwartet:** Geladen wird nur die geprüfte Datei. Dafür kommen interner Speicher (`filesDir`), eine erneute Prüfung oder ein Abgleich von Größe, mtime und inode in Frage.
- **Tatsächlich:** Es zählt nur die Größe.
- **Nachweis:** statisch. Kein PoC (laut Regeln).

### REV-C-04 – Commit-Hygiene: KI-Attribution in beiden KI-Commits auf main
- **Schwere:** M (Teamregel „keine KI-Attribution“) · **Status:** bestätigt
- **Commits:** `25a0eb12` und `5cae5386`. Beide enthalten die Trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` und `Claude-Session: https://claude.ai/code/session_…`.
- **Nachweis:** `git log -2 --format='%B' 5cae5386` (Beleg `git-trailers.txt`). Autor und Committer sind korrekt `BEKO2210 <129214713+BEKO2210@users.noreply.github.com>`.
- **Hinweis:** Die Commits liegen schon auf main. Beheben ließe sich das nur durch Umschreiben der Historie. Ob das passiert, entscheidet Belkis über BEL 1. Eine Empfehlung dazu gebe ich nicht.

### REV-C-05 – Abbrechen gegen Freigeben: Datenrennen auf `handle` (theoretisch Use-after-free)
- **Schwere:** N · **Status:** Verdacht (theoretisch, enges Zeitfenster)
- **Dateien:** `llm/.../LlamaEngine.kt:14` (`handle` nicht `@Volatile`), `:21–32`; `ordo_llm.cpp:171–181`; `ai/CommandEngine.kt:50–56`, `:76–79`
- **Codepfad:**
  - `cancel()` kommt vom Haupt-Thread und liest `handle` ohne Synchronisierung.
  - `close()` läuft gleichzeitig auf `ordo-llm` (Leerlauf-Timer oder `release()`) und führt `nativeFree` aus.
  - Danach kann `nativeCancel` auf freigegebenen Speicher schreiben.
- **Erwartet:** `cancel` und `free` sind gegeneinander abgesichert, etwa über `@Volatile` oder einen Lock. Alternativ liegt das Abbruch-Flag in Kotlin.

### REV-C-06 – Schließen des Blatts bricht die Rechnung nicht ab; 3,3 GB bleiben während der Dateijobs im Prozess
- **Schwere:** N · **Status:** Verdacht · Gerätetest nötig
- **Dateien:** `ai/CommandSheetFragment.kt:135–138` (kein `CommandEngine.cancel()`), `:542`; `ai/CommandEngine.kt:28`; `filejob/FileJobService.kt:36`
- **Codepfad:**
  - Nach Bestätigen oder Schließen läuft eine laufende Rechnung bis zu 200 Token weiter.
  - Das Modell bleibt 3 Minuten geladen, laut Messung 3,3 GB (`CommandModel.kt:32`).
  - Genau in dieser Zeit laufen die Verschiebe- und Papierkorb-Jobs im selben Prozess. Wird der Prozess vom Low-Memory-Killer beendet, bricht der Job mittendrin ab.
- **Gerätetest:** Auf einem Gerät mit 6 GB RAM einen KI-Befehl mit vielen Dateien bestätigen, die App sofort in den Hintergrund legen, eine speicherhungrige App öffnen und in `adb logcat` nach `lowmemorykiller` suchen. Danach prüfen, ob alle Dateien angekommen sind.
- **Akku:** `AiOrbView` zeichnet jeden Frame neu, solange das Blatt offen ist (`AiOrbView.kt:59–64`, `:110–117`). Bei abgeschalteten Animationen passiert das korrekt nicht.

### REV-C-07 – Lösch-Wort als Teilstring: Ordnernamen und Verneinungen zählen als Lösch-Wort
- **Schwere:** N (die Vorschau, die Bestätigung und der Papierkorb bleiben als Schutz) · **Status:** bestätigt (Nachbildung)
- **Dateien:** `ai/CommandCheck.kt:34–37`, `:58–61`
- **Nachweis:** `python3 <tmp>` bildet die Logik 1:1 nach. Diese Befehle lassen `delete` zu:
  - „Verschiebe alle PDFs aus Zum Löschen nach Archiv“
  - „Nicht löschen, nur nach Archiv verschieben“
  - „Entwirf einen Ordner …“ (enthält „wirf“)
  - „Move the PDFs from Trash Talk …“
  - „Kopiere alles aus Entfernte Projekte …“
- **Erwartet:** Ein Lösch-Wort zählt nur als Verb am Wortanfang, also über Wortgrenzen. Verneinung und Ordnernamen werden ausgeschlossen.
- **Tatsächlich:** Die Sperre greift nur, wenn das Modell zusätzlich fälschlich `delete` liefert.
- **Gerätetest:** Die fünf Sätze eingeben und notieren, welche Aktion die Vorschau zeigt.

### REV-C-08 – Rückgängig: Wettlauf mit dem laufenden Job, Kopie-Undo löscht endgültig bei ausgeschaltetem Papierkorb
- **Schwere:** N · **Status:** Codepfad statisch bestätigt · Gerätetest nötig
- **Dateien:** `ai/CommandSheetFragment.kt:500–516`, `:521–527`, `:536–537` (Wettlauf im Kommentar selbst eingeräumt); `filejob/FileJobService.kt:36` (`newCachedThreadPool`, Jobs laufen parallel)
- **Folgen:**
  - Undo, während der Job noch läuft, macht nur einen Teil rückgängig. Restliche Dateien werden danach trotzdem verschoben oder gelöscht.
  - Das Undo nach einem Löschbefehl stellt alle Papierkorb-Einträge mit gleichem `originalPath` wieder her, auch ältere aus früheren Löschvorgängen (`:513–515`).
  - Das Undo nach einem Kopierbefehl nutzt `FileJobService.delete(placed)` und löscht bei ausgeschaltetem Papierkorb endgültig.
  - Neu angelegte Ordner bleiben nach dem Undo zurück. Nach Ablauf der 8 s oder einem Neustart gibt es kein Undo mehr, nur den Papierkorb.
- Ursprungsdateien gehen dabei nicht verloren.
- **Gerätetest:** 200 Dateien per KI verschieben, nach 1 s „Rückgängig“ tippen und den Endzustand beider Ordner vergleichen.

### REV-C-09 – Download: mobile Daten, irreführende Fehlermeldungen, kein Fortsetzen nach Fehler
- **Schwere:** N · **Status:** statisch bestätigt
- **Dateien:** `ai/CommandModel.kt:93–100`, `:79–85`; `values/ai_strings.xml:116–117`
- **Befund:**
  - Es fehlt `setAllowedOverMetered(false)`, und es gibt keinen Hinweis. Die 2,7 GB laufen also auch über mobile Daten.
  - `COLUMN_REASON` wird nicht gelesen. Voller Speicher (`ERROR_INSUFFICIENT_SPACE`) erscheint deshalb als „Prüf die Verbindung“.
  - Jede Pause, auch eine Warteschlange oder das Warten auf WLAN, erscheint als „wartet auf eine Verbindung“.
  - „Download erneut starten“ entfernt den alten Download und beginnt bei 0 Byte (`:93–94`).
- **Gerätetest:**
  - Download starten und den Flugmodus 60 s an- und wieder ausschalten. Erwartet: „Pausiert“ und danach Fortsetzen ab dem alten Stand.
  - Download mit weniger als 2,7 GB freiem Speicher starten. Prüfen, welche Meldung erscheint und ob eine halbe Datei zurückbleibt (`Android/data/<pkg>/files/models`).

### REV-C-10 – Absturzpfade ohne Abfangen
- **Schwere:** N · **Status:** Verdacht
- **Fundstellen:**
  - `CommandModel.kt:50`, `:55`: `getExternalFilesDir()` kann `null` liefern, wenn der Speicher nicht verfügbar ist. Dann wirft `parentFile!!` eine NPE.
  - `CommandSheetFragment.kt:220` und `CommandModel.kt:104–113`: Eine `IOException` beim Hashen (zum Beispiel EIO) wird im `lifecycleScope` nicht gefangen und bringt die App zum Absturz.
  - `CommandSheetFragment.kt:570–573`: Das `post {}` greift auf `requireContext()` und `binding` zu. Wird das Blatt im selben Frame geschlossen, folgen `IllegalStateException` oder NPE.
  - `ordo_llm.cpp:147–167`: Bricht die Ausgabe bei `maxTokens` mitten in einem UTF-8-Zeichen ab, bekommt `NewStringUTF` ungültige Daten. In der debuggable Test-APK ist CheckJNI aktiv, ein Abbruch ist möglich. Die Test-APK hat `debuggable=true` (Beleg `apk-manifest-flags.txt`).

### REV-C-11 – Arbeit auf dem Haupt-Thread
- **Schwere:** N · **Status:** Verdacht (Ruckeln oder ANR) · Gerätetest nötig
- **Fundstellen:**
  - `CommandSheetFragment.kt:314`: `CommandCheck.check()` läuft auf dem Main-Thread. Es enthält die Breitensuche über 3 Ebenen mit `listFiles` (`CommandCheck.kt:93–100`) und `canonicalPath`.
  - `:514`: `Trash.entries()` liest alle Info-Dateien im Klick-Handler.
  - `:109`, `:175`: Abfrage beim DownloadManager.
  - `:411`: `length()` für jede Datei.
- **Gerätetest:** StrictMode aktivieren oder einen Systrace aufnehmen, dann mit vielen Ordnern einen Befehl mit unbekanntem Zielordner eingeben.

### REV-C-12 – Gerätecheck und Modellverwaltung
- **Schwere:** N · **Status:** statisch
- **Fundstellen:**
  - `CommandModel.kt:33`, `:37`, `:58`: Geprüft wird `totalMem ≥ 6·10⁹`. Geräte mit nominal 6 GB melden oft knapp darunter oder darüber, freier RAM (`availMem`) wird nicht beachtet. Die Ablehnung ist ehrlich, die Grenze aber wackelig.
  - Das API-Level wird nicht geprüft. Bei minSdk 23 ist das unkritisch bis auf REV-C-02.
  - `CommandModel.remove()` wird nirgends aufgerufen. Die 2,7 GB lassen sich in der App nicht wieder freigeben.
  - `CommandModel.kt:96`: Der DownloadManager-Titel „Ordo KI“ ist hart auf Deutsch kodiert (siehe REV-A).

### REV-C-13 – Doppeltipp auf Bestätigen führt den Plan zweimal aus
- **Schwere:** N · **Status:** Verdacht
- **Dateien:** `ai/CommandSheetFragment.kt:101–107`, `:487–543`
- **Ursache:** `ready` wird in `execute()` nicht auf `null` gesetzt. `dismiss()` wirkt asynchron.
- **Folgen:** Ein zweiter Tipp startet einen zweiten Job. Der wirft Fehler- oder Konflikt-Dialoge, verliert aber keine Daten. Das zweite `placed` ist dann leer.

### REV-C-14 – Vorschau-Hinweis „0 Tage“ bei Aufbewahrung „Nie“
- **Schwere:** N · **Status:** bestätigt (statisch)
- **Dateien:** `ai/CommandSheetFragment.kt:421–423`; `values/donottranslate_prefs.xml:93–98` (der Wert `0` steht für „Nie“, `values/strings.xml:951–956`)
- **Erwartet:** „Landet im Papierkorb“ ohne Frist oder mit „bis du ihn leerst“.
- **Tatsächlich:** „lässt sich 0 Tage lang wiederherstellen“. Das ist irreführend und klingt nach sofortigem Verlust.

### REV-C-15 – Steuer-Tokens im Befehlstext
- **Schwere:** N · **Status:** Verdacht
- **Dateien:** `ordo_llm.cpp:40–42` (`parse_special=true` auch für den Nutzertext), `CommandPlanner.kt:14–15` (nur `<|` wird entschärft)
- **Befund:** Spezial-Tokens ohne `<|`, etwa `<think>`, `</think>` oder `<tool_call>`, werden als Steuer-Token gelesen. Die Quelle ist nur die eigene Eingabe. Grammatik und `CommandCheck` begrenzen die Folgen.
- **Vorschlag:** Den Nutzertext mit `parse_special=false` tokenisieren.

### REV-C-16 – Private LAN-Adresse in der Historie
- **Schwere:** N/Info · **Status:** bestätigt
- **Befund:** `25a0eb12` enthält `http://192.168.2.158:8086` (Beleg `git-lan-ip.txt`). Am geprüften SHA ist die Adresse entfernt. Das ist harmlos, nur als Hinweis zur Hygiene.

### REV-C-17 – KI nur in Debug-Builds; Test-APK ist debuggable
- **Schwere:** N/Info
- **Dateien:** `home/HomeFragment.kt:111–112` (`assistantButton.isVisible = BuildConfig.DEBUG`). Der Kommentar „until step 3“ ist veraltet, Schritt 3 ist eingebaut.
- **Befund:** Release-Builds haben keinen Einstieg zur KI. Die Test-APK hat `debuggable=true`, damit sind CheckJNI und `run-as` aktiv.

## Edge-Cases DE/EN (statisch bewertet)

- Die Erkennung der Absicht macht ausschließlich das Modell. Die App prüft danach Aktion, Lösch-Wort, Ordner und Schutz.
- Aliasse gibt es nur für Download, Musik/Music, Dokumente/Documents, Bilder/Pictures, Filme/Movies und Kamera/Camera/DCIM. „Fotos“ und „Videos“ führen zur Ordner-Rückfrage, das ist sicher.
- „hier“ oder „here“ führen immer zur Rückfrage, weil `here` stets `null` ist (`:314`).
- Ein leerer Filter, also keine Endung, keine Art, kein Name, kein Alter und keine Größe, nimmt alle Dateien der obersten Ebene (`CommandFiles.kt:26–33`). Die Vorschau zeigt dann keinen Filter-Chip, aber Anzahl und Größe.
- Läuft die Ausgabe über 200 Token, wird das JSON abgeschnitten. `parse` gibt dann `null` zurück, und die App meldet „kann Ordo noch nicht“. Das ist sicher.
- Pfadtricks werden abgelehnt: absolute Pfade, `..`, `Android` in Großschreibung, versteckte Ordner und `.OrdoTrash` (kanonischer Pfad, `CommandCheck.kt:104–117`).
- Neue Zielordner mit `a/b/c` werden verschachtelt unter dem Speicher angelegt. Das ist unkritisch.

## Gerätetests nötig (Zusammenfassung)

1. **REV-C-02:** Auf API 24 die KI öffnen.
2. **REV-C-09:** Download mit Flugmodus mitten im Lauf, mit vollem Speicher und mit Prozesstod (App wischen und neu öffnen, der Fortschritt muss weiterlaufen). Mit `adb shell ls -l` prüfen, ob nach einem Fehler eine halbe Datei zurückbleibt.
3. **REV-C-06:** Prozesstod durch Speichermangel während eines KI-Jobs.
4. **REV-C-07:** Die fünf Lösch-Wort-Sätze.
5. **REV-C-08:** Undo bei vielen Dateien.
6. **REV-C-01 (b):** Speicherpfad gegen `ROOT_REGEX`.
7. **Lebenszyklus:** Während der Rechnung schließen, drehen und erneut öffnen. Speicher mit `dumpsys meminfo` vor dem Laden, mit geladenem Modell und 3 Minuten danach messen.
8. **Tempo und Thermik** des 4B-Modells auf dem Pixel. Laut `docs/KI-BEFEHLE.md` ist das noch nicht gemessen.

## Grenzen

- Ich konnte nicht bauen und keine Unit- oder Instrumentierungstests ausführen, weil die Box kein JDK hat. Alle Aussagen zur Laufzeit sind Verdacht.
- Die Implementierung des llama.cpp-Submoduls habe ich nicht geprüft, nur die API-Header bei `3549c27`. Ob `llama_state_seq_*` mit den rekurrenten Schichten von Qwen3.5 korrekt arbeitet, ist nur durch den Instrumentierungstest des Autors belegt.
- Das Modell habe ich nicht heruntergeladen. Den Hash habe ich nur gegen die HF-Metadaten geprüft.
- Die Antwortqualität DE/EN des Modells habe ich nicht gemessen. Laut `docs/KI-BEFEHLE.md` erreicht es 51/54 und 20/20 auf dem Prüfstand des Autors.

## Abweichungen vom BRIEF

- Statt einer Datei `REVIEWER.md` gibt es auf Anweisung von BEL 1 zwei Dateien `REVIEWER-*.md` (CODE und A11Y).
- Neben den BRIEF-Stufen habe ich K/H/M/N verwendet. Es gilt K = Kritisch, H = Hoch, M = Mittel, N = Niedrig/Info.
- Ich habe nichts getan, was den harten Regeln widerspricht: kein Schreiben ins Repo, kein Push, keine GitHub-Aktion, keine Geräteaktion.
