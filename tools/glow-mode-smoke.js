/*
 * 跨模式冒烟：把「包裹方式 / 过渡方式」逐档设进去，各截一张播放页。
 *
 * 用途是回归 —— 改了晕染的过渡实现之后，确认另外三种模式（走位图那条路，
 * 和「跟手」的颜色插值不是同一段代码）以及「渐变」过渡都还画得出来。
 *
 * 用法：node tools/glow-mode-smoke.js
 * 截完看 shots/smoke-*.png：背景应当各档都有颜色，且形状各不相同。
 */
const { execFileSync } = require('child_process');

const ADB = 'C:/Android/Sdk/platform-tools/adb.exe';
const NODE = 'C:/Users/1/.workbuddy/binaries/node/versions/22.22.2-3/node.exe';
const O = { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024 };

const CASES = [
    ['sides', 'swipe'],
    ['full', 'swipe'],
    ['bottom', 'swipe'],
    ['around', 'fade'],
];

function sh(cmd) {
    return execFileSync(ADB, ['shell', ...cmd.split(' ')], O);
}
function nap(ms) {
    const end = Date.now() + ms;
    while (Date.now() < end) {
        try { execFileSync('cmd', ['/c', 'ping', '-n', '1', '-w', '1000', '127.0.0.1'], { stdio: 'ignore' }); } catch (_) {}
    }
}

for (const [mode, trans] of CASES) {
    sh('am force-stop com.sonance.player');
    nap(1200);
    execFileSync(NODE, ['tools/set-pref.js', `glow_mode=${mode}`, `glow_transition=${trans}`], O);
    sh('am start -n com.sonance.player/.MainActivity');
    nap(20000);
    // 打开播放页：迷你条标题（各机型位置固定在这条上）
    sh('input tap 245 2069');
    nap(4000);
    execFileSync(NODE, ['tools/drag-capture.js', 'shot', `smoke-${mode}-${trans}`], O);
}
// 复位成默认
sh('am force-stop com.sonance.player');
nap(1000);
execFileSync(NODE, ['tools/set-pref.js', 'glow_mode=around', 'glow_transition=swipe'], O);
console.log('冒烟完成，pref 已复位为 around / swipe');
