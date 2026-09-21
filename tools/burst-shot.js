/**
 * 连续快速截图 —— 用来抓「一闪而过」的转场帧。
 *
 * screencap 一次大约 200~300ms，所以直接抓只能覆盖很短的窗口。
 * 配合「把动画临时放慢」使用（例如把 exit 动画改成 tween(3000)），
 * 就能从容拿到一整组中间帧。
 *
 * 注意：**不要和 `input swipe` 并发**（会把模拟器的 qemu 带崩）。
 * 用 `input tap`（瞬时返回）触发，然后再开始抓，是安全的。
 *
 * 用法：
 *   node tools/burst-shot.js [张数] [文件名前缀]
 */
const { execFileSync } = require('child_process');
const fs = require('fs');

const ADB = 'C:/Android/Sdk/platform-tools/adb.exe';
const n = +(process.argv[2] || 6);
const prefix = process.argv[3] || 'burst';

fs.mkdirSync('shots', { recursive: true });
const t0 = Date.now();
for (let i = 1; i <= n; i++) {
  let buf = null;
  for (let attempt = 0; attempt < 3 && !buf; attempt++) {
    try {
      buf = execFileSync(ADB, ['exec-out', 'screencap', '-p'], {
        encoding: null,
        maxBuffer: 64 * 1024 * 1024,
        stdio: ['ignore', 'pipe', 'pipe'],
      });
    } catch (e) {
      try { execFileSync(ADB, ['start-server'], { stdio: 'ignore' }); } catch (_) {}
    }
  }
  if (!buf || buf.length < 1000) {
    console.log(`  第 ${i} 张失败`);
    continue;
  }
  const f = `shots/${prefix}-${i}.png`;
  fs.writeFileSync(f, buf);
  console.log(`  ${f}  ${(buf.length / 1024).toFixed(0)}KB  +${Date.now() - t0}ms`);
}
