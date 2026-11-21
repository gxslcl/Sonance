/* 悬浮玻璃播放条 + 章节索引栏
 * 播放条照搬 App 的悬浮卡片：封面 / 曲名 / 歌手 / 上一曲 · 水滴键 · 下一曲 / 底边进度
 * 这里「曲目」= 章节，进度 = 整页滚动进度；水滴键开关全站动效。
 */
(function () {
  const S = window.Sonance;

  S.onReady(() => {
    const bar = document.getElementById('mini');
    const rail = document.getElementById('rail');
    if (!bar) return;
    const G = S.glow;
    const cv = S.$('.mini-cover', bar);
    const title = S.$('.mini-title', bar);
    const sub = S.$('.mini-sub', bar);
    const fill = S.$('.mini-progress i', bar);
    const drop = S.$('s-drop', bar);
    const playIc = S.$('.mini-play', bar);

    function go(delta) {
      const n = S.clamp(G.active + delta, 0, G.sections.length - 1);
      G.sections[n].el.scrollIntoView({ behavior: S.reduced ? 'auto' : 'smooth', block: 'start' });
    }
    S.$('.mini-prev', bar).addEventListener('click', () => go(-1));
    S.$('.mini-next', bar).addEventListener('click', () => go(1));

    function syncPlay() {
      if (S.motionOn) drop.setAttribute('playing', ''); else drop.removeAttribute('playing');
      playIc.innerHTML = SIcons.svg(S.motionOn ? 'pause' : 'play');
      playIc.setAttribute('aria-label', S.motionOn ? '暂停全站动效' : '恢复全站动效');
      document.documentElement.classList.toggle('motion-off', !S.motionOn);
    }
    playIc.addEventListener('click', () => {
      S.motionOn = !S.motionOn;
      syncPlay();
      S.toast(S.motionOn ? '动效已恢复' : '动效已暂停，页面保持静止');
    });
    syncPlay();

    // 章节索引栏
    if (rail) {
      rail.innerHTML = G.sections.map((s, i) =>
        `<button type="button" data-i="${i}" aria-label="跳到：${s.title}"><span>${String(i).padStart(2, '0')}</span><em>${s.title}</em></button>`).join('');
      rail.addEventListener('click', e => {
        const b = e.target.closest('button');
        if (b) G.sections[+b.dataset.i].el.scrollIntoView({ behavior: S.reduced ? 'auto' : 'smooth', block: 'start' });
      });
    }

    document.addEventListener('s:track', e => {
      const s = e.detail.section;
      const id = s.coverOverride || s.cover;
      cv.innerHTML = s.dark ? '<div class="cv cv-vinylmini"></div>' : SCovers.html(id);
      title.textContent = s.title;
      sub.textContent = s.sub;
      bar.classList.remove('swap'); void bar.offsetWidth; bar.classList.add('swap');
      if (rail) S.$$('button', rail).forEach((b, i) => b.classList.toggle('on', i === e.detail.index));
    });
    document.addEventListener('s:progress', e => {
      fill.style.transform = `scaleX(${e.detail})`;
      const show = window.scrollY > window.innerHeight * 0.55;
      bar.classList.toggle('show', show);
      if (rail) rail.classList.toggle('show', show);
    });
  });
})();
