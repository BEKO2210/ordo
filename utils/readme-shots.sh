#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# The README pictures, reproducible: screenshots and short clips in German and English, light and dark, phone and
# tablet, on the Android 16 emulator (AVD ahnora-36, emulator-5580) with sample data only, never a real phone.
#
#   utils/readme-shots.sh <debug apk> [repo to write into]
#   STEPS="music pick" utils/readme-shots.sh ...   only these steps (basics ai settings music pick tablet)
#   OWN_EMULATOR=1 utils/readme-shots.sh ...       use the emulator-5580 you booted yourself and leave it running
#
# Waits until emulator-5580 is free (another run's emulator is never touched), boots it, installs the APK fresh,
# creates its own sample music (six albums with covers), holiday photos and nothing else, captures, deletes exactly
# what it created and shuts its emulator down. Writes docs/screenshots/{de,en}/*.png (phone 540 px, tablet 720 px
# wide) and docs/media/clips/{de,en}-*.webp. Needs adb, ffmpeg (libwebp) and python3 with Pillow.
set -uo pipefail
APK=$(realpath "${1:?usage: $0 <debug apk> [repo]}")
REPO=$(cd "${2:-$(dirname "$0")/..}" && pwd)
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}; export PATH=$SDK/platform-tools:$PATH
E=emulator-5580; AVD=ahnora-36
RAW=$(mktemp -d); DATA=$(mktemp -d)
log() { echo "[$(date +%T)] $*"; }

STEPS=${STEPS:-basics ai settings music pick tablet}
# ---- the emulator: wait for it to be free, then boot our own
[ "${OWN_EMULATOR:-}" = 1 ] || for i in $(seq 720); do
    adb devices | grep -q "^$E" || pgrep -f "avd $AVD" >/dev/null || break
    [ $((i % 20)) = 1 ] && log "waiting for $E to be free"
    sleep 15
