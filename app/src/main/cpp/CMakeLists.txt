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
