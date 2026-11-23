#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# farm: orient=once timeout=900
# File jobs lose nothing (night). Only with FARM_NIGHT=1 or E2E_FILEJOB=1, otherwise SKIP. WRITES: creates
# /sdcard/ordo-e2e-filejob with files of seeded names (FILEJOB_SEED, default 71) and contents, copies, moves and
# trashes them through Ordo's UI, name clashes included, and force-stops the app in the middle of a copy-replace, a
# move and a delete. Then adb checks by MD5: no file lost, no clashing file replaced without a "Replace", no half
# file under a real name, everything in the trash comes back. The folder and exactly its own trash entries are
# removed at the end, also on Ctrl-C. Nothing else on the device is touched.
. "$(dirname "$0")/lib.sh" "$@"
[ "${FARM_NIGHT:-}" = 1 ] || [ "${E2E_FILEJOB:-}" = 1 ] || { echo "SKIP filejob-safety (night only: FARM_NIGHT=1 or E2E_FILEJOB=1)"; exit 0; }

SEED=${FILEJOB_SEED:-71}
REL=ordo-e2e-filejob
DIR=/sdcard/$REL
BIG_MB=48 # still running when the app is stopped 0.4 s after the tap
TMP=$(mktemp -d)
cleanup() {
    a shell "rm -rf $DIR" >/dev/null 2>&1
    trash_drop "$REL" >/dev/null 2>&1
    rm -rf "$TMP"
}
trap cleanup EXIT

# Seeded names and contents, packed on the host (names look like ordoe2e-3f9a1c.dat; the same seed gives the same
# names). manifest: "<md5> <folder>/<name>" for everything that must survive.
python3 - "$TMP" "$SEED" <<'PY'
import hashlib, io, random, sys, tarfile
tmp, seed = sys.argv[1], int(sys.argv[2])
rnd = random.Random(seed)
def name(): return "ordoe2e-%06x.dat" % rnd.getrandbits(24)
def blob(): return rnd.randbytes(rnd.randint(2048, 9000))
groups = {"copysrc": [], "copydst": [], "trash": []}
files = {}
for _ in range(4): files["copysrc/" + name()] = blob()
clash = list(files)[:2]
for c in clash: files["copydst/" + c.split("/")[1]] = blob() # old targets with other content
files["copydst/" + name()] = blob()                            # a bystander
for _ in range(5): files["trash/" + name()] = blob()
with tarfile.open(tmp + "/files.tar", "w") as out:
    for path, data in files.items():
        info = tarfile.TarInfo(path); info.size = len(data); out.addfile(info, io.BytesIO(data))
with open(tmp + "/manifest", "w") as m:
    for path, data in files.items(): m.write(hashlib.md5(data).hexdigest() + " " + path + "\n")
PY
a shell "rm -rf $DIR; mkdir -p $DIR/bigcopy/src $DIR/bigcopy/dst $DIR/mvsrc $DIR/mvdst"
a push "$TMP/files.tar" /data/local/tmp/ordo-e2e-filejob.tar >/dev/null
a shell "tar -xf /data/local/tmp/ordo-e2e-filejob.tar -C $DIR; rm -f /data/local/tmp/ordo-e2e-filejob.tar"
a shell "dd if=/dev/urandom of=$DIR/bigcopy/src/ordoe2e-big.bin bs=1M count=$BIG_MB 2>/dev/null"
a shell "dd if=/dev/urandom of=$DIR/bigcopy/dst/ordoe2e-big.bin bs=1M count=1 2>/dev/null"
a shell "dd if=/dev/urandom of=$DIR/mvsrc/ordoe2e-mv.bin bs=1M count=$BIG_MB 2>/dev/null"