done
if [ "${OWN_EMULATOR:-}" != 1 ]; then
    adb devices | grep -q "^$E" && { log "ABORT: $E still busy"; exit 3; }
    log "booting $AVD"
    (setsid "$SDK/emulator/emulator" -avd $AVD -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
        -port 5580 > "$RAW/emu.log" 2>&1 &)
    for i in $(seq 100); do [ "$(timeout 5 adb -s $E shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 3; done
    sleep 10
fi

. "$REPO/e2e/lib.sh" "$E"
export E2E_FAST=0
P=$PKG
# Every file this run puts on the emulator, removed again on exit (also on Ctrl-C).
MADE=()
# Other runs' music on this emulator (some covers show a real face) must not appear in the pictures: each folder or
# file that holds it is moved into a hidden folder for the capture and moved back on exit. Nothing of it is deleted.
# The list lives on the emulator, so a run that was killed hard is put right by the next one.
PARK=/sdcard/.ordo-readme-parked
unpark() {
    local unit
    a shell "cat $PARK/list.txt 2>/dev/null" | tr -d '\r' | while IFS= read -r unit; do
        [ -n "$unit" ] && a shell "mkdir -p \"\$(dirname '/sdcard/$unit')\" && mv '$PARK/$unit' '/sdcard/$unit'"
    done
    a shell "rm -f $PARK/list.txt; find $PARK -depth -type d -empty -delete >/dev/null 2>&1"
    a shell "[ -e $PARK ]" && log "WARN: $PARK is not empty, look into it"
}
park() {
    local unit std='^(Music|Download|Podcasts|Ringtones|Alarms|Notifications|Audiobooks|Recordings|Documents|DCIM|Pictures|Movies)/'
    # Android's standard folders stay (moving them is refused); in them the first level below is moved.
    a shell content query --uri content://media/external/audio/media --projection _data | tr -d '\r' |
        sed -n 's#.*_data=/storage/emulated/0/##p' |
        awk -F/ -v std="$std" '{ print ($0 ~ std) ? $1 "/" $2 : $1 }' | sort -u | while IFS= read -r unit; do
            case $unit in *"'"*) log "WARN: not parked (quote in name): $unit"; continue ;; esac
            a shell "mkdir -p \"\$(dirname '$PARK/$unit')\" && mv '/sdcard/$unit' '$PARK/$unit' && echo '$unit' >> $PARK/list.txt"
        done
    a shell "content call --uri content://media --method scan_volume --arg external_primary" >/dev/null 2>&1
    log "parked $(a shell "cat $PARK/list.txt 2>/dev/null" | grep -c .) folders and files with other music"
}
cleanup() {
    local f
    for f in "${MADE[@]}"; do a shell "rm -rf '$f'"; done
    unpark
    a shell "rm -f /sdcard/ordo-readme-*.mp4"
    a shell "content call --uri content://media --method scan_volume --arg external_primary" >/dev/null 2>&1
    a shell wm size reset; a shell wm density reset
    a shell cmd uimode night no
    [ -n "${PIN:-}" ] && a shell locksettings clear --old "$PIN" >/dev/null
    a shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null
    [ "${OWN_EMULATOR:-}" = 1 ] || adb -s $E emu kill >/dev/null 2>&1
    rm -rf "$RAW" "$DATA"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

a uninstall $P >/dev/null 2>&1
a install "$APK" | tail -1
grant
unpark
park

# ---- sample data: six albums with covers, and the same nine holiday photos under a German and an English name
log "making sample data"
python3 - "$DATA" <<'PY'
import math, random, sys, pathlib
from PIL import Image, ImageDraw, ImageFilter
out = pathlib.Path(sys.argv[1])

def gradient(size, top, bottom):
    img = Image.new("RGB", (size, size))
    d = ImageDraw.Draw(img)
    for y in range(size):
        t = y / (size - 1)
        d.line([(0, y), (size, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(top, bottom)))
    return img

def cover(name, top, bottom, accent, kind, seed):
    rnd = random.Random(seed); s = 600
    img = gradient(s, top, bottom)
    layer = Image.new("RGBA", (s, s)); d = ImageDraw.Draw(layer)
    if kind == "rings":
        for r in range(260, 40, -36):
            d.ellipse([s / 2 - r, s / 2 - r, s / 2 + r, s / 2 + r], outline=accent + (150,), width=6)
    elif kind == "sun":
        d.ellipse([170, 120, 430, 380], fill=accent + (235,))
        for i in range(9):
            d.rectangle([0, 300 + i * 22, s, 300 + i * 22 + 8], fill=top + (255,))
    elif kind == "waves":
        for k in range(7):
            pts = [(x, 330 + k * 34 + 26 * math.sin(x / 55 + k)) for x in range(0, s + 10, 10)]
            d.line(pts, fill=accent + (90 + k * 20,), width=9)
        d.ellipse([400, 70, 500, 170], fill=(245, 245, 235, 230))
    elif kind == "aurora":
        for k in range(5):
            pts = [(x, 120 + k * 40 + 60 * math.sin(x / 90 + k * 0.7)) for x in range(0, s + 10, 10)]
            d.line(pts, fill=accent + (70,), width=40)
    elif kind == "dunes":
        d.ellipse([360, 90, 470, 200], fill=(255, 236, 200, 240))
        for k, c in enumerate([(214, 140, 80), (190, 110, 60), (160, 84, 50)]):
            pts = [(x, 380 + k * 60 + 40 * math.sin(x / 120 + k * 2)) for x in range(0, s + 10, 10)]
            d.polygon(pts + [(s, s), (0, s)], fill=c + (255,))
    elif kind == "planes":
        for _ in range(7):
            x, y, w = rnd.randint(40, 520), rnd.randint(40, 520), rnd.randint(40, 90)
            d.polygon([(x, y), (x + w, y + w * 0.35), (x + w * 0.35, y + w * 0.5)], fill=(255, 255, 255, 220))
            d.polygon([(x, y), (x + w * 0.35, y + w * 0.5), (x + w * 0.25, y + w * 0.75)], fill=accent + (220,))
    blur = 14 if kind == "aurora" else 1.2  # the aurora's bands glow, everything else stays crisp
    img = Image.alpha_composite(img.convert("RGBA"), layer.filter(ImageFilter.GaussianBlur(blur))).convert("RGB")
    if kind == "aurora":  # sharp mountains in front of the glow
        ImageDraw.Draw(img).polygon([(0, s), (0, 470), (140, 380), (260, 450), (380, 360), (600, 470), (600, s)], fill=(10, 14, 30))
    img.save(out / f"{name}.jpg", quality=88)

cover("aurora", (8, 18, 48), (30, 12, 60), (60, 230, 190), "aurora", 1)
cover("neon", (40, 10, 70), (250, 70, 140), (40, 240, 255), "sun", 2)
cover("blue", (10, 30, 70), (40, 90, 160), (150, 200, 255), "rings", 3)
cover("desert", (255, 170, 90), (255, 120, 80), (0, 0, 0), "dunes", 4)
cover("paper", (255, 214, 190), (190, 200, 255), (255, 120, 120), "planes", 5)
cover("tide", (6, 14, 40), (16, 50, 90), (120, 180, 230), "waves", 6)

# Holiday photos: simple landscapes, a sky, a sun and three hills, each in its own light.
names = [("Abendessen am See", "Dinner by the lake"), ("Café am Morgen", "Morning coffee"), ("Ferienhaus", "Holiday house"),
         ("Herbstwald", "Autumn forest"), ("Gartenhaus", "Garden house"), ("Leuchtturm", "Lighthouse"),
         ("Strand bei Nacht", "Beach at night"), ("Wanderweg", "Hiking trail"), ("Bergsee", "Mountain lake")]
photos = out / "photos"; photos.mkdir()
for i, (de, en) in enumerate(names):
    rnd = random.Random(i * 7 + 3); w, h = 1200, 900
    hue = [(255, 160, 90), (120, 180, 255), (255, 210, 140), (230, 120, 60), (170, 200, 230), (90, 150, 230),
           (30, 40, 90), (140, 210, 160), (100, 190, 220)][i]
    img = Image.new("RGB", (w, h)); d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / h
        d.line([(0, y), (w, y)], fill=tuple(int(c * (1 - t * 0.5) + 255 * t * 0.3) for c in hue))
    sx = rnd.randint(200, 1000)
    d.ellipse([sx - 70, 160, sx + 70, 300], fill=(255, 245, 210))
    for k in range(3):
        base, phase = 480 + k * 120, rnd.random() * 6
        pts = [(x, base + 70 * math.sin(x / (160 + 40 * k) + phase)) for x in range(0, w + 20, 20)]
        shade = tuple(max(0, int(c * (0.55 - k * 0.15))) for c in hue)
        d.polygon(pts + [(w, h), (0, h)], fill=shade)
    img = img.filter(ImageFilter.GaussianBlur(1.5))
    img.save(photos / f"{de}.jpg", quality=80)
    img.save(photos / f"{en}.jpg", quality=80)
(out / "names.txt").write_text("".join(f"{de}|{en}\n" for de, en in names))

# Three documents for the vault: a scan as JPEG and PDF (grey lines on white) and a text file.
scans = out / "scans"; scans.mkdir()
page = Image.new("RGB", (800, 1100), "white"); d = ImageDraw.Draw(page)
for y in range(120, 1000, 60):
    d.rectangle([80, y, 720 - (y * 7) % 200, y + 18], fill=(200, 205, 215))
page.save(scans / "Scan 0314.jpg", quality=80); page.save(scans / "Scan 0502.pdf")
(scans / "Backup-Codes.txt").write_text("A1B2-C3D4\nE5F6-G7H8\n")
PY

# album|artist|cover|year|title;title;title
ALBUMS=(
    "Aurora Drift|Nordlicht Ensemble|aurora|2025|First Light;Glacier Glow;Silent Fjord"
    "Blue Hours|Mara Lind|blue|2024|Smoke Rings;Late Train;Velvet"
    "Desert Bloom|Sandglass|desert|2026|Mirage;Golden Hour;Dune Walk"
    "Midnight Tide|Okeanos|tide|2023|Undertow;Moonwater;Harbour Lights"
    "Neon Avenue|Kito & The Lights|neon|2026|Midnight Drive;Chrome Hearts;Afterglow"
    "Paper Planes|Juno Park|paper|2025|Paper Planes;Sunday;Lemonade Sky"
)
n=0
for row in "${ALBUMS[@]}"; do
    IFS='|' read -r album artist cov year titles <<<"$row"
    mkdir -p "$DATA/music/$album"; track=0
    IFS=';' read -ra list <<<"$titles"
    for title in "${list[@]}"; do
        track=$((track + 1)); n=$((n + 1))
        # Stereo like real music (a mono-only bug once hid stereo songs), 3-4 minutes, the cover embedded.
        ffmpeg -loglevel error -f lavfi -i "sine=frequency=$((180 + n * 11)):duration=$((172 + (n * 37) % 70))" \
            -i "$DATA/$cov.jpg" -map 0 -map 1 -ac 2 -c:a libmp3lame -b:a 32k -c:v mjpeg -disposition:v attached_pic \
            -id3v2_version 3 -metadata title="$title" -metadata artist="$artist" -metadata album="$album" \
            -metadata album_artist="$artist" -metadata track="$track" -metadata date="$year" \
            "$DATA/music/$album/$(printf %02d $track) $title.mp3"
    done
done
for dir in "${ALBUMS[@]%%|*}"; do MADE+=("/sdcard/Music/$dir"); done
MADE+=("/sdcard/Urlaub 2026" "/sdcard/Holiday 2026" "/sdcard/Scans")
# Only ever remove what this run made: a folder of the same name that is already there stops the run.
for f in "${MADE[@]}"; do a shell "[ -e '$f' ]" && { MADE=(); log "ABORT: $f exists already"; exit 4; }; done
for album_dir in "$DATA"/music/*; do a push "$album_dir" /sdcard/Music/ >/dev/null; done
a push "$DATA/scans" /sdcard/Scans >/dev/null
while IFS='|' read -r de en; do
    # The AI clip moves the photos to Pictures and back; if that undo ever fails, cleanup finds them there too.
    for name in "$de" "$en"; do
        a shell "[ -e '/sdcard/Pictures/$name.jpg' ]" && { log "ABORT: Pictures/$name.jpg exists already"; exit 4; }
        MADE+=("/sdcard/Pictures/$name.jpg")
    done
done < "$DATA/names.txt"
# adb push leaves new files pending in MediaStore (no tags, not counted as music) until a scan: one per file.
for f in "$DATA"/music/*/*.mp3; do
    f=/storage/emulated/0/Music/${f#"$DATA"/music/}
    a shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "'file://${f// /%20}'" >/dev/null 2>&1
done
for i in $(seq 30); do
    [ "$(a shell content query --uri content://media/external/audio/media --projection title \
        --where "\"album IN ('Aurora Drift','Neon Avenue','Paper Planes')\"" 2>/dev/null | grep -c '^Row')" -ge 9 ] && break
    sleep 1
done

# ---- status bar: 12:00, full battery, no notifications
a shell settings put global sysui_demo_allowed 1
demo() { a shell "am broadcast -a com.android.systemui.demo -e command $*" >/dev/null; }
# Set again for every language: after a screen size change System UI can come back with a second SIM in the bar.
statusbar() {
    demo exit; demo enter; demo clock -e hhmm 1200; demo battery -e level 100 -e plugged false
    demo network -e wifi show -e level 4 -e mobile show -e sims 1 -e datatype none -e level 4; demo notifications -e visible false
}

# ---- helpers
L=en
shot() { sleep "${2:-1}"; mkdir -p "$RAW/$L"; a exec-out screencap -p > "$RAW/$L/$1.png"; log "shot $L/$1"; }
night() { a shell cmd uimode night "$1"; sleep 2; }
start() { a shell am force-stop $P; sleep 0.5; a shell am start -W "$@" >/dev/null; sleep 3; }
home() { start -n $P/me.zhanghai.android.files.home.HomeActivity; skip_onboarding >/dev/null; sleep 3; }
folder() { start -a android.intent.action.VIEW -d "file://${1// /%20}" -t inode/directory -n $P/me.zhanghai.android.files.filelist.FileListActivity; }
settings() { start -n $P/me.zhanghai.android.files.settings.SettingsActivity; }
library() { start -n $P/me.zhanghai.android.files.viewer.audio.MusicLauncherActivity; }
scroll_to() { local i; for i in 1 2 3 4 5 6; do ui has "$1" && return 0; swipe_up; done; return 1; }
# record NAME SECONDS: screen recording in the background; the caller acts, then calls `record_end NAME`.
# record NAME: screen recording in the background (at most 60 s) until `record_end NAME`.
record() { a shell "screenrecord --bit-rate 6000000 --time-limit 60 /sdcard/ordo-readme-$1.mp4" & REC=$!; sleep 1; }
record_end() {
    sleep 1; a shell pkill -INT screenrecord; wait "$REC"; sleep 1
    mkdir -p "$RAW/$L"; a pull "/sdcard/ordo-readme-$1.mp4" "$RAW/$L/$1.mp4" >/dev/null; a shell rm -f "/sdcard/ordo-readme-$1.mp4"
}
built_in_apps() { settings; ui tap "Eingebaute Apps|Built-in apps" >/dev/null; ui wait "Ordo Musik|Ordo Music" 8 >/dev/null; }
view_mode() { ui tap "Anzeigen und Sortieren|View and sort" >/dev/null && ui tap "$1" >/dev/null; sleep 1.5; }
# The vault needs a screen lock: a PIN is set only while the vault is shown (cleared on exit too); the three scans
# are moved in on the first pass.
PIN=
FILLED=
vault() {
    a shell locksettings set-pin 4711 | grep -q 4711 || { log "WARN: no PIN set, the vault stays locked"; return; }
    PIN=4711
    if [ -z "$FILLED" ]; then
        folder /storage/emulated/0/Scans
        ui longtap "Backup-Codes.txt" >/dev/null; ui tap "Scan 0314.jpg" >/dev/null; ui tap "Scan 0502.pdf" >/dev/null
        ui tap selectionMoreButton >/dev/null; ui tap "In den Tresor|Move to vault" >/dev/null
        ui tap "Verschieben|Move" >/dev/null; sleep 3; FILLED=1
    fi
    home; ui tap vaultCard >/dev/null; sleep 2
    a shell input text "$PIN"; a shell input keyevent ENTER
    shot vault 3
    a shell locksettings clear --old "$PIN" >/dev/null; PIN=
}
# The app drawer shows every installed app: keep the two rows with Ordo and Ordo Music, left of Ordo's right edge.
crop_launcher() {
    local x2 y1 y2
    read -r _ y1 x2 _ < <(ui bounds Ordo | head -1)
    read -r _ _ _ y2 < <(ui bounds "Ordo Music|Ordo Musik" | head -1)
    [ -n "${x2:-}" ] && [ -n "${y2:-}" ] || { log "WARN: launcher not cropped"; return; }
    python3 -c "import sys; from PIL import Image; f=sys.argv[1]; Image.open(f).crop((0, $y1 - 70, $x2 + 30, $y2 + 30)).save(f)" "$RAW/$L/launcher.png"
}
tap_colour() { # ROW SWATCH: the swatch below the row's title
    local title_y x1 y1 x2 y2
    title_y=$(ui bounds "$1" | tail -1 | awk '{print $4}')
    read -r x1 y1 x2 y2 < <(ui bounds "$2" | awk -v t="$title_y" '$2 > t' | head -1)
    a shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2)); sleep 1.5
}

# ---- the steps, each in German and English
basics() { # home, storage, files, analysis, backup, vault: light
    night no
    home; shot home 2
    swipe_up; shot storage 1
    folder "/storage/emulated/0/$PHOTOS"; view_mode "Raster|Grid"; shot files 2
    view_mode "Liste|List"
    home; swipe_up; ui tap analyzeButton >/dev/null; ui wait "Deine Dateien|Your files" 120 >/dev/null; shot analysis 3
    swipe_up; swipe_up; shot cleanup 1
    home; ui tap backupCard >/dev/null; shot backup 2
    vault
}

ai() { # home and the plan (dark), then a clip of plan, confirm, undo
    night yes
    home; shot home-dark 2
    local answer="{\"action\":\"move\",\"source\":\"$PHOTOS\",\"destination\":\"Pictures\",\"kinds\":[\"image\"]}"
    ask() { a shell am start -W --activity-clear-task -n "$P/me.zhanghai.android.files.ai.DebugAiActivity" \
        --es text "'$AI_TEXT'" --es answer "'$answer'" >/dev/null; ui wait confirmButton 10 >/dev/null; }
    a shell am force-stop $P; ask; shot ai 1
    # A screen read takes seconds while recording: the confirm button is tapped where it was the first time.
    local x1 y1 x2 y2; read -r x1 y1 x2 y2 < <(ui bounds confirmButton | head -1)
    a shell am force-stop $P; a shell input keyevent HOME; sleep 2  # the clip starts on Ordo, not on the launcher
    a shell am start -W -n $P/me.zhanghai.android.files.home.HomeActivity >/dev/null; sleep 3
    record ai-command
    ask; sleep 2
    a shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2)); ui wait "Rückgängig|Undo" 8 >/dev/null; sleep 1.5
    ui tap "Rückgängig|Undo" >/dev/null; sleep 3
    record_end ai-command
    [ "$(a shell "ls '/sdcard/$PHOTOS' | wc -l" | tr -d '\r ')" = 9 ] || log "WARN: undo left $PHOTOS incomplete"
}

