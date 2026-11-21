/* 滚动即换歌：每个章节绑定一张封面，滚动位置驱动全站晕染颜色插值
 * 对应 App 的 GlowTransition.SWIPE（跟手）：滑到一半，颜色就混到一半。
 */
(function () {
  const S = window.Sonance;
  const DARK = { g1: '#6B4426', g2: '#3D2B1E', g3: '#4A3020', bg: '#1A1410', drop: '#D98A22' };
  const KEYS = ['g1', 'g2', 'g3', 'bg'];

  const G = (S.glow = { sections: [], active: -1, dirty: true, maxScroll: 0, vh: 800 });
  let glowEl = null;
  let lastPaint = 0;

  G.setSectionGlow = function (el, hex, instant) {
    const sec = G.sections.find(s => s.el === el);
    if (!sec) return;
    const to = S.palette(hex);
    if (G.sections[G.active] === sec) {
      document.documentElement.style.setProperty('--drop', to.drop);
    }
    if (instant) { sec.pal = to; G.dirty = true; lastPaint = 0; return; }
    S.tweenColors(el, sec.pal, to, 900, cur => { sec.pal = cur; G.dirty = true; });
  };

  function measure() {
    const y0 = window.scrollY;
    G.vh = window.innerHeight || 800;
    G.maxScroll = Math.max(0, document.documentElement.scrollHeight - G.vh);
    G.sections.forEach(s => {
      const r = s.el.getBoundingClientRect();
      s.top = r.top + y0;
      s.h = r.height;
      s.mid = s.top + Math.min(r.height, G.vh) / 2;
    });
    G.dirty = true;
    lastPaint = 0;
  }

  function apply(forcePaint) {
    const secs = G.sections;
    if (!secs.length) return;
    const sy = window.scrollY;
    const y = sy + G.vh / 2;

    // 进度条每帧轻量化同步（不读 scrollHeight，不改 :root 变量）
    G.progress = G.maxScroll > 0 ? S.clamp(sy / G.maxScroll, 0, 1) : 0;
    document.dispatchEvent(new CustomEvent('s:progress', { detail: G.progress }));

    // 当前曲目 = 视口中线所在章节；仅切换章节时才改 :root 的 --drop
    let act = 0;
    secs.forEach((s, k) => { if (y >= s.top) act = k; });
    if (act !== G.active) {
      G.active = act;
      document.body.classList.toggle('on-dark', !!secs[act].dark);
      document.documentElement.style.setProperty('--drop', secs[act].pal.drop);
      document.dispatchEvent(new CustomEvent('s:track', { detail: { index: act, section: secs[act] } }));
      forcePaint = true;
    }

    // 节流背景渐变色重绘（最多约每 60ms 一次），避免快速滚动时整屏反复光栅化
    const now = performance.now();
    if (!forcePaint && now - lastPaint < 60) {
      G.dirty = true;
      return;
    }
    lastPaint = now;

    let i = 0;
    while (i < secs.length - 1 && y >= secs[i + 1].mid) i++;
    let pal;
    if (i >= secs.length - 1 || y < secs[0].mid) {
      pal = (y < secs[0].mid ? secs[0] : secs[secs.length - 1]).pal;
    } else {
      const a = secs[i], b = secs[i + 1];
      const t = S.smooth(0.18, 0.82, (y - a.mid) / (b.mid - a.mid));
      pal = {};
      KEYS.forEach(k => { pal[k] = SColor.mix(a.pal[k], b.pal[k], t); });
    }
    if (glowEl) {
      const st = glowEl.style;
      st.setProperty('--g1', pal.g1);
      st.setProperty('--g2', pal.g2);
      st.setProperty('--g3', pal.g3);
      st.setProperty('--bg-tone', pal.bg);
    }
  }

  S.onReady(() => {
    glowEl = document.getElementById('glow');
    G.sections = S.$$('section[data-cover]').map((el, idx) => {
      const dark = el.hasAttribute('data-dark');
      const cover = SCovers.get(el.dataset.cover);
      return {
        el, idx, dark,
        cover: el.dataset.cover,
        title: el.dataset.title || '',
        sub: el.dataset.sub || '',
        pal: dark ? { ...DARK } : S.palette(cover.glow)
      };
    });
    measure();
    apply(true);
    window.addEventListener('scroll', () => { G.dirty = true; }, { passive: true });
    window.addEventListener('resize', measure, { passive: true });
    window.addEventListener('load', measure);
    setTimeout(measure, 1200);
    if ('ResizeObserver' in window) new ResizeObserver(measure).observe(document.body);
    S.tick(() => { if (G.dirty) { G.dirty = false; apply(false); } });
  });
})();
