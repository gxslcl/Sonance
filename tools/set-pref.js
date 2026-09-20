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
for (const [name] of pairs) {
  const m = check.match(new RegExp(`name="${name}" value="([^"]*)"`));
  console.log(`  ${name} = ${m ? m[1] : '(缺失)'}`);
}
fs.rmSync('.prefs_set.xml', { force: true });