settings_shots() { # overview, appearance, icon colours, the app drawer with recoloured icons: light
    night no
    settings; shot settings 1
    ui tap "Aussehen|Appearance" >/dev/null; shot appearance 1.5
    a shell input keyevent BACK; sleep 1
    built_in_apps
    ui checked "Ordo Musik|Ordo Music" || { ui tap "Ordo Musik|Ordo Music" >/dev/null; sleep 2; }
    swipe_up; swipe_up
    tap_colour "Ordo" "Icon-Farbe Lila|Icon colour Violet"; sleep 6
    tap_colour "Ordo Musik|Ordo Music" "Icon-Farbe Grün|Icon colour Green"; sleep 1
    shot icon-colours 6  # after the snackbar about the new colour has gone
    a shell input keyevent HOME; sleep 3
    read -r W H < <(screen_size)
    a shell input swipe $((W / 2)) $((H * 4 / 5)) $((W / 2)) $((H / 5)) 400
    shot launcher 3
    crop_launcher
    a shell input keyevent HOME; sleep 1
    # back to the defaults: Ordo blue, the music icon off
    built_in_apps; swipe_up; swipe_up
    tap_colour "Ordo" "Icon-Farbe Blau|Icon colour Blue"; sleep 6
    tap_colour "Ordo Musik|Ordo Music" "Icon-Farbe Blau|Icon colour Blue"
    swipe_down; swipe_down
    ui checked "Ordo Musik|Ordo Music" && ui tap "Ordo Musik|Ordo Music" >/dev/null
}

