#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Tresor files never lie on disk as plaintext. Versions before 0.18.0 decrypted an opened image or PDF into
# cache/vault-open; the start of the app now deletes such a leftover, and opening reads through vault.VaultProvider,
# decrypted in memory. Needs a debug build (run-as) and PKG set to it, e.g. PKG=com.beko2210.ordo.dev.
# Opening a Tresor image needs the screen-lock prompt: SKIP, by hand (put a demo image in the Tresor, unlock, tap it:
# it shows in Ordo's viewer without Share/Delete, and `run-as $PKG ls -laR cache` lists no copy of it).
. "$(dirname "$0")/lib.sh" "$@"
leftover() { a shell run-as "$PKG" ls cache/vault-open 2>/dev/null | grep -q .; }

if ! a shell run-as "$PKG" true 2>/dev/null; then
    echo "SKIP no debug build of $PKG (run-as refused)"
    finish
    exit 0
fi
a shell am force-stop "$PKG"
a shell run-as "$PKG" sh -c "'mkdir -p cache/vault-open/1-old && echo geheim > cache/vault-open/1-old/a.jpg'"
check "an old plaintext copy is planted" leftover
launch
sleep 2
if leftover; then fail "the start deletes the old plaintext copy in cache/vault-open"; else
    pass "the start deletes the old plaintext copy in cache/vault-open"; fi
check "the vault provider is registered" \
    bash -c "'$ADB' -s '$SERIAL' shell dumpsys package '$PKG' | grep -q '$PKG.vault'"
echo "SKIP opening a Tresor image (screen-lock prompt; check by hand as the header says)"
a shell am force-stop "$PKG"
finish
