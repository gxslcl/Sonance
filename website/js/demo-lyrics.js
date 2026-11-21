/* 五种全屏歌词演示（对应 App 的 LyricStyle）+ TTML 逐字高亮
 * 示例歌词为本站原创文字。
 */
(function () {
  const S = window.Sonance;
  const LINES = [
    { t: 0, x: '作词：山水有清音', credit: true },
    { t: 1.6, x: '作曲：Sonance', credit: true },
    { t: 3.2, x: '编曲：林间', credit: true },
    { t: 4.8, x: '风从山那边过来', tr: 'The wind comes over the hill' },
    { t: 8.4, x: '把昨天的雨 吹成今天的云', tr: "Turning yesterday's rain into today's clouds" },
    { t: 12.0, x: '我把耳机摘下一半', tr: 'I take off half of my headphones' },
    { t: 15.6, x: '听见溪水 在练习换气', tr: 'And hear the creek practising its breath' },
    { t: 19.2, x: '不必丝 也不必竹', tr: 'No need for strings, no need for flutes' },
    { t: 22.8, x: '整座山 都在轻轻地唱', tr: 'The whole mountain is softly singing' },
    { t: 26.4, x: '你听 那是清音', tr: 'Listen, that is the clear sound' },
    { t: 30.0, x: '落在每一片叶子的背面', tr: 'Landing on the back of every leaf' },
    { t: 33.6, x: '（间奏）', credit: true }
  ];
  const LOOP = 37.2;
  const HINT = {
    classic: '经典列表：当前句放大高亮，其余行逐级变淡',
    vertical: '竖屏歌词：从右往左竖排，像翻一本诗集',
    fluid: '流体声波景深：前后行逐级虚化，背景随声波起伏',
    vinyl: '黑胶环形声轨：歌词沿唱片外沿排成一道弧，带时间戳',
    spatial: '3D 双向深空星廊：歌词从远处左右交错飞来'
  };
  const ts = t => `${String(Math.floor(t / 60)).padStart(2, '0')}:${String(Math.floor(t % 60)).padStart(2, '0')}`;

  S.onReady(() => {
    const stage = S.$('#lyStage');
    if (!stage) return;
    const tabs = S.$('#lyTabs');
    let style = 'classic', els = [], p = 3, t = 4.8, idx = 3, needLayout = true;

    function build() {
      stage.className = 'ly-stage glass ly-' + style;
      let pre = '';
      if (style === 'fluid') pre = `<svg class="ly-wave" viewBox="0 0 1200 200" preserveAspectRatio="none" aria-hidden="true"><path d="M0 120 Q150 60 300 120 T600 120 T900 120 T1200 120 V200 H0Z"/><path d="M0 140 Q150 90 300 140 T600 140 T900 140 T1200 140 V200 H0Z"/></svg>`;
      if (style === 'vinyl') pre = '<div class="ly-disc" aria-hidden="true"><i></i></div><span class="ly-needle" aria-hidden="true"></span>';
      if (style === 'spatial') pre = '<div class="ly-stars" aria-hidden="true"></div>';
      stage.innerHTML = pre + `<p class="ly-hint">${HINT[style]}</p>` + LINES.map((l, k) =>
        `<div class="ly-line${l.credit ? ' credit' : ''}" data-k="${k}">${style === 'vinyl' ? `<small>${ts(l.t)}</small>` : ''}<span>${l.x}</span>${l.tr ? `<em>${l.tr}</em>` : ''}</div>`).join('');
      els = S.$$('.ly-line', stage);
      needLayout = false;
      layout(true);
    }

    function layout(force) {
      const W = stage.clientWidth, H = stage.clientHeight;
      const small = W < 560;
      const disc = style === 'vinyl' && force ? S.$('.ly-disc', stage) : null;
      els.forEach((el, k) => {
        const d = k - p;
        const ad = Math.abs(d);
        const cur = k === idx;
        if (cur !== el.__cur || force) { el.classList.toggle('cur', cur); el.__cur = cur; }
        let tr = '', op = 1, blur = 0;
        if (style === 'classic' || style === 'fluid') {
          const lh = small ? 46 : 58;
          const sc = cur ? 1 : 0.86;
          tr = `translate(-50%, -50%) translateY(${d * lh + (d > 0 ? 14 : 0)}px) scale(${sc})`;
          op = ad > 4.5 ? 0 : 1 - ad * 0.2;
          if (style === 'fluid') { blur = Math.round(Math.min(6, ad * 1.6) * 2) / 2; tr += ` scale(${1 - Math.min(ad, 4) * 0.035})`; }
        } else if (style === 'vertical') {
          const cw = small ? 58 : 84;
          const extra = d > 0 ? 12 : d < 0 ? -12 : 0;
          tr = `translate(-50%, -50%) translateX(${(-d * cw - extra).toFixed(1)}px)`;
          op = ad > 4.2 ? 0 : 1 - ad * 0.22;
        } else if (style === 'vinyl') {
          const R = Math.min(H * 0.46, W * 0.42);
          const cx = -R * 0.38, cy = H / 2;
          const th = d * (small ? 15 : 12.5) * Math.PI / 180;
          const rl = R + (small ? 26 : 40);
          const x = cx + rl * Math.cos(th), y = cy + rl * Math.sin(th);
          tr = `translate(${x}px, ${y}px) rotate(${th}rad) translateY(-50%)`;
          op = ad > 5.5 ? 0 : 1 - ad * 0.16;
          if (disc) { disc.style.width = disc.style.height = (R * 2) + 'px'; disc.style.left = (cx - R) + 'px'; disc.style.top = (cy - R) + 'px'; }
        } else if (style === 'spatial') {
          const t = S.clamp(1 - ad, 0, 1);
          const emph = t * t * (3 - 2 * t);
          const future = d > 0;
          const z = -ad * (future ? 140 : 120) + 30 * emph;
          const persp = 760 / (760 - z);
          const slot = small ? 76 : 102;
          const side = k % 2 === 0 ? 1 : -1;
          const x = side * (future ? 46 : -36) * Math.min(ad, 2.6) * persp * (small ? 0.55 : 1);
          const y = -d * slot;
          const sc = Math.max(0.68, 1 - ad * 0.08) * persp;
          const rx = future ? -ad * 5 : 12 * (1 - emph);
          tr = `translate(-50%, -50%) translate3d(${x.toFixed(1)}px, ${y.toFixed(1)}px, ${z.toFixed(1)}px) rotateX(${rx.toFixed(1)}deg) scale(${sc.toFixed(3)})`;
          const edge = S.clamp(3.2 - ad, 0, 1);
          op = ((future ? Math.max(0.12, 0.78 - ad * 0.2) : Math.max(0.08, 0.52 - ad * 0.18)) * (1 - emph) + emph) * edge;
        }
        el.style.transform = tr;
        el.style.opacity = op.toFixed(3);
        if (el.__blur !== blur || force) {
          el.__blur = blur;
          el.style.filter = blur > 0.05 ? `blur(${blur}px)` : '';
        }
      });
    }

    tabs.addEventListener('click', e => {
      const b = e.target.closest('button'); if (!b) return;
      S.$$('button', tabs).forEach(x => { x.classList.toggle('on', x === b); x.setAttribute('aria-selected', x === b); });
      style = b.dataset.style;
      build();
    });
    window.addEventListener('resize', () => layout(true));
    build();

    S.tick(dt => {
      if (S.motionOn) t = (t + dt) % LOOP;
      let n = 0;
      LINES.forEach((l, k) => { if (t >= l.t) n = k; });
      if (n !== idx) {
        if (n < idx) p = n - 1; // 循环回到开头时只滑一行，不倒卷整段
        idx = n;
        needLayout = true;
      }
      if (Math.abs(p - idx) >= 0.002) {
        p = S.approach(p, idx, 7, dt);
        if (Math.abs(p - idx) < 0.002) p = idx;
        needLayout = true;
      }
      if (needLayout) {
        needLayout = false;
        layout(false);
      }
    }, stage);

    /* ---------- TTML 逐字 ---------- */
    const box = S.$('#ttml');
    if (box) {
      const words = ['听', '见', '溪', '水', ' ', '在', '练', '习', '换', '气'];
      const dur = [0.32, 0.32, 0.36, 0.5, 0.2, 0.28, 0.32, 0.32, 0.38, 0.9];
      let acc = 0;
      const spans = words.map((w, i) => { const b = acc; acc += dur[i]; return { w, b, e: acc }; });
      const total = acc + 1.4;
      box.innerHTML = spans.map(s => `<span>${s.w === ' ' ? '&nbsp;' : s.w}</span>`).join('');
      S.$('#ttmlTr').textContent = 'And hear the creek practising its breath';
      const sp = S.$$('span', box);
      let tt = 0;
      S.tick(dt => {
        if (!S.motionOn) return;
        tt = (tt + dt) % total;
        spans.forEach((s, i) => {
          const k = S.clamp((tt - s.b) / (s.e - s.b), 0, 1);
          if (sp[i].__k === k) return;
          sp[i].__k = k;
          sp[i].style.setProperty('--p', (k * 100).toFixed(1) + '%');
          sp[i].style.transform = k > 0 && k < 1 ? `translateY(${(-2.5 * Math.sin(Math.PI * k)).toFixed(2)}px)` : '';
        });
      }, box);
    }
  });
})();