music() { # library, player, Play on (light); albums, player, Sound and the clips (dark)
    night no
    library; ui wait "First Light" 10 >/dev/null
    ui tap "First Light" >/dev/null; wait_on viewer.audio.AudioPlayerActivity 8; sleep 4
    a shell input keyevent KEYCODE_MEDIA_PAUSE; shot player 2
    ui tap actionOutput >/dev/null; shot play-on 2.5
    a shell input keyevent BACK; sleep 1.5
    a shell input keyevent BACK; sleep 2
    shot library-songs 1.5
    ui tap "Alben|Albums" >/dev/null; shot library-albums 2
    ui tap "Aurora Drift" >/dev/null; shot album 2
    a shell input keyevent BACK; sleep 1.5
    ui tap "Interpreten|Artists" >/dev/null; shot library-artists 2

    night yes
    library; ui tap "Alben|Albums" >/dev/null; shot library-albums-dark 2
    record music
    swipe_up; swipe_down; ui tap "Desert Bloom" >/dev/null; sleep 2
    ui tap "Golden Hour" >/dev/null; wait_on viewer.audio.AudioPlayerActivity 8; sleep 4
    record_end music
    a shell input keyevent KEYCODE_MEDIA_PAUSE; shot player-dark 2
    ui tap actionSound >/dev/null; ui wait "Equalizer" 5 >/dev/null; sleep 1
    ui checked Equalizer || ui tap equalizerEnabled >/dev/null; sleep 0.5
    ui tap "Bass" >/dev/null; shot sound 1.5
    record sound
    for p in "Höhen|Treble" "Gesang|Vocal" "Bass"; do ui tap "$p" >/dev/null; sleep 1.5; done
    swipe_up; swipe_up; sleep 1
    record_end sound
    shot sound-effects 1
    a shell input keyevent BACK; sleep 1; a shell input keyevent BACK; sleep 1
}

