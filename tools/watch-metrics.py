#!/usr/bin/env python3
"""
Zero-dependency real-time terminal dashboard for BocBang Backend (/actuator/prometheus).

Usage:
  python3 tools/watch-metrics.py
  python3 tools/watch-metrics.py http://localhost:8099 --interval 1.0 --api-key YOUR_KEY
  python3 tools/watch-metrics.py --once
"""

from __future__ import annotations

import argparse
import math
import re
import shutil
import sys
import time
import urllib.error
import urllib.request

LABEL_RE = re.compile(r'([a-zA-Z0-9_]+)="((?:\\.|[^"\\])*)"')

RESET = "\033[0m"
BOLD = "\033[1m"
DIM = "\033[2m"
GREEN = "\033[32m"
YELLOW = "\033[33m"
RED = "\033[31m"
CYAN = "\033[36m"
BG_HIGHLIGHT = "\033[48;5;236m"


def parse_prometheus(text: str) -> dict[str, list[tuple[dict[str, str], float]]]:
    by_name: dict[str, list[tuple[dict[str, str], float]]] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        brace = line.find("{")
        labels: dict[str, str] = {}
        if brace != -1:
            end_brace = line.rfind("}")
            if end_brace == -1:
                continue
            name = line[:brace]
            for k, v in LABEL_RE.findall(line[brace + 1 : end_brace]):
                labels[k] = v.replace('\\"', '"')
            rest = line[end_brace + 1 :].strip()
        else:
            parts = line.split(None, 1)
            if len(parts) < 2:
                continue
            name, rest = parts[0], parts[1]
        token = rest.split()[0]
        try:
            val = math.inf if token == "+Inf" else -math.inf if token == "-Inf" else float(token)
        except ValueError:
            continue
        by_name.setdefault(name, []).append((labels, val))
    return by_name


def single(by_name: dict, name: str, default: float | None = None) -> float | None:
    arr = by_name.get(name)
    return arr[0][1] if arr else default


def sum_vals(by_name: dict, name: str, pred=None) -> float:
    return sum(v for lbl, v in by_name.get(name, []) if pred is None or pred(lbl))


def fmt_bytes(b: float | None) -> str:
    if b is None or not math.isfinite(b) or b <= 0:
        return "—"
    if b >= 1024**3:
        return f"{b / (1024**3):.2f} GB"
    if b >= 1024**2:
        return f"{b / (1024**2):.1f} MB"
    return f"{b / 1024:.0f} KB"


def fmt_dur(sec: float | None) -> str:
    if sec is None or not math.isfinite(sec):
        return "—"
    if sec >= 1.0:
        return f"{sec:.2f}s"
    return f"{sec * 1000:.1f}ms"


def bar(pct: float, width: int = 18) -> str:
    clamped = max(0.0, min(100.0, pct))
    filled = int(round((clamped / 100.0) * width))
    color = RED if clamped >= 85 else YELLOW if clamped >= 65 else GREEN
    return f"[{color}{'#' * filled}{DIM}{'.' * (width - filled)}{RESET}] {clamped:5.1f}%"


def fetch_text(url: str, api_key: str | None) -> str:
    req = urllib.request.Request(url)
    if api_key:
        req.add_header("Authorization", f"Bearer {api_key}")
    with urllib.request.urlopen(req, timeout=4.0) as resp:
        return resp.read().decode("utf-8", errors="replace")


