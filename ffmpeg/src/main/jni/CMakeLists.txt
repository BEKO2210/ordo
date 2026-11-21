# Copyright (c) 2026 Ordo contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# libffmpegJNI.so for the :ffmpeg module, built by utils/ffmpeg-audio/build.sh: Media3's CMakeLists.txt of
# libraries/decoder_ffmpeg (1.11.1) plus ffvideo.cc and libswscale. MEDIA3_JNI is that module's jni directory,
# with FFmpeg built into ffmpeg/android-libs/<abi> by its build_ffmpeg.sh.
cmake_minimum_required(VERSION 3.21.0 FATAL_ERROR)

set(CMAKE_CXX_STANDARD 17)

project(libffmpegJNI C CXX)

set(ffmpeg_location "${MEDIA3_JNI}/ffmpeg")
set(ffmpeg_binaries "${ffmpeg_location}/android-libs/${ANDROID_ABI}")

foreach(ffmpeg_lib avutil swresample swscale avcodec)
    add_library(${ffmpeg_lib} STATIC IMPORTED)
    set_target_properties(${ffmpeg_lib} PROPERTIES IMPORTED_LOCATION ${ffmpeg_binaries}/lib${ffmpeg_lib}.a)
endforeach()

include_directories(${ffmpeg_location})
find_library(android_log_lib log)

add_library(ffmpegJNI SHARED ${MEDIA3_JNI}/ffmpeg_jni.cc ffvideo.cc)

set_target_properties(ffmpegJNI PROPERTIES
                      CXX_VISIBILITY_PRESET hidden
                      VISIBILITY_INLINES_HIDDEN ON)

# Static archives: users before what they use (avutil last).
target_link_libraries(ffmpegJNI
                      PRIVATE android
                      PRIVATE swscale
                      PRIVATE swresample
                      PRIVATE avcodec
                      PRIVATE avutil
                      PRIVATE ${android_log_lib})

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(ffmpegJNI PRIVATE "-Wl,-Bsymbolic")
endif()