pick() { # Ordo picking a file for an upload in another app: light
    night no
    a shell am force-stop $P
    a shell am start -W -a android.intent.action.GET_CONTENT -c android.intent.category.OPENABLE -t "audio/*" \
        -n "$P/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null; sleep 3
    ui tap "Music" >/dev/null; sleep 1.5; ui tap "Neon Avenue" >/dev/null; shot pick 2
    a shell am force-stop $P
}

tablet() { # 320 dpi: home and analysis upright (1600x2560), albums across (2560x1600, dark)
    a shell wm size 1600x2560; a shell wm density 320; sleep 3; statusbar
    night no
    home; shot tablet-home 3
    swipe_up; ui tap analyzeButton >/dev/null; ui wait "Deine Dateien|Your files" 120 >/dev/null; shot tablet-analysis 3
    a shell wm size 2560x1600; sleep 3; statusbar
    night yes
    library; ui tap "Alben|Albums" >/dev/null; shot tablet-albums 2
    a shell wm size reset; a shell wm density reset; sleep 3; statusbar
}

launch 8 >/dev/null 2>&1
# photos LANG: only that language's holiday folder is on the device, so no screen shows the other one.
photos() {
    local col=$([ "$1" = de ] && echo 1 || echo 2) other=$([ "$1" = de ] && echo "Holiday 2026" || echo "Urlaub 2026")
    a shell "rm -rf '/sdcard/$other'; mkdir -p '/sdcard/$PHOTOS'"
    cut -d'|' -f$col "$DATA/names.txt" | while IFS= read -r name; do a push "$DATA/photos/$name.jpg" "/sdcard/$PHOTOS/" >/dev/null; done
}
for L in de en; do
    if [ $L = de ]; then
        a shell cmd locale set-app-locales $P --locales de-DE
        PHOTOS="Urlaub 2026"; AI_TEXT="Verschiebe alle Fotos aus Urlaub 2026 nach Pictures"
    else
        a shell cmd locale set-app-locales $P --locales en-US
        PHOTOS="Holiday 2026"; AI_TEXT="Move all photos from Holiday 2026 to Pictures"
    fi
    photos $L; statusbar
    for step in $STEPS; do log "step $L/$step"; [ $step = settings ] && step=settings_shots; $step; done
