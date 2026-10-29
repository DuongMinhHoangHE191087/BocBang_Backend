#!/usr/bin/env bash
# Exposes the USB-connected MediaTek G520 board's backend onto all Wi-Fi / LAN interfaces (0.0.0.0:8099)
# of this PC so every phone/laptop on the same Wi-Fi hotspot can open http://<PC-WIFI-IP>:8099/
# Automatically re-establishes `adb forward` if USB `adbd` blips (`UsbFfs: offline`).

set -euo pipefail

PUBLIC_PORT="${PUBLIC_PORT:-8099}"
ADB_LOCAL_PORT="${ADB_LOCAL_PORT:-18099}"
BOARD_PORT="${BOARD_PORT:-8099}"

adb wait-for-device
adb forward --remove "tcp:${PUBLIC_PORT}" >/dev/null 2>&1 || true
adb forward "tcp:${ADB_LOCAL_PORT}" "tcp:${BOARD_PORT}" >/dev/null

echo "=== BocBang Wi-Fi LAN Demo Gateway ==="
echo "Local PC URL:   http://localhost:${PUBLIC_PORT}/"
while read -r ip; do
    [[ -n "${ip}" ]] && echo "Wi-Fi LAN URL:  http://${ip}:${PUBLIC_PORT}/"
done < <(ip -4 -o addr show scope global | awk '{split($4, a, "/"); print a[1]}')
echo "Press Ctrl+C to stop LAN forwarding."

exec python3 - "${PUBLIC_PORT}" "${ADB_LOCAL_PORT}" "${BOARD_PORT}" <<'PY'
import asyncio
import subprocess
import sys

PUBLIC_PORT = int(sys.argv[1])
TARGET_PORT = int(sys.argv[2])
BOARD_PORT = int(sys.argv[3])
CHUNK = 65536


def ensure_adb_forward() -> None:
    subprocess.run(["adb", "wait-for-device"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
    subprocess.run(
        ["adb", "forward", f"tcp:{TARGET_PORT}", f"tcp:{BOARD_PORT}"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        timeout=5,
    )


async def pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while True:
            data = await reader.read(CHUNK)
            if not data:
                break
            writer.write(data)
            await writer.drain()
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass


async def handle_client(client_r: asyncio.StreamReader, client_w: asyncio.StreamWriter) -> None:
    try:
        upstream_r, upstream_w = await asyncio.open_connection("127.0.0.1", TARGET_PORT)
    except Exception:
        try:
            await asyncio.to_thread(ensure_adb_forward)
            upstream_r, upstream_w = await asyncio.open_connection("127.0.0.1", TARGET_PORT)
        except Exception:
            client_w.close()
            return
    await asyncio.gather(pipe(client_r, upstream_w), pipe(upstream_r, client_w))


async def main() -> None:
    server = await asyncio.start_server(handle_client, host="0.0.0.0", port=PUBLIC_PORT, backlog=256)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
PY
