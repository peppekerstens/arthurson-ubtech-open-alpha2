# easyopus JNI bridge layer - from theeasiestway/android-opus-codec
# easyopus-jni-src. It connects the native method calls of the Java class
# com.theeasiestway.opus.Opus to the C API of libopus (opus_encoder_*/opus_decoder_* etc.).
#
# 2026-08: this CMake build replaces the old way of using app/libs/opus.aar directly
# (which bundled prebuilt libeasyopus.so + libopus.so + libopusenc.so). It now
# compiles libeasyopus.so inside the project and links only the newer
# prebuilt/armeabi-v7a/libopus.so provided by Arthur (opus 1.5.x series, DRED,
# verified to contain all the symbols CodecOpus.cpp uses, including
# opus_encoder_create/opus_encode/opus_decoder_create/opus_decode - see the nm -D output record).
#
# opusenc is no longer linked: the upstream template had add_library(opusenc ...), but
# CodecOpus.cpp uses only the raw OpusEncoder/OpusDecoder API of <opus.h>
# throughout and no ope_* function of libopusenc (Ogg-Opus container format).
# This project does raw frame streaming only (XiaoZhi WebSocket protocol), no
# Ogg container, so this redundant dependency is removed.

cmake_minimum_required(VERSION 3.10.2)

project("easyopus")

# 2026-09-09 guard: prebuilt/libopus.so exists for armeabi-v7a only. A loose abiFilters
# would give an obscure missing-file error. Fail early and clearly.
if(NOT ANDROID_ABI STREQUAL "armeabi-v7a")
  message(FATAL_ERROR "easyopus supports armeabi-v7a only (the prebuilt exists for this ABI only), got: ${ANDROID_ABI}")
endif()

add_library( # output .so name -> libeasyopus.so; the Java side
              # System.loadLibrary("easyopus") looks up this name
              easyopus

              SHARED

              codec/CodecOpus.cpp
              utils/SamplesConverter.cpp
              easyopus.cpp)

# CodecOpus.h writes #include <opus.h> (no opus/ prefix), so the include path
# must point at the include/opus layer, not include itself. The three standard headers
# opus.h/opus_defines.h/opus_types.h come from the official libopus-dev 1.4-1build1 package
# (Ubuntu archive); the C API signature is stable and the result is ABI-compatible with
# the newer libopus.so (1.5.x series) provided by Arthur.
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include/opus)

# --- prebuilt libopus.so (provided by Arthur, armeabi-v7a only) ---
add_library(opus SHARED IMPORTED)
set_target_properties(opus PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libopus.so)

find_library(log-lib log)

target_link_libraries( easyopus
                        opus
                        ${log-lib} )

# alpha2mic: raw ALSA capture of the 5-mic FPGA card (card 0), see alpha2mic/alpha2mic.c.
# Java side: com.open.alpha2.MultiMicNative / MultiMicController. No extra dependency.
add_library(alpha2mic SHARED alpha2mic/alpha2mic.c)
target_link_libraries(alpha2mic ${log-lib})
