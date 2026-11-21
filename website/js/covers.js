/* 生成式封面：延续 App 内置封面（BuiltinCovers）与封面墙占位图的视觉语言
 * 「渐变 + 几何母题」：圆锥渐变、同心环、波点、落日、扭曲棋盘、单字排版、网点、包豪斯
 * 全部由 CSS / 内联 SVG 绘制，零图片、零版权问题。
 */
(function () {
  const C = {
    sunset:   { motif: 'sunset',   title: '飞鸟与海',   artist: '山水乐队', glow: '#F09A8E', ink: '#fff',
                v: { a: '#F4A3A0', b: '#F8C99B', c: '#FBD27A', d: '#4C7C9B', e: '#2E5A79' } },
    conicA:   { motif: 'conic',    title: '第一缕光',   artist: '林间',     glow: '#9B6BF0', ink: '#fff',
                v: { a: '#2EC4B6', b: '#7B2FF7', c: '#F72585' } },
    rings:    { motif: 'rings',    title: '回声',       artist: 'Mono Lake', glow: '#3FCFB8', ink: '#fff',
                v: { a: '#3EE6C8', b: '#2A1B3D' } },
    dotsCoral:{ motif: 'dots',     title: '橘子汽水',   artist: '海盐',     glow: '#E98D72', ink: '#fff',
                v: { a: '#F08A6C', b: '#1E4D45' } },
    dotsLime: { motif: 'dots',     title: '萤火',       artist: '青柠',     glow: '#8C8FE8', ink: '#fff',
                v: { a: '#B5F25C', b: '#25235E' } },
    type:     { motif: 'type',     title: '清音',       artist: '山水',     glow: '#E4B3B3', ink: '#3b3a1a',
                v: { a: '#6E7F1E', b: '#F2DCDC' }, char: '清' },
    checker:  { motif: 'checker',  title: 'Op.14 迷宫', artist: '白日梦',   glow: '#3F9A62', ink: '#1d1d1d',
                v: { a: '#161616', b: '#F3EAD8', c: '#E0482B' } },
    conicB:   { motif: 'conic',    title: '旷野',       artist: '无名氏',   glow: '#38B07C', ink: '#fff',
                v: { a: '#1E3A8A', b: '#22C55E', c: '#FACC15' } },
    halftone: { motif: 'halftone', title: '正午',       artist: 'Sonance',  glow: '#E2CB3C', ink: '#1a1a1a',
                v: { a: '#F5DC3A', b: '#1A1A1A', c: '#C8321E' } },
    arcs:     { motif: 'arcs',     title: '几何午后',   artist: '包豪斯',   glow: '#E8935E', ink: '#1f2a44',
                v: { a: '#F1E9DA', b: '#2D4FA3', c: '#E8743B', d: '#D63A2F' } },
    dusk:     { motif: 'sunset',   title: '黄昏车站',   artist: '海盐',     glow: '#B48AD9', ink: '#fff',
                v: { a: '#8E7CC3', b: '#E7A3C4', c: '#FFD9A0', d: '#3E3A73', e: '#26234F' } },
    mint:     { motif: 'rings',    title: '薄荷雨',     artist: '林间',     glow: '#6FCBA7', ink: '#fff',
                v: { a: '#BFF0DB', b: '#2F7D67' } }
  };

  function sunsetSvg(v) {
    return `<svg viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
      <defs><linearGradient id="__S" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${v.a}"/><stop offset="1" stop-color="${v.b}"/></linearGradient>
      <mask id="__M"><rect width="100" height="100" fill="#fff"/>${[50, 54.5, 58.5, 62, 65].map((y, i) => `<rect y="${y}" width="100" height="${1 + i * 0.45}" fill="#000"/>`).join('')}</mask></defs>
      <rect width="100" height="100" fill="url(#__S)"/>
      <circle cx="50" cy="56" r="21" fill="${v.c}" mask="url(#__M)"/>
      <path d="M0 64 Q12 61 25 64 T50 64 T75 64 T100 64 V100 H0Z" fill="${v.d}"/>
      <path d="M0 74 Q12 71 25 74 T50 74 T75 74 T100 74 V100 H0Z" fill="${v.e}" opacity=".85"/>
      <path d="M0 86 Q12 83 25 86 T50 86 T75 86 T100 86 V100 H0Z" fill="${v.e}"/>
      <path d="M44 66h12M46 69h8M48 72h4" stroke="${v.c}" stroke-width="1" opacity=".55" stroke-linecap="round"/>
      <path d="M22 22l2 1.4 2-1.4M30 17l1.6 1.1 1.6-1.1M36 24l1.4 1 1.4-1" fill="none" stroke="#5a4a5a" stroke-width=".7" stroke-linecap="round" opacity=".6"/>
      <text x="7" y="10" font-size="3.4" fill="#5a4a5a" opacity=".55" font-family="Inter,sans-serif" letter-spacing=".3">SIDE A / SUNSET</text>
    </svg>`;
  }
  function checkerSvg(v) {
    // 预计算扭曲棋盘纯矢量路径，不用 SVG feTurbulence / feDisplacementMap 实时滤镜
    const step = 11.5, x0 = -7, y0 = -7;
    const warp = (x, y) => {
      const dx = x - 50, dy = y - 44, r = Math.hypot(dx, dy);
      const bulge = Math.exp(-(r * r) / 1500) * 6.5;
      const k = r > 0.1 ? bulge / r : 0;
      const wx = x + dx * k + Math.sin(y * 0.085) * 4.2;
      const wy = y + dy * k + Math.cos(x * 0.085) * 4.2;
      return `${wx.toFixed(1)},${wy.toFixed(1)}`;
    };
    let d = '';
    for (let j = 0; j < 10; j++) {
      for (let i = 0; i < 10; i++) {
        if ((i + j) % 2 !== 0) continue;
        const x = x0 + i * step, y = y0 + j * step, mx = x + step / 2, my = y + step / 2;
        d += `M${warp(x, y)}Q${warp(mx, y)} ${warp(x + step, y)}Q${warp(x + step, my)} ${warp(x + step, y + step)}Q${warp(mx, y + step)} ${warp(x, y + step)}Q${warp(x, my)} ${warp(x, y)}Z`;
      }
    }
    return `<svg viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
      <rect width="100" height="100" fill="${v.b}"/>
      <path d="${d}" fill="${v.a}"/>
      <circle cx="50" cy="44" r="22" fill="none" stroke="${v.c}" stroke-width="3.2"/>
      <rect x="5" y="5" width="15" height="6" rx="1" fill="${v.b}"/>
      <text x="7" y="9.6" font-size="3.6" fill="#333" font-family="Inter,sans-serif">OP / 14</text>
    </svg>`;
  }

  let uid = 0;
  /** 返回封面 HTML 字符串；opts.cap=true 时带标题文字 */
  function html(id, opts = {}) {
    const c = C[id] || C.sunset;
    const v = c.v;
    const vars = Object.entries(v).map(([k, val]) => `--${k}:${val}`).join(';');
    let inner = '';
    if (c.motif === 'sunset') {
      const n = 'cvs' + (uid++);
      inner = sunsetSvg(v).replace(/__S/g, n + 's').replace(/__M/g, n + 'm');
    } else if (c.motif === 'checker') {
      inner = checkerSvg(v);
    } else if (c.motif === 'type') {
      inner = `<span class="cv-char">${c.char}</span><span class="cv-mini">${c.title}<br><small>${c.artist}</small></span>`;
    } else if (c.motif === 'halftone') {
      inner = '<span class="cv-ht"></span><span class="cv-ht2"></span><span class="cv-script">so fine</span>';
    } else if (c.motif === 'arcs') {
      inner = '<span class="cv-arc1"></span><span class="cv-arc2"></span><span class="cv-arc3"></span>';
    }
    const cap = opts.cap ? `<span class="cv-cap" style="color:${c.ink}"><b>${c.title}</b><small>${c.artist}</small></span>` : '';
    return `<div class="cv cv-${c.motif}${opts.cls ? ' ' + opts.cls : ''}" style="${vars}" data-cover="${id}">${inner}${cap}</div>`;
  }

  /** 预留共享定义钩子（已改用纯矢量绘制，零 CPU 滤镜开销） */
  function injectDefs() {}

  window.SCovers = { list: C, html, injectDefs, get: id => C[id] || C.sunset };
})();
