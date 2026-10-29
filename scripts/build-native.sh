#!/usr/bin/env bash
# Builds the three JNI shared libraries (libbocbang_transcribe.so, libbocbang_whisper.so,
# libbocbang_llama.so) for either:
#   ./scripts/build-native.sh host           -> Linux x86_64 (for local PC testing)
#   ./scripts/build-native.sh android-arm64  -> Android arm64-v8a via Android NDK (MediaTek G520 / Termux)
#   ./scripts/build-native.sh linux-arm64    -> Linux aarch64 glibc (MediaTek G520 Debian/Ubuntu chroot)

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="${1:-android-arm64}"
JOBS="${JOBS:-$(nproc)}"
BUILD_ROOT="${BOCBANG_CMAKE_BUILD_ROOT:-/tmp/bocbang-native-build}/${TARGET}"
OUT_DIR="${ROOT_DIR}/native/dist/${TARGET}"

mkdir -p "${OUT_DIR}"

COMMON_ARGS=(
    -G Ninja
    -DCMAKE_BUILD_TYPE=Release
)
if [[ -n "${TRANSCRIBE_CPP_DIR:-}" ]]; then
    COMMON_ARGS+=(-DTRANSCRIBE_CPP_DIR="${TRANSCRIBE_CPP_DIR}")
fi
if [[ -n "${WHISPER_CPP_DIR:-}" ]]; then
    COMMON_ARGS+=(-DWHISPER_CPP_DIR="${WHISPER_CPP_DIR}")
fi
if [[ -n "${LLAMA_CPP_DIR:-}" ]]; then
    COMMON_ARGS+=(-DLLAMA_CPP_DIR="${LLAMA_CPP_DIR}")
fi

case "${TARGET}" in
    host)
        EXTRA_ARGS=()
        ;;
    android-arm64)
        NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-/home/kieuviet/Android/Sdk/ndk/25.1.8937393}}"
        if [[ ! -f "${NDK_DIR}/build/cmake/android.toolchain.cmake" ]]; then
            echo "Android NDK not found at ${NDK_DIR}. Set ANDROID_NDK_HOME." >&2
            exit 1
        fi
        EXTRA_ARGS=(
            -DCMAKE_TOOLCHAIN_FILE="${NDK_DIR}/build/cmake/android.toolchain.cmake"
            -DANDROID_ABI=arm64-v8a
            -DANDROID_PLATFORM=android-29
            -DANDROID_STL=c++_static
        )
        ;;
    linux-arm64)
        JAVA_HOME_DIR="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
        EXTRA_ARGS=(
            -DCMAKE_SYSTEM_NAME=Linux
            -DCMAKE_SYSTEM_PROCESSOR=aarch64
            -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc
            -DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++
            -DJAVA_AWT_LIBRARY=NotNeeded
            -DJAVA_JVM_LIBRARY=NotNeeded
            -DJAVA_INCLUDE_PATH="${JAVA_HOME_DIR}/include"
            -DJAVA_INCLUDE_PATH2="${JAVA_HOME_DIR}/include/linux"
            -DJAVA_AWT_INCLUDE_PATH="${JAVA_HOME_DIR}/include"
        )
        ;;
    *)
        echo "Usage: $0 [host | android-arm64 | linux-arm64]" >&2
        exit 1
        ;;
esac

for MODULE in transcribe whisper llama; do
    echo "=== Building bocbang_${MODULE} (${TARGET}) ==="
    MOD_BUILD="${BUILD_ROOT}/${MODULE}"
    cmake -S "${ROOT_DIR}/native" -B "${MOD_BUILD}" \
        "${COMMON_ARGS[@]}" \
        "${EXTRA_ARGS[@]}" \
        -DBOCBANG_JNI_MODULE="${MODULE}"
    cmake --build "${MOD_BUILD}" --parallel "${JOBS}"
    cp -f "${MOD_BUILD}/libbocbang_${MODULE}.so" "${OUT_DIR}/"
done

echo "=== Built JNI libraries in ${OUT_DIR} ==="
ls -lh "${OUT_DIR}"/*.so
