(() => {
  'use strict';

  const TARGET_RATE = 16000;
  const FRAME_SAMPLES = 1600; // 100 ms
  const FRAME_MS = 100;
  const STATUS_POLL_MS = 2000;
  const JOB_POLL_MS = 1500;
  // On CPU, decoding a 15 s utterance with Qwen3-ASR 1.7B can take 20-45 s.
  const FINAL_TIMEOUT_MS = 120000;
  const SPEECH_RMS = 0.015; // frame loudness that counts as speech
  const PAUSE_FRAMES = 8; // 0.8 s of quiet closes an utterance
  const MIN_SPEECH_FRAMES = 3;
  const PREROLL_FRAMES = 3; // 0.3 s kept from before the first loud frame, so the start of a word is not clipped
  const MAX_UTTERANCE_FRAMES = 150; // 15 s chunks keep CPU latency lower and avoid Qwen3-ASR token cap truncation

  const MODEL_LABELS = {
    'qwen3-asr': 'qwen3-asr (Tiếng Việt / Đa ngôn ngữ)',
    'whisper-en': 'whisper-en (Tiếng Anh)',
  };

  const STAGE_LABELS = {
    QUEUED: 'Đang xếp hàng chờ CPU...',
    CONVERTING: 'Đang chuẩn hóa âm thanh 16 kHz mono (ffmpeg)...',
    DETECTING_SPEECH: 'Đang tách đoạn có tiếng nói (Silero VAD)...',
    TRANSCRIBING: 'Đang nhận dạng giọng nói trên CPU...',
    REFINING: 'Đang làm sạch văn bản bằng Qwen3-4B...',
    DONE: 'Hoàn tất',
    FAILED: 'Thất bại',
    CANCELLED: 'Đã hủy',
  };

  const $ = (id) => document.getElementById(id);
  const el = {
    ribbon: $('ribbon'), transcript: $('transcript'), finals: $('finals'), partial: $('partial'), hint: $('hint'),
    toggle: $('toggle'), status: $('status'), micNote: $('micNote'),
    fileOptions: $('fileOptions'), file: $('file'), count: $('count'), apiKey: $('apiKey'),
    statLag: $('statLag'), statLagMax: $('statLagMax'), statFrames: $('statFrames'), statOpen: $('statOpen'),
    clients: $('clients'), rest: $('rest'), restResult: $('restResult'),
    server: $('server'), serverEngine: $('serverEngine'), serverLoad: $('serverLoad'), meterFill: $('meterFill'),
    jobFile: $('jobFile'), jobModel: $('jobModel'), jobRefine: $('jobRefine'),
    jobSubmit: $('jobSubmit'), jobCancel: $('jobCancel'), jobCopy: $('jobCopy'),
    jobError: $('jobError'), jobProgressBox: $('jobProgressBox'), jobStageText: $('jobStageText'),
    jobMetaText: $('jobMetaText'), jobMeterFill: $('jobMeterFill'),
    jobOutput: $('jobOutput'), jobText: $('jobText'),
    jobRawWrap: $('jobRawWrap'), jobRawText: $('jobRawText'),
    jobSegmentsWrap: $('jobSegmentsWrap'), jobSegmentsSummary: $('jobSegmentsSummary'), jobSegments: $('jobSegments'),
  };

  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const selectedSource = () => document.querySelector('input[name="source"]:checked').value;

  const KEY_STORAGE = 'bocbang.apiKey';
  try {
    el.apiKey.value = sessionStorage.getItem(KEY_STORAGE) || '';
  } catch (error) {
    // Storage can be blocked; the key then lasts until the page reloads.
  }
  el.apiKey.addEventListener('input', () => {
    try {
      sessionStorage.setItem(KEY_STORAGE, el.apiKey.value);
    } catch (error) {
      // Same as above.
    }
  });
  const apiKey = () => el.apiKey.value.trim();
  /** Headers for REST calls; a browser WebSocket cannot set headers, so it sends the key in the URL instead. */
  const authHeaders = () => (apiKey() ? { Authorization: `Bearer ${apiKey()}` } : {});

  /** One WebSocket to /ws/stt. Resolves open() once the server says it is ready. */
  class SttConnection {
    constructor(id, handlers) {
      this.id = id;
      this.handlers = handlers;
      this.ws = null;
      this.ready = false;
      this.audioSinceEnd = false;
      /** Ends sent and not yet answered by a final (or by a recognition_failed in its place). */
      this.pendingEnds = 0;
    }

    open() {
      return new Promise((resolve, reject) => {
        const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
        const key = apiKey() ? `&apiKey=${encodeURIComponent(apiKey())}` : '';
        const ws = new WebSocket(`${scheme}://${location.host}/ws/stt?sampleRate=${TARGET_RATE}${key}`);
        ws.binaryType = 'arraybuffer';
        this.ws = ws;
        let settled = false;
        let reason = null;
        const fail = (error) => { if (!settled) { settled = true; reject(error); } };
        ws.onmessage = (event) => {
          const message = JSON.parse(event.data);
          if (message.type === 'final' || message.code === 'recognition_failed') {
            this.pendingEnds = Math.max(0, this.pendingEnds - 1);
          }
          if (message.type === 'ready') {
            this.ready = true;
            settled = true;
            resolve(message);
          } else if (message.type === 'error') {
            reason = message;
            this.handlers.onError(message);
            fail(Object.assign(new Error(message.message), { code: message.code }));
          } else {
            this.handlers.onTranscript(message);
          }
        };
        ws.onclose = (event) => {
          this.ready = false;
          this.handlers.onClose(event, reason);
          fail(new Error('Không kết nối được tới máy chủ. Nếu máy chủ bật xác thực, kiểm tra khóa API.'));
        };
      });
    }

    sendPcm(pcm) {
      if (this.ready && this.ws.readyState === WebSocket.OPEN) {
        this.ws.send(pcm);
        this.audioSinceEnd = true;
      }
    }

    end() {
      if (this.ready && this.audioSinceEnd && this.ws.readyState === WebSocket.OPEN) {
        this.ws.send(JSON.stringify({ type: 'end' }));
        this.audioSinceEnd = false;
        this.pendingEnds++;
      }
    }

    close() {
      if (this.ws && this.ws.readyState <= WebSocket.OPEN) {
        this.ws.close(1000);
      }
    }
  }

  /** Scrolling strip of loudness, one bar per 100 ms frame, with a tick where the server committed a sentence. */
  class Ribbon {
    constructor(canvas) {
      this.canvas = canvas;
      this.ctx = canvas.getContext('2d');
      this.levels = [];
      this.ticks = [];
      this.count = 0;
      this.barWidth = 3;
      this.gap = 1;
      window.addEventListener('resize', () => this.draw());
    }

    reset() {
      this.levels = [];
      this.ticks = [];
      this.count = 0;
      this.draw();
    }

    push(rms) {
      this.levels.push(rms);
      this.count++;
      const capacity = this.capacity();
      if (this.levels.length > capacity) {
        this.levels.splice(0, this.levels.length - capacity);
      }
      this.draw();
    }

    tick() {
      this.ticks.push(this.count);
    }

    capacity() {
      return Math.max(10, Math.floor(this.canvas.clientWidth / (this.barWidth + this.gap)));
    }

    draw() {
      const dpr = window.devicePixelRatio || 1;
      const width = this.canvas.clientWidth;
      const height = this.canvas.clientHeight;
      if (this.canvas.width !== Math.round(width * dpr) || this.canvas.height !== Math.round(height * dpr)) {
        this.canvas.width = Math.round(width * dpr);
        this.canvas.height = Math.round(height * dpr);
      }
      const ctx = this.ctx;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, width, height);
      const style = getComputedStyle(document.documentElement);
      const quiet = style.getPropertyValue('--wave').trim();
      const loud = style.getPropertyValue('--wave-live').trim();
      const tick = style.getPropertyValue('--tick').trim();
      const step = this.barWidth + this.gap;
      const first = this.count - this.levels.length;
      const mid = height / 2;

      this.levels.forEach((rms, i) => {
        const x = width - (this.levels.length - i) * step;
        const amplitude = Math.min(1, Math.pow(rms * 4, 0.6));
        const barHeight = Math.max(2, amplitude * (height - 12));
        ctx.fillStyle = amplitude > 0.15 ? loud : quiet;
        ctx.fillRect(x, mid - barHeight / 2, this.barWidth, barHeight);
      });

      ctx.fillStyle = tick;
      this.ticks = this.ticks.filter((index) => index > first);
      this.ticks.forEach((index) => {
        const x = width - (this.count - index + 1) * step;
        ctx.fillRect(x + this.barWidth, 4, 2, height - 8);
      });
    }
  }

  const ribbon = new Ribbon(el.ribbon);
  ribbon.draw();

  /** Microphone: the worklet resamples to 16 kHz and posts 100 ms frames. */
  async function openMic() {
    if (!window.isSecureContext) {
      throw new Error('Trình duyệt chỉ cho dùng micro trên https hoặc localhost. Hãy dùng mục Bóc băng file dài ở bên trái.');
    }
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
    const context = new AudioContext({ latencyHint: 'interactive' });
    await context.audioWorklet.addModule('pcm-worklet.js');
    const node = new AudioWorkletNode(context, 'pcm-downsampler', {
      processorOptions: { targetRate: TARGET_RATE, frameSamples: FRAME_SAMPLES },
    });
    const mute = context.createGain();
    mute.gain.value = 0;
    context.createMediaStreamSource(stream).connect(node);
    node.connect(mute).connect(context.destination);

    let onFrame = null;
    node.port.onmessage = (event) => {
      if (onFrame) {
        onFrame(new Int16Array(event.data.pcm), event.data.rms);
      }
    };
    return {
      start(callback) { onFrame = callback; },
      stop() {
        onFrame = null;
        stream.getTracks().forEach((track) => track.stop());
        context.close();
      },
    };
  }

  let decoded = { file: null, pcm: null };

  /** Decodes any browser-supported audio file to mono 16-bit PCM at 16 kHz. */
  async function decodeFile(file) {
    if (decoded.file === file) {
      return decoded.pcm;
    }
    const context = new AudioContext();
    const audio = await context.decodeAudioData(await file.arrayBuffer());
    context.close();
    const offline = new OfflineAudioContext(1, Math.ceil(audio.duration * TARGET_RATE), TARGET_RATE);
    const source = offline.createBufferSource();
    source.buffer = audio;
    source.connect(offline.destination);
    source.start();
    const samples = (await offline.startRendering()).getChannelData(0);
    const pcm = new Int16Array(samples.length);
    for (let i = 0; i < samples.length; i++) {
      const value = Math.max(-1, Math.min(1, samples[i]));
      pcm[i] = value < 0 ? value * 0x8000 : value * 0x7fff;
    }
    decoded = { file, pcm };
    return pcm;
  }

  const rmsOf = (frame) => {
    let sum = 0;
    for (let i = 0; i < frame.length; i++) {
      const value = frame[i] / 0x8000;
      sum += value * value;
    }
    return Math.sqrt(sum / Math.max(1, frame.length));
  };

  /** File: sends 100 ms frames at the pace of real speech, the way a live client would. */
  function filePlayer(pcm) {
    let timer = null;
    return {
      start(onFrame, onEnd) {
        const startedAt = performance.now();
        let offset = 0;
        let sent = 0;
        timer = setInterval(() => {
          const due = Math.floor((performance.now() - startedAt) / FRAME_MS) + 1;
          while (sent < due && offset < pcm.length) {
            const frame = pcm.slice(offset, offset + FRAME_SAMPLES);
            offset += FRAME_SAMPLES;
            sent++;
            onFrame(frame, rmsOf(frame));
          }
          if (offset >= pcm.length) {
            clearInterval(timer);
            timer = null;
            onEnd();
          }
        }, 25);
      },
      stop() {
        if (timer) {
          clearInterval(timer);
          timer = null;
        }
      },
    };
  }

  const LAG_WINDOW = 20;
  const run = { active: false, connections: [], source: null, startedAt: 0, frames: 0, recentLags: [], lagMax: 0 };
  const tiles = new Map();

  function setStatus(text, isError = false) {
    el.status.textContent = text;
    el.status.classList.toggle('error', isError);
  }

  const clock = (ms) => {
    const total = Math.max(0, Math.floor(ms / 1000));
    return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
  };

  const formatTimestamp = (seconds) => {
    const s = Math.max(0, Number(seconds) || 0);
    const mins = Math.floor(s / 60);
    const rem = (s - mins * 60).toFixed(1).padStart(4, '0');
    return `${String(mins).padStart(2, '0')}:${rem}`;
  };

  function renderStats() {
    const recent = run.recentLags;
    el.statLag.textContent = recent.length ? `${Math.round(recent.reduce((a, b) => a + b, 0) / recent.length)} ms` : '-';
    el.statLagMax.textContent = recent.length ? `${run.lagMax} ms` : '-';
    el.statFrames.textContent = String(run.frames);
    el.statOpen.textContent = String(run.connections.filter((c) => c.ready).length);
  }

  function setTile(id, state, label, text, lag) {
    let tile = tiles.get(id);
    if (!tile) {
      const node = document.createElement('div');
      node.className = 'tile';
      node.innerHTML = '<div class="tile-head"><span class="tile-name"></span><span class="tile-state"></span></div>'
        + '<p class="tile-text"></p>';
      node.querySelector('.tile-name').textContent = `Luồng ${id + 1}`;
      el.clients.appendChild(node);
      tile = { node, state: node.querySelector('.tile-state'), text: node.querySelector('.tile-text') };
      tiles.set(id, tile);
    }
    tile.node.dataset.state = state;
    tile.state.textContent = lag === undefined ? label : `${label} - ${lag} ms`;
    if (text !== undefined) {
      tile.text.textContent = text;
    }
  }

  function showPartial(text) {
    el.partial.textContent = text;
    el.transcript.scrollTop = el.transcript.scrollHeight;
  }

  function commitFinal(text) {
    el.partial.textContent = '';
    if (!text.trim()) {
      return;
    }
    const line = document.createElement('p');
    line.className = 'line';
    const time = document.createElement('time');
    time.textContent = clock(performance.now() - run.startedAt);
    const body = document.createElement('span');
    body.textContent = text;
    line.append(time, body);
    el.finals.appendChild(line);
    el.transcript.scrollTop = el.transcript.scrollHeight;
  }

  function handlersFor(id) {
    return {
      onTranscript(message) {
        run.recentLags.push(message.lagMs);
        if (run.recentLags.length > LAG_WINDOW) {
          run.recentLags.shift();
        }
        run.lagMax = Math.max(run.lagMax, message.lagMs);
        setTile(id, 'live', 'Đang nghe', message.text, message.lagMs);
        if (id === 0) {
          if (message.type === 'partial') {
            showPartial(message.text);
          } else {
            commitFinal(message.text);
            ribbon.tick();
          }
        }
        renderStats();
      },
      onError(message) {
        if (message.code === 'recognition_failed' || message.code === 'invalid_audio') {
          setTile(id, 'live', 'Bỏ qua một câu', message.message);
          if (id === 0) {
            setStatus(`Bỏ qua một câu: ${message.message}`, true);
          }
          return;
        }
        setTile(id, message.code === 'capacity_exceeded' ? 'refused' : 'failed', 'Bị từ chối', message.message);
      },
      onClose(event, reason) {
        if (!reason) {
          setTile(id, 'closed', 'Đã đóng');
        }
        renderStats();
        if (run.active && run.connections.every((c) => !c.ready)) {
          stop(`Mất kết nối (mã ${event.code}). Với file dài > 1 phút, hãy dùng mục "Bóc băng file âm thanh dài" ở bên trái.`, true);
        }
      },
    };
  }

  function resetView() {
    el.finals.textContent = '';
    el.partial.textContent = '';
    el.hint.textContent = '';
    el.clients.textContent = '';
    tiles.clear();
    ribbon.reset();
    Object.assign(run, { frames: 0, recentLags: [], lagMax: 0, connections: [] });
    resetUtterance();
    renderStats();
  }

  const utterance = { speaking: false, loud: 0, quiet: 0, sent: 0, preroll: [] };
  const resetUtterance = () => Object.assign(utterance, { speaking: false, loud: 0, quiet: 0, sent: 0, preroll: [] });

  function sendToAll(frame) {
    run.connections.forEach((connection) => connection.sendPcm(frame));
    run.frames++;
    utterance.sent++;
  }

  function onFrame(frame, rms) {
    ribbon.push(rms);
    const loud = rms >= SPEECH_RMS;
    if (!utterance.speaking) {
      if (!loud) {
        utterance.preroll.push(frame);
        if (utterance.preroll.length > PREROLL_FRAMES) {
          utterance.preroll.shift();
        }
        return;
      }
      utterance.speaking = true;
      utterance.preroll.forEach(sendToAll);
      utterance.preroll = [];
    }
    sendToAll(frame);
    if (loud) {
      utterance.loud++;
      utterance.quiet = 0;
    } else {
      utterance.quiet++;
    }
    const paused = utterance.quiet >= PAUSE_FRAMES;
    if ((paused && utterance.loud >= MIN_SPEECH_FRAMES) || utterance.sent >= MAX_UTTERANCE_FRAMES) {
      run.connections.forEach((connection) => connection.end());
      resetUtterance();
    } else if (paused) {
      Object.assign(utterance, { speaking: false, loud: 0, quiet: 0, preroll: [] });
    }
    if (run.frames % 5 === 0) {
      renderStats();
    }
  }

  async function start() {
    const mode = selectedSource();
    el.toggle.disabled = true;
    setStatus('Đang chuẩn bị âm thanh...');
    let source;
    try {
      if (mode === 'mic') {
        source = await openMic();
      } else {
        const file = el.file.files[0];
        if (!file) {
          throw new Error('Hãy chọn một file âm thanh trước.');
        }
        source = filePlayer(await decodeFile(file));
      }
    } catch (error) {
      setStatus(error.message || 'Không mở được nguồn âm thanh.', true);
      el.toggle.disabled = false;
      return;
    }

    resetView();
    const total = mode === 'mic' ? 1 : Math.min(64, Math.max(1, parseInt(el.count.value, 10) || 1));
    el.clients.hidden = total === 1;
    run.connections = Array.from({ length: total }, (_, id) => new SttConnection(id, handlersFor(id)));
    run.connections.forEach((_, id) => setTile(id, 'connecting', 'Đang kết nối', ''));
    setStatus(total === 1 ? 'Đang kết nối tới máy chủ...' : `Đang mở ${total} kết nối...`);

    const results = await Promise.allSettled(run.connections.map((connection) => connection.open()));
    const opened = results.filter((r) => r.status === 'fulfilled').length;
    if (opened === 0) {
      const first = results[0].reason;
      const full = first && first.code === 'capacity_exceeded';
      source.stop();
      run.connections = [];
      setStatus(full ? 'Máy chủ đang đầy, thử lại sau ít giây.' : (first && first.message) || 'Không kết nối được.', true);
      el.toggle.disabled = false;
      return;
    }

    run.active = true;
    run.source = source;
    run.startedAt = performance.now();
    el.toggle.textContent = 'Dừng luồng';
    el.toggle.classList.add('running');
    el.toggle.disabled = false;
    const refused = total - opened;
    setStatus(refused > 0
      ? `Đang chạy ${opened}/${total} kết nối, ${refused} bị từ chối vì máy chủ đầy.`
      : mode === 'mic' ? 'Đang nghe. Hãy nói.' : 'Đang phát file ở tốc độ thật...');
    source.start(onFrame, () => stop('Đã phát hết file.'));
    renderStats();
  }

  async function stop(message, isError = false) {
    if (!run.active) {
      return;
    }
    run.active = false;
    const { source, connections } = run;
    source.stop();
    el.toggle.disabled = true;
    setStatus('Đang nhận dạng câu cuối trên CPU...');
    connections.forEach((connection) => connection.end());
    const deadline = performance.now() + FINAL_TIMEOUT_MS;
    while (performance.now() < deadline && connections.some((c) => c.ready && c.pendingEnds > 0)) {
      await sleep(100);
    }
    const unanswered = connections.some((c) => c.ready && c.pendingEnds > 0);
    connections.forEach((connection) => connection.close());
    el.toggle.textContent = 'Bắt đầu luồng';
    el.toggle.classList.remove('running');
    el.toggle.disabled = false;
    if (unanswered) {
      setStatus('Đã dừng, nhưng máy chủ chưa trả kịp kết quả câu cuối.', true);
    } else {
      setStatus(message || 'Đã dừng.', isError);
    }
    renderStats();
  }

  el.toggle.addEventListener('click', () => (run.active ? stop() : start()));

  if (!window.isSecureContext) {
    const fileRadio = document.querySelector('input[name="source"][value="file"]');
    if (fileRadio) {
      fileRadio.checked = true;
    }
  }

  function syncSource() {
    const file = selectedSource() === 'file';
    el.fileOptions.hidden = !file;
    el.micNote.hidden = window.isSecureContext;
    if (!window.isSecureContext) {
      const port = location.port ? `:${location.port}` : '';
      el.micNote.textContent = `Micro trình duyệt cần HTTPS (Nginx) hoặc http://localhost${port}. Trên Wi-Fi LAN HTTP, hãy tải file trực tiếp.`;
    }
  }
  document.querySelectorAll('input[name="source"]').forEach((input) => input.addEventListener('change', syncSource));
  syncSource();

  function encodeWav(pcm) {
    const buffer = new ArrayBuffer(44 + pcm.length * 2);
    const view = new DataView(buffer);
    const text = (offset, value) => [...value].forEach((ch, i) => view.setUint8(offset + i, ch.charCodeAt(0)));
    text(0, 'RIFF');
    view.setUint32(4, 36 + pcm.length * 2, true);
    text(8, 'WAVEfmt ');
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true);
    view.setUint16(22, 1, true);
    view.setUint32(24, TARGET_RATE, true);
    view.setUint32(28, TARGET_RATE * 2, true);
    view.setUint16(32, 2, true);
    view.setUint16(34, 16, true);
    text(36, 'data');
    view.setUint32(40, pcm.length * 2, true);
    new Int16Array(buffer, 44).set(pcm);
    return buffer;
  }

  el.rest.addEventListener('click', async () => {
    const file = el.file.files[0] || el.jobFile.files[0];
    const show = (text, isError) => {
      el.restResult.textContent = text;
      el.restResult.classList.toggle('error', Boolean(isError));
    };
    if (!file) {
      document.querySelector('input[name="source"][value="file"]').click();
      show('Chọn một file âm thanh trước, rồi bấm lại nút này.', true);
      return;
    }
    el.rest.disabled = true;
    show('Đang gửi file...');
    try {
      const pcm = await decodeFile(file);
      const form = new FormData();
      form.append('file', new Blob([encodeWav(pcm)], { type: 'audio/wav' }), 'audio.wav');
      const startedAt = performance.now();
      const response = await fetch('/api/v1/stt/transcriptions', { method: 'POST', body: form, headers: authHeaders() });
      const body = await response.json().catch(() => ({}));
      if (response.ok) {
        const took = Math.round(performance.now() - startedAt);
        show(`${body.text || '(không nhận ra lời nói)'}\n${took} ms cho ${(body.durationMs / 1000).toFixed(1)} giây âm thanh.`);
      } else {
        const retry = response.headers.get('Retry-After');
        show(`${response.status} ${body.title || ''}: ${body.detail || 'Lỗi không rõ.'}${retry ? ` Thử lại sau ${retry} giây.` : ''}`, true);
      }
    } catch (error) {
      show(error.message || 'Không gửi được file.', true);
    } finally {
      el.rest.disabled = false;
    }
  });

  // =========================================================================
  // Long-audio asynchronous job pipeline (POST /api/v1/stt/jobs -> GET /jobs/{id})
  // =========================================================================
  const jobState = {
    jobId: null,
    pollTimer: null,
    startedAt: 0,
    modelsSynced: false,
    transientFailures: 0,
  };

  function setJobError(msg) {
    el.jobError.hidden = !msg;
    el.jobError.textContent = msg || '';
    el.jobError.classList.toggle('error', Boolean(msg));
  }

  function renderJob(job) {
    el.jobProgressBox.hidden = false;
    const pct = Math.max(0, Math.min(100, Math.round((job.progress || 0) * 100)));
    el.jobMeterFill.style.width = `${pct}%`;

    let stageLabel = STAGE_LABELS[job.stage] || job.stage || '';
    if (job.stage === 'TRANSCRIBING' && job.totalSegments > 0) {
      stageLabel = `Đang nhận dạng đoạn ${job.completedSegments}/${job.totalSegments} (${job.model})...`;
    } else if (job.status === 'DONE') {
      const dur = job.durationSeconds ? ` — độ dài âm thanh ${clock(job.durationSeconds * 1000)}` : '';
      stageLabel = `Hoàn tất (${job.model}${job.refine ? ' + Qwen3-4B' : ''})${dur}`;
    }

    const elapsed = clock(performance.now() - jobState.startedAt);
    el.jobStageText.textContent = stageLabel;
    el.jobMetaText.textContent = `${pct}% · ${elapsed}`;

    const segments = Array.isArray(job.segments) ? job.segments : [];
    const hasText = Boolean((job.text && job.text.trim()) || (job.rawText && job.rawText.trim()) || segments.length);

    if (hasText || job.status === 'DONE') {
      el.jobOutput.hidden = false;
      const displayMain = job.text || job.rawText || '(Không phát hiện lời nói trong file âm thanh)';
      el.jobText.textContent = displayMain;
      el.jobCopy.hidden = !(job.text || job.rawText);

      const showRaw = Boolean(job.refine && job.rawText && job.text && job.rawText.trim() !== job.text.trim());
      el.jobRawWrap.hidden = !showRaw;
      if (showRaw) {
        el.jobRawText.textContent = job.rawText;
      }

      el.jobSegmentsWrap.hidden = segments.length === 0;
      if (segments.length > 0) {
        el.jobSegmentsSummary.textContent = `Các phân đoạn theo mốc thời gian (${segments.length} đoạn)`;
        el.jobSegments.textContent = '';
        segments.forEach((seg) => {
          const row = document.createElement('div');
          row.className = 'seg-row';
          const time = document.createElement('span');
          time.className = 'seg-time';
          time.textContent = `${formatTimestamp(seg.start)} → ${formatTimestamp(seg.end)}`;
          const body = document.createElement('span');
          body.className = 'seg-body';
          body.textContent = seg.text || '';
          row.append(time, body);
          el.jobSegments.appendChild(row);
        });
      }
    }

    if (job.status === 'FAILED') {
      setJobError(job.error || 'Tác vụ xử lý thất bại trên máy chủ.');
    } else if (job.status === 'CANCELLED') {
      setJobError('Tác vụ đã bị hủy.');
    }
  }

  function finishJobPolling() {
    if (jobState.pollTimer) {
      clearTimeout(jobState.pollTimer);
      jobState.pollTimer = null;
    }
    jobState.jobId = null;
    jobState.transientFailures = 0;
    el.jobSubmit.disabled = false;
    el.jobCancel.hidden = true;
    el.jobCancel.disabled = false;
  }

  async function pollJob(jobId) {
    if (jobState.jobId !== jobId) {
      return;
    }
    try {
      const response = await fetch(`/api/v1/stt/jobs/${encodeURIComponent(jobId)}`, {
        cache: 'no-store',
        headers: authHeaders(),
      });
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.detail || `Không đọc được trạng thái tác vụ (HTTP ${response.status}).`);
      }
      jobState.transientFailures = 0;
      setJobError('');
      const job = await response.json();
      renderJob(job);
      if (job.status === 'DONE' || job.status === 'FAILED' || job.status === 'CANCELLED') {
        finishJobPolling();
        return;
      }
    } catch (error) {
      jobState.transientFailures++;
      if (jobState.transientFailures >= 10) {
        setJobError(error.message || 'Mất kết nối lâu với máy chủ khi kiểm tra tiến độ.');
      }
    }
    if (jobState.jobId === jobId) {
      jobState.pollTimer = setTimeout(() => pollJob(jobId), JOB_POLL_MS);
    }
  }

  el.jobSubmit.addEventListener('click', async () => {
    const file = el.jobFile.files[0] || el.file.files[0];
    if (!file) {
      setJobError('Vui lòng chọn file âm thanh cần bóc băng trước.');
      el.jobFile.focus();
      return;
    }
    setJobError('');
    el.jobSubmit.disabled = true;
    el.jobCopy.hidden = true;
    el.jobOutput.hidden = true;
    el.jobProgressBox.hidden = false;
    el.jobMeterFill.style.width = '2%';
    el.jobStageText.textContent = `Đang tải file "${file.name}" (${(file.size / (1024 * 1024)).toFixed(1)} MB) lên board...`;
    el.jobMetaText.textContent = '0%';
    jobState.startedAt = performance.now();
    jobState.transientFailures = 0;

    try {
      const form = new FormData();
      form.append('file', file, file.name);
      form.append('model', el.jobModel.value);
      form.append('refine', String(el.jobRefine.checked));

      const response = await fetch('/api/v1/stt/jobs', {
        method: 'POST',
        body: form,
        headers: authHeaders(),
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) {
        throw new Error(body.detail || `${response.status} ${body.title || 'Không tạo được tác vụ.'}`);
      }
      jobState.jobId = body.jobId;
      el.jobCancel.hidden = false;
      el.jobCancel.disabled = false;
      renderJob(body);
      jobState.pollTimer = setTimeout(() => pollJob(body.jobId), 600);
    } catch (error) {
      setJobError(error.message || 'Không gửi được file lên máy chủ.');
      finishJobPolling();
    }
  });

  el.jobCancel.addEventListener('click', async () => {
    const id = jobState.jobId;
    if (!id) {
      return;
    }
    el.jobCancel.disabled = true;
    try {
      await fetch(`/api/v1/stt/jobs/${encodeURIComponent(id)}`, {
        method: 'DELETE',
        headers: authHeaders(),
      });
    } catch (error) {
      // Next poll or local cleanup handles state.
    }
    setJobError('Đã gửi yêu cầu hủy tác vụ.');
    finishJobPolling();
  });

  el.jobCopy.addEventListener('click', async () => {
    const text = el.jobText.textContent || '';
    if (!text) {
      return;
    }
    try {
      await navigator.clipboard.writeText(text);
      const prev = el.jobCopy.textContent;
      el.jobCopy.textContent = 'Đã sao chép!';
      setTimeout(() => { el.jobCopy.textContent = prev; }, 1500);
    } catch (error) {
      const range = document.createRange();
      range.selectNodeContents(el.jobText);
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
    }
  });

  function syncModelsFromStatus(status) {
    if (jobState.modelsSynced || !Array.isArray(status.models) || status.models.length === 0) {
      return;
    }
    jobState.modelsSynced = true;
    el.jobModel.textContent = '';
    status.models.forEach((m) => {
      const opt = document.createElement('option');
      opt.value = m;
      opt.textContent = MODEL_LABELS[m] || m;
      if (m === status.defaultModel) {
        opt.selected = true;
      }
      el.jobModel.appendChild(opt);
    });
    if (typeof status.refinerEnabled === 'boolean') {
      el.jobRefine.checked = status.refinerEnabled;
      el.jobRefine.disabled = !status.refinerEnabled;
    }
  }

  async function pollStatus() {
    try {
      const response = await fetch('/api/v1/stt/status', { cache: 'no-store', headers: authHeaders() });
      if (response.status === 401) {
        el.serverEngine.textContent = 'Máy chủ cần khóa API';
        el.serverLoad.textContent = '';
        setTimeout(pollStatus, STATUS_POLL_MS);
        return;
      }
      const status = await response.json();
      syncModelsFromStatus(status);
      const { activeSessions, maxSessions, workers, queuedTasks } = status.capacity;
      el.serverEngine.textContent = `Engine ${status.engine}`;
      el.serverLoad.textContent = status.ready
        ? `${activeSessions}/${maxSessions} luồng, ${workers} worker, chờ ${queuedTasks}`
        : `Đầy ${activeSessions}/${maxSessions}`;
      el.meterFill.style.width = `${Math.min(100, (activeSessions / Math.max(1, maxSessions)) * 100)}%`;
      el.server.classList.toggle('full', !status.ready);
    } catch (error) {
      el.serverEngine.textContent = 'Không liên lạc được máy chủ';
      el.serverLoad.textContent = '';
    }
    setTimeout(pollStatus, STATUS_POLL_MS);
  }
  pollStatus();
})();
