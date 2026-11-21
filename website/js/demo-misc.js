/* 页面其余交互与数据：顶栏、品牌图标、3D 黑胶加载、多语言曲库、桌面歌词、53 款图标、31 套皮肤、工艺计数、下载 */
(function () {
  const S = window.Sonance;

  // 多语言示例曲库（原创虚拟曲目，展示混排与 A–Z 索引）
  const TRACKS = [
    { id: 1,  title: 'สายลมบนภูเขา',   artist: 'Narin',      album: 'Morning Mist',   cover: 'mint',      key: '#', dur: '3:42', recent: true,  fav: true  },
    { id: 2,  title: '새벽 비 소리',     artist: 'Haru',       album: 'Seoul Rain',     cover: 'dusk',      key: '#', dur: '4:15', recent: true,  fav: false },
    { id: 3,  title: '山あいの風',       artist: 'Kaze',       album: 'Komorebi',       cover: 'sunset',    key: '#', dur: '3:58', recent: false, fav: true  },
    { id: 4,  title: 'Afterglow Study',  artist: 'Claire',     album: 'Glass Room',     cover: 'conicA',    key: 'A', dur: '3:24', recent: true,  fav: false },
    { id: 5,  title: 'Clear Horizon',    artist: 'Mono Lake',  album: 'Echoes Vol.2',   cover: 'rings',     key: 'C', dur: '5:12', recent: false, fav: true  },
    { id: 6,  title: '第一缕光',         artist: '林间',       album: '山水间',         cover: 'conicA',    key: 'D', dur: '4:06', recent: true,  fav: true  },
    { id: 7,  title: '飞鸟与海',         artist: '山水乐队',   album: '落日线',         cover: 'sunset',    key: 'F', dur: '4:56', recent: true,  fav: true  },
    { id: 8,  title: '黄昏车站',         artist: '海盐',       album: '末班车',         cover: 'dusk',      key: 'H', dur: '3:49', recent: false, fav: false },
    { id: 9,  title: '几何午后',         artist: '包豪斯',     album: 'Triangle',       cover: 'arcs',      key: 'J', dur: '3:18', recent: true,  fav: false },
    { id: 10, title: '橘子汽水',         artist: '海盐',       album: '夏日切片',       cover: 'dotsCoral', key: 'J', dur: '3:31', recent: false, fav: true  },
    { id: 11, title: '旷野',             artist: '无名氏',     album: 'Open Field',     cover: 'conicB',    key: 'K', dur: '5:04', recent: false, fav: false },
    { id: 12, title: 'Linen & Vinyl',    artist: 'Owen',       album: 'Turntable',      cover: 'checker',   key: 'L', dur: '4:22', recent: true,  fav: false },
    { id: 13, title: 'Op.14 迷宫',       artist: '白日梦',     album: 'Nocturne',       cover: 'checker',   key: 'O', dur: '3:48', recent: false, fav: true  },
    { id: 14, title: '清音',             artist: '山水',       album: '招隐集',         cover: 'type',      key: 'Q', dur: '4:32', recent: true,  fav: true  },
    { id: 15, title: 'Stillwater',       artist: 'Lin',        album: 'Ambient 04',     cover: 'mint',      key: 'S', dur: '6:10', recent: false, fav: false },
    { id: 16, title: '萤火',             artist: '青柠',       album: '夜行',           cover: 'dotsLime',  key: 'Y', dur: '3:55', recent: false, fav: true  },
    { id: 17, title: '正午',             artist: 'Sonance',    album: 'Half-Tone',      cover: 'halftone',  key: 'Z', dur: '4:44', recent: true,  fav: false },
    { id: 18, title: '薄荷雨',           artist: '林间',       album: '清晨',           cover: 'mint',      key: 'B', dur: '3:28', recent: false, fav: false }
  ];

  S.onReady(() => {
    /* ---------- 1. 顶栏滚动 & 品牌图标 ---------- */
    const nav = S.$('#nav');
    const navLinks = S.$$('.nav-seg a', nav);
    function syncNavScroll() {
      if (nav) nav.classList.toggle('scrolled', window.scrollY > 40);
    }
    window.addEventListener('scroll', syncNavScroll, { passive: true });
    syncNavScroll();

    document.addEventListener('s:track', e => {
      const id = e.detail.section.el.id;
      const map = { player: '#player', lyrics: '#player', visualizer: '#player', wall: '#player', vinyl: '#player', studio: '#player', library: '#player', connect: '#player', philosophy: '#player', skins: '#skins', craft: '#skins', download: '#download', community: '#community' };
      const target = map[id] || '';
      navLinks.forEach(a => a.classList.toggle('on', a.getAttribute('href') === target));
    });

    const icons = window.SONANCE_ICONS || [];
    const defIcon = icons.find(x => x.id === 's2_4') || icons[0];
    if (defIcon) {
      ['#brandIc', '#libIc', '#footIc'].forEach(sel => {
        const el = S.$(sel);
        if (el) {
          el.style.background = defIcon.bg;
          el.innerHTML = defIcon.svg;
        }
      });
    }

    /* ---------- 2. Section 06 · 3D 黑胶唱机按需加载 ---------- */
    const vinylBtn = S.$('#vinylLoad');
    const vinylStage = S.$('#vinylStage');
    if (vinylBtn && vinylStage) {
      const posterHtml = vinylStage.innerHTML;
      let loaded = false;
      vinylBtn.addEventListener('click', () => {
        loaded = !loaded;
        if (loaded) {
          vinylStage.innerHTML = '<iframe class="vinyl-iframe" src="vinyl/index.html" title="3D 黑胶唱机演示" loading="lazy"></iframe>';
          S.$('b', vinylBtn).textContent = '收起 3D 唱机';
          S.$('small', vinylBtn).textContent = '回到静态唱机示意图';
          S.$('.drop-ic', vinylBtn).innerHTML = SIcons.svg('close');
        } else {
          vinylStage.innerHTML = posterHtml;
          SIcons.hydrate(vinylStage);
          S.$('b', vinylBtn).textContent = '放上唱片';
          S.$('small', vinylBtn).textContent = '加载 3D 场景（约 0.8 MB）';
          S.$('.drop-ic', vinylBtn).innerHTML = SIcons.svg('play');
        }
      });
    }

    /* ---------- 3. Section 08 · 本地曲库演示 ---------- */
    const libList = S.$('#libList');
    const libIndex = S.$('#libIndex');
    const libSearch = S.$('#libSearch');
    const libTabs = S.$('#libTabs');
    const libCount = S.$('#libCount');
    const libRand = S.$('.lib-random');
    if (libList && libIndex) {
      const ALPHA = ['#', ...'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('')];
      let tab = 'all', q = '', activeId = 7;

      libIndex.innerHTML = ALPHA.map(ch => `<button type="button" data-k="${ch}">${ch}</button>`).join('');

      function filtered() {
        return TRACKS.filter(t => {
          if (tab === 'recent' && !t.recent) return false;
          if (tab === 'fav' && !t.fav) return false;
          if (q) {
            const kw = q.toLowerCase();
            return (t.title + ' ' + t.artist + ' ' + t.album).toLowerCase().includes(kw);
          }
          return true;
        });
      }

      function renderLib() {
        const list = filtered();
        if (libCount) libCount.textContent = list.length;
        if (!list.length) {
          libList.innerHTML = '<li class="lib-empty">没有找到匹配的歌曲</li>';
          return;
        }
        libList.innerHTML = list.map(t => {
          const playing = t.id === activeId;
          return `<li class="lib-row${playing ? ' playing' : ''}" data-id="${t.id}" data-key="${t.key}">
            <div class="lib-cv">${SCovers.html(t.cover)}${playing ? '<span class="lib-eq" aria-hidden="true"><i></i><i></i><i></i></span>' : ''}</div>
            <div class="lib-info"><b>${t.title}</b><small>${t.artist} · ${t.album}</small></div>
            <span class="lib-dur tnum">${t.dur}</span>
            <button type="button" class="lib-fav${t.fav ? ' on' : ''}" aria-label="${t.fav ? '取消收藏' : '收藏'} ${t.title}">${SIcons.svg('heart')}</button>
          </li>`;
        }).join('');
      }

      libList.addEventListener('click', e => {
        const favBtn = e.target.closest('.lib-fav');
        if (favBtn) {
          e.stopPropagation();
          const id = +favBtn.closest('.lib-row').dataset.id;
          const tr = TRACKS.find(x => x.id === id);
          if (tr) { tr.fav = !tr.fav; renderLib(); }
          return;
        }
        const row = e.target.closest('.lib-row');
        if (row) {
          activeId = +row.dataset.id;
          renderLib();
        }
      });

      libTabs.addEventListener('click', e => {
        const b = e.target.closest('button'); if (!b) return;
        tab = b.dataset.v;
        S.$$('button', libTabs).forEach(x => x.classList.toggle('on', x === b));
        renderLib();
      });

      libSearch.addEventListener('input', () => {
        q = libSearch.value.trim();
        renderLib();
      });

      if (libRand) {
        libRand.addEventListener('click', () => {
          const list = filtered();
          if (!list.length) return;
          const pick = list[Math.floor(Math.random() * list.length)];
          activeId = pick.id;
          renderLib();
          const row = S.$(`.lib-row[data-id="${activeId}"]`, libList);
          if (row) row.scrollIntoView({ behavior: S.reduced ? 'auto' : 'smooth', block: 'nearest' });
        });
      }

      libIndex.addEventListener('click', e => {
        const b = e.target.closest('button[data-k]');
        if (!b) return;
        const k = b.dataset.k;
        S.$$('button', libIndex).forEach(x => x.classList.toggle('on', x === b));
        const rows = S.$$('.lib-row', libList);
        let hit = rows.find(r => r.dataset.key === k);
        if (!hit && k !== '#') {
          const idx = ALPHA.indexOf(k);
          hit = rows.find(r => ALPHA.indexOf(r.dataset.key) >= idx);
        }
        if (hit) hit.scrollIntoView({ behavior: S.reduced ? 'auto' : 'smooth', block: 'start' });
      });

      renderLib();
    }

    /* ---------- 4. Section 09 · 小组件封面 / 桌面歌词 / 53 款图标 ---------- */
    S.$$('.wg-cv[data-wcover]').forEach(el => {
      el.innerHTML = SCovers.html(el.dataset.wcover);
    });

    const dlyBar = S.$('#dlyBar');
    if (dlyBar) {
      const modes = [
        { cls: 'mode-none', label: '无背景立体字' },
        { cls: 'mode-light', label: '浅色磨砂背景' },
        { cls: 'mode-dark', label: '深色磨砂背景' }
      ];
      const lines = ['风从山那边过来', '听见溪水 在练习换气', '非必丝与竹，山水有清音'];
      let mi = 0, li = 0;
      dlyBar.addEventListener('click', () => {
        mi = (mi + 1) % modes.length;
        li = (li + 1) % lines.length;
        dlyBar.className = 'dly-bar ' + modes[mi].cls;
        S.$('.dly-line', dlyBar).textContent = lines[li];
        S.toast('桌面歌词样式：' + modes[mi].label);
      });
    }

    const marquee = S.$('#iconMarquee');
    if (marquee && icons.length) {
      const half = Math.ceil(icons.length / 2);
      const rowA = icons.slice(0, half);
      const rowB = icons.slice(half);
      const tile = ic => `<div class="ic-tile" title="${ic.name}"><span class="ic-squircle" style="background:${ic.bg}">${ic.svg}</span><small>${ic.name.split(' · ')[0]}</small></div>`;
      const makeRow = (list, rev) => `<div class="mq-row${rev ? ' rev' : ''}"><div class="mq-track">${list.map(tile).join('')}${list.map(tile).join('')}</div></div>`;
      marquee.innerHTML = makeRow(rowA, false) + makeRow(rowB, true);
    }

    /* ---------- 5. Section 10 · 31 套皮肤 ---------- */
    const skinsSec = S.$('#skins');
    const skinRail = S.$('#skinRail');
    const skinFilter = S.$('#skinFilter');
    const skinsBg = S.$('.skins-bg', skinsSec);
    const skins = window.SONANCE_SKINS || [];
    if (skinsSec && skinRail && skins.length) {
      let filter = 'all', pinnedId = null;

      function applySkinPreview(skin) {
        if (!skin) {
          skinsSec.classList.remove('previewing', 'preview-dark', 'preview-light');
          skinsBg.style.opacity = '0';
          skinsSec.style.removeProperty('--sk-text');
          skinsSec.style.removeProperty('--sk-dim');
          skinsSec.style.removeProperty('--sk-accent');
          skinsSec.style.removeProperty('--sk-on');
          return;
        }
        const g0 = (skin.grad && skin.grad[0]) || skin.surface2 || skin.bg;
        const g1 = (skin.grad && skin.grad[1]) || skin.bg;
        skinsBg.style.background = `radial-gradient(circle at 72% 30%, ${skin.accent}2e 0%, transparent 50%), linear-gradient(135deg, ${g0}, ${g1})`;
        skinsBg.style.opacity = '1';
        skinsSec.classList.add('previewing');
        skinsSec.classList.toggle('preview-dark', !skin.light);
        skinsSec.classList.toggle('preview-light', !!skin.light);
        skinsSec.style.setProperty('--sk-text', skin.text);
        skinsSec.style.setProperty('--sk-dim', skin.textDim);
        skinsSec.style.setProperty('--sk-accent', skin.accent);
        skinsSec.style.setProperty('--sk-on', skin.onAccent || (skin.light ? '#fff' : '#121212'));
      }

      function renderSkins() {
        const list = filter === 'all' ? skins : skins.filter(s => (filter === 'light' ? s.light : !s.light));
        skinRail.innerHTML = list.map(sk => {
          const pin = sk.id === pinnedId;
          return `<button type="button" class="skin-card${pin ? ' on' : ''}" data-sid="${sk.id}" style="--s-bg:${sk.bg};--s-sf:${sk.surface};--s-sf2:${sk.surface2};--s-tx:${sk.text};--s-dim:${sk.textDim};--s-ac:${sk.accent};--s-on:${sk.onAccent}">
            <div class="sk-mock">
              <div class="sk-top"><i></i><span></span></div>
              <div class="sk-rows">
                <div class="sk-r"><i style="background:${sk.accent}"></i><span><b style="width:68%"></b><small style="width:42%"></small></span></div>
                <div class="sk-r"><i></i><span><b style="width:54%"></b><small style="width:36%"></small></span></div>
              </div>
              <div class="sk-mini"><span class="sk-art"></span><span class="sk-bar"></span><span class="sk-dot"></span></div>
            </div>
            <div class="sk-meta"><b>${sk.name}</b><small>${sk.tagline}</small></div>
          </button>`;
        }).join('');
      }

      skinRail.addEventListener('pointerover', e => {
        if (e.pointerType === 'touch') return;
        const c = e.target.closest('.skin-card[data-sid]');
        if (!c) return;
        const sk = skins.find(x => x.id === c.dataset.sid);
        if (sk) applySkinPreview(sk);
      });
      skinRail.addEventListener('pointerleave', () => {
        const sk = pinnedId ? skins.find(x => x.id === pinnedId) : null;
        applySkinPreview(sk);
      });
      skinRail.addEventListener('click', e => {
        const c = e.target.closest('.skin-card[data-sid]');
        if (!c) return;
        pinnedId = pinnedId === c.dataset.sid ? null : c.dataset.sid;
        const sk = pinnedId ? skins.find(x => x.id === pinnedId) : null;
        applySkinPreview(sk);
        S.$$('.skin-card', skinRail).forEach(x => x.classList.toggle('on', x.dataset.sid === pinnedId));
      });

      if (skinFilter) {
        skinFilter.addEventListener('click', e => {
          const b = e.target.closest('button'); if (!b) return;
          filter = b.dataset.v;
          S.$$('button', skinFilter).forEach(x => x.classList.toggle('on', x === b));
          renderSkins();
        });
      }
      renderSkins();
    }

    /* ---------- 6. Section 11 · 工艺数字递增 ---------- */
    const nums = S.$$('.num b[data-count]');
    if (nums.length) {
      const nio = new IntersectionObserver(entries => {
        entries.forEach(en => {
          if (!en.isIntersecting) return;
          const el = en.target;
          nio.unobserve(el);
          const target = +el.dataset.count;
          if (S.reduced || target === 0) { el.textContent = String(target); return; }
          const t0 = performance.now();
          const step = now => {
            const k = S.clamp((now - t0) / 900, 0, 1);
            const e = 1 - Math.pow(1 - k, 3);
            el.textContent = String(Math.round(target * e));
            if (k < 1) requestAnimationFrame(step);
          };
          requestAnimationFrame(step);
        });
      }, { threshold: 0.2 });
      nums.forEach(n => nio.observe(n));
    }

    /* ---------- 7. Section 12 · 夸克下载 / 一键复制口令与群号 / 二维码放大 ---------- */
    const dlCover = S.$('#dlCover');
    if (dlCover) dlCover.innerHTML = SCovers.html('sunset');

    const COPY_MAP = {
      quark: () => S.copy(S.config.quarkCode, '已复制夸克口令，打开夸克网盘 APP 即可获取'),
      qq: () => S.copy(S.config.qqGroup, '已复制 QQ 交流群号：' + S.config.qqGroup),
      wechat: () => S.copy(S.config.wechat, '已复制微信号：' + S.config.wechat),
      sha256: () => S.copy(S.config.sha256, '已复制 SHA-256 校验值')
    };
    S.$$('[data-copy]').forEach(el => {
      const act = COPY_MAP[el.dataset.copy];
      if (!act) return;
      if (el.tagName !== 'BUTTON' && el.tagName !== 'A') {
        el.setAttribute('role', 'button');
        el.tabIndex = 0;
        el.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); act(); } });
      }
      el.addEventListener('click', act);
    });

    const qrModal = S.$('#qrModal');
    if (qrModal) {
      let lastTrigger = null;
      const closeQr = () => {
        if (!qrModal.classList.contains('open')) return;
        qrModal.classList.remove('open');
        qrModal.setAttribute('aria-hidden', 'true');
        if (lastTrigger) { lastTrigger.focus({ preventScroll: true }); lastTrigger = null; }
      };
      S.$$('.qr-zoom').forEach(btn => {
        btn.addEventListener('click', () => {
          lastTrigger = btn;
          const src = btn.dataset.qr;
          const title = btn.dataset.qrTitle || '二维码预览';
          qrModal.innerHTML = `<div class="qr-card glass">
            <button type="button" class="qr-close" aria-label="关闭二维码预览">${SIcons.svg('close')}</button>
            <img src="${src}" alt="${title}">
            <p>${title}</p>
          </div>`;
          qrModal.classList.add('open');
          qrModal.setAttribute('aria-hidden', 'false');
          const cl = S.$('.qr-close', qrModal);
          if (cl) {
            cl.addEventListener('click', closeQr);
            cl.focus({ preventScroll: true });
          }
        });
      });
      qrModal.addEventListener('click', e => { if (e.target === qrModal) closeQr(); });
      document.addEventListener('keydown', e => { if (e.key === 'Escape') closeQr(); });
    }
  });
})();
