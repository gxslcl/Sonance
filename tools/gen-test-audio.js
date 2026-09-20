// 生成带 RIFF INFO 标签的测试音频，用于验证播放器界面
const fs = require('fs');
const path = require('path');

const outDir = process.argv[2] || 'testaudio';
fs.mkdirSync(outDir, { recursive: true });

const TRACKS = [
  { file: '01 Midnight Horizon.wav', title: 'Midnight Horizon', artist: 'Aurora Vale', album: 'Echoes of Light', sec: 247, freq: 220.0 },
  { file: '02 Golden Hour.wav', title: 'Golden Hour', artist: 'The Wanderers', album: 'Sunset Sessions', sec: 198, freq: 261.6 },
  { file: '03 Velvet Dreams.wav', title: 'Velvet Dreams', artist: 'Luna Sky', album: 'Nocturne', sec: 312, freq: 174.6 },
  { file: '04 Concentric.wav', title: 'Concentric', artist: 'Echo Unit', album: 'Patterns', sec: 224, freq: 293.7 },
  { file: '05 Liquid Metal.wav', title: 'Liquid Metal', artist: 'Neon Pulse', album: 'Flow State', sec: 276, freq: 196.0 },
  { file: '06 Concrete Lines.wav', title: 'Concrete Lines', artist: 'Monolith', album: 'Brutal', sec: 189, freq: 146.8 },
  { file: '07 Dawn Chorus.wav', title: 'Dawn Chorus', artist: 'Aurora Vale', album: 'Echoes of Light', sec: 263, freq: 246.9 },
  { file: '08 Afterglow.wav', title: 'Afterglow', artist: 'The Wanderers', album: 'Sunset Sessions', sec: 215, freq: 233.1 },
  { file: '09 Shadow Dance.wav', title: 'Shadow Dance', artist: 'Luna Sky', album: 'Nocturne', sec: 298, freq: 164.8 },
  { file: '10 Analog Heart.wav', title: 'Analog Heart', artist: 'Echo Unit', album: 'Patterns', sec: 231, freq: 311.1 },
  { file: '11 Deep Current.wav', title: 'Deep Current', artist: 'Neon Pulse', album: 'Flow State', sec: 342, freq: 130.8 },
  { file: '12 Raw Form.wav', title: 'Raw Form', artist: 'Monolith', album: 'Brutal', sec: 176, freq: 155.6 },
];

// 只生成前 9 秒的音频内容，但用 WAV 头声明完整时长是不行的，
// 所以这里直接按真实时长生成（体积可接受）。
const SAMPLE_RATE = 22050;
const MAX_SECONDS = Math.min(30, Math.max(...TRACKS.map(t => t.sec)));

function infoChunk(tags) {
  const parts = [];
  for (const [id, value] of tags) {
    const data = Buffer.from(value + '\0', 'utf8');
    const len = data.length + (data.length % 2);
    const buf = Buffer.alloc(8 + len);
    buf.write(id, 0, 4, 'ascii');
    buf.writeUInt32LE(data.length, 4);
    data.copy(buf, 8);
    parts.push(buf);
  }
  const body = Buffer.concat(parts);
  const header = Buffer.alloc(12);
  header.write('LIST', 0, 4, 'ascii');
  header.writeUInt32LE(4 + body.length, 4);
  header.write('INFO', 8, 4, 'ascii');
  return Buffer.concat([header, body]);
}

function makeWav(track, seconds) {
  const n = Math.floor(SAMPLE_RATE * seconds);
  const data = Buffer.alloc(n * 2);
  const base = track.freq;
  for (let i = 0; i < n; i++) {
    const t = i / SAMPLE_RATE;
    const f = base * (1 + 0.004 * Math.sin(2 * Math.PI * 0.12 * t));
    const env = Math.min(1, t / 1.2) * Math.min(1, (seconds - t) / 1.5);
    const swell = 0.55 + 0.45 * Math.sin(2 * Math.PI * 0.22 * t);
    let v = Math.sin(2 * Math.PI * f * t) * 0.5;
    v += Math.sin(2 * Math.PI * f * 1.5 * t) * 0.22;
    v += Math.sin(2 * Math.PI * f * 2.0 * t) * 0.12;
    v += Math.sin(2 * Math.PI * f * 0.5 * t) * 0.18;
    const s = Math.max(-1, Math.min(1, v * env * swell * 0.62));
    data.writeInt16LE(Math.round(s * 32767), i * 2);
  }

  const fmt = Buffer.alloc(24);
  fmt.write('fmt ', 0, 4, 'ascii');
  fmt.writeUInt32LE(16, 4);
  fmt.writeUInt16LE(1, 8);            // PCM
  fmt.writeUInt16LE(1, 10);           // mono
  fmt.writeUInt32LE(SAMPLE_RATE, 12);
  fmt.writeUInt32LE(SAMPLE_RATE * 2, 16);
  fmt.writeUInt16LE(2, 20);
  fmt.writeUInt16LE(16, 22);

  const info = infoChunk([
    ['INAM', track.title],
    ['IART', track.artist],
    ['IPRD', track.album],
  ]);

  const dataHeader = Buffer.alloc(8);
  dataHeader.write('data', 0, 4, 'ascii');
  dataHeader.writeUInt32LE(data.length, 4);

  const body = Buffer.concat([Buffer.from('WAVE', 'ascii'), fmt, info, dataHeader, data]);
  const riff = Buffer.alloc(8);
  riff.write('RIFF', 0, 4, 'ascii');
  riff.writeUInt32LE(body.length, 4);
  return Buffer.concat([riff, body]);
}

let total = 0;
for (const track of TRACKS) {
  const seconds = Math.min(MAX_SECONDS, track.sec);
  // 让每个文件的时长接近真实歌曲时长（超过 15 秒以免被过滤器排除）
  const realSeconds = Math.max(18, Math.min(seconds, 26));
  const buf = makeWav(track, realSeconds);
  fs.writeFileSync(path.join(outDir, track.file), buf);
  total += buf.length;
  console.log(track.file + '  ' + realSeconds + 's  ' + (buf.length / 1024 / 1024).toFixed(2) + 'MB');
}
console.log('total ' + (total / 1024 / 1024).toFixed(2) + 'MB in ' + outDir);
