(() => {
  'use strict';

  const TARGET_RATE = 16000;
  const FRAME_SAMPLES = 1600; // 100 ms
  const FRAME_MS = 100;
  const STATUS_POLL_MS = 1500;
  // Engines that answer only after an end command (the native one) need the end sent when the speaker pauses,
  // and a long wait for the last answer after Stop.
  const FINAL_TIMEOUT_MS = 30000;
  const SPEECH_RMS = 0.015; // frame loudness that counts as speech
  const PAUSE_FRAMES = 8; // 0.8 s of quiet closes an utterance
  const MIN_SPEECH_FRAMES = 3;
  const PREROLL_FRAMES = 3; // 0.3 s kept from before the first loud frame, so the start of a word is not clipped
  const MAX_UTTERANCE_FRAMES = 250; // 25 s of audio sent since the last end, well inside the server's 60 s limit

  const $ = (id) => document.getElementById(id);
  const el = {
    ribbon: $('ribbon'), transcript: $('transcript'), finals: $('finals'), partial: $('partial'), hint: $('hint'),
    toggle: $('toggle'), status: $('status'), micNote: $('micNote'),
    fileOptions: $('fileOptions'), file: $('file'), count: $('count'), apiKey: $('apiKey'),
    statLag: $('statLag'), statLagMax: $('statLagMax'), statFrames: $('statFrames'), statOpen: $('statOpen'),
    clients: $('clients'), rest: $('rest'), restResult: $('restResult'),
    server: $('server'), serverEngine: $('serverEngine'), serverLoad: $('serverLoad'), meterFill: $('meterFill'),
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
          // Every end is answered by a final or by recognition_failed. A streaming engine can also send finals nobody
          // asked for; those cannot be told apart here, so the count never goes below zero.
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
      throw new Error('Trình duyệt chỉ cho dùng micro trên https hoặc localhost. Hãy chọn File âm thanh.');
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
    const total = Math.floor(ms / 1000);
    return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
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
          stop(`Mất kết nối (mã ${event.code}).`, true);
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

  /**
   * Audio goes to the server only while someone is speaking. Quiet before the first word is not sent: a minute of
   * silence would otherwise fill the server's utterance limit before the speaker says anything. An utterance is
   * closed with end when the speaker pauses, or when 25 s of audio has been sent since the last end.
   */
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
      // A burst too short to be speech: stop streaming, but keep the sent count so the server-side total stays bounded.
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
    el.toggle.textContent = 'Dừng';
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
    setStatus('Đang nhận dạng câu cuối...');
    connections.forEach((connection) => connection.end());
    const deadline = performance.now() + FINAL_TIMEOUT_MS;
    while (performance.now() < deadline && connections.some((c) => c.ready && c.pendingEnds > 0)) {
      await sleep(100);
    }
    const unanswered = connections.some((c) => c.ready && c.pendingEnds > 0);
    connections.forEach((connection) => connection.close());
    el.toggle.textContent = 'Bắt đầu';
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

  function syncSource() {
    const file = selectedSource() === 'file';
    el.fileOptions.hidden = !file;
    el.micNote.hidden = file || window.isSecureContext;
    if (!window.isSecureContext) {
      el.micNote.textContent = 'Micro cần https hoặc localhost. Mở trang này bằng http://localhost:8080 hoặc dùng File âm thanh.';
    }
  }
  document.querySelectorAll('input[name="source"]').forEach((input) => input.addEventListener('change', syncSource));
  syncSource();

  /** The same file, sent whole to the REST endpoint as a 16 kHz mono WAV. */
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
    const file = el.file.files[0];
    const show = (text, isError) => {
      el.restResult.textContent = text;
      el.restResult.classList.toggle('error', Boolean(isError));
    };
    if (!file) {
      document.querySelector('input[name="source"][value="file"]').click();
      show('Chọn một file ở mục Nguồn âm thanh, rồi bấm lại nút này.', true);
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
      const { activeSessions, maxSessions, workers, queuedTasks } = status.capacity;
      el.serverEngine.textContent = `Engine ${status.engine}`;
      el.serverLoad.textContent = status.ready
        ? `${activeSessions}/${maxSessions} luồng, ${workers} luồng xử lý, hàng đợi ${queuedTasks}`
        : `Đầy ${activeSessions}/${maxSessions}`;
      el.meterFill.style.width = `${Math.min(100, (activeSessions / maxSessions) * 100)}%`;
      el.server.classList.toggle('full', !status.ready);
    } catch (error) {
      el.serverEngine.textContent = 'Không liên lạc được máy chủ';
      el.serverLoad.textContent = '';
    }
    setTimeout(pollStatus, STATUS_POLL_MS);
  }
  pollStatus();
})();
