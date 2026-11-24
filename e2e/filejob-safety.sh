#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# farm: orient=once timeout=900
# File jobs lose nothing (night). Only with FARM_NIGHT=1 or E2E_FILEJOB=1, otherwise SKIP. WRITES: creates
# /sdcard/.ordo-e2e-filejob-<seed>-<time>-<pid> with files of seeded names (FILEJOB_SEED, default 71) and contents, copies, moves and
# trashes them through Ordo's UI, name clashes included, and force-stops the app in the middle of a copy-replace, a
# move and a delete. Then adb checks by MD5: no file lost, no clashing file replaced without a "Replace", no half
# file under a real name, everything in the trash comes back. The folder and exactly its own trash entries are
# removed at the end, also on Ctrl-C. Nothing else on the device is touched: the folder name is unique per run
# (.ordo-e2e-filejob-<seed>-<time>-<pid>), FILEJOB_SEED must be digits only (else abort), each stop waits for proof on the
# device that the job is mid-run (else FAIL), the run aborts if it exists, holds a marker file with the run id, is deleted only
# if the marker matches, and only trash entries with a file name this run created are removed.
. "$(dirname "$0")/lib.sh" "$@"
[ "${FARM_NIGHT:-}" = 1 ] || [ "${E2E_FILEJOB:-}" = 1 ] || { echo "SKIP filejob-safety (night only: FARM_NIGHT=1 or E2E_FILEJOB=1)"; exit 0; }

