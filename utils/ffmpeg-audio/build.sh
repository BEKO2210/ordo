#!/bin/bash
# Copyright (c) 2026 Ordo contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the native part of the :ffmpeg module (Media3's decoder_ffmpeg, audio only) from source, following
# libraries/decoder_ffmpeg/README.md of Media3 1.11.1: FFmpeg via the module's own build_ffmpeg.sh, then the JNI
# wrapper (ffmpeg_jni.cc + its CMakeLists.txt) with the NDK's CMake toolchain. Writes
#   <out>/<abi>/libffmpegJNI.so   FFmpeg linked in statically, LGPL-2.1-or-later
# and refreshes ffmpeg/src/main/java and ffmpeg/proguard-rules.txt (Media3's, unchanged, Apache-2.0).
# Gradle runs it once per checkout (:ffmpeg:buildFfmpegAudio, about a minute); by hand:
# usage: utils/ffmpeg-audio/build.sh <out>   (Linux x86_64; needs git, make, cmake >= 3.21, the NDK below)
set -euo pipefail

MEDIA3_TAG=1.11.1                         # must match androidx_media3_version in app/build.gradle
FFMPEG_TAG=n6.0.1                         # release/6.0, the version the Media3 README recommends
DECODERS=(ac3 eac3 dca truehd mlp)        # only what budget phones lack (W90: no AC3/E-AC3/DTS/TrueHD)
ABIS=(arm64-v8a armeabi-v7a x86_64)       # build_ffmpeg.sh also builds x86; Ordo does not ship it
MIN_SDK=24                                # app minSdk
NDK=${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/30.0.16248370}

out=$(mkdir -p "${1:?usage: $0 <out-dir>}" && cd "$1" && pwd)
repo=$(cd "$(dirname "$0")/../.." && pwd)
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT

git clone -q --depth 1 --branch "$MEDIA3_TAG" --filter=blob:none --sparse https://github.com/androidx/media "$work/media"
git -C "$work/media" sparse-checkout set libraries/decoder_ffmpeg
module=$work/media/libraries/decoder_ffmpeg/src/main
git clone -q --depth 1 --branch "$FFMPEG_TAG" https://git.ffmpeg.org/ffmpeg.git "$module/jni/ffmpeg"

# No --enable-gpl, --enable-nonfree or --enable-version3 in build_ffmpeg.sh: FFmpeg stays LGPL-2.1-or-later.
(cd "$module/jni" && ./build_ffmpeg.sh "$module" "$NDK" linux-x86_64 "$MIN_SDK" "${DECODERS[@]}")

rm -rf "$repo/ffmpeg/src/main/java"
cp -r "$module/java" "$repo/ffmpeg/src/main/java"
cp "$module/../../proguard-rules.txt" "$repo/ffmpeg/proguard-rules.txt"
strip=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip
for abi in "${ABIS[@]}"; do
    # NDK r28+ links with 16 KB max-page-size by default (Google Play's 16 KB rule).
    cmake -S "$module/jni" -B "$work/jni-$abi" -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$MIN_SDK" >/dev/null
    cmake --build "$work/jni-$abi" >/dev/null
    mkdir -p "$out/$abi"
    "$strip" --strip-unneeded -o "$out/$abi/libffmpegJNI.so" "$work/jni-$abi/libffmpegJNI.so"
done
(cd "$out" && sha256sum */libffmpegJNI.so)