sum() { a shell "md5sum $1 2>/dev/null" | awk '{print $1}'; }
man() { awk -v p="$1" '$2 == p { print $1 }' "$TMP/manifest"; }
SRC_NAMES=$(awk '{print $2}' "$TMP/manifest" | grep '^copysrc/')
DST_NAMES=$(awk '{print $2}' "$TMP/manifest" | grep '^copydst/')
CLASH=$(head -2 <<<"$SRC_NAMES")
TRASH_NAMES=$(awk '{print $2}' "$TMP/manifest" | grep '^trash/')
BIG_SUM=$(sum $DIR/bigcopy/src/ordoe2e-big.bin); BIG_OLD=$(sum $DIR/bigcopy/dst/ordoe2e-big.bin)
MV_SUM=$(sum $DIR/mvsrc/ordoe2e-mv.bin)
check "the test files are in place" test "$(a shell "find $DIR -type f | wc -l" | tr -d '\r')" = $(($(wc -l < "$TMP/manifest") + 3))
while read -r md5 path; do
    [ "$(sum "$DIR/$path")" = "$md5" ] || { fail "$path was written wrong"; break; }
done < "$TMP/manifest"

# Every trash entry of this test: "<md5> path=<original path>".
trashed() {
    a shell "cd /storage/emulated/0/.OrdoTrash 2>/dev/null || exit 0; for i in \$(grep -lE '$(trash_pattern "$REL")' info/*.info); do
        id=\${i#info/}; id=\${id%.info}; echo \$(md5sum files/\$id | cut -d' ' -f1) \$(grep -h '^path=' \$i); done" | tr -d '\r'
}
# in_trash REL-PATH MD5: the file is in Ordo's trash with exactly that content.
in_trash() { trashed | grep -q "^$2 path=.*/$REL/$1\$"; }

open_dir() {
    a shell am start -W -a android.intent.action.VIEW -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" \
        -d "file://$DIR/$1" >/dev/null; sleep 2; guard
}
select_all() {
    ui longtap "$1" >/dev/null; sleep 1
    ui tap selectionMoreButton >/dev/null; ui tap "Alles auswählen|Select all" >/dev/null; sleep 1
}
# paste_answering ANSWER: pastes, then answers every name clash with ANSWER until none is asked any more.
paste_answering() {
    local i
    ui tap "$1" >/dev/null; sleep 1.5
    for i in 1 2 3 4 5 6; do
        ui has "überschreiben|Replace" || break
        ui tap "$2" >/dev/null; sleep 1
    done
}

launch

# 1. Copy with name clashes: every clash is answered "Skip", so the old targets must stay byte for byte.
open_dir copysrc
check "the source folder opens" ui has "$(head -1 <<<"$SRC_NAMES" | cut -d/ -f2)"
select_all "$(head -1 <<<"$SRC_NAMES" | cut -d/ -f2)"
ui tap "Kopieren|Copy" >/dev/null; sleep 1
open_dir copydst
check "paste is offered" ui has "Hierher kopieren|Copy here"
paste_answering "Hierher kopieren|Copy here" "Überspringen|Skip"
sleep 2
for n in $SRC_NAMES; do
    if grep -qx "$n" <<<"$CLASH"; then
        check "skipped clash ${n#*/}: the old file stays" test "$(sum "$DIR/copydst/${n#*/}")" = "$(man "copydst/${n#*/}")"
    else
        check "new file ${n#*/} was copied intact" test "$(sum "$DIR/copydst/${n#*/}")" = "$(man "$n")"
    fi
    check "source ${n#*/} is untouched" test "$(sum "$DIR/$n")" = "$(man "$n")"
done
bystander=$(grep -vxFf <(sed 's/^copysrc/copydst/' <<<"$SRC_NAMES") <<<"$DST_NAMES")
check "the bystander is untouched" test "$(sum "$DIR/$bystander")" = "$(man "$bystander")"

# 2. Copy-replace, replace allowed, app stopped in the middle: the target is the old or the new file, never a part.
open_dir bigcopy/src
ui longtap ordoe2e-big.bin >/dev/null; sleep 1
ui tap "Kopieren|Copy" >/dev/null; sleep 1
open_dir bigcopy/dst
ui tap "Hierher kopieren|Copy here" >/dev/null; sleep 1.5
check "the replace dialog asks first" ui has "überschreiben|Replace"
ui tap "Ersetzen|Replace" >/dev/null; sleep 0.4
a shell am force-stop "$PKG"; sleep 1
got=$(sum $DIR/bigcopy/dst/ordoe2e-big.bin)
check "after the stop the target is whole: the old or the new file" test "$got" = "$BIG_OLD" -o "$got" = "$BIG_SUM"
check "the copy's source is untouched" test "$(sum $DIR/bigcopy/src/ordoe2e-big.bin)" = "$BIG_SUM"
a shell "rm -f $DIR/bigcopy/dst/.ordoe2e-big.bin.ordo-part $DIR/bigcopy/dst/*.ordo-part"

# 3. Move, app stopped in the middle: the content is in the source or the target, whole.
launch
open_dir mvsrc
ui longtap ordoe2e-mv.bin >/dev/null; sleep 1
ui tap "Verschieben|Move" >/dev/null; sleep 1
open_dir mvdst
ui tap "Hierher verschieben|Move here" >/dev/null; sleep 0.4
a shell am force-stop "$PKG"; sleep 1
s=$(sum $DIR/mvsrc/ordoe2e-mv.bin); d=$(sum $DIR/mvdst/ordoe2e-mv.bin)
check "after the stop the moved file is whole in the source or the target" test "$s" = "$MV_SUM" -o "$d" = "$MV_SUM"
check "no half file under the real name" eval '{ [ -z "$s" ] || [ "$s" = "$MV_SUM" ]; } && { [ -z "$d" ] || [ "$d" = "$MV_SUM" ]; }'

# 4. Delete to the trash, app stopped right after the confirmation: each file is in its folder or in the trash.
launch
open_dir trash
first=$(head -1 <<<"$TRASH_NAMES" | cut -d/ -f2)
check "the trash test folder opens" ui has "$first"
select_all "$first"
ui tap selectionDeleteButton >/dev/null; sleep 1; guard
ui tap button1 >/dev/null; sleep 0.3
a shell am force-stop "$PKG"; sleep 1.5
for n in $TRASH_NAMES; do
    [ "$(sum "$DIR/$n")" = "$(man "$n")" ] || in_trash "$n" "$(man "$n")" || fail "${n#*/} is in neither its folder nor the trash"
done

# What the stop left in the folder goes to the trash now, without a stop, so all files are in the trash.
launch
if [ "$(a shell "ls $DIR/trash 2>/dev/null | wc -l" | tr -d '\r')" != 0 ]; then
    open_dir trash
    left=$(a shell "ls $DIR/trash" | tr -d '\r' | head -1)
    select_all "$left"
    ui tap selectionDeleteButton >/dev/null; sleep 1; guard
    ui tap button1 >/dev/null
    eventually 20 test "$(a shell "ls $DIR/trash | wc -l" | tr -d '\r')" = 0
fi
check "nothing is left in the folder" test "$(a shell "ls $DIR/trash | wc -l" | tr -d '\r')" = 0
for n in $TRASH_NAMES; do
    check "${n#*/} is in the trash with its own content" in_trash "$n" "$(man "$n")"
done

# 5. The trash gives a file back: through Ordo's trash screen, to its own place, byte for byte.
rest=$(head -1 <<<"$TRASH_NAMES")
a shell am start -n "$PKG/me.zhanghai.android.files.trash.TrashActivity" >/dev/null 2>&1; sleep 2; guard
if ui has "${rest#*/}"; then
    ui longtap "${rest#*/}" >/dev/null; sleep 1
    ui tap "Wiederherstellen|Restore" >/dev/null; sleep 2; guard
    check "the restored file is back at its place" eventually 10 test "$(sum "$DIR/$rest")" = "$(man "$rest")"
    check "the restored file left the trash" eval '! in_trash "$rest" "$(man "$rest")"'
else
    fail "the trash screen does not list ${rest#*/}"
fi
back; back
check "no left-over part files" test "$(a shell "find $DIR -name '*.ordo-part' | wc -l" | tr -d '\r')" = 0
finish
