// 把 lyricstest/ 下的歌词缓存推到 App 私有目录（走 run-as）
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const ADB = 'C:/Android/Sdk/platform-tools/adb.exe';
const PKG = 'com.sonance.player';
const CACHE = `/data/data/${PKG}/files/lyrics_cache`;
const SRC = 'lyricstest';

function run(args) {
  for (let i = 0; i < 5; i++) {
    try {
      return execFileSync(ADB, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (e) {
      const m = (e.stdout || '') + (e.stderr || '');
      if (/offline|no devices|daemon not running|unauthorized/.test(m)) {
        try { execFileSync(ADB, ['start-server'], { stdio: 'ignore' }); } catch (_) { /* ignore */ }
        continue;
      }
      return 'ERR ' + m;
    }
  }
  return 'ERR retries exhausted';
}

run(['shell', 'run-as', PKG, 'mkdir', '-p', CACHE]);

let ok = 0;
for (const f of fs.readdirSync(SRC)) {
  const tmp = '/data/local/tmp/' + f;
  run(['push', path.resolve(SRC, f), tmp]);
  run(['shell', 'chmod', '666', tmp]);
  const r = run(['shell', 'run-as', PKG, 'cp', tmp, `${CACHE}/${f}`]);
  if (!r.startsWith('ERR')) ok++;
}
console.log(`pushed ${ok}/${fs.readdirSync(SRC).length}`);
console.log(run(['shell', 'run-as', PKG, 'ls', CACHE]).trim().split('\n').length + ' files in cache');
