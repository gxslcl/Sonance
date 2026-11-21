/* 手机里的播放页：用 HTML 实时搭建（不是截图），颜色才能跟着封面变
 * - 5 种晕染方式（GlowMode）  - 4 种切歌动效（PlayerSwitchMotion）
 * - 封面取色 → 晕染 / 水滴键颜色（PlayButtonColorMode.COVER）
 */
(function () {
  const S = window.Sonance;
  const GLOW_HINT = {
    bottom: '只从封面下边缘向外晕开',
    sides: '从封面左右两侧向外晕开',
    around: '封面四条边都向外晕开，上方也带一点',
    full: '扩散到屏幕边缘才淡出，范围最大',
    immersive: '整屏铺满封面主色，浓度更深，底部按钮区自动减淡'
  };
  const MOTION_HINT = {
    slide: '双封面并排横滑切换，标题同步随动',
    depth: '旧封面退后滑离，新封面弹性推入，文字错峰轻跟',
    tilt: '空间透视旋换 + 丝绸高光掠过，歌名纵向微滚切换',
    dissolve: '光学呼吸缩放 + 主色光雾溶切，东方留白静谧手感'
  };
  const fmt = s => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;

  function phoneColors(glow) {
    const c = SColor;
    return {
      pc: c.pastel(glow, 0.70, 0.42),
      pc2: c.pastel(glow, 0.88, 0.30),
      pcd: c.pastel(glow, 0.60, 0.40),
      base: c.pastel(glow, 0.93, 0.12),
      drop: c.deep(glow, 0.44)
    };
  }

  function create(root, opts = {}) {
    const ic = SIcons.svg;
    root.innerHTML = `
      <div class="ph-screen">
        <div class="ph-glows">${['bottom', 'sides', 'around', 'full', 'immersive'].map(g => `<i data-g="${g}"></i>`).join('')}</div>
        <div class="ph-status"><span>13:15</span><span class="ph-sicons"><i></i><i></i><b>53</b></span></div>
        <div class="ph-top">
          <span class="ph-circ">${ic('chevronDown')}</span>
          <span class="ph-viz"><i></i><i></i><i></i></span>
          <span class="ph-caps">${ic('vinyl')}<em></em>${ic('more')}</span>
        </div>
        <div class="ph-coverbox"><div class="ph-covers"></div></div>
        <div class="ph-badge"><span class="hires-badge"><b>Hi·Res</b><small>AUDIO</small></span></div>
        <div class="ph-info">
          <span class="ph-heart">${ic('heart')}</span>
          <div class="ph-meta"><b></b><span></span><small></small></div>
          <span class="ph-lypill">${ic('lyrics')}词</span>
        </div>
        <div class="ph-prog">
          <div class="ph-track"><i></i><b></b></div>
          <div class="ph-times"><span class="t0">0:00</span><span>1.0×</span><span class="t1">0:00</span></div>
        </div>
        <div class="ph-ctrl">
          <button type="button" class="ph-btn acc" tabindex="-1" aria-hidden="true">${ic('shuffle')}</button>
          <button type="button" class="ph-btn ph-prev" aria-label="上一首">${ic('prev')}</button>
          <s-drop playing class="ph-drop" role="button" aria-label="播放 / 暂停">${ic('pause')}</s-drop>
          <button type="button" class="ph-btn ph-next" aria-label="下一首">${ic('next')}</button>
          <button type="button" class="ph-btn" tabindex="-1" aria-hidden="true">${ic('queue')}</button>
        </div>
        <div class="ph-vol">${ic('volume')}<div class="ph-vtrack"><i></i></div><span class="acc">${ic('sliders')}</span></div>
        <div class="ph-home"></div>
      </div>`;

    const scr = S.$('.ph-screen', root);
    const covers = S.$('.ph-covers', root);
    const meta = S.$('.ph-meta', root);
    const fill = S.$('.ph-track i', root), knob = S.$('.ph-track b', root);
    const t0 = S.$('.t0', root), t1 = S.$('.t1', root);
    const drop = S.$('.ph-drop', root);

    const st = {
      list: opts.covers || ['sunset', 'halftone', 'checker', 'conicA'],
      idx: 0, glow: opts.glow || 'around', motion: opts.motion || 'slide',
      colors: null, playing: true, pos: 27, dur: 296, custom: null
    };

    function applyColors(c) {
      scr.style.setProperty('--pc', c.pc);
      scr.style.setProperty('--pc2', c.pc2);
      scr.style.setProperty('--pcd', c.pcd);
      scr.style.setProperty('--pbase', c.base);
      scr.style.setProperty('--drop', c.drop);
    }
    function setColors(glowHex, instant) {
      const to = phoneColors(glowHex);
      if (!st.colors || instant) { st.colors = to; applyColors(to); return; }
      S.tweenColors(scr, st.colors, to, 800, cur => { st.colors = cur; applyColors(cur); });
    }
    function setGlowMode(g) {
      st.glow = g;
      S.$$('.ph-glows i', root).forEach(i => i.classList.toggle('on', i.dataset.g === g));
      scr.dataset.glow = g;
    }
    function metaFor(item) {
      if (item.custom) return { title: item.title, artist: '你的图片', album: '本地预览' };
      const c = SCovers.get(item.id);
      return { title: c.title, artist: c.artist, album: c.title + ' · Single' };
    }
    function slideHtml(item) {
      if (item.custom) return `<div class="cv cv-img" style="background-image:url('${item.url}')"></div>`;
      return SCovers.html(item.id);
    }
    function current() { return st.custom || { id: st.list[st.idx] }; }
    function renderMeta(item) {
      const m = metaFor(item);
      meta.innerHTML = `<b>${m.title}</b><span>${m.artist}</span><small>${m.album}</small>`;
    }

    let lastSec = -1, lastDur = -1, barAcc = 1;

    function show(item, dir = 1, first = false) {
      const slide = document.createElement('div');
      slide.className = 'ph-slide';
      slide.innerHTML = slideHtml(item);
      const old = S.$$('.ph-slide', covers);
      const m = st.motion;
      if (!first && !S.reduced) {
        covers.style.setProperty('--dir', dir);
        slide.classList.add('in-' + m);
        old.forEach(o => { o.classList.remove('in-slide', 'in-depth', 'in-tilt', 'in-dissolve'); o.classList.add('out-' + m); setTimeout(() => o.remove(), 760); });
        meta.classList.remove('swap'); void meta.offsetWidth; meta.classList.add('swap');
        if (m === 'tilt') { const sh = document.createElement('i'); sh.className = 'ph-sheen'; covers.appendChild(sh); setTimeout(() => sh.remove(), 900); }
        if (m === 'dissolve') { const fog = document.createElement('i'); fog.className = 'ph-fog'; covers.appendChild(fog); setTimeout(() => fog.remove(), 900); }
      } else old.forEach(o => o.remove());
      covers.appendChild(slide);
      renderMeta(item);
      st.pos = first ? st.pos : 0;
      st.dur = 180 + Math.floor(Math.random() * 140);
      lastSec = -1; lastDur = -1; barAcc = 1;
      const glowHex = item.custom ? item.glow : SCovers.get(item.id).glow;
      setColors(glowHex, first);
      root.dispatchEvent(new CustomEvent('ph:cover', { detail: { item, glow: glowHex } }));
    }

    function step(d) {
      st.custom = null;
      st.idx = (st.idx + d + st.list.length) % st.list.length;
      show(current(), d);
    }
    function setCover(id) {
      st.custom = null;
      const i = st.list.indexOf(id);
      const d = i >= st.idx ? 1 : -1;
      st.idx = i < 0 ? 0 : i;
      show(current(), d);
    }
    function setCustom(url, glow, title) {
      st.custom = { custom: true, url, glow, title };
      show(st.custom, 1);
    }

    S.$('.ph-prev', root).addEventListener('click', () => step(-1));
    S.$('.ph-next', root).addEventListener('click', () => step(1));
    drop.addEventListener('click', () => {
      st.playing = !st.playing;
      drop.toggleAttribute('playing', st.playing);
      S.$('.drop-ic', drop).innerHTML = ic(st.playing ? 'pause' : 'play');
      scr.classList.toggle('paused', !st.playing);
    });

    // 进度推进（文本按整秒、进度条约 4Hz 更新，避免每帧无意义 DOM 写入）
    S.tick(dt => {
      if (st.playing && S.motionOn) st.pos = Math.min(st.dur, st.pos + dt);
      barAcc += dt;
      if (barAcc >= 0.25) {
        barAcc = 0;
        const k = st.pos / st.dur;
        fill.style.transform = `scaleX(${k})`;
        knob.style.left = (k * 100).toFixed(2) + '%';
      }
      const sec = Math.floor(st.pos);
      if (sec !== lastSec) { lastSec = sec; t0.textContent = fmt(sec); }
      if (st.dur !== lastDur) { lastDur = st.dur; t1.textContent = fmt(st.dur); }
    }, root);

    setGlowMode(st.glow);
    show(current(), 1, true);

    return {
      root, st, step, setCover, setCustom, setGlowMode,
      setMotion: m => { st.motion = m; },
      get playing() { return st.playing && S.motionOn; }
    };
  }

  window.SPhone = { create, GLOW_HINT, MOTION_HINT };

  /* ---------- 页面接线 ---------- */
  S.onReady(() => {
    // Hero：自动换歌
    const heroEl = S.$('[data-phone="hero"]');
    if (heroEl) {
      const heroSec = S.$('#hero');
      const hp = create(heroEl, { covers: ['sunset', 'halftone', 'checker', 'conicA', 'dotsCoral'], glow: 'around' });
      heroEl.addEventListener('ph:cover', e => {
        S.glow.setSectionGlow(heroSec, e.detail.glow);
        const sec = S.glow.sections.find(s => s.el === heroSec);
        if (sec && e.detail.item.id) sec.coverOverride = e.detail.item.id;
      });
      let acc = 0;
      S.tick(dt => {
        if (!hp.playing || document.hidden) return;
        acc += dt;
        if (acc > 6) { acc = 0; hp.step(1); }
      }, heroEl);
    }

    // 播放页演示
    const demoEl = S.$('[data-phone="demo"]');
    if (!demoEl) return;
    const sec = S.$('#player');
    const list = ['conicA', 'sunset', 'halftone', 'checker', 'rings', 'dotsCoral', 'type', 'conicB'];
    const ph = create(demoEl, { covers: list, glow: 'around' });
    const dots = S.$('#coverDots');
    dots.innerHTML = list.map((id, i) => `<button type="button" role="radio" aria-checked="${i === 0}" aria-label="封面：${SCovers.get(id).title}" data-id="${id}" class="${i === 0 ? 'on' : ''}">${SCovers.html(id)}</button>`).join('');
    dots.addEventListener('click', e => {
      const b = e.target.closest('button'); if (!b) return;
      ph.setCover(b.dataset.id);
    });
    demoEl.addEventListener('ph:cover', e => {
      S.glow.setSectionGlow(sec, e.detail.glow);
      const s = S.glow.sections.find(x => x.el === sec);
      if (s) s.coverOverride = e.detail.item.id || null;
      S.$$('button', dots).forEach(b => {
        const on = b.dataset.id === e.detail.item.id;
        b.classList.toggle('on', on); b.setAttribute('aria-checked', on);
      });
    });

    const seg = (id, fn) => {
      const el = S.$(id);
      el.addEventListener('click', e => {
        const b = e.target.closest('button'); if (!b) return;
        S.$$('button', el).forEach(x => { x.classList.toggle('on', x === b); x.setAttribute('aria-checked', x === b); });
        fn(b.dataset.v);
      });
    };
    seg('#glowSeg', v => { ph.setGlowMode(v); S.$('#glowHint').textContent = GLOW_HINT[v]; });
    seg('#motionSeg', v => { ph.setMotion(v); S.$('#motionHint').textContent = MOTION_HINT[v]; ph.step(1); });
    S.$('#swPrev').addEventListener('click', () => ph.step(-1));
    S.$('#swNext').addEventListener('click', () => ph.step(1));

    // 水滴 / 普通
    const shapeDemo = S.$('#shapeDemo');
    seg('#shapeSeg', v => {
      shapeDemo.setAttribute('shape', v);
      S.$$('.ph-drop').forEach(d => d.setAttribute('shape', v));
    });
    shapeDemo.addEventListener('click', () => {
      const p = !shapeDemo.hasAttribute('playing');
      shapeDemo.toggleAttribute('playing', p);
      S.$('.drop-ic', shapeDemo).innerHTML = SIcons.svg(p ? 'pause' : 'play');
    });

    // 拖入 / 选择自己的图片：本地取色
    const zone = S.$('#dropZone'), file = S.$('#coverFile');
    function useFile(f) {
      if (!f || !f.type.startsWith('image/')) { S.toast('请选择一张图片'); return; }
      const url = URL.createObjectURL(f);
      const img = new Image();
      img.onload = () => {
        const { main } = SColor.extract(img);
        ph.setCustom(url, main, f.name.replace(/\.[^.]+$/, '').slice(0, 18) || '我的封面');
        S.toast('已从你的图片里取色：' + main.toUpperCase());
      };
      img.src = url;
    }
    file.addEventListener('change', () => useFile(file.files[0]));
    ['dragenter', 'dragover'].forEach(t => zone.addEventListener(t, e => { e.preventDefault(); zone.classList.add('over'); }));
    ['dragleave', 'drop'].forEach(t => zone.addEventListener(t, e => { e.preventDefault(); zone.classList.remove('over'); }));
    zone.addEventListener('drop', e => useFile(e.dataTransfer.files[0]));
  });
})();
