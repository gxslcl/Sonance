// 生成一份「繁体 + 带时间轴」的测试歌词，并按 App 的缓存 key 规则命名
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const OUT = 'lyricstest';
fs.mkdirSync(OUT, { recursive: true });

const API = 'https://api.lrc.cx/lyrics';

// 与 LyricsCache.keyOf / MediaRepository.sanitize 保持一致
function keyOf(title, artist, apiBase) {
  const t = title.trim().toLowerCase();
  const a = artist.trim().toLowerCase();
  const salt = apiBase.trim().toLowerCase();
  return crypto.createHash('sha1').update(`${salt}|${t}|${a}`).digest('hex');
}

const LRC = [
  '[ti:測試歌曲]',
  '[ar:未知藝術家]',
  '[al:測試專輯]',
  '[00:00.00]這是一首測試用的歌',
  '[00:04.00]歌詞來源是繁體版本',
  '[00:08.00]需要自動轉成簡體顯示',
  '[00:12.00]時間軸要能跟著播放跑',
  '[00:16.00]當前這一行會高亮',
  '[00:20.00]字號也要可以調整',
  '[00:24.00]背景是毛玻璃效果',
  '',
].join('\n');

const entries = [
  ['01 Midnight Horizon', '未知艺术家'],
  ['02 Golden Hour', '未知艺术家'],
  ['03 Velvet Dreams', '未知艺术家'],
  ['04 Concentric', '未知艺术家'],
  ['05 Liquid Metal', '未知艺术家'],
  ['06 Concrete Lines', '未知艺术家'],
  ['07 Dawn Chorus', '未知艺术家'],
  ['08 Afterglow', '未知艺术家'],
  ['09 Shadow Dance', '未知艺术家'],
  ['10 Analog Heart', '未知艺术家'],
  ['11 Deep Current', '未知艺术家'],
  ['12 Raw Form', '未知艺术家'],
];

for (const [title, artist] of entries) {
  const key = keyOf(title, artist, API);
  const file = path.join(OUT, key + '.lrc');
  fs.writeFileSync(file, LRC, 'utf8');
  console.log(`${title}  ->  ${key}`);
}
console.log('');
console.log('输出目录: ' + OUT);
console.log('歌词预览:');
console.log(LRC.split('\n').slice(3, 7).join('\n'));
