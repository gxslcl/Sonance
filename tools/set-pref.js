/**
 * 通用 pref 设置工具（调 UI 实验用）。
 *
 * 直接改写 /data/data/<pkg>/shared_prefs/sonance_prefs.xml：
 * 应用没有跑的情况下写文件，再启动，prefs 才会被重新读入。
 *
 * 用法：
 *   node tools/set-pref.js glass_blur_dp=0 glass_style=2
 *   node tools/set-pref.js --show                只看当前值
 *   node tools/set-pref.js --show skin_id
 *
 * 类型自动推断：true/false → boolean，纯数字 → int，其余 → string。
 */
const { execFileSync } = require('child_process');
const fs = require('fs');

const ADB = 'C:/Android/Sdk/platform-tools/adb.exe';
const PKG = 'com.sonance.player';
const XML = `/data/data/${PKG}/shared_prefs/sonance_prefs.xml`;

function run(args) {
  for (let i = 0; i < 5; i++) {
    try {
      return execFileSync(ADB, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 32 * 1024 * 1024 });
    } catch (e) {
      const m = String(e.stdout || '') + String(e.stderr || '');
      if (/offline|no devices|daemon not running|unauthorized/.test(m)) {
        try { execFileSync(ADB, ['start-server'], { stdio: 'ignore' }); } catch (_) {}
        continue;
      }
      return 'ERR ' + m;
    }
  }
  return 'ERR retries exhausted';
}

const args = process.argv.slice(2);
if (!args.length) {
  console.log('用法: node tools/set-pref.js key=value [...]  |  --show [key...]');
  process.exit(1);
}

// --show：只打印当前的 prefs
if (args[0] === '--show') {
  const xml = String(run(['shell', 'run-as', PKG, 'cat', XML]));
  const want = args.slice(1);
  const all = [...xml.matchAll(/<(\w+) name="([^"]*)" value="([^"]*)" \/>/g)]
    .map((m) => [m[2], m[3]]);
  for (const [k, v] of all) {
    if (!want.length || want.includes(k)) console.log(`  ${k} = ${v}`);
  }
  if (!all.length) console.log('  (读不到 prefs)');
  process.exit(0);
}

const pairs = args.map((a) => {
  const i = a.indexOf('=');
  return [a.slice(0, i), a.slice(i + 1)];
});

let xml = String(run(['shell', 'run-as', PKG, 'cat', XML]));
if (!xml.includes('<map')) {
  xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n";
}
run(['shell', 'am', 'force-stop', PKG]);

for (const [name, value] of pairs) {
  const type = value === 'true' || value === 'false' ? 'boolean' : /^-?\d+$/.test(value) ? 'int' : 'string';
  const re = new RegExp(`<\\w+ name="${name}" value="[^"]*" />`);
  const node = `<${type} name="${name}" value="${value}" />`;
  if (re.test(xml)) xml = xml.replace(re, node);
  else xml = xml.replace('</map>', `    ${node}\n</map>`);
}

const tmp = '/data/local/tmp/sonance_prefs_set.xml';
fs.writeFileSync('.prefs_set.xml', xml, 'utf8');
run(['push', '.prefs_set.xml', tmp]);
run(['shell', 'chmod', '666', tmp]);
console.log('写入: ' + run(['shell', 'run-as', PKG, 'cp', tmp, XML]).trim());

const check = String(run(['shell', 'run-as', PKG, 'cat', XML]));
let ok = true;
for (const [name] of pairs) {
  const m = check.match(new RegExp(`name="${name}" value="([^"]*)"`));
  if (!m) ok = false;
  console.log(`  ${name} = ${m ? m[1] : '(缺失)'}`);
}
fs.rmSync('.prefs_set.xml', { force: true });

// ⚠️ 写完必须再把进程按死一次，而且要**确认它真的死了**。
//
// 桌面小组件（`SonanceWidgetProvider`）会让系统在包被杀掉之后又把进程拉起来 ——
// 那次启动会在我们写文件**之前**就把 prefs 读进内存，于是进程一直用着旧值，
// 直到下一次冷启动才看到新值（而且它不一定会回写文件，所以"读文件是对的、
// 界面却是旧的"这种最误导人的组合就这么来的：本轮在模拟器上连续踩中两次）。
// 这里轮询到进程真的没了为止，调用方紧接着 `am start` 就能拿到新值。
if (ok) {
  for (let i = 0; i < 10; i++) {
    run(['shell', 'am', 'force-stop', PKG]);
    const pid = String(run(['shell', 'pidof', PKG])).trim();
    if (!pid) { console.log('  进程已确认退出'); break; }
    const end = Date.now() + 400;
    while (Date.now() < end) {
      try { execFileSync('cmd', ['/c', 'ping', '-n', '1', '-w', '1000', '127.0.0.1'], { stdio: 'ignore' }); } catch (_) {}
    }
    if (i === 9) console.log('  ⚠️ 进程一直活着，pref 可能不生效');
  }
}
