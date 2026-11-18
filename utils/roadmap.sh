#!/bin/bash
# Ordo's living roadmap, kept with autopoiesis (https://github.com/BEKO2210/autopoiesis).
# Every goal is a cell with a check command; a passing check closes the cell, nobody ticks boxes by hand.
# The state lives in .apo/ (local, not in git) and is rebuilt from this script on any machine.
#
#   utils/roadmap.sh    check all cells, open the next release cycle if needed, write docs/ROADMAP-LIVE.md
#
# The release cycle never ends: once the newest tag is out, the three cells for the version after it appear,
# before 1.0.0 and after it alike.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v apo >/dev/null || { echo "apo fehlt: uv tool install git+https://github.com/BEKO2210/autopoiesis.git" >&2; exit 1; }
[ -f .apo/cells.db ] || apo init >/dev/null
# Releases are the version tags on origin. Local tags do not count: the fork's history also has Material Files' tags.
TAG="git ls-remote --tags origin"

id_of() { apo status | grep -F -- " $1  p=" | awk '{print $2}' | head -1; }

# cell TITLE VERIFY INSTRUCTION [DEP_TITLE...]: adds the cell once; later runs leave it alone.
cell() {
    local title=$1 verify=$2 instruction=$3; shift 3
    [ -n "$(id_of "$title")" ] && return 0
    local deps=() dep id
    for dep in "$@"; do
        id=$(id_of "$dep"); [ -n "$id" ] || { echo "Abhängigkeit fehlt: $dep" >&2; exit 1; }
        deps+=(--dep "$id")
    done
    apo add "$title" --verify "$verify" --instruction "$instruction" "${deps[@]}" >/dev/null
}

VERSION="sed -n \"s/.*versionName '\\\\(.*\\\\)'/\\\\1/p\" app/build.gradle"
TESTS=app/src/test/java/me/zhanghai/android/files

# ---- Goals on the way to 1.0.0 ----------------------------------------------------------------------------------
cell "Analyse neu: Belegung, Rangliste, Seiten statt Reiter" \
    "$TAG 'refs/tags/v0.4.*' | grep -q ." \
    "Speicheranalyse verständlich für jeden; jede Zeile antippbar, Zurück landet am selben Punkt."
cell "Analyse: Diagramme zurück, fehlerfrei und antippbar" \
    "grep -Eq 'PieChart|DonutView|TreemapView|SunburstView' app/src/main/res/layout/analysis_overview.xml && test -f $TESTS/analysis/AnalysisChartTest.kt" \
    "Echte Visualisierung in der Analyse-Übersicht (Bibliothek mit mindestens 10.000 Sternen oder eigene Ansichten): nichts verschluckt alles, jede Fläche antippbar, hell und dunkel lesbar; Datenaufbereitung in AnalysisChartTest abgesichert."
cell "Analyse: Scan-Animation statt Ladekreisel" \
    "test -f app/src/main/java/me/zhanghai/android/files/analysis/ScanProgressView.kt" \
    "Eigene Scan-Ansicht im Aurora-Stil mit hochzählenden Zahlen; richtet sich nach der Animations-Einstellung."
cell "KI wählt das Modell passend zum Gerät" \
    "test -f $TESTS/ai/ModelChoiceTest.kt" \
    "Modell nach Chip und Arbeitsspeicher wählen; der Nutzer sieht keinen Modellnamen. Fertig, wenn ModelChoiceTest die Wahl absichert."
cell "KI-Tempo auf echten Geräten gemessen" \
    "grep -q 'Turbo' docs/KI-BEFEHLE.md" \
    "Turbo, Ausgewogen und Sanft auf Tab S9 Ultra, Pixel 9 und S21 messen; Zahlen mit Datum in docs/KI-BEFEHLE.md."
cell "Datenmüll finden: leere Ordner und Vorschau-Caches" \
    "grep -qi 'emptyFolders\\|empty_folders' app/src/main/java/me/zhanghai/android/files/analysis/StorageInsights.kt" \
    "Leere Ordner und Thumbnail-Caches als Aufräum-Vorschlag in der Analyse."
cell "Sicherung wiederherstellen per Knopf" \
    "grep -rq 'fun restore' app/src/main/java/me/zhanghai/android/files/backup/" \
    "Eine Sicherung (ZIP, auch verschlüsselt) in der App auswählen und zurückspielen."
