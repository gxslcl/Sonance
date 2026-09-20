// 装包 + 授权 + 预置歌词缓存，然后把 App 拉起来
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const ADB = 'C:/Android/Sdk/platform-tools/adb.exe';
const APK = 'C:/Users/1/WorkBuddy/2026-09-19-18-01-53/Sonance/app/build/outputs/apk/debug/app-debug.apk';
const PKG = 'com.sonance.player';
const SEED = 'lyricstest';
const CACHE = `/data/data/${PKG}/files/lyrics_cache`;

function run(args, label) {
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

console.log('--- install ---');
console.log(run(['install', '-r', '-t', APK]).trim());

console.log('--- grant ---');
for (const p of ['android.permission.READ_MEDIA_AUDIO', 'android.permission.POST_NOTIFICATIONS']) {
  console.log('  ' + run(['shell', 'pm', 'grant', PKG, p]).trim());
}

console.log('--- adb root ---');
console.log(run(['root']).trim() || '(already root)');
run(['wait-for-device']);

console.log('--- seed lyrics cache ---');
run(['shell', 'mkdir', '-p', CACHE]);
for (const f of fs.readdirSync(SEED)) {
  const src = path.resolve(SEED, f);
  const out = run(['push', src, `${CACHE}/${f}`]);
  console.log('  ' + f.slice(0, 12) + '…  ' + out.split('\n').filter(Boolean).pop());
}

console.log('--- verify ---');
console.log(run(['shell', 'ls', '-la', CACHE]).trim());

console.log('--- start ---');
run(['shell', 'am', 'start', '-n', `${PKG}/.MainActivity`]);
console.log('started');
