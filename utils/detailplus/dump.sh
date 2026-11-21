#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# On pop-os: compiles MpvHook.kt + Dump.kt with Gradle's cached Kotlin compiler and writes the .frag files.
cd ~/ordo-sr
C=~/.gradle/caches/modules-2/files-2.1
STD=$(ls $C/org.jetbrains.kotlin/kotlin-stdlib/2.4.21/*/kotlin-stdlib-2.4.21.jar)
CP=$(ls $C/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.21/*/*.jar $C/org.jetbrains.kotlin/kotlin-daemon-embeddable/2.4.21/*/*.jar \
  $C/org.jetbrains.kotlin/kotlin-script-runtime/2.4.21/*/*.jar $C/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.11.0/*/*.jar \
  $C/org.jetbrains/annotations/13.0/*/*.jar | grep -v sources | tr "\n" ":")$STD
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-reflect -no-stdlib -cp "$STD" -d dump.jar MpvHook.kt Dump.kt 2>&1 | grep -v "^warning" | tail -5
rm -rf frags
java -cp "dump.jar:$STD" me.zhanghai.android.files.viewer.video.picture.DumpKt frags Anime4K_Upscale_CNN_x2_M.glsl FSRCNNX_x2_8-0-4-1.glsl
ls frags | wc -l
