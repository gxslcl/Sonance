/*
 * 第三轮 UI 改动的模拟器驱动脚本。
 *
 * 用法（子命令可反复调用，设备状态在两次调用之间保持）：
 *   node tools/ui3.js setup              安装 debug 包 + 授权 + 启动
 *   node tools/ui3.js dump               导出可点/可读节点（含 bounds），用来定坐标
 *   node tools/ui3.js tap X Y
 *   node tools/ui3.js swipe X1 Y1 X2 Y2 [ms]
 *   node tools/ui3.js back
 *   node tools/ui3.js sleep MS
 *   node tools/ui3.js shot NAME
 *
 * 坐标一律先用 dump 拿到真实 bounds 再算，不靠像素估算。
 */
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ADB = 'C:/Android/Sdk/platform-tools/adb.exe';
const PKG = 'com.sonance.player';
const APK = 'app/build/outputs/apk/debug/app-debug.apk';
const OPTS = { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024 };

let ready = false;
function busyWait(ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    try { execFileSync('cmd', ['/c', 'ping', '-n', '1', '-w', '1000', '127.0.0.1'], { stdio: 'ignore' }); } catch (_) {}
  }
}
function ensureDevice() {
  if (ready) return;
  for (let i = 0; i < 30; i++) {
    try {
      execFileSync(ADB, ['start-server'], { stdio: 'ignore' });
      const out = execFileSync(ADB, ['devices'], OPTS);
      if (/emulator-\d+\s+device/.test(out)) { ready = true; return; }
    } catch (_) {}
    busyWait(2000);
  }
  throw new Error('emulator never became ready');
}
function run(args) {
  for (let i = 0; i < 6; i++) {
    ensureDevice();
    try { return execFileSync(ADB, args, OPTS); }
    catch (e) {
      const m = String(e.stdout || '') + String(e.stderr || '');
      if (/offline|no devices|daemon not running|closed/i.test(m)) { ready = false; busyWait(1500); continue; }
      throw new Error('adb ' + args.join(' ') + ' -> ' + m.slice(0, 200));
    }
  }
  throw new Error('adb retries exhausted');
}
const sh = (cmd) => run(['shell', ...cmd.split(' ')]);
const nap = (ms) => busyWait(ms);

function dumpNodes(filter = true) {
  sh('uiautomator dump /sdcard/ui.xml');
  const xml = sh('cat /sdcard/ui.xml');
  const out = [];
  for (const n of xml.split('<node')) {
    const t = (n.match(/text="([^"]*)"/) || [])[1] || '';
    const d = (n.match(/content-desc="([^"]*)"/) || [])[1] || '';
    const b = (n.match(/bounds="([^"]*)"/) || [])[1] || '';
    if (!b) continue;
    if (filter && !t && !d) continue;
    out.push({
      label: t || '[' + d + ']',
      bounds: b,
      clickable: /clickable="true"/.test(n),
    });
  }
  return out;
}

const cmd = process.argv[2] || 'help';
switch (cmd) {
  case 'setup': {
    console.log('设备: ' + run(['devices']).trim().split('\n').slice(1).join(' | '));
    sh('wm size 1080x2400');
    sh('wm density 420');
    console.log('卸载旧包: ' + run(['uninstall', PKG]).trim());
    console.log('安装 debug: ' + run(['install', '-r', '-t', APK]).trim().split('\n').slice(-1)[0]);
    for (const p of ['android.permission.READ_MEDIA_AUDIO', 'android.permission.POST_NOTIFICATIONS']) {
      run(['shell', 'pm', 'grant', PKG, p]);
    }
    run(['logcat', '-c']);
    sh(`am start -n ${PKG}/.MainActivity`);
    console.log('已启动，等界面就绪…');
    nap(30000);
    break;
  }
  case 'dump': {
    const nodes = dumpNodes();
    console.log('节点数: ' + nodes.length);
    for (const n of nodes) {
      const m = n.bounds.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
      const cx = m ? Math.round((+m[1] + +m[3]) / 2) : '?';
      const cy = m ? Math.round((+m[2] + +m[4]) / 2) : '?';
      console.log('  ' + (n.clickable ? '可点 ' : '     ') +
        n.label.slice(0, 30).padEnd(32) + n.bounds.padEnd(26) + '中心 (' + cx + ', ' + cy + ')');
    }
    break;
  }
  case 'tap': {
    const [x, y] = process.argv.slice(3);
    sh(`input tap ${x} ${y}`);
    console.log('tap ' + x + ' ' + y);
    break;
  }
  case 'swipe': {
    const a = process.argv.slice(3);
    const ms = a[4] || 300;
    sh(`input swipe ${a[0]} ${a[1]} ${a[2]} ${a[3]} ${ms}`);
    console.log('swipe ' + a.slice(0, 4).join(' '));
    break;
  }
  case 'back': {
    sh('input keyevent KEYCODE_BACK');
    console.log('back');
    break;
  }
  case 'sleep': {
    nap(Number(process.argv[3] || 1000));
    break;
  }
  case 'shot': {
    fs.mkdirSync('shots', { recursive: true });
    const file = path.join('shots', (process.argv[3] || 'shot') + '.png');
    for (let i = 0; i < 8; i++) {
      try {
        const buf = execFileSync(ADB, ['exec-out', 'screencap', '-p'],
          Object.assign({}, OPTS, { encoding: null }));
        if (buf && buf.length > 1000) {
          fs.writeFileSync(file, buf);
          console.log('截图 ' + file + ' ' + (buf.length / 1024).toFixed(0) + 'KB');
          break;
        }
      } catch (_) { ready = false; busyWait(1200); }
    }
    break;
  }
  default:
    console.log('子命令: setup | dump | tap X Y | swipe X1 Y1 X2 Y2 [ms] | back | sleep MS | shot NAME');
}