cell "Zwei Spalten auf Tablets" \
    "test -f $TESTS/filelist/TwoPaneTest.kt" \
    "Zwei Ordner nebeneinander auf Tablets und im Querformat, Kopieren und Verschieben zwischen den Spalten."
cell "Name endgültig" \
    "test -s docs/NAME.md" \
    "Belkis entscheidet den endgültigen Namen; Entscheidung mit Markenprüfung in docs/NAME.md."
cell "Store-Texte Deutsch und Englisch" \
    "grep -q Ordo fastlane/metadata/android/en-US/full_description.txt && test -s fastlane/metadata/android/de-DE/full_description.txt" \
    "Titel, Kurz- und Langbeschreibung für Google Play in fastlane/metadata/android/{en-US,de-DE}."
cell "Store-Bilder im Repo" \
    "ls fastlane/metadata/android/en-US/images/phoneScreenshots/*.jpg fastlane/metadata/android/de-DE/images/phoneScreenshots/*.jpg >/dev/null 2>&1" \
    "Die Store-Bilder (1080×1920) je Sprache unter fastlane/metadata/android/<Sprache>/images/phoneScreenshots."
cell "Berechtigungen für Google Play begründet" \
    "test -s docs/PLAY-BERECHTIGUNGEN.md" \
    "Begründungen für MANAGE_EXTERNAL_STORAGE, QUERY_ALL_PACKAGES, REQUEST_INSTALL_PACKAGES und den Vordergrunddienst."
cell "Vordergrunddienst-Typ für Dateiaufträge geklärt" \
    "! grep -q 'foregroundServiceType=\"specialUse\"' app/src/main/AndroidManifest.xml || grep -q 'specialUse: Entscheidung' docs/PLAY-BERECHTIGUNGEN.md" \
    "Google ordnet Kopieren und Übertragen dem Typ dataSync zu; Ordo meldet specialUse. Typ wechseln oder die Begründung festhalten (Zeile „specialUse: Entscheidung …“ in docs/PLAY-BERECHTIGUNGEN.md)."
cell "Native Bibliothek für 16-KB-Seiten" \
    "grep -rq 'max-page-size=16384\\|ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES' llm/build.gradle llm/src/main/cpp/CMakeLists.txt" \
    "libordo_llm.so mit 16-KB-Seitenausrichtung bauen (Pflicht bei Google Play) und prüfen."
cell "Geschlossener Test bei Google Play bestanden" \
    "test -s docs/PLAY-TEST.md" \
    "12 Tester, 14 Tage (Belkis); Ergebnis und Rückmeldungen in docs/PLAY-TEST.md." \
    "Store-Texte Deutsch und Englisch" "Store-Bilder im Repo" "Berechtigungen für Google Play begründet" \
    "Native Bibliothek für 16-KB-Seiten" "Vordergrunddienst-Typ für Dateiaufträge geklärt"
cell "Version 1.0.0 im Play Store" \
    "$TAG refs/tags/v1.0.0 | grep -q ." \
    "Produktion bei Google Play, Tag v1.0.0." \
    "Geschlossener Test bei Google Play bestanden" "Name endgültig"

# ---- Quality after the first release ----------------------------------------------------------------------------
cell "KI: neue Befehle als Vorschau zum Einschalten" \
    "grep -q 'AI_PREVIEW' app/src/main/java/me/zhanghai/android/files/settings/Settings.kt" \
    "Das neue Befehlsverständnis (Zeigen, Suchen, Rückfragen) als Schalter in den Einstellungen, standardmäßig aus."
cell "KI-Vorschau mit eigenen Sätzen aus dem Alltag geprüft" \
    "test -s docs/KI-VORSCHAU-PRAXIS.md" \
    "20 bis 30 eigene Sätze von Belkis auf Pixel und Tab; Treffer, Fehlgriffe und Zeiten mit Datum in docs/KI-VORSCHAU-PRAXIS.md. Erst danach entscheiden, ob die Vorschau Standard wird." \
    "KI: neue Befehle als Vorschau zum Einschalten"
cell "KI-Blatt lesbar ohne Unschärfe-Effekt" \
    "grep -q 'isCrossWindowBlurEnabled' app/src/main/java/me/zhanghai/android/files/ai/CommandSheetFragment.kt" \
    "Auf Geräten ohne Fenster-Unschärfe (Galaxy Tab S9 Ultra) ist das KI-Blatt deckend; der Griff ist schmal mit runder Markierung."
