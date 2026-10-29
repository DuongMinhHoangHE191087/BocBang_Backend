#!/usr/bin/env bash
# Headless ADB deployment for MediaTek G520 Android (userdebug) board — NO screen & NO board internet required.
#
# Bundles a self-contained ARM64 Linux chroot (Ubuntu Base 26.04 + OpenJDK 17 JRE + static ffmpeg)
# on your PC, pushes the Spring Boot fat JAR + JNI .so libraries + models via USB adb, and generates run.sh.
#
# Usage:
#   PUSH_MODELS=/workspace/MeetingNotes-models ./scripts/deploy-board.sh
#   ./scripts/deploy-board.sh                  # code/JNI update only (keeps existing models & rootfs)

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="${1:-linux-arm64}"
BOARD_DIR="${BOARD_DIR:-/data/local/tmp/bocbang}"
CACHE_DIR="${CACHE_DIR:-/workspace/bocbang-board-runtime}"

UBUNTU_BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/ubuntu-base-26.04.1-base-arm64.tar.gz"
JRE_URL="https://api.adoptium.net/v3/binary/latest/17/ga/linux/aarch64/jre/hotspot/normal/eclipse"
FFMPEG_URL="https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-linuxarm64-gpl.tar.xz"

echo "=== 1. Ensuring JNI libraries (${TARGET}) ==="
if [[ "${FORCE_NATIVE_BUILD:-0}" == "1" ]] || [[ ! -f "${ROOT_DIR}/native/dist/${TARGET}/libbocbang_llama.so" ]]; then
    "${ROOT_DIR}/scripts/build-native.sh" "${TARGET}"
else
    echo "Using existing ${ROOT_DIR}/native/dist/${TARGET}/*.so (set FORCE_NATIVE_BUILD=1 to rebuild)"
fi

echo "=== 2. Building Spring Boot fat JAR ==="
"${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" bootJar --console=plain
"${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" --stop >/dev/null 2>&1 || true
JAR_FILE="$(ls "${ROOT_DIR}"/build/libs/*-SNAPSHOT.jar | head -n 1)"

echo "=== 3. Caching offline ARM64 runtime (Ubuntu Base + OpenJDK 17 + static ffmpeg) in ${CACHE_DIR} ==="
mkdir -p "${CACHE_DIR}"
if [[ ! -f "${CACHE_DIR}/ubuntu-base-arm64.tar.gz" ]]; then
    echo "Downloading Ubuntu Base 26.04 ARM64 (~34 MB)..."
    curl -fL "${UBUNTU_BASE_URL}" -o "${CACHE_DIR}/ubuntu-base-arm64.tar.gz"
fi
if [[ ! -f "${CACHE_DIR}/openjdk17-jre-arm64.tar.gz" ]]; then
    echo "Downloading Eclipse Temurin OpenJDK 17 JRE ARM64 (~44 MB)..."
    curl -fL "${JRE_URL}" -o "${CACHE_DIR}/openjdk17-jre-arm64.tar.gz"
fi
if [[ ! -f "${CACHE_DIR}/ffmpeg-arm64" ]]; then
    echo "Downloading static ffmpeg ARM64..."
    curl -fL "${FFMPEG_URL}" -o "${CACHE_DIR}/ffmpeg-arm64.tar.xz"
    tar -xJf "${CACHE_DIR}/ffmpeg-arm64.tar.xz" -C "${CACHE_DIR}" --wildcards --strip-components=2 '*/bin/ffmpeg'
    mv "${CACHE_DIR}/ffmpeg" "${CACHE_DIR}/ffmpeg-arm64"
    chmod 755 "${CACHE_DIR}/ffmpeg-arm64"
    rm -f "${CACHE_DIR}/ffmpeg-arm64.tar.xz"
fi

echo "=== 4. Switching adb to root (headless userdebug board) ==="
adb wait-for-device
adb root >/dev/null 2>&1 || true
adb wait-for-device

