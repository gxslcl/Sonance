// 卸载后装 release 包，重新预置歌词缓存，验证 R8 混淆后功能完好
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const ADB = 'C:/Android/Sdk/platform-tools/adb.exe';
const APK = 'C:/Users/1/WorkBuddy/2026-09-19-18-01-53/Sonance/app/build/outputs/apk/release/app-release.apk';
const PKG = 'com.sonance.player';
const CACHE = `/data/data/${PKG}/files/lyrics_cache`;

function run(args) {
  for (let i = 0; i < 5; i++) {
    try {
      return execFileSync(ADB, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (e) {
      const m = String(e.stdout || '') + String(e.stderr || '');
      if (/offline|no devices|daemon not running|unauthorized/.test(m)) {
        try { execFileSync(ADB, ['start-server'], { stdio: 'ignore' }); } catch (_) { /* ignore */ }
        continue;
      }
      return 'ERR ' + m;
    }
  }
  return 'ERR retries exhausted';
}

console.log('uninstall: ' + run(['uninstall', PKG]).trim());
console.log('install  : ' + run(['install', '-r', '-t', APK]).trim());

for (const p of ['android.permission.READ_MEDIA_AUDIO', 'android.permission.POST_NOTIFICATIONS']) {
  run(['shell', 'pm', 'grant', PKG, p]);
}
console.log('granted  : ok');

run(['shell', 'run-as', PKG, 'mkdir', '-p', CACHE]);
let ok = 0;
for (const f of fs.readdirSync('lyricstest')) {
  const tmp = '/data/local/tmp/' + f;
  run(['push', path.resolve('lyricstest', f), tmp]);
  run(['shell', 'chmod', '666', tmp]);
  const r = run(['shell', 'run-as', PKG, 'cp', tmp, `${CACHE}/${f}`]);
  if (!r.startsWith('ERR')) ok++;
}
console.log(`cache    : ${ok}/12`);

run(['shell', 'am', 'start', '-n', `${PKG}/.MainActivity`]);
console.log('started  : release build');