cell "Einstellungen bildlich: Symbol an jedem Eintrag, Auswahl mit Bildern" \
    "test -f app/src/main/java/me/zhanghai/android/files/settings/VisualListPreference.kt && test -f e2e/settings-visual.sh" \
    "Jeder Eintrag der Einstellungen hat ein Symbol; Nachtmodus, Animationen und die übrigen Listen zeigen Bilder statt Text (Sonne, Mond, Vorschau). Auf dem echten Tablet gefilmt und geprüft."
cell "Bewegung aus einem Guss: Menüs, Dialoge, Piktogramme" \
    "grep -q 'ThemeOverlay.Ordo.Material3.NoMotion' app/src/main/res/values/styles.xml && ls app/src/main/res/drawable/settings_pictogram_*.xml >/dev/null 2>&1" \
    "Menüs, Dialoge und Seitenmenü öffnen und schließen einheitlich; Piktogramme der Einstellungen bewegen sich ruhig. Alles folgt Einstellungen > Aussehen > Animationen und dem Systemschalter."

# ---- Everything opens and can be edited inside Ordo, one building block at a time --------------------------------
cell "Stand der Technik für Medien belegt" \
    "test -s docs/MEDIEN-RECHERCHE.md" \
    "Für Musik, Video, PDF, Markdown und Office je Baustein: beste Bibliothek, Version, Lizenz (verträglich mit GPL-3.0), 16-KB-Seiten, Quelle an der Originalstelle geprüft. Keine Blogs. Ergebnis in docs/MEDIEN-RECHERCHE.md."
cell "Musik-Player: Audio direkt in Ordo abspielen" \
    "ls app/src/main/java/me/zhanghai/android/files/viewer/audio/*.kt >/dev/null 2>&1 && grep -q 'mediaPlayback' app/src/main/AndroidManifest.xml" \
    "MP3 und alle gängigen Formate, Wiedergabe im Hintergrund mit Benachrichtigung, Sperrbildschirm und Kopfhörertasten, Warteschlange, lückenlos, Titelbild und Texte; Equalizer (10 Bänder, Voreinstellungen), Bassverstärkung, Lautstärkeausgleich (ReplayGain), Tempo mit gleicher Tonhöhe, Stille überspringen, Schlaf-Timer, Mono und Balance. Auf Pixel und Tab geprüft." \
    "Stand der Technik für Medien belegt"
cell "Klang: eigene Effekte mit lebenden Bildern" \
    "ls $TESTS/player/KlangChainTest.kt $TESTS/player/ClickFreeTest.kt >/dev/null 2>&1 && grep -q 'CrossfeedStage' app/src/main/java/me/zhanghai/android/files/player/KlangChain.kt && grep -q 'libbs2b' app/src/main/res/raw/licenses.xml && grep -q 'KlangPictureView' app/src/main/res/layout/klang_effect_card.xml && test -x e2e/klang.sh" \
    "Raumklang für Kopfhörer (Crossfeed nach Bauer, bs2b-Port, MIT), Stereobreite, Hall (Freeverb, gemeinfrei), analoge Wärme und Klarheit (DynamicsProcessing-Mehrband-Kompressor), ohne Marken-Namen; ersetzt den veralteten Plattform-Virtualizer. Jede Karte im unteren Klang-Blatt zeigt ein ruhiges lebendes Bild, still bei reduzierter Bewegung. Rechenlast auf einem schwachen Gerät gemessen und Raumklang mit echtem Kopfhörer gehört." \
    "Musik-Player: Audio direkt in Ordo abspielen"
cell "Musik-Player als eigenes App-Symbol zum Einschalten" \
    "grep -q 'activity-alias' app/src/main/AndroidManifest.xml && grep -rq 'setComponentEnabledSetting' app/src/main/java/me/zhanghai/android/files/" \
    "Schalter in den Einstellungen: Ordo Musik bekommt ein eigenes Symbol auf dem Startbildschirm und lässt sich dort wieder ausschalten. Verhalten auf Pixel und Samsung gemessen." \
    "Musik-Player: Audio direkt in Ordo abspielen"
