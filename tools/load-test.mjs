// Thử tải: mở N kết nối WebSocket, mỗi kết nối phát âm thanh im lặng ở tốc độ thật rồi in phân vị lagMs.
// Cần Node 22 trở lên (có WebSocket sẵn). Cách dùng: node tools/load-test.mjs [host:port] [số kết nối] [số giây]
const base = process.argv[2] ?? 'localhost:8080';
const clients = Number(process.argv[3] ?? 24);
const seconds = Number(process.argv[4] ?? 10);
const frame = new Int16Array(1600); // 100 ms ở 16 kHz

function run(id) {
  return new Promise((resolve) => {
    const ws = new WebSocket(`ws://${base}/ws/stt?sampleRate=16000`);
    ws.binaryType = 'arraybuffer';
    const result = { id, lags: [], finals: 0, error: null, closeCode: null };
    let timer;
    ws.onmessage = (event) => {
      const message = JSON.parse(event.data);
      if (message.type === 'ready') {
        let sent = 0;
        timer = setInterval(() => {
          ws.send(frame);
          if (++sent >= seconds * 10) {
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
const refused = results.filter((r) => r.error === 'capacity_exceeded').length;

console.log(`${clients} kết nối, ${seconds} giây âm thanh mỗi kết nối, mất ${((Date.now() - startedAt) / 1000).toFixed(1)} giây`);
console.log(`lagMs: p50=${percentile(0.5)} p95=${percentile(0.95)} p99=${percentile(0.99)} max=${lags[lags.length - 1]}`);
console.log(`bị từ chối vì đầy: ${refused}, lỗi khác: ${results.filter((r) => r.error && r.error !== 'capacity_exceeded').length}`);
