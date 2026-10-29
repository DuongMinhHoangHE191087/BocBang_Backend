#!/usr/bin/env bash
# Builds the Spring Boot fat JAR + ARM64 JNI shared libraries and pushes them to the
# connected MediaTek G520 Android board via adb.
#
# Usage:
#   ./scripts/deploy-board.sh                 # default target: android-arm64 (Termux / Android JVM)
#   ./scripts/deploy-board.sh linux-arm64     # for Debian/Ubuntu chroot on the board
#   PUSH_MODELS=/workspace/MeetingNotes-models ./scripts/deploy-board.sh   # also push the 4 models

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="${1:-android-arm64}"
BOARD_DIR="${BOARD_DIR:-/data/local/tmp/bocbang}"

echo "=== 1. Building JNI libraries (${TARGET}) ==="
"${ROOT_DIR}/scripts/build-native.sh" "${TARGET}"

echo "=== 2. Building Spring Boot fat JAR ==="
"${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" bootJar --console=plain
"${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" --stop >/dev/null 2>&1 || true

JAR_FILE="$(ls "${ROOT_DIR}"/build/libs/*-SNAPSHOT.jar | head -n 1)"

echo "=== 3. Checking adb connection ==="
adb wait-for-device
adb shell "mkdir -p ${BOARD_DIR}/lib ${BOARD_DIR}/models ${BOARD_DIR}/jobs"

echo "=== 4. Pushing JAR and JNI .so libraries to ${BOARD_DIR} ==="
adb push "${JAR_FILE}" "${BOARD_DIR}/bocbang-backend.jar"
adb push "${ROOT_DIR}/native/dist/${TARGET}"/*.so "${BOARD_DIR}/lib/"

if [[ -n "${PUSH_MODELS:-}" ]]; then
    echo "=== Pushing model files from ${PUSH_MODELS} ==="
    adb push "${PUSH_MODELS}"/* "${BOARD_DIR}/models/"
fi

LAUNCHER="$(mktemp)"
cat > "${LAUNCHER}" <<EOF
#!/system/bin/sh
DIR="${BOARD_DIR}"
export STT_NATIVE_LIB_DIR="\${DIR}/lib"
export STT_MODEL_DIR="\${DIR}/models"
export STT_JOBS_DIR="\${DIR}/jobs"
export STT_THREADS="\${STT_THREADS:-4}"
export STT_UNLOAD_BEFORE_REFINE="\${STT_UNLOAD_BEFORE_REFINE:-true}"

exec java -Xms64m -Xmx256m -jar "\${DIR}/bocbang-backend.jar" "\$@"
EOF

adb push "${LAUNCHER}" "${BOARD_DIR}/run.sh"
rm -f "${LAUNCHER}"
adb shell "chmod 755 ${BOARD_DIR}/run.sh ${BOARD_DIR}/lib/*.so"

echo "=== Deployed to ${BOARD_DIR} ==="
echo "Start on board with: adb shell ${BOARD_DIR}/run.sh"