cell "Ordo Musik: eigene Musiksammlung mit Titeln, Alben, Interpreten und Ordnern" \
    "test -s app/src/main/java/me/zhanghai/android/files/viewer/audio/MusicLibrary.kt && test -s $TESTS/viewer/audio/MusicLibraryTest.kt && test -x e2e/music-library.sh && grep -q music-library e2e/run.sh" \
    "Ordo Musik zeigt immer die Sammlung statt in den Player zu springen: Titel, Alben (Raster), Interpreten und Ordner aus MediaStore, Suche, „Alle zufällig“, Albumseiten in Titelreihenfolge, Mini-Player unten. Aussehen nach dem Vorbild Samsung Music (großer Titel, Reiter), Goldener Schnitt, hell und dunkel, Telefon und Tablet; e2e/music-library.sh auf dem Emulator und dem Tab bestanden." \
    "Musik-Player als eigenes App-Symbol zum Einschalten"
cell "Video-Player: Filme direkt in Ordo ansehen" \
    "ls app/src/main/java/me/zhanghai/android/files/viewer/video/*.kt >/dev/null 2>&1" \
    "Alle gängigen Formate, Untertitel, Tonspur-Wahl, Bild-im-Bild, Tempo, Gesten für Helligkeit und Lautstärke; als eigenes Symbol einschaltbar wie der Musik-Player." \
    "Stand der Technik für Medien belegt" "Musik-Player als eigenes App-Symbol zum Einschalten"
cell "Abspielen auf: Bluetooth, Kabel und Cast-Geräte wählen" \
    "grep -q 'SystemOutputSwitcherDialogController' app/src/main/java/me/zhanghai/android/files/viewer/audio/AudioPlayerActivity.kt" \
    "Ein Knopf im Player öffnet die Ausgabe-Auswahl von Android (ab Android 11), ohne Google-Play-Dienste; Android 6–10 öffnet die Bluetooth-Einstellungen. Auf Pixel und Samsung geprüft. Recherche: docs/STREAMING-RECHERCHE.md." \
    "Musik-Player: Audio direkt in Ordo abspielen"
cell "Streaming: Musik an Chromecast und Google TV" \
    "ls app/src/main/java/me/zhanghai/android/files/player/cast/*.kt >/dev/null 2>&1 && ls $TESTS/player/cast/*Test.kt >/dev/null 2>&1" \
    "Eigener Cast-V2-Sender ohne Google-Play-Dienste (GPL-verträglich): mDNS-Suche, TLS mit gepinntem Gerätezertifikat, Default Media Receiver; lokaler Dateiserver mit Zufalls-Token, genau eine Datei, nur für das gewählte Gerät, nur solange gestreamt wird. Benachrichtigung, Sperrbildschirm und Lautstärketasten steuern den Fernseher. Auf echtem Chromecast geprüft." \
    "Abspielen auf: Bluetooth, Kabel und Cast-Geräte wählen"
cell "Streaming: Musik an Fernseher und Receiver per DLNA" \
    "ls app/src/main/java/me/zhanghai/android/files/player/dlna/*.kt >/dev/null 2>&1 && ls $TESTS/player/dlna/*Test.kt >/dev/null 2>&1" \
    "SSDP-Suche, AVTransport und Lautstärke per SOAP, DIDL-Lite-Metadaten; derselbe Dateiserver wie bei Cast. Auf mindestens einem Samsung- oder LG-Fernseher und mit Kodi geprüft." \
    "Streaming: Musik an Chromecast und Google TV"
cell "Streaming: Videos und Bilder an den Fernseher" \
    "grep -rq 'PLAYBACK_TYPE_REMOTE' app/src/main/java/me/zhanghai/android/files/viewer/video/ 2>/dev/null" \
    "Video-Player und Bildansicht nutzen dieselben Streaming-Ziele wie die Musik (Cast und DLNA), mit Untertiteln, wo das Ziel sie kann." \
    "Streaming: Musik an Fernseher und Receiver per DLNA" "Video-Player: Filme direkt in Ordo ansehen"
cell "PDF-Leser: suchen, markieren, Nachtmodus" \
    "grep -rq 'fun search' app/src/main/java/me/zhanghai/android/files/viewer/pdf/" \
    "Der vorhandene PDF-Betrachter wird ein Leser: Textsuche, Text markieren und kopieren, Lesezeichen, Nachtmodus, zuletzt gelesene Seite." \
    "Stand der Technik für Medien belegt"
cell "Markdown lesen und schreiben" \
    "ls app/src/main/java/me/zhanghai/android/files/viewer/markdown/*.kt >/dev/null 2>&1" \
    "MD-Dateien schön gesetzt lesen (Tabellen, Aufgabenlisten, Code) und mit Vorschau bearbeiten." \
    "Stand der Technik für Medien belegt"