# Rescue accidental regular file at ${BOARD_DIR}/models if it matches qwen3-4b-instruct GGUF
adb shell "if [ -f '${BOARD_DIR}/models' ]; then
    mv '${BOARD_DIR}/models' '${BOARD_DIR}/qwen3-4b-instruct-2507-q4_k_m.gguf.tmp'
    mkdir -p '${BOARD_DIR}/models'
    mv '${BOARD_DIR}/qwen3-4b-instruct-2507-q4_k_m.gguf.tmp' '${BOARD_DIR}/models/qwen3-4b-instruct-2507-q4_k_m.gguf'
fi"

adb shell "mkdir -p '${BOARD_DIR}/models' '${BOARD_DIR}/jobs' '${BOARD_DIR}/rootfs'"

if ! adb shell "[ -x '${BOARD_DIR}/rootfs/opt/jre/bin/java' ]"; then
    echo "=== 5. Provisioning ARM64 Linux chroot on board (first-time setup) ==="
    adb push "${CACHE_DIR}/ubuntu-base-arm64.tar.gz" "${BOARD_DIR}/ubuntu-base-arm64.tar.gz"
    adb push "${CACHE_DIR}/openjdk17-jre-arm64.tar.gz" "${BOARD_DIR}/openjdk17-jre-arm64.tar.gz"
    adb shell "
        set -e
        tar -xzf '${BOARD_DIR}/ubuntu-base-arm64.tar.gz' -C '${BOARD_DIR}/rootfs'
        mkdir -p '${BOARD_DIR}/rootfs/opt/jre' '${BOARD_DIR}/rootfs/usr/local/bin' \
                 '${BOARD_DIR}/rootfs/proc' '${BOARD_DIR}/rootfs/sys' '${BOARD_DIR}/rootfs/dev' '${BOARD_DIR}/rootfs/tmp'
        tar -xzf '${BOARD_DIR}/openjdk17-jre-arm64.tar.gz' -C '${BOARD_DIR}/rootfs/opt/jre' --strip-components=1
        chmod 1777 '${BOARD_DIR}/rootfs/tmp'
        rm -f '${BOARD_DIR}/ubuntu-base-arm64.tar.gz' '${BOARD_DIR}/openjdk17-jre-arm64.tar.gz'
    "
    if [[ -f /usr/aarch64-linux-gnu/lib/libstdc++.so.6 ]]; then
        REAL_STDCPP="$(readlink -f /usr/aarch64-linux-gnu/lib/libstdc++.so.6)"
        STDCPP_NAME="$(basename "${REAL_STDCPP}")"
        adb push "${REAL_STDCPP}" "${BOARD_DIR}/rootfs/usr/lib/aarch64-linux-gnu/${STDCPP_NAME}" >/dev/null
        adb shell "ln -sf '${STDCPP_NAME}' '${BOARD_DIR}/rootfs/usr/lib/aarch64-linux-gnu/libstdc++.so.6'"
    fi
    if [[ -f /usr/aarch64-linux-gnu/lib/libgcc_s.so.1 ]]; then
        REAL_GCC="$(readlink -f /usr/aarch64-linux-gnu/lib/libgcc_s.so.1)"
        adb push "${REAL_GCC}" "${BOARD_DIR}/rootfs/usr/lib/aarch64-linux-gnu/libgcc_s.so.1" >/dev/null
    fi
    adb push "${CACHE_DIR}/ffmpeg-arm64" "${BOARD_DIR}/rootfs/usr/local/bin/ffmpeg"
    adb shell "chmod 755 '${BOARD_DIR}/rootfs/usr/local/bin/ffmpeg'"
else
    echo "=== 5. ARM64 Linux chroot already provisioned on board ==="
fi

