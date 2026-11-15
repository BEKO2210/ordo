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
