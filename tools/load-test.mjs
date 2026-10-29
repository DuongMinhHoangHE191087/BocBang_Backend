// Thử tải: mở N kết nối WebSocket, mỗi kết nối phát âm thanh ở tốc độ thật rồi in phân vị lagMs.
// Cần Node 22 trở lên (có WebSocket sẵn).
// Cách dùng: node tools/load-test.mjs [host:port] [số kết nối] [số giây] [file.wav] [--end-every=giây] [--api-key=khóa]
// --end-every: gửi lệnh end sau mỗi chừng ấy giây âm thanh. Cần với engine chỉ trả văn bản sau end (engine jni):
//   không có nó, kết nối chỉ nhận một kết quả ở cuối và phân vị lagMs gần như vô nghĩa.
// --api-key: khóa API khi máy chủ bật xác thực (gửi qua ?apiKey= vì WebSocket của Node không đặt được header).
// Không có file.wav thì phát im lặng (nhẹ hơn nhiều so với tiếng nói thật). File phải là WAV 16 kHz mono 16-bit;
// mỗi kết nối bắt đầu ở một vị trí khác nhau trong file và lặp lại khi hết.
import { readFileSync } from 'node:fs';

const flags = Object.fromEntries(process.argv.slice(2)
  .filter((arg) => arg.startsWith('--'))
  .map((arg) => { const [name, ...value] = arg.slice(2).split('='); return [name, value.join('=')]; }));
const positional = process.argv.slice(2).filter((arg) => !arg.startsWith('--'));
const base = positional[0] ?? 'localhost:8080';
const clients = Number(positional[1] ?? 24);
const seconds = Number(positional[2] ?? 10);
const wavPath = positional[3];
const endEvery = Number(flags['end-every'] ?? 0);
const apiKey = flags['api-key'];
const FRAME_SAMPLES = 1600; // 100 ms ở 16 kHz

function readWav(path) {
  const buf = readFileSync(path);
  let offset = 12;
  let format = null;
  while (offset + 8 <= buf.length) {
    const id = buf.toString('ascii', offset, offset + 4);
    const size = buf.readUInt32LE(offset + 4);
    if (id === 'fmt ') {
      format = { channels: buf.readUInt16LE(offset + 10), rate: buf.readUInt32LE(offset + 12), bits: buf.readUInt16LE(offset + 22) };
    } else if (id === 'data') {
      if (!format || format.rate !== 16000 || format.channels !== 1 || format.bits !== 16) {
        throw new Error('File phải là WAV 16 kHz, mono, 16-bit');
      }
      const bytes = buf.subarray(offset + 8, offset + 8 + size);
      return new Int16Array(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.length - (bytes.length % 2)));
    }
    offset += 8 + size + (size & 1);
  }
  throw new Error('Không tìm thấy dữ liệu âm thanh trong file WAV');
}

const audio = wavPath ? readWav(wavPath) : new Int16Array(FRAME_SAMPLES);
const frames = Math.max(1, Math.floor(audio.length / FRAME_SAMPLES));

function run(id) {
  return new Promise((resolve) => {
    const key = apiKey ? `&apiKey=${encodeURIComponent(apiKey)}` : '';
    const ws = new WebSocket(`ws://${base}/ws/stt?sampleRate=16000${key}`);
    ws.binaryType = 'arraybuffer';
    const result = { id, lags: [], finals: 0, error: null, closeCode: null };
    // Spread the clients over the file so they do not all decode the same words at the same moment.
    const first = (id * 7) % frames;
    let timer;
    ws.onmessage = (event) => {
      const message = JSON.parse(event.data);
      if (message.type === 'ready') {
        let sent = 0;
        timer = setInterval(() => {
          const start = ((first + sent) % frames) * FRAME_SAMPLES;
          ws.send(audio.subarray(start, start + FRAME_SAMPLES));
          sent++;
          if (endEvery > 0 && sent < seconds * 10 && sent % (endEvery * 10) === 0) {
            ws.send('{"type":"end"}');
          }
          if (sent >= seconds * 10) {
            clearInterval(timer);
            ws.send('{"type":"end"}');
            setTimeout(() => ws.close(1000), 600);
          }
        }, 100);
      } else if (message.type === 'error') {
        result.error = message.code;
      } else {
        result.lags.push(message.lagMs);
        if (message.type === 'final') result.finals++;
      }
    };
    ws.onclose = (event) => {
      clearInterval(timer);
      result.closeCode = event.code;
      resolve(result);
    };
    ws.onerror = () => {};
  });
}

const startedAt = Date.now();
const results = await Promise.all(Array.from({ length: clients }, (_, id) => run(id)));
const lags = results.flatMap((r) => r.lags).sort((a, b) => a - b);
const percentile = (p) => lags[Math.min(lags.length - 1, Math.floor(lags.length * p))];
const errors = {};
results.forEach((r) => {
  // Our own close is 1000; anything else (a refused handshake is 1006) means the server dropped or refused us.
  const reason = r.error ?? (r.closeCode === 1000 ? null : `đóng với mã ${r.closeCode}`);
  if (reason) errors[reason] = (errors[reason] ?? 0) + 1;
});

console.log(`${clients} kết nối, ${seconds} giây âm thanh mỗi kết nối, mất ${((Date.now() - startedAt) / 1000).toFixed(1)} giây`);
console.log(`số kết quả nhận được: ${lags.length}${lags.length < clients * 2 && !endEvery && !Object.keys(errors).length ? ' (ít: engine chỉ trả sau end, thử --end-every=8)' : ''}`);
console.log(`lagMs: p50=${percentile(0.5)} p95=${percentile(0.95)} p99=${percentile(0.99)} max=${lags[lags.length - 1]}`);
console.log(`kết nối bị từ chối hoặc ngắt: ${Object.keys(errors).length ? JSON.stringify(errors) : 'không'}`);
console.log('(capacity_exceeded: máy chủ đã đầy lúc vào; overloaded: bị ngắt giữa chừng vì tụt quá xa thời gian thực)');
