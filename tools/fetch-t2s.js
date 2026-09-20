// 拉取 OpenCC 的繁→简单字映射表，作为内置资源
const https = require('https');
const fs = require('fs');
const path = require('path');

const SOURCES = [
  'https://raw.githubusercontent.com/BYVoid/OpenCC/master/data/dictionary/TSCharacters.txt',
  'https://cdn.jsdelivr.net/gh/BYVoid/OpenCC@master/data/dictionary/TSCharacters.txt',
];

function get(url) {
  return new Promise((resolve) => {
    const req = https.get(url, { timeout: 25000, headers: { 'User-Agent': 'node' } }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        resolve(get(res.headers.location));
        return;
      }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ ok: res.statusCode === 200, status: res.statusCode, body: Buffer.concat(chunks) }));
    });
    req.on('error', (e) => resolve({ ok: false, error: e.message }));
    req.on('timeout', () => { req.destroy(); resolve({ ok: false, error: 'timeout' }); });
  });
}

(async () => {
  for (const url of SOURCES) {
    const r = await get(url);
    if (!r.ok) {
      console.log(`FAIL ${url}  ${r.status || r.error}`);
      continue;
    }
    const text = r.body.toString('utf8');
    const lines = text.split('\n').map((l) => l.trim()).filter(Boolean);

    // 格式：繁<TAB>简1 简2 ...  取第一个候选；跳过注释
    const pairs = [];
    for (const line of lines) {
      if (line.startsWith('#')) continue;
      const parts = line.split(/\s+/);
      if (parts.length < 2) continue;
      const from = parts[0];
      const to = parts[1];
      if (from.length !== 1 || to.length !== 1) continue;
      if (from === to) continue;
      pairs.push([from, to]);
    }

    if (pairs.length < 1000) {
      console.log(`SUSPICIOUS: only ${pairs.length} pairs from ${url}`);
      continue;
    }

    const outDir = path.join('app', 'src', 'main', 'res', 'raw');
    fs.mkdirSync(outDir, { recursive: true });
    const out = path.join(outDir, 't2s.txt');
    fs.writeFileSync(out, pairs.map((p) => p[0] + p[1]).join('\n'), 'utf8');
    console.log(`OK ${url}`);
    console.log(`   lines=${lines.length}  pairs=${pairs.length}`);
    console.log(`   -> ${out}  (${(fs.statSync(out).size / 1024).toFixed(1)} KB)`);
    console.log('   sample: ' + pairs.slice(0, 8).map((p) => p[0] + '→' + p[1]).join('  '));
    return;
  }
  console.log('ALL SOURCES FAILED');
  process.exitCode = 1;
})();
