// 通过 Node 驱动 adb，规避本机 bash 缺少 coreutils 的问题
const { execFileSync, execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ADB = 'C:/Android/Sdk/platform-tools/adb.exe';

function raw(args) {
  return execFileSync(ADB, args, {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    maxBuffer: 64 * 1024 * 1024,
  });
}

let deviceReady = false;
function ensureDevice() {
  if (deviceReady) return;
  for (let i = 0; i < 30; i++) {
    try {
      raw(['start-server']);
      const out = raw(['devices']);
      if (/emulator-\d+\s+device/.test(out)) {
        deviceReady = true;
        return;
      }
    } catch (_) { /* retry */ }
    busyWait(2000);
  }
}

function busyWait(ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    try { execSync('ping -n 1 -w 1000 127.0.0.1 > nul', { stdio: 'ignore' }); } catch (_) { /* ignore */ }
  }
}

function adb(args, opts = {}) {
  ensureDevice();
  const attempts = opts.noRetry ? 1 : 5;
  let lastErr = null;
  for (let i = 0; i < attempts; i++) {
    try {
      return execFileSync(ADB, args, {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'pipe'],
        maxBuffer: 64 * 1024 * 1024,
      });
    } catch (e) {
      const msg = (e.stdout || '') + (e.stderr || '');
      lastErr = new Error('adb ' + args.join(' ') + ' failed: ' + msg);
      if (msg.includes('device offline') || msg.includes('daemon not running') ||
          msg.includes('no devices/emulators found')) {
        deviceReady = false;
        ensureDevice();
        continue;
      }
      if (opts.ignoreError) return msg;
      throw lastErr;
    }
  }
  if (opts.ignoreError) return String(lastErr && lastErr.message);
  throw lastErr || new Error('adb failed');
}

function shell(cmd, opts) {
  return adb(['shell', cmd], opts);
}

function sleep(ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    try {
      execSync('ping -n 1 -w 1000 127.0.0.1 > nul', { stdio: 'ignore' });
    } catch (_) { /* ignore */ }
  }
}

const cmd = process.argv[2];

switch (cmd) {
  case 'wait': {
    const max = Number(process.argv[3] || 60);
    for (let i = 1; i <= max; i++) {
      const st = shell('getprop sys.boot_completed', { ignoreError: true }).trim();
      const dev = adb(['get-state'], { ignoreError: true }).trim();
      console.log(`try ${i}: device=${dev} boot=${st}`);
      if (st === '1') {
        console.log('BOOTED');
        process.exit(0);
      }
      sleep(5000);
    }
    console.log('TIMEOUT');
    process.exit(1);
    break;
  }

  case 'install': {
    const apk = process.argv[3];
    console.log(adb(['install', '-r', '-t', apk], { ignoreError: true }).trim());
    break;
  }

  case 'pushmusic': {
    const dir = process.argv[3] || 'testaudio';
    shell('mkdir -p /sdcard/Music/Sonance', { ignoreError: true });
    for (const f of fs.readdirSync(dir)) {
      adb(['push', path.join(dir, f), '/sdcard/Music/Sonance/' + f], { ignoreError: true });
      console.log('pushed ' + f);
    }
    break;
  }

  case 'scan': {
    const files = shell('ls /sdcard/Music/Sonance', { ignoreError: true }).split(/\r?\n/).filter(Boolean);
    for (const f of files) {
      const uri = 'file:///sdcard/Music/Sonance/' + f;
      shell(`am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "${uri}"`, { ignoreError: true });
    }
    console.log('scan broadcast sent for ' + files.length + ' files');
    break;
  }

  case 'query': {
    console.log(shell('content query --uri content://media/external/audio/media --projection _id:title:artist:album:duration', { ignoreError: true }));
    break;
  }

  case 'grant': {
    const pkg = process.argv[3] || 'com.sonance.player';
    for (const p of ['android.permission.READ_MEDIA_AUDIO', 'android.permission.POST_NOTIFICATIONS']) {
      console.log(shell(`pm grant ${pkg} ${p}`, { ignoreError: true }).trim() || ('granted ' + p));
    }
    break;
  }

  case 'start': {
    const pkg = process.argv[3] || 'com.sonance.player';
    shell(`am start -n ${pkg}/.MainActivity`, { ignoreError: true });
    break;
  }

  case 'size': {
    const w = process.argv[3], h = process.argv[4], d = process.argv[5];
    if (!w || w === 'reset') {
      console.log(shell('wm size reset', { ignoreError: true }));
      console.log(shell('wm density reset', { ignoreError: true }));
    } else {
      console.log(shell(`wm size ${w}x${h}`, { ignoreError: true }));
      if (d) console.log(shell(`wm density ${d}`, { ignoreError: true }));
    }
    busyWait(4000);
    console.log(shell('wm size', { ignoreError: true }).trim());
    break;
  }

  case 'sleep': {
    busyWait(Number(process.argv[3] || 3000));
    break;
  }

  case 'rotate': {
    const r = process.argv[3] || '0';
    shell('settings put system accelerometer_rotation 0', { ignoreError: true });
    shell(`settings put system user_rotation ${r}`, { ignoreError: true });
    console.log('rotation=' + r);
    break;
  }

  case 'shot': {
    const name = process.argv[3] || 'shot';
    const outDir = process.argv[4] || 'shots';
    fs.mkdirSync(outDir, { recursive: true });
    const file = path.join(outDir, name + '.png');
    const remote = '/sdcard/.sonance_shot.png';
    let ok = false;
    for (let i = 0; i < 8 && !ok; i++) {
      deviceReady = false;
      ensureDevice();
      try {
        execFileSync(ADB, ['shell', 'screencap', '-p', remote], { stdio: 'ignore', timeout: 20000 });
        execFileSync(ADB, ['pull', remote, file], { stdio: 'ignore', timeout: 20000 });
        if (fs.existsSync(file) && fs.statSync(file).size > 5000) ok = true;
      } catch (_) { /* retry */ }
      if (!ok) busyWait(900);
    }
    if (!ok) {
      console.log('FAILED to capture screenshot');
      process.exit(1);
    }
    console.log('saved ' + file + ' ' + (fs.statSync(file).size / 1024).toFixed(0) + 'KB');
    break;
  }

  case 'tap': {
    shell(`input tap ${process.argv[3]} ${process.argv[4]}`, { ignoreError: true });
    console.log('tap ' + process.argv[3] + ',' + process.argv[4]);
    break;
  }

  case 'swipe': {
    shell(`input swipe ${process.argv[3]} ${process.argv[4]} ${process.argv[5]} ${process.argv[6]} ${process.argv[7] || 200}`, { ignoreError: true });
    break;
  }

  case 'back': {
    shell('input keyevent 4', { ignoreError: true });
    break;
  }

  case 'logs': {
    console.log(shell('logcat -d -t 400', { ignoreError: true }));
    break;
  }

  case 'crash': {
    console.log(shell('logcat -d -b crash -t 200', { ignoreError: true }));
    break;
  }

  default:
    console.log('usage: emu.js <wait|install|pushmusic|scan|query|grant|start|size|rotate|shot|tap|swipe|back|logs|crash>');
}