echo "=== 6. Pushing Spring Boot JAR and JNI .so libraries ==="
adb shell "mkdir -p '${BOARD_DIR}/rootfs/opt/bocbang/lib' '${BOARD_DIR}/rootfs/models' '${BOARD_DIR}/rootfs/jobs'"
adb push "${JAR_FILE}" "${BOARD_DIR}/rootfs/opt/bocbang/bocbang-backend.jar"
adb push "${ROOT_DIR}/native/dist/${TARGET}"/*.so "${BOARD_DIR}/rootfs/opt/bocbang/lib/"
adb shell "chmod 755 '${BOARD_DIR}/rootfs/opt/bocbang/lib/'*.so"

if [[ -n "${PUSH_MODELS:-}" ]]; then
    echo "=== 7. Syncing model files from ${PUSH_MODELS} (skipping already-uploaded files) ==="
    for model_file in "${PUSH_MODELS}"/*; do
        [[ -f "${model_file}" ]] || continue
        fname="$(basename "${model_file}")"
        local_size="$(stat -c '%s' "${model_file}")"
        remote_size="$(adb shell "stat -c '%s' '${BOARD_DIR}/models/${fname}' 2>/dev/null || echo 0" | tr -d '\r')"
        if [[ "${local_size}" == "${remote_size}" ]]; then
            echo "Skipping ${fname} (already on board, ${local_size} bytes)"
        else
            echo "Pushing ${fname} (${local_size} bytes)..."
            adb push "${model_file}" "${BOARD_DIR}/models/${fname}"
        fi
    done
fi

LAUNCHER="$(mktemp)"
cat > "${LAUNCHER}" <<'EOF'
#!/system/bin/sh
set -e
BOARD_DIR="/data/local/tmp/bocbang"
ROOTFS="${BOARD_DIR}/rootfs"

if [ "$(id -u)" != "0" ]; then
    echo "ERROR: run 'adb root' on your PC first (chroot requires root)." >&2
    exit 1
fi

mountpoint -q "${ROOTFS}/proc"   || mount -t proc proc "${ROOTFS}/proc"
mountpoint -q "${ROOTFS}/sys"    || mount --bind /sys "${ROOTFS}/sys"
mountpoint -q "${ROOTFS}/dev"    || mount --bind /dev "${ROOTFS}/dev"
mountpoint -q "${ROOTFS}/dev/pts" || (mkdir -p "${ROOTFS}/dev/pts" && mount --bind /dev/pts "${ROOTFS}/dev/pts" 2>/dev/null || true)
mountpoint -q "${ROOTFS}/models" || mount --bind "${BOARD_DIR}/models" "${ROOTFS}/models"
mountpoint -q "${ROOTFS}/jobs"   || mount --bind "${BOARD_DIR}/jobs" "${ROOTFS}/jobs"

export PATH="/opt/jre/bin:/usr/local/bin:/usr/bin:/bin"
export HOME="/root"
export LANG="C.UTF-8"
export LD_LIBRARY_PATH="/opt/bocbang/lib:/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu"
export SERVER_PORT="${SERVER_PORT:-8099}"
export STT_NATIVE_LIB_DIR="/opt/bocbang/lib"
export STT_MODEL_DIR="/models"
export STT_JOBS_DIR="/jobs"
export STT_FFMPEG_BIN="/usr/local/bin/ffmpeg"
export STT_THREADS="${STT_THREADS:-4}"
export STT_UNLOAD_BEFORE_REFINE="${STT_UNLOAD_BEFORE_REFINE:-true}"

exec /system/bin/chroot "${ROOTFS}" /opt/jre/bin/java \
    -Xms64m -Xmx512m \
    --enable-native-access=ALL-UNNAMED \
    -jar /opt/bocbang/bocbang-backend.jar "$@"
EOF

adb push "${LAUNCHER}" "${BOARD_DIR}/run.sh"
rm -f "${LAUNCHER}"
adb shell "chmod 755 '${BOARD_DIR}/run.sh'"

echo "=== Deployed successfully to ${BOARD_DIR} ==="
echo "Forward port:  adb forward tcp:8099 tcp:8099"
echo "Start server:  adb shell ${BOARD_DIR}/run.sh"
