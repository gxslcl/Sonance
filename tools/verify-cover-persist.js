/*
 * 验证「下载的封面能否持久化 + 进通知栏」。
 *
 * 阶段 A（本脚本）：
 *   1. 全新安装 + 清数据 → 保证 cover_cache 是空的
 *   2. 启动、播放第一首歌，等封面下载完
 *   3. 检查 cover_cache 目录、SonanceCover 日志
 *   4. 截图播放态；下拉通知栏再截图（验证锁屏/通知栏封面）
 *
 * 阶段 B（跑完 A 之后单独执行）：
 *   杀进程重启 → 直接进歌曲列表（不播放）→ 截图，看封面是否已显示
 *
 * 之所以自己写 adb 包装而不是直接调 adb：这个环境里 adb 会周期性 offline，
 * 每次调用都要带重试。
 */
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ADB = 'C:/Android/Sdk/platform-tools/adb.exe';
const PKG = 'com.sonance.player';
const APK = 'app/build/outputs/apk/debug/app-debug.apk';
const CACHE = `/data/data/${PKG}/files/cover_cache`;

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
      const out = execFileSync(ADB, ['devices'], {
        encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
      });
      if (/emulator-\d+\s+device/.test(out)) { ready = true; return; }
    } catch (_) {}
    busyWait(2000);
  }
  throw new Error('emulator never became ready');
}

function run(args, opts = {}) {
  for (let i = 0; i < 6; i++) {
    ensureDevice();
    try {
      return execFileSync(ADB, args, {
        encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024,
      });
    } catch (e) {
      const msg = (e.stdout || '') + (e.stderr || '');
      if (/offline|no devices|daemon not running|closed/i.test(msg)) {
        ready = false; busyWait(1500); continue;
      }
      if (opts.ignoreError) return msg;
      throw new Error('adb ' + args.join(' ') + ' -> ' + msg.slice(0, 300));
    }
  }
  throw new Error('adb retries exhausted: ' + args.join(' '));
}

function shell(cmd, opts = {}) { return run(['shell', ...cmd.split(' ')], opts); }

function shot(name) {
  fs.mkdirSync('shots', { recursive: true });
  const file = path.join('shots', name + '.png');
  for (let i = 0; i < 8; i++) {
    try {
      const buf = execFileSync(ADB, ['exec-out', 'screencap', '-p'], {
        maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
      });
      if (buf && buf.length > 1000) { fs.writeFileSync(file, buf); console.log('  截图 ' + file + ' ' + (buf.length / 1024).toFixed(0) + 'KB'); return; }
    } catch (_) { ready = false; busyWait(1200); }
  }
  console.log('  截图失败: ' + name);
}

const step = (t) => console.log('\n===== ' + t + ' =====');

/* ---------------------------------------------------------- */

/**
 * 阶段 B：验证「重启后列表直接显示已下载的封面」。
 *
 * 关键在于**全程不播放**：以前下载的封面只活在内存的 onlineCovers 里，
 * 重启就没了，列表要等播放到那首歌才补图。现在 CoverArt 会把 coverKey
 * 交给 ArtworkLoader 去读磁盘缓存，所以不播放也该有封面。
 */
if (process.argv.includes('--phase-b')) {
  step('B1. 杀掉进程后重启（不播放任何歌）');
  shell(`am force-stop ${PKG}`);
  busyWait(2000);
  run(['logcat', '-c'], { ignoreError: true });
  shell(`am start -n ${PKG}/.MainActivity`);
  console.log('  已启动，等界面就绪…');
  busyWait(30000);

  console.log('  SonanceCover 日志（不该有新的搜索请求）:');
  const l2 = run(['logcat', '-d'], { ignoreError: true })
    .split('\n').filter(l => /SonanceCover/.test(l));
  console.log(l2.length ? l2.map(l => '    ' + l.slice(l.indexOf('SonanceCover'))).join('\n') : '    (无)');

  step('B2. 截图歌曲列表（未播放）');
  shot('72-list-after-restart');

  step('B3. 再截一张迷你播放条区域确认');
  console.log('  cover_cache: ' + shell(`run-as ${PKG} ls -la ${CACHE}`, { ignoreError: true })
    .split('\n').filter(l => /\.jpg/.test(l)).map(l => l.trim()).join(' | '));
  process.exit(0);
}

/* ---------------------------------------------------------- */

step('0. 设备与音乐文件');
console.log('设备: ' + run(['devices']).trim().split('\n').slice(1).join(' | '));
shell('wm size 1080x2400', { ignoreError: true });
shell('wm density 420', { ignoreError: true });
const music = shell('ls /sdcard/Music/Sonance', { ignoreError: true });
console.log('音乐文件:\n' + music.trim().split('\n').slice(0, 14).map(l => '  ' + l).join('\n'));

