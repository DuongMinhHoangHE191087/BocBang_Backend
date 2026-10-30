(() => {
  "use strict";

  const HISTORY_LEN = 60;
  const history = {
    procCpu: [],
    sysCpu: [],
    rssMb: [],
    heapMb: [],
    rps: [],
    wsKbps: []
  };

  let prevSnap = null;
  let timerId = null;

  const el = (id) => document.getElementById(id);

  function pushPoint(arr, value) {
    arr.push(Number.isFinite(value) ? value : 0);
    if (arr.length > HISTORY_LEN) {
      arr.shift();
    }
  }

  function parsePrometheus(text) {
    const samples = [];
    const lines = text.split("\n");
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].trim();
      if (!line || line.startsWith("#")) continue;
      const braceOpen = line.indexOf("{");
      let name;
      let labels = {};
      let rest;
      if (braceOpen !== -1) {
        name = line.slice(0, braceOpen);
        const braceClose = line.lastIndexOf("}");
        if (braceClose === -1) continue;
        const labelStr = line.slice(braceOpen + 1, braceClose);
        const re = /([a-zA-Z0-9_]+)="((?:\\.|[^"\\])*)"/g;
        let m;
        while ((m = re.exec(labelStr)) !== null) {
          labels[m[1]] = m[2].replace(/\\"/g, '"');
        }
        rest = line.slice(braceClose + 1).trim();
      } else {
        const sp = line.search(/\s/);
        if (sp === -1) continue;
        name = line.slice(0, sp);
        rest = line.slice(sp + 1).trim();
      }
      const valToken = rest.split(/\s+/)[0];
      const value = valToken === "+Inf" ? Infinity : valToken === "-Inf" ? -Infinity : Number(valToken);
      if (!Number.isNaN(value)) {
        samples.push({ name, labels, value });
      }
    }
    return samples;
  }

  function single(byName, name, fallback = null) {
    const arr = byName.get(name);
    return arr && arr.length > 0 ? arr[0].value : fallback;
  }

  function sumValues(byName, name, filterFn) {
    const arr = byName.get(name);
    if (!arr) return 0;
    let total = 0;
    for (const s of arr) {
      if (!filterFn || filterFn(s.labels)) total += s.value;
    }
    return total;
  }

  function estimateQuantileSeconds(buckets, q) {
    if (!buckets || buckets.length === 0) return null;
    const sorted = buckets.slice().sort((a, b) => a.le - b.le);
    const total = sorted[sorted.length - 1].count;
    if (total <= 0) return null;
    const target = total * q;
    let prevCount = 0;
    let prevLe = 0;
    for (const b of sorted) {
      if (!Number.isFinite(b.le)) return prevLe > 0 ? prevLe : null;
      if (b.count >= target) {
        const inBucket = b.count - prevCount;
        const frac = inBucket > 0 ? (target - prevCount) / inBucket : 0;
        return prevLe + (b.le - prevLe) * Math.max(0, Math.min(1, frac));
      }
      prevCount = b.count;
      prevLe = b.le;
    }
    return null;
  }

  function fmtBytes(bytes) {
    if (bytes == null || !Number.isFinite(bytes) || bytes <= 0) return "—";
    const gb = bytes / (1024 ** 3);
    if (gb >= 1) return `${gb.toFixed(2)} GB`;
    const mb = bytes / (1024 ** 2);
    if (mb >= 1) return `${mb.toFixed(1)} MB`;
    return `${(bytes / 1024).toFixed(0)} KB`;
  }

  function fmtDurationSec(sec) {
    if (sec == null || !Number.isFinite(sec)) return "—";
    if (sec >= 1) return `${sec.toFixed(2)} s`;
    return `${(sec * 1000).toFixed(1)} ms`;
  }

  function fmtUptime(sec) {
    if (!Number.isFinite(sec) || sec < 0) return "—";
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    const s = Math.floor(sec % 60);
    return h > 0 ? `${h}h ${m}m ${s}s` : m > 0 ? `${m}m ${s}s` : `${s}s`;
  }

  function drawChart(canvasId, seriesList, fixedMax) {
    const canvas = el(canvasId);
    if (!canvas) return;
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth || 300;
    const h = canvas.clientHeight || 84;
    if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
    }
    const ctx = canvas.getContext("2d");
    ctx.save();
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, w, h);

    const styles = getComputedStyle(document.documentElement);
    const gridColor = styles.getPropertyValue("--line").trim() || "#ccc";

    let maxVal = fixedMax || 0;
    if (!fixedMax) {
      for (const s of seriesList) {
        for (const v of s.data) {
          if (v > maxVal) maxVal = v;
        }
      }
      maxVal = maxVal > 0 ? maxVal * 1.18 : 1;
    }

    ctx.strokeStyle = gridColor;
    ctx.lineWidth = 1;
    ctx.globalAlpha = 0.45;
    for (const frac of [0.25, 0.5, 0.75]) {
      const y = Math.round(h * frac) + 0.5;
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(w, y);
      ctx.stroke();
    }
    ctx.globalAlpha = 1;

    for (const s of seriesList) {
      const pts = s.data;
      if (pts.length < 2) continue;
      const color = styles.getPropertyValue(s.cssVar).trim() || "#2438d8";
      ctx.strokeStyle = color;
      ctx.lineWidth = 2;
      ctx.beginPath();
      const stepX = w / (HISTORY_LEN - 1);
      const offset = HISTORY_LEN - pts.length;
      for (let i = 0; i < pts.length; i++) {
        const x = (offset + i) * stepX;
        const clamped = Math.max(0, Math.min(maxVal, pts[i]));
        const y = h - 4 - (clamped / maxVal) * (h - 8);
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      ctx.stroke();
    }
    ctx.restore();
  }

  function setBar(barEl, ratio) {
    const pct = Math.max(0, Math.min(100, ratio * 100));
    barEl.style.width = `${pct.toFixed(1)}%`;
    barEl.style.backgroundColor =
      pct >= 90 ? "var(--danger)" : pct >= 70 ? "var(--tick)" : "var(--accent)";
  }

  function escapeHtml(str) {
    return String(str)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function statusClass(code) {
    if (code.startsWith("2")) return "s2xx";
    if (code.startsWith("1")) return "s1xx";
    if (code.startsWith("4")) return "s4xx";
    return "s5xx";
  }

  async function pollOnce() {
    const headers = {};
    const key = el("monApiKey").value.trim();
    if (key) headers["Authorization"] = `Bearer ${key}`;

    const startedAt = performance.now();
    try {
      const [promRes, readyRes] = await Promise.all([
        fetch("/actuator/prometheus", { headers, cache: "no-store" }),
        fetch("/actuator/health/readiness", { cache: "no-store" }).catch(() => null)
      ]);

      if (!promRes.ok) {
        throw new Error(
          promRes.status === 401
            ? "Máy chủ yêu cầu API Key cho /actuator/prometheus — hãy nhập vào ô API Key phía trên."
            : `HTTP ${promRes.status} khi đọc /actuator/prometheus`
        );
      }

      const text = await promRes.text();
      const nowMs = performance.now();
      const samples = parsePrometheus(text);
      const byName = new Map();
      for (const s of samples) {
        let list = byName.get(s.name);
        if (!list) {
          list = [];
          byName.set(s.name, list);
        }
        list.push(s);
      }

      el("monError").hidden = true;
      const badge = el("healthBadge");
      if (readyRes && readyRes.status === 503) {
        badge.textContent = "ĐẦY SLOT (BUSY 503)";
        badge.className = "badge warn";
      } else {
        badge.textContent = `TRỰC TUYẾN (${Math.round(nowMs - startedAt)} ms)`;
        badge.className = "badge ok";
      }

      renderDashboard(byName, nowMs);
    } catch (err) {
      const banner = el("monError");
      banner.textContent = `Không đọc được metrics: ${err.message || err}`;
      banner.hidden = false;
      const badge = el("healthBadge");
      badge.textContent = "MẤT KẾT NỐI";
      badge.className = "badge err";
    }
  }

  function renderDashboard(byName, nowMs) {
    const dtSec = prevSnap ? Math.max(0.1, (nowMs - prevSnap.nowMs) / 1000) : null;

    // 1. CPU
    const procCpuPct = Math.max(0, (single(byName, "process_cpu_usage", 0) || 0) * 100);
    const sysCpuPct = Math.max(0, (single(byName, "system_cpu_usage", 0) || 0) * 100);
    const cores = single(byName, "system_cpu_count", null);
    const load1m = single(byName, "system_load_average_1m", null);

    el("cpuSummary").textContent = `${sysCpuPct.toFixed(1)}% hệ thống`;
    el("procCpu").textContent = `${procCpuPct.toFixed(1)}%`;
    el("sysCpu").textContent = `${sysCpuPct.toFixed(1)}%`;
    el("cpuCoresLoad").textContent =
      `${cores != null ? cores : "?"} nhân / ${load1m != null && load1m >= 0 ? load1m.toFixed(2) : "—"}`;

    pushPoint(history.procCpu, procCpuPct);
    pushPoint(history.sysCpu, sysCpuPct);
    drawChart("cpuChart", [
      { data: history.sysCpu, cssVar: "--tick" },
      { data: history.procCpu, cssVar: "--accent" }
    ], 100);

    // 2. Memory (Native C++ RSS + JVM Heap + Board Free)
    const rssBytes = single(byName, "process_memory_rss_bytes", null);
    const hwmBytes = single(byName, "process_memory_hwm_bytes", null);
    const sysTotalBytes = single(byName, "system_memory_total_bytes", null);
    const sysAvailBytes = single(byName, "system_memory_available_bytes", null);
    const heapBytes = sumValues(byName, "jvm_memory_used_bytes", (l) => l.area === "heap");
    const nonHeapBytes = sumValues(byName, "jvm_memory_used_bytes", (l) => l.area === "nonheap");

    el("memSummary").textContent = rssBytes != null ? `RSS ${fmtBytes(rssBytes)}` : `Heap ${fmtBytes(heapBytes)}`;
    el("procRss").textContent =
      rssBytes != null ? `${fmtBytes(rssBytes)} / ${fmtBytes(hwmBytes)}` : "Cập nhật JAR để xem RSS";
    el("jvmMem").textContent = `${fmtBytes(heapBytes)} / ${fmtBytes(nonHeapBytes)}`;
    el("sysMemAvail").textContent =
      sysAvailBytes != null && sysTotalBytes != null
        ? `${fmtBytes(sysAvailBytes)} / ${fmtBytes(sysTotalBytes)}`
        : "—";

    pushPoint(history.rssMb, (rssBytes != null ? rssBytes : heapBytes) / (1024 ** 2));
    pushPoint(history.heapMb, heapBytes / (1024 ** 2));
    drawChart("memChart", [
      { data: history.rssMb, cssVar: "--accent" },
      { data: history.heapMb, cssVar: "--ok" }
    ], null);

    // 3. HTTP Endpoints & Live RPS
    const hideActuator = el("hideActuator").checked;
    const httpCounts = byName.get("http_server_requests_seconds_count") || [];
    const httpSums = byName.get("http_server_requests_seconds_sum") || [];
    const httpMaxes = byName.get("http_server_requests_seconds_max") || [];
    const httpActives = byName.get("http_server_requests_active_seconds_active_count")
      || byName.get("http_server_requests_active_seconds_count")
      || [];
    const httpBuckets = byName.get("http_server_requests_seconds_bucket") || [];

    const routeMap = new Map();
    const routeKey = (l) => `${l.method || "?"}|${l.uri || "?"}|${l.status || "?"}`;

    for (const s of httpCounts) {
      const k = routeKey(s.labels);
      routeMap.set(k, {
        key: k,
        method: s.labels.method || "?",
        uri: s.labels.uri || "?",
        status: s.labels.status || "?",
        count: s.value,
        sum: 0,
        max: 0,
        active: 0,
        buckets: []
      });
    }
    for (const s of httpSums) {
      const r = routeMap.get(routeKey(s.labels));
      if (r) r.sum = s.value;
    }
    for (const s of httpMaxes) {
      const r = routeMap.get(routeKey(s.labels));
      if (r) r.max = s.value;
    }
    for (const s of httpActives) {
      for (const r of routeMap.values()) {
        if (r.method === s.labels.method && (s.labels.uri === r.uri || s.labels.uri === "UNKNOWN") && s.value > 0) {
          r.active += s.value;
        }
      }
    }
    for (const b of httpBuckets) {
      const r = routeMap.get(routeKey(b.labels));
      if (r && b.labels.le != null) {
        const le = b.labels.le === "+Inf" ? Infinity : Number(b.labels.le);
        if (!Number.isNaN(le)) r.buckets.push({ le, count: b.value });
      }
    }

    let totalFilteredCount = 0;
    const prevRoutes = prevSnap ? prevSnap.routes : new Map();
    const rows = [];
    for (const r of routeMap.values()) {
      const isActuator = r.uri.startsWith("/actuator");
      if (!isActuator) totalFilteredCount += r.count;
      if (hideActuator && isActuator) continue;

      const prev = prevRoutes.get(r.key);
      const dCount = prev ? Math.max(0, r.count - prev.count) : 0;
      const dSum = prev ? Math.max(0, r.sum - prev.sum) : 0;
      const rps = dtSec ? dCount / dtSec : 0;
      const avgSec = r.count > 0 ? r.sum / r.count : 0;
      const recentSec = dCount > 0 ? dSum / dCount : null;
      const p95Sec = estimateQuantileSeconds(r.buckets, 0.95);
      rows.push({ ...r, dCount, rps, avgSec, recentSec, p95Sec });
    }

    rows.sort((a, b) => (b.dCount - a.dCount) || (b.active - a.active) || (b.count - a.count));

    const prevFilteredCount = prevSnap ? prevSnap.totalFilteredCount : totalFilteredCount;
    const totalRps = dtSec ? Math.max(0, totalFilteredCount - prevFilteredCount) / dtSec : 0;
    const totalActiveHttp = sumValues(
      byName,
      byName.has("http_server_requests_active_seconds_active_count")
        ? "http_server_requests_active_seconds_active_count"
        : "http_server_requests_active_seconds_count",
      (l) => !String(l.uri || "").startsWith("/actuator")
    );

    const wsAudioBytes = single(byName, "stt_ws_audio_bytes_total", 0) || 0;
    const prevWsBytes = prevSnap ? prevSnap.wsAudioBytes : wsAudioBytes;
    const wsKbps = dtSec ? Math.max(0, wsAudioBytes - prevWsBytes) / 1024 / dtSec : 0;
    const wsOpen = single(byName, "stt_ws_connections_active", null);
    const sessionsActive = single(byName, "stt_sessions_active", 0) || 0;
    const sessionsMax = single(byName, "stt_sessions_max", 0) || 0;

    el("rpsSummary").textContent = `${totalRps.toFixed(1)} req/s`;
    el("httpActive").textContent = `${Math.round(totalActiveHttp)}`;
    el("wsSummary").textContent =
      `${wsOpen != null ? Math.round(wsOpen) : Math.round(sessionsActive)} mở / ${wsKbps.toFixed(1)} KB/s`;
    el("uptimeVal").textContent = fmtUptime(single(byName, "process_uptime_seconds", NaN));

    pushPoint(history.rps, totalRps);
    pushPoint(history.wsKbps, wsKbps);
    drawChart("rpsChart", [
      { data: history.rps, cssVar: "--accent" },
      { data: history.wsKbps, cssVar: "--tick" }
    ], null);

    // 4. Capacity & Queues
    const workers = single(byName, "stt_inference_workers", 0) || 0;
    const inferActive = single(byName, "stt_inference_active", 0) || 0;
    const inferQueued = single(byName, "stt_inference_queued", 0) || 0;
    const gateActive = single(byName, "stt_native_active", 0) || 0;
    const gateQueued = single(byName, "stt_native_queued", 0) || 0;
    const qWaitCount = single(byName, "stt_queue_wait_seconds_count", 0) || 0;
    const qWaitSum = single(byName, "stt_queue_wait_seconds_sum", 0) || 0;

    el("slotSummary").textContent = `${sessionsActive} / ${sessionsMax} slot`;
    el("slotText").textContent = `${sessionsActive} / ${sessionsMax}`;
    setBar(el("slotBar"), sessionsMax > 0 ? sessionsActive / sessionsMax : 0);

    el("workerText").textContent = `${inferActive} / ${workers} (chờ: ${inferQueued})`;
    setBar(el("workerBar"), workers > 0 ? inferActive / workers : 0);

    el("gateText").textContent = `${gateActive > 0 ? "ĐANG CHẠY C++" : "Rảnh"} (chờ: ${gateQueued})`;
    setBar(el("gateBar"), gateActive > 0 ? 1 : gateQueued > 0 ? 0.8 : 0);

    el("rejectedSlots").textContent = `${Math.round(single(byName, "stt_sessions_rejected_total", 0) || 0)}`;
    el("rejectedAuth").textContent = `${Math.round(single(byName, "stt_auth_rejected_total", 0) || 0)}`;
    el("queueWaitAvg").textContent = qWaitCount > 0 ? fmtDurationSec(qWaitSum / qWaitCount) : "0 ms";

    // 5. Resident C++ Models & Active JNI Operation
    const activeOps = (byName.get("stt_native_operation_active") || [])
      .filter((s) => s.value > 0)
      .map((s) => s.labels.operation);
    el("activeNativeOp").textContent =
      activeOps.length > 0 ? `CPU JNI đang chạy: ${activeOps.join(", ")}` : gateActive > 0 ? "CPU JNI: Đang bận" : "CPU JNI: Đang rảnh";

    const loadedModels = byName.get("stt_native_model_loaded") || [];
    const pillsWrap = el("modelPills");
    if (loadedModels.length > 0) {
      pillsWrap.innerHTML = loadedModels
        .map((m) => {
          const model = m.labels.model || "?";
          const role = (m.labels.role || "").toUpperCase();
          const isLoaded = m.value > 0;
          const isRunning = activeOps.some((op) => op.includes(model) || op === m.labels.role);
          const cls = isRunning ? "running" : isLoaded ? "loaded" : "idle";
          const stateTxt = isRunning ? "ĐANG CHẠY" : isLoaded ? "ĐÃ NẠP RAM" : "CHƯA NẠP";
          return `<span class="pill ${cls}">${escapeHtml(role)}: <code>${escapeHtml(model)}</code> &middot; ${stateTxt}</span>`;
        })
        .join("");
    } else {
      pillsWrap.innerHTML = `<span class="pill idle">Engine giả lập / cập nhật JAR trên board để xem trạng thái nạp từng model GGUF</span>`;
    }

    // 6. Render HTTP Table
    const httpBody = el("httpTableBody");
    if (rows.length === 0) {
      httpBody.innerHTML = `<tr><td colspan="10" class="empty">Chưa có cuộc gọi API nào ngoài <code>/actuator/*</code>.</td></tr>`;
    } else {
      httpBody.innerHTML = rows
        .map((r) => `
          <tr class="${r.dCount > 0 || r.active > 0 ? "active-row" : ""}">
            <td><strong>${escapeHtml(r.method)}</strong></td>
            <td><code>${escapeHtml(r.uri)}</code></td>
            <td><span class="status-pill ${statusClass(r.status)}">${escapeHtml(r.status)}</span></td>
            <td class="num">${Math.round(r.count)}</td>
            <td class="num">${r.rps > 0 ? `${r.rps.toFixed(1)}/s` : "—"}</td>
            <td class="num">${r.active > 0 ? Math.round(r.active) : "0"}</td>
            <td class="num">${fmtDurationSec(r.avgSec)}</td>
            <td class="num">${r.recentSec != null ? fmtDurationSec(r.recentSec) : "—"}</td>
            <td class="num">${r.p95Sec != null ? fmtDurationSec(r.p95Sec) : "—"}</td>
            <td class="num">${r.max > 0 ? fmtDurationSec(r.max) : "—"}</td>
          </tr>`)
        .join("");
    }

    // 7. Render Long-Audio Jobs Panel
    const jobsActive = single(byName, "stt_jobs_active", 0) || 0;
    const jobsQueued = single(byName, "stt_jobs_queued", 0) || 0;
    const jobsAccepted = sumValues(byName, "stt_jobs_submitted_total", (l) => l.outcome === "accepted");
    const jobsRefused = sumValues(byName, "stt_jobs_submitted_total", (l) => l.outcome !== "accepted");
    const jobsDone = sumValues(byName, "stt_jobs_duration_seconds_count", (l) => l.status === "DONE");
    const jobsFailed = sumValues(byName, "stt_jobs_duration_seconds_count", (l) => l.status === "FAILED");
    const jobsCancelled = sumValues(byName, "stt_jobs_duration_seconds_count", (l) => l.status === "CANCELLED");
    const jobsAudio = single(byName, "stt_jobs_audio_seconds_total", 0) || 0;
    const jobsSegs = sumValues(byName, "stt_jobs_segments_total");

    el("jobsTopStat").textContent = `Đang chạy: ${Math.round(jobsActive)} | Hàng đợi: ${Math.round(jobsQueued)}`;
    el("jobsSubmitted").textContent = `${Math.round(jobsAccepted)} / ${Math.round(jobsRefused)}`;
    el("jobsOutcomes").textContent = `${Math.round(jobsDone)} / ${Math.round(jobsFailed)} / ${Math.round(jobsCancelled)}`;
    el("jobsAudioSec").textContent = `${jobsAudio.toFixed(1)}s`;
    el("jobsSegments").textContent = `${Math.round(jobsSegs)}`;

    const stageCounts = byName.get("stt_jobs_stage_duration_seconds_count") || [];
    const stageSums = byName.get("stt_jobs_stage_duration_seconds_sum") || [];
    const stageMaxes = byName.get("stt_jobs_stage_duration_seconds_max") || [];
    const stageOrder = { CONVERTING: 1, DETECTING_SPEECH: 2, TRANSCRIBING: 3, REFINING: 4 };
    const stages = stageCounts.map((c) => {
      const st = c.labels.stage || "?";
      const md = c.labels.model || "?";
      const sumObj = stageSums.find((x) => x.labels.stage === st && x.labels.model === md);
      const maxObj = stageMaxes.find((x) => x.labels.stage === st && x.labels.model === md);
      const sum = sumObj ? sumObj.value : 0;
      return {
        stage: st,
        model: md,
        count: c.value,
        sum,
        avg: c.value > 0 ? sum / c.value : 0,
        max: maxObj ? maxObj.value : 0
      };
    });
    stages.sort((a, b) => (stageOrder[a.stage] || 99) - (stageOrder[b.stage] || 99));

    const stageBody = el("jobStageBody");
    stageBody.innerHTML = stages.length === 0
      ? `<tr><td colspan="6" class="empty">Chưa có job file dài nào chạy kể từ lúc bật máy chủ.</td></tr>`
      : stages.map((s) => `
          <tr>
            <td><code>${escapeHtml(s.stage)}</code></td>
            <td><code>${escapeHtml(s.model)}</code></td>
            <td class="num">${Math.round(s.count)}</td>
            <td class="num">${fmtDurationSec(s.sum)}</td>
            <td class="num">${fmtDurationSec(s.avg)}</td>
            <td class="num">${s.max > 0 ? fmtDurationSec(s.max) : "—"}</td>
          </tr>`).join("");

    // 8. Render WebSocket & Native JNI Table
    const wsAccepted = sumValues(byName, "stt_ws_connections_total", (l) => l.outcome === "accepted");
    const wsRejected = sumValues(byName, "stt_ws_connections_total", (l) => l.outcome === "rejected");
    const wsErrors = sumValues(byName, "stt_ws_errors_total");
    const wsFinal = sumValues(byName, "stt_ws_utterances_total", (l) => l.type === "final");
    const wsPartial = sumValues(byName, "stt_ws_utterances_total", (l) => l.type === "partial");
    const finalLagCount = sumValues(byName, "stt_ws_utterance_lag_seconds_count", (l) => l.type === "final");
    const finalLagSum = sumValues(byName, "stt_ws_utterance_lag_seconds_sum", (l) => l.type === "final");
    const finalLagBuckets = (byName.get("stt_ws_utterance_lag_seconds_bucket") || [])
      .filter((b) => b.labels.type === "final")
      .map((b) => ({ le: b.labels.le === "+Inf" ? Infinity : Number(b.labels.le), count: b.value }));
    const finalLagP95 = estimateQuantileSeconds(finalLagBuckets, 0.95);

    el("wsConnStat").textContent = `${wsOpen != null ? Math.round(wsOpen) : 0} / ${Math.round(wsAccepted)}`;
    el("wsErrStat").textContent = `${Math.round(wsRejected)} / ${Math.round(wsErrors)}`;
    el("wsUttStat").textContent = `${Math.round(wsFinal)} / ${Math.round(wsPartial)}`;
    el("wsLagStat").textContent = finalLagCount > 0
      ? `${fmtDurationSec(finalLagSum / finalLagCount)} / ${finalLagP95 != null ? fmtDurationSec(finalLagP95) : "—"}`
      : "—";

    const natCounts = byName.get("stt_native_duration_seconds_count") || [];
    const natSums = byName.get("stt_native_duration_seconds_sum") || [];
    const natMaxes = byName.get("stt_native_duration_seconds_max") || [];
    const waitCounts = byName.get("stt_native_wait_seconds_count") || [];
    const waitSums = byName.get("stt_native_wait_seconds_sum") || [];

    const natRows = natCounts.map((c) => {
      const op = c.labels.operation || "?";
      const out = c.labels.outcome || "?";
      const sObj = natSums.find((x) => x.labels.operation === op && x.labels.outcome === out);
      const mObj = natMaxes.find((x) => x.labels.operation === op && x.labels.outcome === out);
      const wCnt = waitCounts.find((x) => x.labels.operation === op);
      const wSum = waitSums.find((x) => x.labels.operation === op);
      const sum = sObj ? sObj.value : 0;
      const waitAvg = wCnt && wCnt.value > 0 && wSum ? wSum.value / wCnt.value : 0;
      return {
        op,
        out,
        count: c.value,
        waitAvg,
        execAvg: c.value > 0 ? sum / c.value : 0,
        max: mObj ? mObj.value : 0
      };
    });

    const natBody = el("nativeTableBody");
    natBody.innerHTML = natRows.length === 0
      ? `<tr><td colspan="6" class="empty">Chưa phát sinh lời gọi JNI C++.</td></tr>`
      : natRows.map((n) => `
          <tr class="${activeOps.includes(n.op) ? "active-row" : ""}">
            <td><code>${escapeHtml(n.op)}</code></td>
            <td><span class="status-pill ${n.out === "success" ? "s2xx" : "s5xx"}">${escapeHtml(n.out)}</span></td>
            <td class="num">${Math.round(n.count)}</td>
            <td class="num">${fmtDurationSec(n.waitAvg)}</td>
            <td class="num">${fmtDurationSec(n.execAvg)}</td>
            <td class="num">${n.max > 0 ? fmtDurationSec(n.max) : "—"}</td>
          </tr>`).join("");

    prevSnap = {
      nowMs,
      totalFilteredCount,
      wsAudioBytes,
      routes: routeMap
    };
  }

  function scheduleLoop() {
    if (timerId) {
      clearInterval(timerId);
      timerId = null;
    }
    const ms = Number(el("pollInterval").value);
    if (ms > 0) {
      timerId = setInterval(pollOnce, ms);
    }
  }

  el("pollInterval").addEventListener("change", () => {
    scheduleLoop();
    if (Number(el("pollInterval").value) > 0) pollOnce();
  });
  el("hideActuator").addEventListener("change", pollOnce);
  el("monApiKey").addEventListener("change", pollOnce);

  pollOnce();
  scheduleLoop();
})();