SEED=${FILEJOB_SEED:-71}
case $SEED in ''|*[!0-9]*) echo "ABORT filejob-safety: FILEJOB_SEED must be digits only (^[0-9]+\$), nothing touched"; exit 1 ;; esac
[ ${#SEED} -le 9 ] || { echo "ABORT filejob-safety: FILEJOB_SEED has more than 9 digits, nothing touched"; exit 1; }
PREFIX=/sdcard/.ordo-e2e-filejob-
RUN_ID="$SEED-$(date +%Y%m%d%H%M%S)-$$"
REL=.ordo-e2e-filejob-$RUN_ID
DIR=$PREFIX$RUN_ID
MARKER=$DIR/.ordo-e2e-run
CREATED=0
BIG_MB=128 # the job must still be running when the app is stopped; the stop waits for proof of that
N_TRASH=40
TMP=$(mktemp -d)
# Removes only trash entries of this run's folder whose file name this run created ($TMP/names).
trash_drop_own() {
    [ -s "$TMP/names" ] || return 0
    a shell "cd /storage/emulated/0/.OrdoTrash/info 2>/dev/null || exit 0; for i in \$(grep -lE '$(trash_pattern "$REL")' *.info); do
        p=\$(grep -h '^path=' \$i); n=\${p##*/}
        case ' $(tr '\n' ' ' < "$TMP/names") ' in *\" \$n \"*) rm -rf ../files/\${i%.info} \$i;; esac; done" >/dev/null 2>&1
}
cleanup() {
    if [ "$CREATED" = 1 ] && [[ "$DIR" == "$PREFIX"* ]] && [[ "$RUN_ID" =~ ^[0-9]+-[0-9]{14}-[0-9]+$ ]] \
        && [ "$(a shell "cat $MARKER 2>/dev/null" | tr -d '\r')" = "$RUN_ID" ]; then
        a shell "rm -rf $DIR" >/dev/null 2>&1
        trash_drop_own
    fi
    rm -rf "$TMP"
}
trap cleanup EXIT

# Seeded names and contents, packed on the host (names look like ordoe2e-3f9a1c.dat; the same seed gives the same
# names). manifest: "<md5> <folder>/<name>" for everything that must survive.
python3 - "$TMP" "$SEED" "$N_TRASH" <<'PY'
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
for _ in range(int(sys.argv[3])): files["trash/" + name()] = blob()
with tarfile.open(tmp + "/files.tar", "w") as out:
    for path, data in files.items():
        info = tarfile.TarInfo(path); info.size = len(data); out.addfile(info, io.BytesIO(data))
with open(tmp + "/manifest", "w") as m:
    for path, data in files.items(): m.write(hashlib.md5(data).hexdigest() + " " + path + "\n")
PY
awk '{n=split($2, a, "/"); print a[n]}' "$TMP/manifest" > "$TMP/names"
printf '%s\n' ordoe2e-big.bin ordoe2e-mv.bin >> "$TMP/names"
if [ -n "$(a shell "ls -d $DIR 2>/dev/null" | tr -d '\r')" ]; then
    echo "ABORT filejob-safety: $DIR already exists, nothing touched"; exit 1
fi
a shell "mkdir -p /sdcard && mkdir $DIR" || { echo "ABORT filejob-safety: cannot create $DIR"; exit 1; }
CREATED=1
a shell "echo $RUN_ID > $MARKER; mkdir -p $DIR/bigcopy/src $DIR/bigcopy/dst $DIR/mvsrc $DIR/mvdst"
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
check "the test files are in place" test "$(a shell "find $DIR -type f | wc -l" | tr -d '\r')" = $(($(wc -l < "$TMP/manifest") + 4)) # plus the marker
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

# stop_midrun CONDITION: polls on the device until CONDITION (a shell test that is only true while the job is
# running) holds, then force-stops the app in that same device call. Returns 1 if it never held, i.e. the job was not seen running.
stop_midrun() {
    local seen
    seen=$(a shell "i=0; while [ \$i -lt 600 ]; do if $1; then am force-stop $PKG; echo SEEN; break; fi; i=\$((i+1)); sleep 0.02; done" | tr -d '\r')
    a shell am force-stop "$PKG"; sleep 1
    [ "$seen" = SEEN ]
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
ui tap "Ersetzen|Replace" >/dev/null
check "the copy was running when the app was stopped (part file seen)" \
    stop_midrun "ls $DIR/bigcopy/dst/.ordo-part* $DIR/bigcopy/dst/.*.ordo-part >/dev/null 2>&1"
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
ui tap "Hierher verschieben|Move here" >/dev/null
check "the move was running when the app was stopped (source still there, target or part file growing)" \
    stop_midrun "[ -e $DIR/mvsrc/ordoe2e-mv.bin ] && ls $DIR/mvdst/ordoe2e-mv.bin $DIR/mvdst/.ordo-part* $DIR/mvdst/.*.ordo-part >/dev/null 2>&1"
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
ui tap button1 >/dev/null
check "the delete was running when the app was stopped (some, not all files gone from the folder)" \
    stop_midrun "[ \$(ls $DIR/trash | wc -l) -lt $N_TRASH ]"
left_n=$(a shell "ls $DIR/trash | wc -l" | tr -d '\r')
check "the delete had neither finished nor not started at the stop" test "$left_n" -ge 1 -a "$left_n" -lt "$N_TRASH"
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

# 5. The trash gives everything back: through Ordo's trash screen, to their own places, byte for byte.
a shell am start -n "$PKG/me.zhanghai.android.files.trash.TrashActivity" >/dev/null 2>&1; sleep 2; guard
rest=$(head -1 <<<"$TRASH_NAMES")
if ui has "${rest#*/}"; then
    # one own entry at a time: the trash is global, "select all" would restore other apps' entries too
    for n in $TRASH_NAMES; do
        ui longtap "${n#*/}" >/dev/null; sleep 1
        ui tap "Wiederherstellen|Restore" >/dev/null; sleep 1; guard
    done
    sleep 2
    for n in $TRASH_NAMES; do
        check "restored ${n#*/} is back at its place" eventually 20 test "$(sum "$DIR/$n")" = "$(man "$n")"
        check "restored ${n#*/} left the trash" eval '! in_trash "$n" "$(man "$n")"'
    done
else
    fail "the trash screen does not list ${rest#*/}"
fi
back; back
check "no left-over part files" test "$(a shell "find $DIR -name '*.ordo-part' | wc -l" | tr -d '\r')" = 0
finish
