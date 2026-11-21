/* 律动柱：伪频谱 + 帧率无关物理衰减（解析解，按 dt 计算）
 * 可选：麦克风真实频谱（Web Audio AnalyserNode，对数分箱 + 自动增益）
 */
(function () {
  const S = window.Sonance;

  S.onReady(() => {
    const cv = S.$('#vizCanvas');
    if (!cv) return;
    const ctx = cv.getContext('2d');
    let W = 0, H = 0, dpr = 1, N = 40;
    let style = 'bars';
    let h = [], peak = [], peakV = [];
    let analyser = null, freq = null, agc = 0.25, stream = null;

    function resize() {
      dpr = Math.min(2, window.devicePixelRatio || 1);
      W = cv.clientWidth; H = cv.clientHeight;
      cv.width = W * dpr; cv.height = H * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      N = W < 560 ? 26 : W < 900 ? 36 : 44;
      h = new Array(N).fill(0); peak = new Array(N).fill(0); peakV = new Array(N).fill(0);
    }
    resize();
    window.addEventListener('resize', resize);

    // 伪频谱：低频带节拍，高频带噪声，整体向高频滚降
    function fakeTarget(i, t) {
      const x = i / (N - 1);
      const beat = Math.pow(Math.max(0, Math.sin(t * Math.PI * 2 * 1.9)), 10);   // ~114 BPM
      const off = Math.pow(Math.max(0, Math.sin(t * Math.PI * 2 * 1.9 + 2.2)), 14);
      const roll = 0.95 - x * 0.62;
      const n1 = 0.5 + 0.5 * Math.sin(t * 3.1 + i * 0.9) * Math.sin(t * 1.7 + i * 0.37);
      const n2 = 0.5 + 0.5 * Math.sin(t * 7.3 + i * 2.3);
      let v = roll * (0.42 + 0.38 * n1 + 0.12 * n2);
      v += (1 - x) * (1 - x) * beat * 0.55 + (x > 0.35 && x < 0.75 ? off * 0.25 : 0);
      if (x > 0.82) v *= 0.4 + 0.6 * n2;
      return S.clamp(v, 0.02, 1);
    }
    function micTarget(i) {
      const bins = freq.length;
      const lo = Math.floor(Math.pow(bins, i / N)), hi = Math.max(lo + 1, Math.floor(Math.pow(bins, (i + 1) / N)));
      let m = 0;
      for (let b = lo; b < hi && b < bins; b++) m = Math.max(m, freq[b]);
      return m / 255;
    }

    const ATTACK = 28, RELEASE = 5.5, G = 2.6;
    function update(dt, t) {
      let mx = 0;
      if (analyser) { analyser.getByteFrequencyData(freq); }
      for (let i = 0; i < N; i++) {
        let tg = analyser ? micTarget(i) : fakeTarget(i, t);
        if (analyser) { mx = Math.max(mx, tg); tg = S.clamp(tg / Math.max(agc, 0.08), 0, 1); }
        if (!S.motionOn) tg = h[i];
        // 帧率无关：指数趋近的解析解（上升快、下降慢）
        h[i] = S.approach(h[i], tg, tg > h[i] ? ATTACK : RELEASE, dt);
        // 峰值帽：匀加速下落的解析解 y = y0 - v0·dt - ½g·dt²
        if (h[i] >= peak[i]) { peak[i] = h[i]; peakV[i] = 0; }
        else { peak[i] = Math.max(h[i], peak[i] - peakV[i] * dt - 0.5 * G * dt * dt); peakV[i] += G * dt; }
      }
      if (analyser) agc = mx > agc ? S.approach(agc, mx, 12, dt) : S.approach(agc, mx, 0.6, dt);
    }

    function draw() {
      ctx.clearRect(0, 0, W, H);
      const base = style === 'mirror' ? H * 0.5 : H * 0.78;
      const maxH = style === 'mirror' ? H * 0.42 : H * 0.68;
      const gap = Math.max(3, W / N * 0.26), bw = (W - gap * (N + 1)) / N;
      const grad = ctx.createLinearGradient(0, base - maxH, 0, base);
      grad.addColorStop(0, '#2346A8'); grad.addColorStop(1, '#3D5BD9');

      if (style === 'wave') {
        const pts = h.map((v, i) => [gap + i * (bw + gap) + bw / 2, base - v * maxH]);
        const path = new Path2D();
        path.moveTo(0, base); path.lineTo(pts[0][0], pts[0][1]);
        for (let i = 0; i < pts.length - 1; i++) {
          const mx = (pts[i][0] + pts[i + 1][0]) / 2, my = (pts[i][1] + pts[i + 1][1]) / 2;
          path.quadraticCurveTo(pts[i][0], pts[i][1], mx, my);
        }
        path.lineTo(pts[pts.length - 1][0], pts[pts.length - 1][1]); path.lineTo(W, base); path.closePath();
        const fg = ctx.createLinearGradient(0, base - maxH, 0, base);
        fg.addColorStop(0, 'rgba(35,70,168,.85)'); fg.addColorStop(1, 'rgba(61,91,217,.25)');
        ctx.fillStyle = fg; ctx.fill(path);
        ctx.strokeStyle = '#2346A8'; ctx.lineWidth = 2.5; ctx.stroke(path);
        // 倒影
        ctx.save(); ctx.translate(0, base * 2); ctx.scale(1, -1); ctx.globalAlpha = 0.14; ctx.fillStyle = '#3D5BD9'; ctx.fill(path); ctx.restore();
        return;
      }
      for (let i = 0; i < N; i++) {
        const x = gap + i * (bw + gap);
        const bh = Math.max(2, h[i] * maxH);
        ctx.fillStyle = grad;
        if (style === 'mirror') {
          ctx.fillRect(x, base - bh, bw, bh * 2);
        } else {
          ctx.fillRect(x, base - bh, bw, bh);
          // 倒影（复刻截图：地平线下半透明倒影）
          const rg = ctx.createLinearGradient(0, base, 0, base + bh * 0.5);
          rg.addColorStop(0, 'rgba(61,91,217,.28)'); rg.addColorStop(1, 'rgba(61,91,217,0)');
          ctx.fillStyle = rg; ctx.fillRect(x, base + 2, bw, bh * 0.5);
          // 峰值帽
          ctx.fillStyle = 'rgba(35,70,168,.9)';
          ctx.fillRect(x, base - peak[i] * maxH - 6, bw, 3);
        }
      }
      if (style !== 'mirror') {
        ctx.fillStyle = 'rgba(255,255,255,.55)';
        ctx.fillRect(0, base, W, 1.5);
      }
    }

    const hz = S.$('#vizHz');
    let hzAcc = 0;
    S.tick((dt, t) => {
      update(dt, t);
      draw();
      hzAcc += dt;
      if (hzAcc > 0.5) { hzAcc = 0; hz.textContent = Math.round(S.fps) + ' Hz'; }
    }, cv);

    const seg = S.$('#vizSeg');
    seg.addEventListener('click', e => {
      const b = e.target.closest('button'); if (!b) return;
      S.$$('button', seg).forEach(x => x.classList.toggle('on', x === b));
      style = b.dataset.v;
    });

    const mic = S.$('#vizMic');
    mic.addEventListener('click', async () => {
      if (analyser) {
        stream.getTracks().forEach(t => t.stop());
        analyser = null; stream = null;
        if (S.bgm) S.bgm.duck(false);
        mic.classList.remove('on'); S.$('span', mic).textContent = '用麦克风试试';
        return;
      }
      try {
        stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        const ac = new (window.AudioContext || window.webkitAudioContext)();
        const src = ac.createMediaStreamSource(stream);
        analyser = ac.createAnalyser();
        analyser.fftSize = 2048; analyser.smoothingTimeConstant = 0.5;
        freq = new Uint8Array(analyser.frequencyBinCount);
        src.connect(analyser);
        if (S.bgm) S.bgm.duck(true);
        mic.classList.add('on'); S.$('span', mic).textContent = '关闭麦克风';
        S.toast('正在用麦克风采样，声音不会离开你的浏览器');
      } catch (err) {
        S.toast('没有拿到麦克风权限，继续用演示频谱');
      }
    });
  });
})();
