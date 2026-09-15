# Native build for Transcriber's whisper.cpp backend.
#
# Layout expected (see README §Setup):
#   app/src/main/cpp/
#     CMakeLists.txt   <-- this file
#     jni_whisper.cpp  <-- our thin JNI wrapper
#     whisper.cpp/     <-- the upstream whisper.cpp repo (git clone or submodule)
#
# We don't vendor whisper.cpp in this repo; CI / the build script fetches it
# into the path above before invoking the Gradle build.

cmake_minimum_required(VERSION 3.22.1)

# Force optimized compilation for the native build, independent of the
# Gradle build variant. AGP's CMake integration passes
# -DCMAKE_BUILD_TYPE=Debug for a `debug` Gradle build, which compiles
# ggml/whisper.cpp at -O0 — no auto-vectorization, no loop unrolling.
# For compute-bound numerical code that's not "a slower debug build," it's
# 10-50x+ slower: observed multi-minute whisper.cpp inference on a 10s
# clip on a current flagship phone (Galaxy S24 Ultra), where a couple of
# seconds is normal. whisper.cpp's and ggml's own CMakeLists.txt each try
# to default CMAKE_BUILD_TYPE to Release, but only `if (NOT
# CMAKE_BUILD_TYPE)` — AGP has already set it to "Debug" by the time
# those run, so that guard never fires. Force it here, before
# add_subdirectory() pulls whisper.cpp in, so their guard sees it already
# set and leaves it alone. Nobody single-steps through ggml's matmul
# kernels in the Kotlin-focused Android Studio debugger anyway — only our
# thin JNI shim and the Kotlin/Compose side benefit from being "debug".
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

project(transcriber_native LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Android 15+ requires native .so files aligned to 16 KB page boundaries on
# arm64-v8a. NDK r27 enables this by default for direct targets, but
# whisper.cpp's CMakeLists explicitly sets CMAKE_SHARED_LINKER_FLAGS which
# clobbers it for libggml*.so and libwhisper.so. Use add_link_options() so
# the flag applies to every target defined after this line, including those
# in add_subdirectory() below.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    add_link_options("-Wl,-z,max-page-size=16384")
endif()

set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp")

if (NOT EXISTS "${WHISPER_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "whisper.cpp not found at ${WHISPER_DIR}. "
        "Run ./scripts/fetch-whisper-cpp.sh (or follow README §Setup) before building.")
endif()

# whisper.cpp's own toggles. Keep it small on mobile.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper.cpp-build EXCLUDE_FROM_ALL)

# Our JNI shim.
add_library(transcriber_jni SHARED jni_whisper.cpp)

target_include_directories(transcriber_jni PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
)

target_link_libraries(transcriber_jni
    whisper
    log
    android
)