def render_frame(
    base_url: str,
    by_name: dict,
    prev: dict | None,
    dt: float,
    scrape_ms: float,
    include_actuator: bool,
) -> tuple[str, dict]:
    cols = shutil.get_terminal_size((110, 36)).columns
    lines: list[str] = []

    uptime = single(by_name, "process_uptime_seconds", 0.0) or 0.0
    up_h, up_m, up_s = int(uptime // 3600), int((uptime % 3600) // 60), int(uptime % 60)
    lines.append(
        f"{BOLD}{CYAN}BocBang Live Monitor{RESET}  {DIM}{base_url}/actuator/prometheus{RESET}  "
        f"Uptime: {BOLD}{up_h:02d}:{up_m:02d}:{up_s:02d}{RESET}  Scrape: {scrape_ms:.0f}ms"
    )
    lines.append(DIM + ("─" * min(cols, 112)) + RESET)

    # CPU & Memory
    proc_cpu = max(0.0, (single(by_name, "process_cpu_usage", 0.0) or 0.0) * 100.0)
    sys_cpu = max(0.0, (single(by_name, "system_cpu_usage", 0.0) or 0.0) * 100.0)
    cores = int(single(by_name, "system_cpu_count", 0) or 0)
    load1m = single(by_name, "system_load_average_1m", -1.0) or -1.0

    rss = single(by_name, "process_memory_rss_bytes")
    hwm = single(by_name, "process_memory_hwm_bytes")
    sys_tot = single(by_name, "system_memory_total_bytes")
    sys_avail = single(by_name, "system_memory_available_bytes")
    heap = sum_vals(by_name, "jvm_memory_used_bytes", lambda l: l.get("area") == "heap")
    heap_max = sum_vals(by_name, "jvm_memory_max_bytes", lambda l: l.get("area") == "heap" and l.get("id") != "")

    lines.append(
        f"  {BOLD}Process CPU{RESET} {bar(proc_cpu)}    "
        f"{BOLD}System CPU{RESET} {bar(sys_cpu)}  ({cores} cores, load1m={load1m:.2f})"
    )
    if sys_tot and sys_avail is not None and sys_tot > 0:
        used_pct = ((sys_tot - sys_avail) / sys_tot) * 100.0
        lines.append(
            f"  {BOLD}Board RAM  {RESET} {bar(used_pct)}    "
            f"Avail: {BOLD}{fmt_bytes(sys_avail)}{RESET} / {fmt_bytes(sys_tot)}   "
            f"Proc RSS (Java+GGUF): {BOLD}{fmt_bytes(rss)}{RESET} (peak {fmt_bytes(hwm)})"
        )
    else:
        heap_pct = (heap / heap_max * 100.0) if heap_max > 0 else 0.0
        lines.append(
            f"  {BOLD}JVM Heap   {RESET} {bar(heap_pct)}    "
            f"Used: {BOLD}{fmt_bytes(heap)}{RESET}   "
            f"Proc RSS: {BOLD}{fmt_bytes(rss) if rss else 'redeploy JAR for C++ RSS'}{RESET}"
        )

    # Capacity, Workers, JNI Gate, Loaded Models
    sess_act = int(single(by_name, "stt_sessions_active", 0) or 0)
    sess_max = int(single(by_name, "stt_sessions_max", 0) or 0)
    sess_rej = int(single(by_name, "stt_sessions_rejected_total", 0) or 0)
    inf_act = int(single(by_name, "stt_inference_active", 0) or 0)
    inf_wrk = int(single(by_name, "stt_inference_workers", 0) or 0)
    inf_q = int(single(by_name, "stt_inference_queued", 0) or 0)
    gate_act = int(single(by_name, "stt_native_active", 0) or 0)
    gate_q = int(single(by_name, "stt_native_queued", 0) or 0)
    jobs_act = int(single(by_name, "stt_jobs_active", 0) or 0)
    jobs_q = int(single(by_name, "stt_jobs_queued", 0) or 0)

    active_ops = [l.get("operation", "?") for l, v in by_name.get("stt_native_operation_active", []) if v > 0]
    gate_state = f"{YELLOW}BUSY ({','.join(active_ops) or 'jni'}){RESET}" if gate_act > 0 else f"{GREEN}IDLE{RESET}"

    lines.append(
        f"  {BOLD}Slots:{RESET} {sess_act}/{sess_max} (rejected={sess_rej})  |  "
        f"{BOLD}Workers:{RESET} {inf_act}/{inf_wrk} (q={inf_q})  |  "
        f"{BOLD}JNI Gate:{RESET} {gate_state} (q={gate_q})  |  "
        f"{BOLD}Jobs:{RESET} {jobs_act} run, {jobs_q} queued"
    )

    loaded = [f"{l.get('model','?')}" for l, v in by_name.get("stt_native_model_loaded", []) if v > 0]
    if by_name.get("stt_native_model_loaded"):
        models_txt = ", ".join(f"{GREEN}{m}{RESET}" for m in loaded) if loaded else f"{DIM}none resident{RESET}"
        lines.append(f"  {BOLD}Resident C++ Models in RAM:{RESET} {models_txt}")

    lines.append(DIM + ("─" * min(cols, 112)) + RESET)

    # HTTP Requests Table
    counts = by_name.get("http_server_requests_seconds_count", [])
    sums = {(l.get("method"), l.get("uri"), l.get("status")): v for l, v in by_name.get("http_server_requests_seconds_sum", [])}
    maxes = {(l.get("method"), l.get("uri"), l.get("status")): v for l, v in by_name.get("http_server_requests_seconds_max", [])}
    act_key = (
        "http_server_requests_active_seconds_active_count"
        if "http_server_requests_active_seconds_active_count" in by_name
        else "http_server_requests_active_seconds_count"
    )
    actives = by_name.get(act_key, [])

    routes: dict[tuple[str, str, str], tuple[float, float]] = {}
    rows = []
    prev_routes = prev.get("routes", {}) if prev else {}

    for l, cnt in counts:
        method = l.get("method", "?")
        uri = l.get("uri", "?")
        status = l.get("status", "?")
        if not include_actuator and uri.startswith("/actuator"):
            continue
        key = (method, uri, status)
        s_val = sums.get(key, 0.0)
        m_val = maxes.get(key, 0.0)
        routes[key] = (cnt, s_val)
        p_cnt, p_sum = prev_routes.get(key, (cnt, s_val))
        d_cnt = max(0.0, cnt - p_cnt)
        d_sum = max(0.0, s_val - p_sum)
        rps = (d_cnt / dt) if prev and dt > 0 else 0.0
        avg_s = (s_val / cnt) if cnt > 0 else 0.0
        rec_s = (d_sum / d_cnt) if d_cnt > 0 else None
        in_flight = sum(
            v for al, v in actives if al.get("method") == method and al.get("uri") in (uri, "UNKNOWN") and v > 0
        )
        rows.append((d_cnt, in_flight, cnt, method, uri, status, rps, avg_s, rec_s, m_val))

    rows.sort(key=lambda r: (r[0], r[1], r[2]), reverse=True)
    lines.append(
        f"  {BOLD}{'METHOD':<6} {'STATUS':<6} {'URI':<34} {'TOTAL':>7} {'RPS':>7} {'ACTIVE':>6} {'AVG':>9} {'RECENT':>9} {'MAX':>9}{RESET}"
    )
    if not rows:
        lines.append(f"  {DIM}(No non-actuator HTTP requests yet — pass --include-actuator to show /actuator calls){RESET}")
    for d_cnt, in_flight, cnt, method, uri, status, rps, avg_s, rec_s, m_val in rows[:12]:
        hl = BG_HIGHLIGHT if (d_cnt > 0 or in_flight > 0) else ""
        st_col = GREEN if status.startswith("2") else CYAN if status.startswith("1") else YELLOW if status.startswith("4") else RED
        rps_str = f"{rps:.1f}/s" if rps > 0 else "—"
        rec_str = fmt_dur(rec_s) if rec_s is not None else "—"
        max_str = fmt_dur(m_val) if m_val > 0 else "—"
        lines.append(
            f"{hl}  {method:<6} {st_col}{status:<6}{RESET}{hl} {uri[:34]:<34} "
            f"{int(cnt):>7} {rps_str:>7} {int(in_flight):>6} {fmt_dur(avg_s):>9} {rec_str:>9} {max_str:>9}{RESET}"
        )

    # WebSocket + Async Jobs + Native JNI summary
    lines.append(DIM + ("─" * min(cols, 112)) + RESET)
    ws_open = int(single(by_name, "stt_ws_connections_active", 0) or 0)
    ws_bytes = single(by_name, "stt_ws_audio_bytes_total", 0.0) or 0.0
    prev_ws_bytes = prev.get("ws_bytes", ws_bytes) if prev else ws_bytes
    ws_kbps = max(0.0, ws_bytes - prev_ws_bytes) / 1024.0 / dt if prev and dt > 0 else 0.0
    ws_final = int(sum_vals(by_name, "stt_ws_utterances_total", lambda l: l.get("type") == "final"))
    ws_err = int(sum_vals(by_name, "stt_ws_errors_total"))
    lines.append(
        f"  {BOLD}WebSocket (/ws/stt):{RESET} open={ws_open}  stream={ws_kbps:.1f} KB/s ({fmt_bytes(ws_bytes)} total)  "
        f"finals={ws_final}  errors={ws_err}"
    )

    stage_counts = by_name.get("stt_jobs_stage_duration_seconds_count", [])
    stage_sums = {(l.get("stage"), l.get("model")): v for l, v in by_name.get("stt_jobs_stage_duration_seconds_sum", [])}
    if stage_counts:
        parts = []
        for l, c in stage_counts:
            st, md = l.get("stage", "?"), l.get("model", "?")
            tot = stage_sums.get((st, md), 0.0)
            parts.append(f"{st}[{md}]: {int(c)}x avg {fmt_dur(tot / c if c else 0)}")
        lines.append(f"  {BOLD}Job Stages:{RESET} " + " | ".join(parts))

    nat_counts = by_name.get("stt_native_duration_seconds_count", [])
    nat_sums = {(l.get("operation"), l.get("outcome")): v for l, v in by_name.get("stt_native_duration_seconds_sum", [])}
    if nat_counts:
        nparts = []
        for l, c in nat_counts:
            op, out = l.get("operation", "?"), l.get("outcome", "?")
            tot = nat_sums.get((op, out), 0.0)
            nparts.append(f"{op}({out}): {int(c)}x avg {fmt_dur(tot / c if c else 0)}")
        lines.append(f"  {BOLD}JNI Calls:{RESET}  " + " | ".join(nparts))

    lines.append(f"\n  {DIM}Press Ctrl+C to exit.{RESET}")
    return "\n".join(lines), {"routes": routes, "ws_bytes": ws_bytes}


def main() -> int:
    parser = argparse.ArgumentParser(description="Watch BocBang Backend Prometheus metrics in real time.")
    parser.add_argument("url", nargs="?", default="http://127.0.0.1:8099", help="Base server URL (default: http://127.0.0.1:8099)")
    parser.add_argument("-i", "--interval", type=float, default=1.0, help="Refresh interval in seconds (default: 1.0)")
    parser.add_argument("--api-key", default=None, help="Bearer API key if STT_PROTECT_ACTUATOR=true")
    parser.add_argument("--include-actuator", action="store_true", help="Include /actuator/* HTTP calls in table")
    parser.add_argument("--once", action="store_true", help="Print one snapshot and exit")
    args = parser.parse_args()

    base = args.url.rstrip("/")
    prom_url = f"{base}/actuator/prometheus"

    if args.once:
        t0 = time.monotonic()
        raw = fetch_text(prom_url, args.api_key)
        frame, _ = render_frame(base, parse_prometheus(raw), None, 1.0, (time.monotonic() - t0) * 1000, args.include_actuator)
        print(frame)
        return 0

    prev = None
    prev_ts = time.monotonic()
    sys.stdout.write("\033[?1049h\033[?25l")
    sys.stdout.flush()
    try:
        while True:
            t0 = time.monotonic()
            dt = max(0.05, t0 - prev_ts)
            try:
                raw = fetch_text(prom_url, args.api_key)
                scrape_ms = (time.monotonic() - t0) * 1000.0
                frame, prev = render_frame(
                    base, parse_prometheus(raw), prev, dt, scrape_ms, args.include_actuator
                )
                prev_ts = t0
            except urllib.error.URLError as exc:
                frame = f"{BOLD}{RED}Disconnected from {prom_url}:{RESET} {exc}\nRetrying every {args.interval}s..."
            sys.stdout.write("\033[H\033[J" + frame)
            sys.stdout.flush()
            elapsed = time.monotonic() - t0
            time.sleep(max(0.1, args.interval - elapsed))
    except KeyboardInterrupt:
        return 0
    finally:
        sys.stdout.write("\033[?25h\033[?1049l")
        sys.stdout.flush()


if __name__ == "__main__":
    raise SystemExit(main())