cell "Bildbetrachter auf Spitzenniveau" \
    "grep -rqi 'ultrahdr\\|gainmap\\|Gainmap' app/src/main/java/me/zhanghai/android/files/viewer/image/ && test -f e2e/image-viewer.sh" \
    "Der vorhandene Bildbetrachter wird neu gemessen und ausgebaut: alle aktuellen Formate (HEIC, AVIF, WebP, Ultra HDR, RAW soweit belegt), flüssiges Zoomen auch bei riesigen Bildern, Bildinfos, Drehen und Zuschneiden ohne Qualitätsverlust, schöne Übergänge. Auf Pixel und Tab geprüft." \
    "Stand der Technik für Medien belegt"
cell "PDF bearbeiten: markieren, kommentieren, Formulare, Seiten ordnen" \
    "test -s docs/PDF-BEARBEITEN.md && grep -rq 'fun save' app/src/main/java/me/zhanghai/android/files/viewer/pdf/" \
    "Nach dem Leser: Text markieren und kommentieren, Formulare ausfüllen, Seiten drehen, löschen und zusammenfügen. Bibliothek mit verträglicher Lizenz aus der Recherche; Entscheidung und Grenzen in docs/PDF-BEARBEITEN.md." \
    "PDF-Leser: suchen, markieren, Nachtmodus"
cell "Jede gängige Datei öffnen, lesen und schreiben – und es sieht gut aus" \
    "test -s docs/DATEITYPEN.md && ls app/src/main/java/me/zhanghai/android/files/viewer/data/*.kt >/dev/null 2>&1" \
    "Liste aller Dateitypen, die auf einem Handy heute vorkommen (JSON, YAML, XML, CSV, TOML, Logs, Code, Markdown, Untertitel, E-Books …), in docs/DATEITYPEN.md: was Ordo heute damit tut und was es können soll. Dann je Gruppe ein eigener, schöner Betrachter und Editor (Baumansicht für JSON, Tabelle für CSV, farbiger Code), kein nackter Text." \
    "Markdown lesen und schreiben"
cell "Eingebaute Apps entkoppeln: jede als eigenes App-Symbol, einzeln schaltbar" \
    "test -f app/src/main/java/me/zhanghai/android/files/settings/LauncherIconsPreferenceFragment.kt && [ \"\$(grep -c '<activity-alias' app/src/main/AndroidManifest.xml)\" -ge 3 ]" \
    "Eine Seite in den Einstellungen mit einem Schalter je eingebauter App (Musik, Video, Bilder, PDF, Notizen): eingeschaltet bekommt sie ein eigenes Symbol und einen eigenen Namen auf dem Startbildschirm und öffnet direkt ihre Ansicht. Erst freischalten, wenn die jeweilige App gut genug ist. Ein Mechanismus für alle, auf Pixel und Samsung gemessen." \
    "Musik-Player als eigenes App-Symbol zum Einschalten" "Video-Player: Filme direkt in Ordo ansehen"
cell "Icon-Farbe für Ordo und jede eingebaute App selbst wählen" \
    "[ \"\$(grep -c 'android.files.launcher.Home' app/src/main/AndroidManifest.xml)\" -ge 8 ] && [ \"\$(grep -c 'android.files.launcher.Music' app/src/main/AndroidManifest.xml)\" -ge 8 ] && test -x e2e/icon-colors.sh && grep -q 'fun choosingAColourLeavesExactlyThatAliasOnAndNeverNone' $TESTS/launcher/BuiltInAppTest.kt" \
    "Acht Hintergrundfarben je Symbol (Ordo, Ordo Musik und jede künftige eingebaute App), ein activity-alias je Farbe aus utils/launcher-icon-colors.py, Auswahl mit Vorschau unter Einstellungen › Eingebaute Apps; nie ohne Launcher-Eintrag umschalten, Themed Icons bleiben." \
    "Musik-Player als eigenes App-Symbol zum Einschalten"
cell "Goldener Schnitt als Maßsystem in ganz Ordo" \
    "test -s docs/DESIGN-SYSTEM.md && ! grep -rqE 'android:(padding|layout_margin)[A-Za-z]*=\"(4|6|12|16|20|24|28|32)dp\"' app/src/main/res/layout/" \
    "Alle eigenen Abstände, Größen, Proportionen und Rundungen folgen φ (Fibonacci-Reihe 3/5/8/13/21/34/55/89 dp, dimens_phi.xml); Bildschirm für Bildschirm umgestellt und auf Telefon und Tablet gemessen. Schriftgrößen bleiben Material 3."