step('1. 安装 + 清数据（保证 cover_cache 为空）');
// --keep：跳过安装与清数据。配合 setup-cover-test.js 用 —— 那个脚本会写入
// online_cover_enabled=true（默认是关的，不打开就不会联网补封面），
// 这里再 pm clear 一下就把它冲掉了。
const KEEP = process.argv.includes('--keep');
if (KEEP) {
  console.log('  --keep：跳过安装与 pm clear，沿用现有安装与设置');
  shell('am force-stop ' + PKG, { ignoreError: true });
  run(['logcat', '-c'], { ignoreError: true });
  console.log('  cover_cache 现状: ' + (shell(`run-as ${PKG} ls ${CACHE}`, { ignoreError: true }).trim().replace(/\n/g, ' ') || '(空/不存在)'));
} else {
  console.log(run(['install', '-r', '-t', APK]).trim().split('\n').slice(-1)[0]);
  console.log('pm clear: ' + shell(`pm clear ${PKG}`).trim());
  shell(`pm grant ${PKG} android.permission.READ_MEDIA_AUDIO`, { ignoreError: true });
  shell(`pm grant ${PKG} android.permission.POST_NOTIFICATIONS`, { ignoreError: true });
}

step('2. 启动并扫描');
run(['logcat', '-c'], { ignoreError: true });
shell(`am start -n ${PKG}/.MainActivity`);
busyWait(28000);
const winDump = shell('dumpsys window windows', { ignoreError: true });
const focus = winDump.split('\n').filter(l => /mCurrentFocus|mFocusedApp/.test(l)).slice(0, 3);
console.log('当前焦点: ' + (focus.map(l => l.trim()).join(' / ') || '(未知)').slice(0, 160));

step('3. 列表结构（找第一首歌的可点坐标）');
shell('uiautomator dump /sdcard/ui.xml', { ignoreError: true });
const ui = shell('cat /sdcard/ui.xml', { ignoreError: true });
const nodes = ui.split('<node').filter(n => /text="[^"]+"/.test(n) || /content-desc="[^"]+"/.test(n));
for (const n of nodes.slice(0, 22)) {
  const t = (n.match(/text="([^"]*)"/) || [])[1] || '';
  const d = (n.match(/content-desc="([^"]*)"/) || [])[1] || '';
  const b = (n.match(/bounds="([^"]*)"/) || [])[1] || '';
  const ck = /clickable="true"/.test(n) ? '可点 ' : '     ';
  if (t || d) console.log('  ' + ck + (t || ('[' + d + ']')).slice(0, 34).padEnd(36) + b);
}

step('4. 播放第一首歌');
shell('input tap 540 723');
busyWait(3000);
const pb = shell('dumpsys media_session', { ignoreError: true });
console.log('播放状态: ' + (pb.split('\n').filter(l => /state=PlaybackState/.test(l)).slice(0, 2).join(' | ') || '(未播放)').slice(0, 200));

console.log('等待封面下载…（测试音频每首 ~26s，等 12s 免得切到下一首）');
busyWait(12000);

const nowPlaying = shell('dumpsys media_session', { ignoreError: true })
  .split('\n').filter(l => /description=/.test(l)).slice(0, 2);
console.log('当前曲目: ' + nowPlaying.map(l => l.trim()).join(' | '));

step('5. 结果');
const logs = run(['logcat', '-d'], { ignoreError: true }).split('\n').filter(l => /SonanceCover/.test(l));
console.log('SonanceCover 日志:');
console.log(logs.length ? logs.map(l => '  ' + l.slice(l.indexOf('SonanceCover'))).join('\n') : '  (无)');

console.log('\ncover_cache 目录:');
const ls = shell(`run-as ${PKG} ls -la ${CACHE}`, { ignoreError: true }).trim();
console.log(ls.split('\n').map(l => '  ' + l).join('\n') || '  (读不到)');

step('6. 通知栏封面');
shell('cmd statusbar expand-notifications', { ignoreError: true });
busyWait(2500);
shot('70-notification');
console.log('\nmedia_session 里的 metadata（看有没有 artwork）:');
const ms = shell('dumpsys media_session', { ignoreError: true });
const metaLines = ms.split('\n').filter(l => /metadata|album_art|artwork|ART/i.test(l)).slice(0, 14);
console.log(metaLines.length ? metaLines.map(l => '  ' + l.trim().slice(0, 150)).join('\n') : '  (没找到 metadata 行)');

shell('cmd statusbar collapse', { ignoreError: true });
busyWait(1200);
shot('71-playing');
