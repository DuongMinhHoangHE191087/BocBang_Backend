cmake_minimum_required(VERSION 3.22)
project(bocbang-native C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Paths to the three GGML-based C++ libraries on the build machine.
set(TRANSCRIBE_CPP_DIR
    "/home/kieuviet/AndroidStudioProjects/NeuroPilotJniDemo_V _NoModel_V1/NeuroPilotJniDemo_V _NoModel_V1/transcribe/src/main/cpp/third_party/transcribe.cpp"
    CACHE PATH "Path to transcribe.cpp source tree")
set(WHISPER_CPP_DIR "/workspace/modules/whisper.cpp" CACHE PATH "Path to whisper.cpp source tree")
set(LLAMA_CPP_DIR   "/workspace/llama.cpp"           CACHE PATH "Path to llama.cpp source tree")

# Target selects which library to build in a given CMake invocation so each library links its own
# bundled ggml static archive without CMake target-name collisions (transcribe | whisper | llama).
set(BOCBANG_JNI_MODULE "transcribe" CACHE STRING "Which JNI library to build: transcribe | whisper | llama")

# Locate JNI headers when not building with Android NDK (which provides <jni.h> in the sysroot).
if(NOT ANDROID)
    find_package(JNI REQUIRED)
    include_directories(${JNI_INCLUDE_DIRS})
endif()

# CPU tuning: ARMv8.2-A + dotprod + fp16 for MediaTek Genio 520 / 720 (Cortex-A78 + Cortex-A55).
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64|ARM64")
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

set(VERSION_SCRIPT "${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
set(COMMON_LINK_FLAGS
    "-Wl,--version-script=${VERSION_SCRIPT}"
    "-Wl,-Bsymbolic"
    "-Wl,--gc-sections")

if(BOCBANG_JNI_MODULE STREQUAL "transcribe")
    set(TRANSCRIBE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(TRANSCRIBE_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(TRANSCRIBE_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(TRANSCRIBE_METAL OFF CACHE BOOL "" FORCE)
    set(TRANSCRIBE_INSTALL OFF CACHE BOOL "" FORCE)
    add_subdirectory("${TRANSCRIBE_CPP_DIR}" "${CMAKE_BINARY_DIR}/transcribe.cpp")

    add_library(bocbang_transcribe SHARED transcribe_jni.cpp)
    target_include_directories(bocbang_transcribe PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${TRANSCRIBE_CPP_DIR}/include")
    target_link_libraries(bocbang_transcribe PRIVATE transcribe m)
    if(ANDROID)
        target_link_libraries(bocbang_transcribe PRIVATE log)
    endif()
    target_compile_options(bocbang_transcribe PRIVATE -O3 -fvisibility=hidden)
    target_link_options(bocbang_transcribe PRIVATE ${COMMON_LINK_FLAGS})

elseif(BOCBANG_JNI_MODULE STREQUAL "whisper")
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    add_subdirectory("${WHISPER_CPP_DIR}" "${CMAKE_BINARY_DIR}/whisper.cpp")

    add_library(bocbang_whisper SHARED whisper_jni.cpp)
    target_include_directories(bocbang_whisper PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${WHISPER_CPP_DIR}/include"
        "${WHISPER_CPP_DIR}/ggml/include")
    target_link_libraries(bocbang_whisper PRIVATE whisper m)
    if(ANDROID)
        target_link_libraries(bocbang_whisper PRIVATE log)
    endif()
    target_compile_options(bocbang_whisper PRIVATE -O3 -fvisibility=hidden)
    target_link_options(bocbang_whisper PRIVATE ${COMMON_LINK_FLAGS})

elseif(BOCBANG_JNI_MODULE STREQUAL "llama")
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
    add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_BINARY_DIR}/llama.cpp")

    add_library(bocbang_llama SHARED llama_jni.cpp)
    target_include_directories(bocbang_llama PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${LLAMA_CPP_DIR}/include"
        "${LLAMA_CPP_DIR}/ggml/include")
    target_link_libraries(bocbang_llama PRIVATE llama m)
    if(ANDROID)
        target_link_libraries(bocbang_llama PRIVATE log)
    endif()
    target_compile_options(bocbang_llama PRIVATE -O3 -fvisibility=hidden)
    target_link_options(bocbang_llama PRIVATE ${COMMON_LINK_FLAGS})

else()
    message(FATAL_ERROR "Unknown BOCBANG_JNI_MODULE: ${BOCBANG_JNI_MODULE}")
endif()
