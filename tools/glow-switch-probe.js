/*
 * 抓「切歌过渡」的中间帧：把播放页的纵向分页器按住停在途中，逐档截图。
 *
 * 为什么需要它：切歌时背景（封面晕染）是**两页交叉淡化**画出来的，
 * 而「闪一下」这种缺陷只在过渡的中间那几十到几百毫秒里出现 ——
 * `input swipe` 期间截图会把 qemu 带崩，普通的「按下一瞬间截一张」又抓不到中点。
 * 用 `input motionevent` 按住不放，过渡状态就被冻结住，可以慢慢截。
 *
 * 用法：
 *   node tools/glow-switch-probe.js <输出前缀> [每档像素] [档数]
 * 之后用 tools/glow-profile.py 分析 shots/<前缀>-*.png 的背景亮度。
 */
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ADB = 'C:/Android/Sdk/platform-tools/adb.exe';
const O = { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024 };

function sh(cmd) {
  for (let i = 0; i < 5; i++) {
    try { return execFileSync(ADB, ['shell', ...cmd.split(' ')], O); }
    catch (e) {
      const m = String(e.stdout || '') + String(e.stderr || '');
      if (/offline|no devices|daemon not running|unauthorized/i.test(m)) {
        try { execFileSync(ADB, ['start-server'], { stdio: 'ignore' }); } catch (_) {}
        continue;
      }
      throw e;
    }
  }
}

function shot(name) {
  fs.mkdirSync('shots', { recursive: true });
  const file = path.join('shots', name + '.png');
  const buf = execFileSync(ADB, ['exec-out', 'screencap', '-p'],
    Object.assign({}, O, { encoding: null }));
  if (!buf || buf.length < 1000) { console.log('  ' + name + ' 截图失败'); return; }
  fs.writeFileSync(file, buf);
  console.log('  ' + file + '  ' + (buf.length / 1024).toFixed(0) + 'KB');
}

const prefix = process.argv[2] || 'glow';
const step = +(process.argv[3] || 350);
const n = +(process.argv[4] || 4);
const x = +(process.argv[5] || 540);
const y0 = +(process.argv[6] || 1800);

// 先落定态
sh(`input motionevent UP ${x} ${y0}`);   // 清掉可能残留的按下状态
shot(prefix + '-0-settled');

sh(`input motionevent DOWN ${x} ${y0}`);
let y = y0;
for (let i = 1; i <= n; i++) {
  // 分 50px 小步，单次大跳会被判成 fling，抓不到中间态
  const steps = Math.max(1, Math.round(step / 50));
  for (let k = 1; k <= steps; k++) {
    const ny = Math.round(y - (step * k) / steps);
    sh(`input motionevent MOVE ${x} ${ny}`);
  }
  y -= step;
  shot(prefix + '-' + i + '-drag' + (step * i));
}
sh(`input motionevent UP ${x} ${y}`);
console.log('已松手，等分页器落定…');
const end = Date.now() + 2500;
while (Date.now() < end) {
  try { execFileSync('cmd', ['/c', 'ping', '-n', '1', '-w', '1000', '127.0.0.1'], { stdio: 'ignore' }); } catch (_) {}
}
shot(prefix + '-9-settled-next');
