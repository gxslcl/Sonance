/* 背景音乐 · 星轨（原创 G 大调太空抒情氛围曲，纯 Web Audio API 现场合成，~66 BPM，16 小节循环）
 * - 零外部音频文件、零版权风险、零网络体积开销
 * - Rhodes 电钢分解和弦 + 柔美带颤音主旋律 + 太空和声垫 + 2.8s 立体声厅堂混响与柔波回声
 * - 进入页面尝试自动播放；被浏览器策略拦截时在首次交互无缝开启，音效试听/麦克风时自动避让
 */
(function () {
  const S = (window.Sonance = window.Sonance || {});

  const BEAT = 60 / 66;     // ~0.909s
  const STEP = BEAT / 2;    // eighth note (~0.4545s)
  const STEPS_PER_BAR = 8;
  const TOTAL_BARS = 16;

  // G 大调音阶频率表
  const N = {
    E2: 82.41, G2: 98.00, A2: 110.00, B2: 123.47,
    C3: 130.81, D3: 146.83, E3: 164.81, Fs3: 185.00, G3: 196.00, A3: 220.00, B3: 246.94,
    C4: 261.63, D4: 293.66, E4: 329.63, Fs4: 369.99, G4: 392.00, A4: 440.00, B4: 493.88,
    C5: 523.25, D5: 587.33, E5: 659.25, Fs5: 739.99, G5: 783.99
  };

  // 抒情和声进行：Gmaj7 -> Bm7 -> Em7 -> Cmaj7 | Am7 -> Bm7 -> Cmaj7 -> D7sus4
  const PROG = [
    { bass: N.G2, pad: [N.G3, N.B3, N.D4, N.Fs4], arp: [N.G3, N.D4, N.Fs4, N.B4, N.Fs4, N.D4] },
    { bass: N.B2, pad: [N.B3, N.D4, N.Fs4, N.A4], arp: [N.B3, N.Fs4, N.A4, N.D5, N.A4, N.Fs4] },
    { bass: N.E2, pad: [N.E3, N.G3, N.B3, N.D4],  arp: [N.E3, N.B3, N.D4, N.G4, N.D4, N.B3] },
    { bass: N.C3, pad: [N.C4, N.E4, N.G4, N.B4],  arp: [N.C4, N.G4, N.B4, N.E5, N.B4, N.G4] },
    { bass: N.A2, pad: [N.A3, N.C4, N.E4, N.G4],  arp: [N.A3, N.E4, N.G4, N.C5, N.G4, N.E4] },
    { bass: N.B2, pad: [N.B3, N.D4, N.Fs4, N.A4], arp: [N.B3, N.Fs4, N.A4, N.D5, N.A4, N.Fs4] },
    { bass: N.C3, pad: [N.C4, N.E4, N.G4, N.B4],  arp: [N.C4, N.G4, N.B4, N.E5, N.B4, N.G4] },
    { bass: N.D3, pad: [N.D4, N.G4, N.A4, N.C5],  arp: [N.D4, N.G4, N.A4, N.D5, N.A4, N.G4] }
  ];

  // 原创 16 小节抒情主旋律（太空舒缓叙事感：[freq, durBeats, vel]）
  const LEAD = {
    0:  { 1: [N.B4, 1.4, 0.82], 3: [N.D5, 1.6, 0.90], 6: [N.B4, 1.4, 0.78] },
    1:  { 0: [N.A4, 2.4, 0.86], 5: [N.Fs4, 1.8, 0.72] },
    2:  { 1: [N.G4, 1.4, 0.80], 3: [N.B4, 1.6, 0.88], 6: [N.A4, 1.4, 0.76] },
    3:  { 0: [N.G4, 3.2, 0.84] },
    4:  { 1: [N.E4, 1.2, 0.76], 3: [N.G4, 1.4, 0.82], 5: [N.A4, 1.8, 0.88] },
    5:  { 0: [N.B4, 2.2, 0.88], 4: [N.D5, 2.2, 0.90] },
    6:  { 0: [N.E5, 2.0, 0.92], 3: [N.D5, 1.4, 0.84], 6: [N.B4, 1.6, 0.80] },
    7:  { 0: [N.A4, 3.4, 0.84] },
    8:  { 1: [N.D5, 1.4, 0.90], 3: [N.Fs5, 1.8, 0.95], 6: [N.E5, 1.4, 0.86] },
    9:  { 0: [N.D5, 2.6, 0.90], 5: [N.B4, 1.8, 0.78] },
    10: { 1: [N.B4, 1.4, 0.82], 3: [N.E5, 1.8, 0.92], 6: [N.D5, 1.4, 0.84] },
    11: { 0: [N.B4, 3.0, 0.86] },
    12: { 1: [N.C5, 1.4, 0.84], 3: [N.B4, 1.2, 0.80], 5: [N.A4, 1.8, 0.82] },
    13: { 0: [N.B4, 2.2, 0.84], 4: [N.G4, 2.2, 0.78] },
    14: { 0: [N.G4, 1.8, 0.80], 3: [N.A4, 1.8, 0.84], 6: [N.B4, 1.6, 0.86] },
    15: { 0: [N.A4, 2.0, 0.82], 4: [N.G4, 3.2, 0.84] }
  };

  const ARP_STEPS = new Set([0, 2, 3, 4, 6, 7]);

  let ctx = null, masterGain = null, duckGain = null, dryBus = null, wetBus = null, delayIn = null;
  let timer = null, stepIdx = 0, nextTime = 0;
  let state = 'muted'; // 'playing' | 'armed' | 'muted'
  let userMuted = false, ducked = false, toastShown = false;
  let btn = null, lbl = null;

  function makeImpulse(c, seconds = 2.8) {
    const len = Math.floor(c.sampleRate * seconds);
    const buf = c.createBuffer(2, len, c.sampleRate);
    for (let ch = 0; ch < 2; ch++) {
      const data = buf.getChannelData(ch);
      let lp = 0;
      for (let i = 0; i < len; i++) {
        const env = Math.pow(1 - i / len, 2.5);
        const w = (Math.random() * 2 - 1) * env;
        lp = lp * 0.74 + w * 0.26;
        data[i] = lp * 0.85;
      }
    }
    return buf;
  }

  function ensureGraph() {
    if (ctx) return true;
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return false;
    ctx = new AC();

    const comp = ctx.createDynamicsCompressor();
    comp.threshold.value = -16;
    comp.ratio.value = 3;

    masterGain = ctx.createGain();
    masterGain.gain.value = 0.0001;

    duckGain = ctx.createGain();
    duckGain.gain.value = ducked ? 0.14 : 1.0;

    dryBus = ctx.createGain();
    dryBus.gain.value = 0.74;

    wetBus = ctx.createGain();
    wetBus.gain.value = 0.46;

    const rev = ctx.createConvolver();
    rev.buffer = makeImpulse(ctx, 2.8);

    // 柔和回声延迟线（增强星际空间感）
    delayIn = ctx.createGain();
    delayIn.gain.value = 0.22;
    const dly = ctx.createDelay(1.0);
    dly.delayTime.value = STEP * 0.75; // 附点十六分音符律动回声
    const dlyLP = ctx.createBiquadFilter();
    dlyLP.type = 'lowpass';
    dlyLP.frequency.value = 1150;
    const dlyFb = ctx.createGain();
    dlyFb.gain.value = 0.24;

    delayIn.connect(dly);
    dly.connect(dlyLP);
    dlyLP.connect(dlyFb);
    dlyFb.connect(dly);
    dlyLP.connect(wetBus);
    dlyLP.connect(dryBus);

    dryBus.connect(comp);
    wetBus.connect(rev);
    rev.connect(comp);
    comp.connect(duckGain);
    duckGain.connect(masterGain);
    masterGain.connect(ctx.destination);
    return true;
  }

  function playPadAndBass(chord, t, barDur) {
    // 深沉温暖的次低音根音
    const sub = ctx.createOscillator();
    sub.type = 'sine';
    sub.frequency.value = chord.bass;
    const sg = ctx.createGain();
    sg.gain.setValueAtTime(0.0001, t);
    sg.gain.linearRampToValueAtTime(0.14, t + 0.35);
    sg.gain.setValueAtTime(0.14, t + barDur - 0.8);
    sg.gain.exponentialRampToValueAtTime(0.0001, t + barDur + 0.3);
    sub.connect(sg);
    sg.connect(dryBus);
    sub.start(t);
    sub.stop(t + barDur + 0.35);

    // 太空氛围和声垫
    const lp = ctx.createBiquadFilter();
    lp.type = 'lowpass';
    lp.frequency.value = 430;

    const g = ctx.createGain();
    g.gain.setValueAtTime(0.0001, t);
    g.gain.linearRampToValueAtTime(0.055, t + 1.5);
    g.gain.setValueAtTime(0.055, t + barDur - 1.3);
    g.gain.exponentialRampToValueAtTime(0.0001, t + barDur + 0.45);
    lp.connect(g);
    g.connect(dryBus);
    g.connect(wetBus);

    chord.pad.forEach((f, i) => {
      const o = ctx.createOscillator();
      o.type = i === 0 ? 'triangle' : 'sine';
      o.frequency.value = f;
      o.detune.value = (i % 2 ? 1 : -1) * 4.2;
      const vg = ctx.createGain();
      vg.gain.value = i === 0 ? 0.36 : 0.22;
      o.connect(vg);
      vg.connect(lp);
      o.start(t);
      o.stop(t + barDur + 0.5);
    });
  }

  function playRhodesArp(freq, t, panVal) {
    const dur = 1.9;
    const lp = ctx.createBiquadFilter();
    lp.type = 'lowpass';
    lp.frequency.setValueAtTime(1100, t);
    lp.frequency.exponentialRampToValueAtTime(420, t + 0.9);

    const env = ctx.createGain();
    env.gain.setValueAtTime(0.0001, t);
    env.gain.linearRampToValueAtTime(0.068, t + 0.022);
    env.gain.exponentialRampToValueAtTime(0.0001, t + dur);

    const o1 = ctx.createOscillator();
    o1.type = 'sine';
    o1.frequency.value = freq;
    const o2 = ctx.createOscillator();
    o2.type = 'sine';
    o2.frequency.value = freq * 2;
    const g2 = ctx.createGain();
    g2.gain.value = 0.14;

    o1.connect(lp);
    o2.connect(g2); g2.connect(lp);
    lp.connect(env);

    const pan = ctx.createStereoPanner ? ctx.createStereoPanner() : null;
    if (pan) {
      pan.pan.value = panVal;
      env.connect(pan);
      pan.connect(dryBus);
      pan.connect(wetBus);
    } else {
      env.connect(dryBus);
      env.connect(wetBus);
    }
    o1.start(t); o2.start(t);
    o1.stop(t + dur + 0.05); o2.stop(t + dur + 0.05);
  }

  function playLead(freq, t, durBeats, vel) {
    const dur = durBeats * BEAT;
    const lp = ctx.createBiquadFilter();
    lp.type = 'lowpass';
    lp.frequency.setValueAtTime(1800, t);
    lp.frequency.exponentialRampToValueAtTime(680, t + Math.min(dur, 1.5));

    const env = ctx.createGain();
    const peak = 0.13 * vel;
    env.gain.setValueAtTime(0.0001, t);
    env.gain.linearRampToValueAtTime(peak, t + 0.045);
    env.gain.exponentialRampToValueAtTime(peak * 0.58, t + 0.45);
    env.gain.exponentialRampToValueAtTime(0.0001, t + dur + 0.55);

    const o1 = ctx.createOscillator();
    o1.type = 'sine';
    o1.frequency.value = freq;

    const o2 = ctx.createOscillator();
    o2.type = 'triangle';
    o2.frequency.value = freq;
    const g2 = ctx.createGain();
    g2.gain.value = 0.22;

    // 长音加入柔美颤音
    let lfo = null;
    if (durBeats >= 1.5) {
      lfo = ctx.createOscillator();
      lfo.frequency.value = 4.5;
      const lg = ctx.createGain();
      lg.gain.setValueAtTime(0, t);
      lg.gain.linearRampToValueAtTime(4.2, t + 0.45);
      lfo.connect(lg);
      lg.connect(o1.detune);
      lg.connect(o2.detune);
      lfo.start(t);
      lfo.stop(t + dur + 0.6);
    }

    o1.connect(lp);
    o2.connect(g2); g2.connect(lp);
    lp.connect(env);
    env.connect(dryBus);
    env.connect(wetBus);
    env.connect(delayIn);

    o1.start(t); o2.start(t);
    o1.stop(t + dur + 0.6); o2.stop(t + dur + 0.6);
  }

  function scheduleStep(idx, t) {
    const bar = Math.floor(idx / STEPS_PER_BAR) % TOTAL_BARS;
    const step = idx % STEPS_PER_BAR;
    const chord = PROG[bar % PROG.length];

    if (step === 0) {
      playPadAndBass(chord, t, STEPS_PER_BAR * STEP);
    }
    if (ARP_STEPS.has(step)) {
      const aIdx = step < 2 ? 0 : step - 1;
      const note = chord.arp[aIdx % chord.arp.length];
      playRhodesArp(note, t, (aIdx % 2 ? 0.24 : -0.24));
    }
    const mel = LEAD[bar] && LEAD[bar][step];
    if (mel) playLead(mel[0], t, mel[1], mel[2]);
  }

  function pump() {
    if (!ctx || ctx.state !== 'running') return;
    while (nextTime < ctx.currentTime + 0.45) {
      scheduleStep(stepIdx, nextTime);
      nextTime += STEP;
      stepIdx = (stepIdx + 1) % (TOTAL_BARS * STEPS_PER_BAR);
    }
  }

  function syncUI() {
    if (!btn) return;
    btn.classList.toggle('playing', state === 'playing');
    btn.classList.toggle('armed', state === 'armed');
    btn.setAttribute('aria-pressed', String(state === 'playing'));
    if (lbl) lbl.textContent = '星轨';
  }

  function clearUnlock() {
    ['pointerdown', 'keydown', 'touchstart'].forEach(ev =>
      window.removeEventListener(ev, onFirstGesture, true)
    );
  }

  function onFirstGesture(e) {
    if (userMuted || state === 'playing') { clearUnlock(); return; }
    if (e.target && e.target.closest && e.target.closest('#bgmBtn')) {
      clearUnlock();
      return;
    }
    clearUnlock();
    play(false);
  }

  function startLoop() {
    if (timer) return;
    nextTime = ctx.currentTime + 0.06;
    pump();
    timer = setInterval(pump, 160);
    masterGain.gain.cancelScheduledValues(ctx.currentTime);
    masterGain.gain.setValueAtTime(Math.max(0.0001, masterGain.gain.value), ctx.currentTime);
    masterGain.gain.linearRampToValueAtTime(0.38, ctx.currentTime + 1.4);
    state = 'playing';
    syncUI();
  }

  async function play(fromUser = false) {
    if (!ensureGraph()) return;
    if (fromUser) userMuted = false;
    clearUnlock();
    try {
      if (ctx.state === 'suspended') await ctx.resume();
    } catch (_) {}

    if (ctx.state === 'running') {
      startLoop();
    } else {
      state = 'armed';
      syncUI();
      ['pointerdown', 'keydown', 'touchstart'].forEach(ev =>
        window.addEventListener(ev, onFirstGesture, { capture: true, passive: true })
      );
      if (!toastShown && !fromUser) {
        toastShown = true;
        S.toast('点击页面任意处开启背景音乐 · 星轨');
      }
    }
  }

  function pause(fromUser = false) {
    if (fromUser) userMuted = true;
    clearUnlock();
    if (timer) { clearInterval(timer); timer = null; }
    if (ctx && masterGain) {
      const t = ctx.currentTime;
      masterGain.gain.cancelScheduledValues(t);
      masterGain.gain.setValueAtTime(masterGain.gain.value, t);
      masterGain.gain.linearRampToValueAtTime(0.0001, t + 0.3);
      setTimeout(() => {
        if (state === 'muted' && ctx && ctx.state === 'running') {
          ctx.suspend().catch(() => {});
        }
      }, 340);
    }
    state = 'muted';
    syncUI();
  }

  function toggle() {
    if (state === 'playing') pause(true);
    else play(true);
  }

  function duck(on) {
    ducked = !!on;
    if (!ctx || !duckGain) return;
    const t = ctx.currentTime;
    duckGain.gain.cancelScheduledValues(t);
    duckGain.gain.setTargetAtTime(ducked ? 0.14 : 1.0, t, ducked ? 0.15 : 0.35);
  }

  S.bgm = { play, pause, toggle, duck };

  S.onReady(() => {
    btn = S.$('#bgmBtn');
    lbl = S.$('.bgm-lbl', btn);
    if (btn) btn.addEventListener('click', toggle);
    play(false);
  });
})();
