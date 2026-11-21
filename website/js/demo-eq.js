/* 音效工作室交互：音色预设、10 段 64-bit 风格均衡器曲线、A/B 对比、声场效果、伴奏消音与可选 Web Audio 试听 */
(function () {
  const S = window.Sonance;

  function fmtFreq(f) {
    return f >= 1000 ? (f / 1000) + 'k' : String(f);
  }
  function fmtDb(v) {
    const r = Math.abs(v) < 0.05 ? 0 : v;
    return (r > 0 ? '+' : '') + r.toFixed(1);
  }
  // 将 10 个频点 (-12..+12 dB) 映射成 SVG 路径（Catmull-Rom 转三次贝塞尔）
  function buildCurve(vals, w, h, padX, padY) {
    const n = vals.length;
    const pts = vals.map((db, i) => [
      padX + (i / (n - 1)) * (w - padX * 2),
      padY + ((12 - S.clamp(db, -12, 12)) / 24) * (h - padY * 2)
    ]);
    let d = `M${pts[0][0].toFixed(1)},${pts[0][1].toFixed(1)}`;
    for (let i = 0; i < n - 1; i++) {
      const p0 = pts[Math.max(0, i - 1)];
      const p1 = pts[i];
      const p2 = pts[i + 1];
      const p3 = pts[Math.min(n - 1, i + 2)];
      const c1x = p1[0] + (p2[0] - p0[0]) / 6;
      const c1y = S.clamp(p1[1] + (p2[1] - p0[1]) / 6, padY - 12, h - padY + 12);
      const c2x = p2[0] - (p3[0] - p1[0]) / 6;
      const c2y = S.clamp(p2[1] - (p3[1] - p1[1]) / 6, padY - 12, h - padY + 12);
      d += ` C${c1x.toFixed(1)},${c1y.toFixed(1)} ${c2x.toFixed(1)},${c2y.toFixed(1)} ${p2[0].toFixed(1)},${p2[1].toFixed(1)}`;
    }
    return { d, pts };
  }

  S.onReady(() => {
    const panel = S.$('#studioPanel');
    const data = window.SONANCE_EQ;
    if (!panel || !data) return;

    const freqs = data.freqs;
    const presets = data.presets;
    const quickIds = ['off', 'extreme_hifi', 'vocal', 'bass', 'live', 'night'];

    const st = {
      cat: '全部',
      presetId: 'extreme_hifi',
      vals: [...presets[1].eq],
      targetVals: [...presets[1].eq],
      bypass: false, // false = A (开启), true = B (旁通)
      field: { width: 110, bass: 18, reverb: 14, bs2b: false, hpf: true },
      karaoke: false,
      karaDepth: 0
    };

    /* ---------- 1. 页签切换 ---------- */
    const tabBtns = S.$$('.studio-tabs button[data-tab]', panel);
    const panes = S.$$('.pane[data-pane]', panel);
    tabBtns.forEach(btn => {
      btn.addEventListener('click', () => {
        const k = btn.dataset.tab;
        tabBtns.forEach(b => b.classList.toggle('on', b === btn));
        panes.forEach(p => p.classList.toggle('on', p.dataset.pane === k));
      });
    });

    /* ---------- 2. 音色预设页签 ---------- */
    const catsEl = S.$('#presetCats', panel);
    const gridEl = S.$('#presetGrid', panel);
    const allCats = ['全部', ...data.categories];

    catsEl.innerHTML = allCats.map(c =>
      `<button type="button" data-cat="${c}" class="${c === st.cat ? 'on' : ''}">${c}</button>`
    ).join('');

    function renderPresets() {
      const list = st.cat === '全部' ? presets : presets.filter(p => p.cat === st.cat);
      gridEl.innerHTML = list.map(p => {
        const { d } = buildCurve(p.eq, 120, 38, 4, 6);
        const on = p.id === st.presetId;
        return `<button type="button" class="preset-card${on ? ' on' : ''}" data-pid="${p.id}">
          <div class="preset-top"><b>${p.name}</b><span class="preset-badge">${p.cat}</span></div>
          <p class="preset-desc">${p.desc}</p>
          <svg class="preset-mini" viewBox="0 0 120 38" aria-hidden="true">
            <line x1="4" y1="19" x2="116" y2="19" stroke="currentColor" stroke-opacity=".18" stroke-dasharray="2 2"/>
            <path d="${d}" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
        </button>`;
      }).join('');
    }
    catsEl.addEventListener('click', e => {
      const b = e.target.closest('button[data-cat]');
      if (!b) return;
      st.cat = b.dataset.cat;
      S.$$('button', catsEl).forEach(x => x.classList.toggle('on', x === b));
      renderPresets();
    });

    /* ---------- 3. 均衡器页签 ---------- */
    const curveSvg = S.$('#eqCurve', panel);
    const slidersEl = S.$('#eqSliders', panel);
    const chipsEl = S.$('#eqChips', panel);
    const abBtn = S.$('#abToggle', panel);
    const listenBtn = S.$('#eqListen', panel);

    slidersEl.innerHTML = freqs.map((f, i) => `
      <div class="eq-col" data-i="${i}">
        <span class="eq-db tnum">${fmtDb(st.vals[i])}</span>
        <div class="eq-fader">
          <div class="eq-track"><i class="eq-zero"></i><b class="eq-fill"></b><em class="eq-thumb"></em></div>
          <input type="range" class="eq-range" min="-12" max="12" step="0.2" value="${st.vals[i]}" aria-label="${fmtFreq(f)} Hz 增益">
        </div>
        <span class="eq-freq tnum">${fmtFreq(f)}</span>
      </div>`).join('');

    const cols = S.$$('.eq-col', slidersEl);
    const inputs = cols.map(c => S.$('.eq-range', c));
    const dbLabels = cols.map(c => S.$('.eq-db', c));
    const fills = cols.map(c => S.$('.eq-fill', c));
    const thumbs = cols.map(c => S.$('.eq-thumb', c));

    chipsEl.innerHTML = quickIds.map(id => {
      const p = presets.find(x => x.id === id);
      return p ? `<button type="button" class="eq-chip${p.id === st.presetId ? ' on' : ''}" data-pid="${p.id}">${p.name}</button>` : '';
    }).join('');

    function syncChips() {
      S.$$('.eq-chip', chipsEl).forEach(c => c.classList.toggle('on', c.dataset.pid === st.presetId));
      S.$$('.preset-card', gridEl).forEach(c => c.classList.toggle('on', c.dataset.pid === st.presetId));
    }

    function applyPreset(id) {
      const p = presets.find(x => x.id === id);
      if (!p) return;
      st.presetId = id;
      st.targetVals = [...p.eq];
      if (st.bypass) {
        st.bypass = false;
        syncAB();
      }
      syncChips();
    }

    gridEl.addEventListener('click', e => {
      const card = e.target.closest('.preset-card[data-pid]');
      if (!card) return;
      applyPreset(card.dataset.pid);
      S.toast('已切换到预设：' + presets.find(p => p.id === card.dataset.pid).name);
    });

    chipsEl.addEventListener('click', e => {
      const chip = e.target.closest('.eq-chip[data-pid]');
      if (!chip) return;
      applyPreset(chip.dataset.pid);
    });

    inputs.forEach((inp, i) => {
      inp.addEventListener('input', () => {
        const v = parseFloat(inp.value);
        st.vals[i] = st.targetVals[i] = v;
        st.presetId = '';
        if (st.bypass) {
          st.bypass = false;
          syncAB();
        }
        syncChips();
        drawEq();
        syncAudio();
      });
    });

    function syncAB() {
      const sp = S.$$('span', abBtn);
      sp[0].classList.toggle('on', !st.bypass);
      sp[1].classList.toggle('on', st.bypass);
      abBtn.setAttribute('aria-pressed', String(st.bypass));
      slidersEl.classList.toggle('bypassed', st.bypass);
      drawEq();
      syncAudio();
    }
    abBtn.addEventListener('click', () => {
      st.bypass = !st.bypass;
      syncAB();
      S.toast(st.bypass ? 'B · 旁通原声对比' : 'A · 已开启均衡器');
    });

    function drawEq() {
      const W = 1000, H = 220, padX = 48, padY = 24;
      const midY = H / 2;
      const shown = st.bypass ? new Array(10).fill(0) : st.vals;
      const { d, pts } = buildCurve(shown, W, H, padX, padY);
      const area = `${d} L${pts[pts.length - 1][0].toFixed(1)},${midY} L${pts[0][0].toFixed(1)},${midY} Z`;

      let ghost = '';
      if (st.bypass) {
        const g = buildCurve(st.vals, W, H, padX, padY);
        ghost = `<path d="${g.d}" fill="none" stroke="var(--accent)" stroke-opacity=".28" stroke-width="2" stroke-dasharray="5 5"/>`;
      }

      const gridLines = [-12, -6, 0, 6, 12].map(db => {
        const y = padY + ((12 - db) / 24) * (H - padY * 2);
        const cls = db === 0 ? 'eq-grid-zero' : 'eq-grid-line';
        const lbl = db === 0 ? '0 dB' : (db > 0 ? `+${db}` : String(db));
        return `<line x1="${padX - 10}" y1="${y}" x2="${W - padX + 10}" y2="${y}" class="${cls}"/>
                <text x="6" y="${y + 4}" class="eq-grid-lbl">${lbl}</text>`;
      }).join('');

      const dots = pts.map(([x, y]) =>
        `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="5.5" class="eq-pt"/>`
      ).join('');

      curveSvg.innerHTML = `<defs>
        <linearGradient id="eqAreaG" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="var(--accent)" stop-opacity=".28"/>
          <stop offset="50%" stop-color="var(--accent)" stop-opacity=".06"/>
          <stop offset="100%" stop-color="var(--accent)" stop-opacity=".24"/>
        </linearGradient>
      </defs>${gridLines}${ghost}<path d="${area}" fill="url(#eqAreaG)"/><path d="${d}" fill="none" stroke="var(--accent)" stroke-width="3" stroke-linecap="round"/>${dots}`;

      // 同步 10 根推子轨道、滑块、读数
      for (let i = 0; i < 10; i++) {
        const v = st.vals[i];
        const pct = ((v + 12) / 24) * 100;
        const fillFrom = Math.min(50, pct);
        const fillH = Math.abs(pct - 50);
        inputs[i].value = v.toFixed(1);
        dbLabels[i].textContent = fmtDb(st.bypass ? 0 : v);
        thumbs[i].style.bottom = `calc(${pct.toFixed(2)}% - 8px)`;
        fills[i].style.bottom = `${fillFrom.toFixed(2)}%`;
        fills[i].style.height = `${fillH.toFixed(2)}%`;
      }
    }

    /* ---------- 4. 声场效果页签 ---------- */
    const fieldEl = S.$('#fieldGrid', panel);
    fieldEl.innerHTML = `
      <div class="field-item">
        <div class="field-head"><b>声场宽度</b><span class="tnum" id="fwVal">${st.field.width}%</span></div>
        <input type="range" id="fwRange" min="80" max="160" step="2" value="${st.field.width}" aria-label="声场宽度">
        <p>自然拓宽立体声横向分离度，余量随宽度自适应补偿</p>
      </div>
      <div class="field-item">
        <div class="field-head"><b>虚拟低音</b><span class="tnum" id="fbVal">${st.field.bass}%</span></div>
        <input type="range" id="fbRange" min="0" max="100" step="2" value="${st.field.bass}" aria-label="虚拟低音">
        <p>合成基频谐波，让小尺寸耳机或手机外放也能听出下潜质感</p>
      </div>
      <div class="field-item">
        <div class="field-head"><b>厅堂混响（Freeverb）</b><span class="tnum" id="frVal">${st.field.reverb}%</span></div>
        <input type="range" id="frRange" min="0" max="100" step="2" value="${st.field.reverb}" aria-label="厅堂混响">
        <p>轻柔的早期反射与空气尾韵，消除干涩与压迫感</p>
      </div>
      <div class="field-item toggles">
        <label class="switch"><input type="checkbox" id="bs2bToggle"><span></span><div><b>BS2B 双耳交叉馈送</b><small>模拟音箱自然串音，减轻头戴耳机的头中效应</small></div></label>
        <label class="switch"><input type="checkbox" id="hpfToggle" checked><span></span><div><b>外放高通保护（58 Hz）</b><small>滤除扬声器无法重放的超低频，防止破音</small></div></label>
      </div>`;

    S.$('#fwRange', fieldEl).addEventListener('input', e => {
      st.field.width = +e.target.value;
      S.$('#fwVal', fieldEl).textContent = st.field.width + '%';
    });
    S.$('#fbRange', fieldEl).addEventListener('input', e => {
      st.field.bass = +e.target.value;
      S.$('#fbVal', fieldEl).textContent = st.field.bass + '%';
      syncAudio();
    });
    S.$('#frRange', fieldEl).addEventListener('input', e => {
      st.field.reverb = +e.target.value;
      S.$('#frVal', fieldEl).textContent = st.field.reverb + '%';
    });
    S.$('#bs2bToggle', fieldEl).addEventListener('change', e => { st.field.bs2b = e.target.checked; });
    S.$('#hpfToggle', fieldEl).addEventListener('change', e => { st.field.hpf = e.target.checked; syncAudio(); });

    /* ---------- 5. 伴奏消音页签 ---------- */
    const karaInput = S.$('#karaOn', panel);
    const karaPath = S.$('.kara-full', panel);
    const karaBand = S.$('.kara-band', panel);
    function drawKaraoke(depth) {
      const pts = [];
      for (let i = 0; i <= 48; i++) {
        const x = 20 + (i / 48) * 560;
        // 160 Hz - 4.8 kHz 大致落在 x = 150..420
        const inVocal = S.smooth(110, 175, x) * (1 - S.smooth(395, 455, x));
        const ripple = Math.sin(i * 0.65) * 8 + Math.cos(i * 0.3) * 6;
        const y = 78 + ripple + inVocal * depth * 72;
        pts.push([x, y]);
      }
      let d = `M${pts[0][0].toFixed(1)},${pts[0][1].toFixed(1)}`;
      for (let i = 1; i < pts.length; i++) {
        const mx = (pts[i - 1][0] + pts[i][0]) / 2;
        const my = (pts[i - 1][1] + pts[i][1]) / 2;
        d += ` Q${pts[i - 1][0].toFixed(1)},${pts[i - 1][1].toFixed(1)} ${mx.toFixed(1)},${my.toFixed(1)}`;
      }
      const last = pts[pts.length - 1];
      d += ` L${last[0].toFixed(1)},${last[1].toFixed(1)} L580,180 L20,180 Z`;
      karaPath.setAttribute('d', d);
      if (karaBand) karaBand.style.opacity = (0.28 + depth * 0.55).toFixed(2);
    }
    karaInput.addEventListener('change', () => { st.karaoke = karaInput.checked; });

    /* ---------- 6. 可选 Web Audio 合成音试听 ---------- */
    let audioCtx = null, eqFilters = [], bassNode = null, hpfNode = null, masterGain = null, oscList = [], audioPlaying = false;

    function syncAudio() {
      if (!audioPlaying || !audioCtx) return;
      const now = audioCtx.currentTime;
      eqFilters.forEach((f, i) => {
        const g = st.bypass ? 0 : st.vals[i];
        f.gain.setTargetAtTime(g, now, 0.03);
      });
      if (bassNode) bassNode.gain.setTargetAtTime((st.field.bass / 100) * 6, now, 0.04);
      if (hpfNode) hpfNode.frequency.setTargetAtTime(st.field.hpf ? 58 : 20, now, 0.04);
    }

    function stopAudio() {
      audioPlaying = false;
      if (S.bgm) S.bgm.duck(false);
      if (masterGain && audioCtx) {
        const now = audioCtx.currentTime;
        masterGain.gain.setTargetAtTime(0, now, 0.04);
      }
      setTimeout(() => {
        oscList.forEach(o => { try { o.stop(); o.disconnect(); } catch (_) {} });
        oscList = [];
        if (audioCtx) { try { audioCtx.close(); } catch (_) {} audioCtx = null; }
        eqFilters = []; bassNode = null; hpfNode = null; masterGain = null;
      }, 120);
      listenBtn.classList.remove('on');
      S.$('span', listenBtn).textContent = '试听合成音';
      listenBtn.querySelector('.ic').outerHTML = SIcons.svg('play');
    }

    function startAudio() {
      try {
        const AC = window.AudioContext || window.webkitAudioContext;
        audioCtx = new AC();
        masterGain = audioCtx.createGain();
        masterGain.gain.value = 0.001;

        hpfNode = audioCtx.createBiquadFilter();
        hpfNode.type = 'highpass';
        hpfNode.frequency.value = st.field.hpf ? 58 : 20;

        bassNode = audioCtx.createBiquadFilter();
        bassNode.type = 'lowshelf';
        bassNode.frequency.value = 100;
        bassNode.gain.value = (st.field.bass / 100) * 6;

        eqFilters = freqs.map((hz, i) => {
          const f = audioCtx.createBiquadFilter();
          f.type = 'peaking';
          f.frequency.value = hz;
          f.Q.value = 1.4;
          f.gain.value = st.bypass ? 0 : st.vals[i];
          return f;
        });

        let head = hpfNode;
        hpfNode.connect(bassNode);
        head = bassNode;
        eqFilters.forEach(f => { head.connect(f); head = f; });
        head.connect(masterGain);
        masterGain.connect(audioCtx.destination);

        // 温暖合成和弦（Cmaj9）+ 轻柔粉红噪声覆盖全频段，便于听出 EQ 变化
        [130.81, 196.0, 246.94, 293.66, 329.63].forEach((freq, idx) => {
          const osc = audioCtx.createOscillator();
          const g = audioCtx.createGain();
          osc.type = idx === 0 ? 'triangle' : 'sine';
          osc.frequency.value = freq;
          g.gain.value = idx === 0 ? 0.16 : 0.07;
          osc.connect(g); g.connect(hpfNode);
          osc.start();
          oscList.push(osc);
        });
        const buf = audioCtx.createBuffer(1, audioCtx.sampleRate * 2, audioCtx.sampleRate);
        const ch = buf.getChannelData(0);
        let b0 = 0, b1 = 0, b2 = 0;
        for (let i = 0; i < ch.length; i++) {
          const w = Math.random() * 2 - 1;
          b0 = 0.997 * b0 + w * 0.029;
          b1 = 0.985 * b1 + w * 0.032;
          b2 = 0.950 * b2 + w * 0.048;
          ch[i] = (b0 + b1 + b2 + w * 0.018) * 0.28;
        }
        const noise = audioCtx.createBufferSource();
        noise.buffer = buf; noise.loop = true;
        const ng = audioCtx.createGain(); ng.gain.value = 0.22;
        noise.connect(ng); ng.connect(hpfNode);
        noise.start();
        oscList.push(noise);

        audioPlaying = true;
        if (S.bgm) S.bgm.duck(true);
        masterGain.gain.setTargetAtTime(0.45, audioCtx.currentTime, 0.08);
        listenBtn.classList.add('on');
        S.$('span', listenBtn).textContent = '停止试听';
        listenBtn.querySelector('.ic').outerHTML = SIcons.svg('pause');
      } catch (_) {
        S.toast('当前浏览器不支持 Web Audio 试听');
      }
    }

    listenBtn.addEventListener('click', () => {
      if (audioPlaying) stopAudio();
      else startAudio();
    });

    /* ---------- 7. 帧循环：滑块平滑趋近 & 消音波形动画 ---------- */
    renderPresets();
    drawEq();
    drawKaraoke(0);

    S.tick(dt => {
      let dirty = false;
      for (let i = 0; i < 10; i++) {
        if (Math.abs(st.vals[i] - st.targetVals[i]) > 0.01) {
          st.vals[i] = S.approach(st.vals[i], st.targetVals[i], 11, dt);
          dirty = true;
        } else if (st.vals[i] !== st.targetVals[i]) {
          st.vals[i] = st.targetVals[i];
          dirty = true;
        }
      }
      if (dirty) { drawEq(); syncAudio(); }

      const kt = st.karaoke ? 1 : 0;
      if (Math.abs(st.karaDepth - kt) > 0.005) {
        st.karaDepth = S.approach(st.karaDepth, kt, 8, dt);
        drawKaraoke(st.karaDepth);
      }
    }, panel);
  });
})();
