// 装包 + 打开「在线封面 / 在线歌词」开关，方便在模拟器上看效果
const { execFileSync } = require('child_process');
const fs = require('fs');

const ADB = 'C:/Android/Sdk/platform-tools/adb.exe';
const APK = 'C:/Users/1/WorkBuddy/2026-09-19-18-01-53/Sonance/app/build/outputs/apk/debug/app-debug.apk';
const PKG = 'com.sonance.player';
const XML = `/data/data/${PKG}/shared_prefs/sonance_prefs.xml`;

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

console.log('install  : ' + run(['install', '-r', '-t', APK]).trim());
for (const p of ['android.permission.READ_MEDIA_AUDIO', 'android.permission.POST_NOTIFICATIONS']) {
  run(['shell', 'pm', 'grant', PKG, p]);
}
run(['shell', 'am', 'force-stop', PKG]);
run(['shell', 'run-as', PKG, 'mkdir', '-p', `/data/data/${PKG}/shared_prefs`]);

let xml = run(['shell', 'run-as', PKG, 'cat', XML]);
if (typeof xml !== 'string' || !xml.includes('<map')) {
  xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n";
}

const want = [
  ['online_cover_enabled', 'boolean', 'true'],
  ['online_lyrics_enabled', 'boolean', 'true'],
  ['glass_style', 'int', '4'],        // 4 = LIQUID
  ['glass_blur_dp', 'int', '48'],
  ['glass_visibility', 'int', '92'],
];

for (const [name, type, value] of want) {
  const re = new RegExp(`<${type} name="${name}" value="[^"]*" />`);
  if (re.test(xml)) {
    xml = xml.replace(re, `<${type} name="${name}" value="${value}" />`);
  } else {
    xml = xml.replace('</map>', `    <${type} name="${name}" value="${value}" />\n</map>`);
  }
}

const tmp = '/data/local/tmp/sonance_prefs2.xml';
fs.writeFileSync('.prefs_tmp2.xml', xml, 'utf8');
run(['push', '.prefs_tmp2.xml', tmp]);
run(['shell', 'chmod', '666', tmp]);
console.log('prefs    : ' + run(['shell', 'run-as', PKG, 'cp', tmp, XML]).trim());
fs.rmSync('.prefs_tmp2.xml', { force: true });

const check = run(['shell', 'run-as', PKG, 'cat', XML]);
for (const [name] of want) {
  const m = check.match(new RegExp(`name="${name}" value="([^"]*)"`));
  console.log(`  ${name} = ${m ? m[1] : '(缺失)'}`);
}

run(['shell', 'am', 'start', '-n', `${PKG}/.MainActivity`]);
console.log('started');
