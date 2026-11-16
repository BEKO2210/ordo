# Copyright (c) 2026 Ordo contributors
# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22)
project(ordo_llm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Arm-optimized kernels (Apache-2.0), picked at runtime per CPU, as in examples/llama.android.
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
endif()
add_subdirectory(llama.cpp build-llama)

add_library(ordo_llm SHARED ordo_llm.cpp)
# z: CRC-32 of the saved prompt state (zlib ships with Android).
target_link_libraries(ordo_llm PRIVATE llama android log z)
# Google Play needs 16 KB page alignment; NDK 30 does it by default, pinned here so a toolchain change cannot drop it.
target_link_options(ordo_llm PRIVATE "-Wl,-z,max-page-size=16384")
