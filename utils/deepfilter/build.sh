#!/bin/bash
# Copyright (c) 2026 Ordo contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds libordo_df.so for "Dialog freistellen plus" (docs/VIDEO-PLAYER.md): DeepFilterNet's libDF (Rust,
# MIT OR Apache-2.0, https://github.com/Rikorose/DeepFilterNet) at a pinned commit, Ordo's thin C interface
# (ordo_df/) as a static library and the JNI file ordo_df_jni.c linked into one .so per ABI. No model inside: the
# app downloads DeepFilterNet3_onnx.tar.gz from the same commit on request and checks its SHA-256 (DialogModel.kt).
# Writes <out>/<abi>/libordo_df.so for arm64-v8a and x86_64 only (32-bit phones are too slow for it).
# Gradle runs it once per checkout (:app:buildDeepFilter, a few minutes); by hand:
# usage: utils/deepfilter/build.sh <out>   (Linux x86_64; needs git, rustup with cargo, the NDK below)
set -euo pipefail

DF_SHA=7b14b895fe7fc42cab72b4a4c1e49c982bd6404b   # DeepFilterNet main, 2026-11-06, libDF 0.5.7-pre
MIN_SDK=24
NDK=${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/30.0.16248370
declare -A TARGETS=([arm64-v8a]=aarch64-linux-android [x86_64]=x86_64-linux-android)

here=$(cd "$(dirname "$0")" && pwd)
out=$(mkdir -p "${1:?usage: $0 <out-dir>}" && cd "$1" && pwd)
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
export PATH=$HOME/.cargo/bin:$PATH

git init -q "$work/df"
git -C "$work/df" fetch -q --depth 1 https://github.com/Rikorose/DeepFilterNet "$DF_SHA"
git -C "$work/df" checkout -q FETCH_HEAD
[ "$(git -C "$work/df" rev-parse HEAD)" = "$DF_SHA" ] || { echo "DeepFilterNet not at $DF_SHA" >&2; exit 1; }

cp -r "$here/ordo_df" "$work/ordo_df"
sed -i "s|DEEPFILTER_LIBDF|$work/df/libDF|" "$work/ordo_df/Cargo.toml"
# libDF only builds with the tract versions of its own lock file (newer tract changed its API).
cp "$work/df/Cargo.lock" "$work/ordo_df/Cargo.lock"

bin=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
for abi in "${!TARGETS[@]}"; do
    target=${TARGETS[$abi]}
    rustup target add "$target" >/dev/null 2>&1 || true
    linker=$bin/$target$MIN_SDK-clang
    upper=$(echo "$target" | tr 'a-z-' 'A-Z_')
    env "CARGO_TARGET_${upper}_LINKER=$linker" "CC_${target//-/_}=$linker" "AR_${target//-/_}=$bin/llvm-ar" \
        cargo build -q --release --target "$target" --manifest-path "$work/ordo_df/Cargo.toml" \
        --target-dir "$work/target"
    mkdir -p "$out/$abi"
    # 16 KB pages (Google Play rule); only the JNI symbols are exported.
    "$linker" -shared -fPIC -O2 -o "$work/libordo_df-$abi.so" "$here/ordo_df_jni.c" \
        "$work/target/$target/release/libordo_df.a" -lm -ldl -llog \
        -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
    "$bin/llvm-strip" --strip-unneeded -o "$out/$abi/libordo_df.so" "$work/libordo_df-$abi.so"
    for a in $("$bin/llvm-readelf" -lW "$out/$abi/libordo_df.so" | awk '$1 == "LOAD" { print $NF }'); do
        (( a >= 16384 )) || { echo "$abi/libordo_df.so: LOAD align $a < 16 KB" >&2; rm -f "$out/$abi/libordo_df.so"; exit 1; }
    done
done
