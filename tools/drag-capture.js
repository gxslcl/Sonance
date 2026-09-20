/**
 * 分步注入触摸事件：DOWN → MOVE…（中途截图）→ UP。
 *
 * 为什么不用 `input swipe`：它是一条命令持续若干秒，期间截图会与手势并发，
 * 实测会把模拟器的 qemu 直接带崩（今天崩了 4 次，全是这个组合）。
 * `input motionevent` 每条命令瞬时返回，触摸状态由系统保持，
 * 于是「按住不放」这个中间态可以停留任意久，截图完全不需要并发 —— 稳定得多，
 * 而且能精确控制拖动偏移量。
 *
 * 用法：
 *   node tools/drag-capture.js down [x] [y]
 *   node tools/drag-capture.js move <x> [y]        （y 默认沿用上次）
 *   node tools/drag-capture.js shot <name>
 *   node tools/drag-capture.js up [x] [y]
 *   node tools/drag-capture.js drag <offset>       （在当前 x 基础上左移 offset 并截图）
 */
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ADB = 'C:/Android/Sdk/platform-tools/adb.exe';
const O = { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024 };
const STATE = '.drag-state.json';

let x = 1000;
let y = 1400;

function run(args, opts = {}) {
  for (let i = 0; i < 5; i++) {
    try {
      return execFileSync(ADB, args, Object.assign({}, O, opts));
    } catch (e) {
      const m = String(e.stdout || '') + String(e.stderr || '');
      if (/offline|no devices|daemon not running|unauthorized/.test(m)) {
        try { execFileSync(ADB, ['start-server'], { stdio: 'ignore' }); } catch (_) {}
        continue;
      }
      throw e;
    }
  }
  return '';
}

const shell = (c) => run(['shell', ...c.split(' ')]);
const load = () => { try { const s = JSON.parse(fs.readFileSync(STATE, 'utf8')); x = s.x; y = s.y; } catch (_) {} };
const save = () => fs.writeFileSync(STATE, JSON.stringify({ x, y }), 'utf8');

function shot(name) {
  fs.mkdirSync('shots', { recursive: true });
  const file = path.join('shots', name + '.png');
  const buf = run(['exec-out', 'screencap', '-p'], { encoding: null });
  if (!buf || buf.length < 1000) { console.log('  截图失败'); return; }
  fs.writeFileSync(file, buf);
  console.log(`  ${file}  ${(buf.length / 1024).toFixed(0)}KB`);
}

const cmd = process.argv[2];
load();

switch (cmd) {
  case 'down':
    x = +(process.argv[3] || x);
    y = +(process.argv[4] || y);
    shell(`input motionevent DOWN ${x} ${y}`);
    save();
    console.log(`DOWN (${x},${y})`);
    break;
  case 'move':
    x = +process.argv[3];
    y = +(process.argv[4] || y);
    shell(`input motionevent MOVE ${x} ${y}`);
    save();
    console.log(`MOVE (${x},${y})`);
    break;
  case 'up':
    x = +(process.argv[3] || x);
    y = +(process.argv[4] || y);
    shell(`input motionevent UP ${x} ${y}`);
    save();
    console.log(`UP (${x},${y})`);
    break;
  case 'shot':
    shot(process.argv[3] || 'shot');
    break;
  case 'drag': {
    // 从当前 x 分小步左移 offset，避免单次大跳被当成 fling
    const total = +process.argv[3];
    const steps = Math.max(1, Math.round(Math.abs(total) / 50));
    for (let i = 1; i <= steps; i++) {
      const nx = Math.round(x - (total * i) / steps);
      shell(`input motionevent MOVE ${nx} ${y}`);
    }
    x -= total;
    save();
    console.log(`drag -${total} -> x=${x}`);
    break;
  }
  default:
    console.log('用法: down | move <x> [y] | up [x] [y] | shot <name> | drag <offset>');
}