done
a shell am force-stop $P
log "crashes: $(crashes)"

# ---- finish: phone shots 540 px wide, tablet 720 px, 256-colour PNG; clips as animated WebP, 360 px, 12 fps
log "processing"
python3 - "$RAW" "$REPO" <<'PY'
import pathlib, sys
from PIL import Image
raw, repo = map(pathlib.Path, sys.argv[1:])
for f in sorted(raw.glob("*/*.png")):
    img = Image.open(f).convert("RGB")
    width = 960 if img.width > img.height >= 1500 else 720 if img.width >= 1500 else 540
    img = img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)
    dest = repo / "docs/screenshots" / f.parent.name / f.name
    dest.parent.mkdir(parents=True, exist_ok=True)
    img.quantize(256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.FLOYDSTEINBERG).save(dest, optimize=True)
    print(dest.relative_to(repo), dest.stat().st_size // 1024, "kB")
PY
mkdir -p "$REPO/docs/media/clips"
for f in "$RAW"/*/*.mp4; do
    L=$(basename "$(dirname "$f")"); name=$(basename "$f" .mp4)
    ffmpeg -loglevel error -y -i "$f" -vf "fps=12,scale=360:-2:flags=lanczos" -an -c:v libwebp_anim -q:v 55 \
        -compression_level 6 -loop 0 "$REPO/docs/media/clips/$L-$name.webp"
    log "clip $L-$name $(($(stat -c %s "$REPO/docs/media/clips/$L-$name.webp") / 1024)) kB"
done
log FERTIG