cell "Dokumente bearbeiten: Entscheidung für Office-Formate" \
    "test -s docs/OFFICE-ENTSCHEIDUNG.md" \
    "DOCX, XLSX, PPTX und ODT: ehrlich klären, was auf dem Gerät ohne Cloud in Spitzenqualität geht (ansehen, bearbeiten, gar nicht), mit Größe und Lizenz. Entscheidung von Belkis in docs/OFFICE-ENTSCHEIDUNG.md." \
    "Markdown lesen und schreiben"

# ---- The release cycle: opens again after every release, for ever -----------------------------------------------
LAST=$($TAG 'refs/tags/v[0-9]*' | sed -n 's|.*refs/tags/v\([0-9.]*\)$|\1|p' | sort -V | tail -1)
if [ -n "$LAST" ]; then
    cell "Nach v$LAST: nächste Version hat eine eigene Nummer" \
        "[ \"\$($VERSION)\" != \"$LAST\" ]" \
        "versionName und versionCode in app/build.gradle hochzählen (Fix x.y.Z, Funktionen x.Y.0)."
    cell "Nach v$LAST: beide READMEs nennen die neue Version" \
        "v=\$($VERSION); [ \"\$v\" != \"$LAST\" ] && grep -q \"(version \$v)\" README.md && grep -q \"(Version \$v)\" README.de.md" \
        "README.md und README.de.md mitziehen; DocsConsistencyTest prüft es beim Bauen." \
        "Nach v$LAST: nächste Version hat eine eigene Nummer"
    cell "Nach v$LAST: neue Version veröffentlicht" \
        "v=\$($VERSION); [ \"\$v\" != \"$LAST\" ] && $TAG \"refs/tags/v\$v\" | grep -q ." \
        "Tests grün, auf den Testgeräten geprüft, PR gemergt, GitHub-Release mit APK und Tag." \
        "Nach v$LAST: beide READMEs nennen die neue Version"
fi

# A tick checks only cells whose dependencies are done, so a finished chain needs one tick per link.
for _ in 1 2 3 4 5; do apo tick --quiet || true; done

# ---- docs/ROADMAP-LIVE.md ---------------------------------------------------------------------------------------
status=$(apo status)
line() { sed -E 's/^ *\[[a-z]+\] c-[0-9a-f]+ (.*)  p=[0-9]+  deps:.*$/\1/'; }
open=$(echo "$status" | grep -E '^ *\[(active|quarantined)\]' | line | sed 's/^/- [ ] /' || true)
done_=$(echo "$status" | grep -E '^ *\[done\]' | line | sed 's/^/- [x] /' || true)
# Finished release cycles stay in the list but leave the graph, or it would grow with every release.
old=$(echo "$status" | grep -E '^ *\[done\] c-[0-9a-f]+ Nach v' | awk '{print "n" $2}' | tr - _ | paste -sd'|' || true)
graph=$(apo graph | { [ -n "$old" ] && grep -Ev "$old" || cat; })
next=$(apo next | sed -E 's/^c-[0-9a-f]+ //')
{
    echo "# Ordo – lebende Roadmap"
    echo
    echo "<!-- Erzeugt von utils/roadmap.sh. Nicht von Hand ändern: Ziele stehen im Skript, erledigt ist, was sein Prüfbefehl bestätigt. -->"
    echo
    echo "Stand: $(date +%d.%m.%Y), letzte Version: v${LAST:-–}. Als Nächstes: **${next:-nichts offen}**"
    echo
    echo "Jedes Ziel ist eine Zelle mit einem Prüfbefehl ([autopoiesis](https://github.com/BEKO2210/autopoiesis))."
    echo "Besteht der Befehl, ist das Ziel erledigt. Nach jeder veröffentlichten Version entstehen die Zellen"
    echo "für die nächste – vor 1.0.0 und danach."
    echo
    echo "## Offen"
    echo
    echo "${open:-Nichts offen.}"
    echo
    echo "## Erledigt"
    echo
    echo "${done_:-Noch nichts.}"
    echo
    echo "## Graph"
    echo
    echo '```mermaid'
    echo "$graph"
    echo '```'
} > docs/ROADMAP-LIVE.md
echo "$status"
