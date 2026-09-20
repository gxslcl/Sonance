// 直接改 SharedPreferences 打开「在线歌词」，省去在 UI 里翻设置页
const { execFileSync } = require('child_process');
const fs = require('fs');

const ADB = 'C:/Android/Sdk/platform-tools/adb.exe';
const PKG = 'com.sonance.player';
const XML = `/data/data/${PKG}/shared_prefs/sonance_prefs.xml`;

function run(args, bin) {
  for (let i = 0; i < 5; i++) {
    try {
      return execFileSync(ADB, args, {
        encoding: bin ? undefined : 'utf8',
        stdio: ['ignore', 'pipe', 'pipe'],
        maxBuffer: 32 * 1024 * 1024,
      });
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

console.log('force-stop: ' + run(['shell', 'am', 'force-stop', PKG]).trim());
run(['shell', 'run-as', PKG, 'mkdir', '-p', `/data/data/${PKG}/shared_prefs`]);

let xml = run(['shell', 'run-as', PKG, 'cat', XML]);
if (typeof xml !== 'string' || xml.startsWith('ERR') || !xml.includes('<map')) {
  console.log('已有的 prefs 读不到，构造一个新的');
  xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n";
}

if (/name="online_lyrics_enabled"/.test(xml)) {
  xml = xml.replace(/<boolean name="online_lyrics_enabled" value="[^"]*" \/>/,
    '<boolean name="online_lyrics_enabled" value="true" />');
} else {
  xml = xml.replace('</map>', '    <boolean name="online_lyrics_enabled" value="true" />\n</map>');
}

console.log('--- 写回的内容 ---');
console.log(xml.trim());

const tmp = '/data/local/tmp/sonance_prefs.xml';
fs.writeFileSync('.prefs_tmp.xml', xml, 'utf8');
run(['push', '.prefs_tmp.xml', tmp]);
run(['shell', 'chmod', '666', tmp]);
console.log('cp: ' + run(['shell', 'run-as', PKG, 'cp', tmp, XML]).trim());
console.log('verify: ' + run(['shell', 'run-as', PKG, 'cat', XML]).trim().replace(/\n/g, ' '));
console.log('start: ' + run(['shell', 'am', 'start', '-n', `${PKG}/.MainActivity`]).trim());
fs.rmSync('.prefs_tmp.xml', { force: true });
