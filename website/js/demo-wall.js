/* 封面墙：Bento 拼贴 + 点击抽出唱片（FLIP）+ 向下拖动插回墙上 */
(function () {
  const S = window.Sonance;
  const ORDER = [
    ['conicA', 2], ['rings', 1], ['sunset', 1], ['dotsCoral', 1], ['type', 1],
    ['checker', 2], ['dotsLime', 1], ['halftone', 1], ['arcs', 1], ['conicB', 2],
    ['dusk', 1], ['mint', 1], ['sunset', 1], ['rings', 1], ['dotsCoral', 1], ['conicA', 1],
    ['type', 1], ['halftone', 1]
  ];
  const LYR = [
    ['风从山那边过来', '把昨天的雨 吹成今天的云'],
    ['我把耳机摘下一半', '听见溪水 在练习换气'],
    ['不必丝 也不必竹', '整座山 都在轻轻地唱'],
    ['你听 那是清音', '落在每一片叶子的背面']
  ];

  S.onReady(() => {
    const grid = S.$('#wallGrid');
    const ov = S.$('#wallFocus');
    if (!grid || !ov) return;
    const ic = SIcons.svg;

    grid.innerHTML = ORDER.map(([id, s], i) =>
      `<button type="button" class="wall-card s${s}" data-id="${id}" aria-label="播放：${SCovers.get(id).title}">${SCovers.html(id, { cap: true })}</button>`).join('');

    ov.innerHTML = `
      <div class="wf-bg"></div>
      <button type="button" class="wf-close" aria-label="把唱片插回墙上">${ic('chevronDown')}</button>
      <button type="button" class="wf-tune" aria-hidden="true" tabindex="-1">${ic('sliders')}</button>
      <div class="wf-stage">
        <div class="wf-art">
          <div class="wf-disc"><div class="wf-label"></div></div>
          <div class="wf-sleeve"></div>
        </div>
        <div class="wf-lyrics"><p class="a"></p><p class="b"></p></div>
        <div class="wf-info"><b></b><span></span></div>
        <div class="wf-prog"><i></i></div>
        <div class="wf-times"><span class="w0">0:01</span><span class="w1">3:48</span></div>
        <div class="wf-ctrl">
          <button type="button" class="wf-prev" aria-label="上一张">${ic('prev')}</button>
          <s-drop playing class="wf-drop">${ic('pause')}</s-drop>
          <button type="button" class="wf-next" aria-label="下一张">${ic('next')}</button>
        </div>
        <span class="wf-queue">${ic('queue')}曲目列表 · 1 首</span>
        <p class="wf-hint">↓ 向下拖动，把唱片插回墙上</p>
      </div>`;
    SIcons.hydrate(ov);

    const stage = S.$('.wf-stage', ov), sleeve = S.$('.wf-sleeve', ov), disc = S.$('.wf-disc', ov), label = S.$('.wf-label', ov);
    const bg = S.$('.wf-bg', ov);
    let curCard = null, busy = false, lyrI = 0, pos = 0, dur = 228;

    function setCover(id) {
      const c = SCovers.get(id);
      sleeve.innerHTML = SCovers.html(id, { cap: true });
      label.innerHTML = SCovers.html(id);
      S.$('.wf-info b', ov).textContent = c.title;
      S.$('.wf-info span', ov).textContent = c.artist;
      ov.style.setProperty('--wf-top', SColor.deep(c.glow, 0.36, 0.45));
      ov.style.setProperty('--wf-mid', SColor.deep(c.glow, 0.16, 0.35));
      ov.style.setProperty('--drop', SColor.deep(c.glow, 0.48, 0.45));
      pos = 1; dur = 180 + Math.floor(Math.random() * 120);
    }

    function flipFrom(rect) {
      const to = sleeve.getBoundingClientRect();
      const sx = rect.width / to.width;
      sleeve.style.transition = 'none';
      sleeve.style.transform = `translate(${rect.left - to.left}px, ${rect.top - to.top}px) scale(${sx})`;
      void sleeve.offsetWidth;
      sleeve.style.transition = '';
      sleeve.style.transform = '';
    }

    function open(card) {
      if (busy) return;
      busy = true;
      curCard = card;
      const rect = card.getBoundingClientRect();
      setCover(card.dataset.id);
      ov.classList.add('open');
      ov.setAttribute('aria-hidden', 'false');
      document.documentElement.classList.add('wall-lock');
      card.classList.add('lifted');
      flipFrom(rect);
      setTimeout(() => { ov.classList.add('disc-out'); busy = false; S.$('.wf-close', ov).focus({ preventScroll: true }); }, S.reduced ? 0 : 520);
    }

    function close() {
      if (busy || !ov.classList.contains('open')) return;
      busy = true;
      ov.classList.remove('disc-out');
      const back = () => {
        const card = curCard;
        if (card) {
          const r = card.getBoundingClientRect(), to = sleeve.getBoundingClientRect();
          // 拖动时 stage 有位移，先取当前位置再飞回
          sleeve.style.transform = `translate(${r.left - to.left}px, ${r.top - to.top}px) scale(${r.width / to.width})`;
        }
        ov.classList.add('closing');
        stage.style.transition = ''; bg.style.opacity = '';
        setTimeout(() => {
          ov.classList.remove('open', 'closing');
          ov.setAttribute('aria-hidden', 'true');
          stage.style.transform = '';
          sleeve.style.transition = 'none'; sleeve.style.transform = ''; void sleeve.offsetWidth; sleeve.style.transition = '';
          document.documentElement.classList.remove('wall-lock');
          if (card) { card.classList.remove('lifted'); card.focus({ preventScroll: true }); }
          busy = false;
        }, S.reduced ? 0 : 560);
      };
      setTimeout(back, S.reduced ? 0 : 260);
    }

    grid.addEventListener('click', e => { const c = e.target.closest('.wall-card'); if (c) open(c); });
    S.$('#wallRandom').addEventListener('click', () => {
      const cards = S.$$('.wall-card', grid);
      open(cards[Math.floor(Math.random() * cards.length)]);
    });
    S.$('.wf-close', ov).addEventListener('click', close);
    document.addEventListener('keydown', e => { if (e.key === 'Escape') close(); });

    function hop(d) {
      const cards = S.$$('.wall-card', grid);
      let i = cards.indexOf(curCard);
      i = (i + d + cards.length) % cards.length;
      curCard.classList.remove('lifted');
      curCard = cards[i];
      curCard.classList.add('lifted');
      ov.classList.remove('disc-out');
      setTimeout(() => { setCover(curCard.dataset.id); ov.classList.add('disc-out'); }, 280);
    }
    S.$('.wf-prev', ov).addEventListener('click', () => hop(-1));
    S.$('.wf-next', ov).addEventListener('click', () => hop(1));
    const wdrop = S.$('.wf-drop', ov);
    wdrop.addEventListener('click', () => {
      const p = !wdrop.hasAttribute('playing');
      wdrop.toggleAttribute('playing', p);
      S.$('.drop-ic', wdrop).innerHTML = ic(p ? 'pause' : 'play');
      ov.classList.toggle('paused', !p);
    });

    // 向下拖动：跟手位移，松手超过阈值就插回墙上
    let y0 = null, dy = 0;
    stage.addEventListener('pointerdown', e => {
      if (e.target.closest('button, s-drop')) return;
      y0 = e.clientY; dy = 0;
      stage.setPointerCapture(e.pointerId);
      stage.style.transition = 'none';
    });
    stage.addEventListener('pointermove', e => {
      if (y0 == null) return;
      dy = Math.max(0, e.clientY - y0);
      const k = Math.min(1, dy / 400);
      stage.style.transform = `translateY(${dy}px) scale(${1 - k * 0.12})`;
      bg.style.opacity = String(1 - k * 0.6);
    });
    const end = () => {
      if (y0 == null) return;
      y0 = null;
      stage.style.transition = '';
      if (dy > 120) close();
      else { stage.style.transform = ''; bg.style.opacity = ''; }
    };
    stage.addEventListener('pointerup', end);
    stage.addEventListener('pointercancel', end);

    // 歌词与进度
    const pa = S.$('.wf-lyrics .a', ov), pb = S.$('.wf-lyrics .b', ov);
    const fill = S.$('.wf-prog i', ov), w0 = S.$('.w0', ov), w1 = S.$('.w1', ov);
    let la = 0;
    const fmt = s => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;
    pa.textContent = LYR[0][0]; pb.textContent = LYR[0][1];
    S.tick(dt => {
      if (!ov.classList.contains('open') || ov.classList.contains('paused') || !S.motionOn) return;
      pos = Math.min(dur, pos + dt);
      fill.style.transform = `scaleX(${pos / dur})`;
      w0.textContent = fmt(pos); w1.textContent = fmt(dur);
      la += dt;
      if (la > 3.6) {
        la = 0; lyrI = (lyrI + 1) % LYR.length;
        pa.classList.add('swap'); pb.classList.add('swap');
        setTimeout(() => { pa.textContent = LYR[lyrI][0]; pb.textContent = LYR[lyrI][1]; pa.classList.remove('swap'); pb.classList.remove('swap'); }, 260);
      }
    });
  });
})();
