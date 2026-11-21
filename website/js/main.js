/* 核心：全站配置、统一 rAF 调度、可见性、进场动画、通用工具 */
(function () {
  const S = (window.Sonance = window.Sonance || {});

  S.config = {
    version: '2.5',
    quarkUrl: 'https://pan.quark.cn/s/f5e9b71419b3',
    quarkCode: '我用夸克网盘给你分享了「山水有清音」，点击链接或复制整段内容，打开「夸克网盘APP」即可获取。\n亝一五枫及并词让哈走看忛\n/~602e3cRirc~:/',
    qqGroup: '1129486853',
    wechat: 'gxslcl',
    sha256: '6F96F0780C00067027455C92B7C40075CB0EC5AC6DFB6169E4D44FABFA03E913',
    minAndroid: 'Android 7.0+',
    repo: 'https://github.com/gxslcl/Sonance'
  };

  S.reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  S.motionOn = !S.reduced; // 迷你播放条上的水滴键可以开关全站动效

  /* ---------- 统一 rAF 调度：只跑可见模块 ---------- */
  const tasks = new Set();
  let last = performance.now();
  S.time = 0;
  S.fps = 60;
  let fpsAcc = 0, fpsN = 0;
  function frame(now) {
    const dt = Math.min(0.05, (now - last) / 1000);
    last = now;
    S.time += dt;
    fpsAcc += dt; fpsN++;
    if (fpsAcc > 0.5) { S.fps = fpsN / fpsAcc; fpsAcc = 0; fpsN = 0; }
    tasks.forEach(t => {
      if (t.el && !t.el.__visible) return;
      try { t.fn(dt, S.time); } catch (e) { console.error(e); tasks.delete(t); }
    });
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);
  /** 注册每帧任务；传 el 时只在 el 可见时执行 */
  S.tick = function (fn, el) {
    const t = { fn, el };
    if (el) S.watch(el);
    tasks.add(t);
    return () => tasks.delete(t);
  };

  /* ---------- 可见性 ---------- */
  const io = new IntersectionObserver(entries => {
    entries.forEach(e => {
      e.target.__visible = e.isIntersecting;
      e.target.dispatchEvent(new CustomEvent(e.isIntersecting ? 's:show' : 's:hide'));
    });
  }, { rootMargin: '120px 0px' });
  S.watch = el => { if (!el.__watched) { el.__watched = true; el.__visible = false; io.observe(el); } };

  /* ---------- 进场动画 ---------- */
  function initReveal() {
    const items = document.querySelectorAll('[data-reveal]');
    const rio = new IntersectionObserver(entries => {
      entries.forEach(e => {
        if (!e.isIntersecting) return;
        const el = e.target;
        const group = el.parentElement ? [...el.parentElement.querySelectorAll(':scope > [data-reveal]')] : [el];
        const idx = Math.max(0, group.indexOf(el));
        el.style.transitionDelay = (idx * 60) + 'ms';
        el.classList.add('in');
        rio.unobserve(el);
      });
    }, { rootMargin: '0px 0px -8% 0px' });
    items.forEach(el => rio.observe(el));
  }

  /* ---------- 工具 ---------- */
  S.$ = (sel, root = document) => root.querySelector(sel);
  S.$$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  S.lerp = (a, b, t) => a + (b - a) * t;
  S.clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  S.smooth = (e0, e1, x) => { const t = S.clamp((x - e0) / (e1 - e0), 0, 1); return t * t * (3 - 2 * t); };
  /** 帧率无关的指数趋近：rate 越大越快（单位 1/秒） */
  S.approach = (cur, target, rate, dt) => target + (cur - target) * Math.exp(-rate * dt);

  /** 颜色变量补间（OKLab 插值） */
  S.tweenColors = function (el, from, to, ms, onFrame) {
    if (el.__tw) el.__tw.cancel = true;
    const job = { cancel: false };
    el.__tw = job;
    const t0 = performance.now();
    const step = now => {
      if (job.cancel) return;
      const k = S.reduced ? 1 : S.clamp((now - t0) / ms, 0, 1);
      const e = 1 - Math.pow(1 - k, 3);
      const cur = {};
      for (const key in to) cur[key] = SColor.mix(from[key] || to[key], to[key], e);
      onFrame(cur);
      if (k < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  };

  /** 封面主色 → 一套晕染色板 */
  S.palette = function (glow, dark) {
    if (dark) return dark;
    const c = SColor;
    return {
      g1: c.pastel(glow, 0.80, 0.52),
      g2: c.pastel(c.shiftHue(glow, 28), 0.84, 0.46),
      g3: c.pastel(c.shiftHue(glow, -32), 0.86, 0.40),
      bg: c.pastel(glow, 0.955, 0.30),
      drop: c.deep(glow, 0.46)
    };
  };

  S.toast = function (msg) {
    let t = document.getElementById('toast');
    if (!t) { t = document.createElement('div'); t.id = 'toast'; t.setAttribute('role', 'status'); document.body.appendChild(t); }
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(t.__h);
    t.__h = setTimeout(() => t.classList.remove('show'), 2200);
  };

  S.copy = function (text, okMsg) {
    const done = () => S.toast(okMsg || '已复制到剪贴板');
    const fallback = () => {
      try {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.setAttribute('readonly', '');
        ta.style.cssText = 'position:fixed;left:-9999px;opacity:0';
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        ta.remove();
        done();
      } catch (_) {
        S.toast('请手动长按复制');
      }
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, fallback);
    } else {
      fallback();
    }
  };

  S.ready = [];
  S.onReady = fn => S.ready.push(fn);

  document.addEventListener('DOMContentLoaded', () => {
    document.documentElement.classList.add('js');
    if (S.reduced) document.documentElement.classList.add('reduced');
    SCovers.injectDefs();
    SIcons.hydrate();
    // 配置注入
    S.$$('[data-cfg]').forEach(el => {
      const k = el.dataset.cfg;
      if (k === 'quarkUrl') el.setAttribute('href', S.config.quarkUrl);
      else if (S.config[k] != null) el.textContent = S.config[k];
    });
    if (S.config.repo) {
      S.$$('[data-repo]').forEach(el => {
        el.href = S.config.repo;
        el.target = '_blank';
        el.rel = 'noopener noreferrer';
        el.hidden = false;
      });
    }
    S.ready.forEach(fn => { try { fn(); } catch (e) { console.error(e); } });
    // 离开视口的章节自动暂停 CSS 动画
    S.$$('section[data-cover]').forEach(sec => {
      sec.addEventListener('s:hide', () => sec.classList.add('offscreen'));
      sec.addEventListener('s:show', () => sec.classList.remove('offscreen'));
      S.watch(sec);
    });
    SIcons.hydrate();
    initReveal();
  });
})();
