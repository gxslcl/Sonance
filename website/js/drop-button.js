/* <s-drop>：水滴播放键
 * 对应 App 的 PlayButtonShape.DROPLET：播放时有机形变，暂停 / 关闭动效时平滑回到正圆。
 * 光晕是同形状的模糊副本，颜色取 --drop（跟随当前封面）。
 * 属性：playing（播放态形变）、ambient（常驻轻微呼吸）、shape="normal"（普通正圆）
 */
(function () {
  const N = 7, R = 40;
  let seq = 0;

  function pathFor(pts) {
    // 闭合 Catmull-Rom → 三次贝塞尔
    let d = `M${pts[0][0].toFixed(2)},${pts[0][1].toFixed(2)}`;
    for (let i = 0; i < pts.length; i++) {
      const p0 = pts[(i - 1 + N) % N], p1 = pts[i], p2 = pts[(i + 1) % N], p3 = pts[(i + 2) % N];
      const c1x = p1[0] + (p2[0] - p0[0]) / 6, c1y = p1[1] + (p2[1] - p0[1]) / 6;
      const c2x = p2[0] - (p3[0] - p1[0]) / 6, c2y = p2[1] - (p3[1] - p1[1]) / 6;
      d += `C${c1x.toFixed(2)},${c1y.toFixed(2)} ${c2x.toFixed(2)},${c2y.toFixed(2)} ${p2[0].toFixed(2)},${p2[1].toFixed(2)}`;
    }
    return d + 'Z';
  }

  class SDrop extends HTMLElement {
    static get observedAttributes() { return ['playing', 'ambient', 'shape']; }
    connectedCallback() {
      if (this.__built) return;
      this.__built = true;
      const id = 'dg' + (seq++);
      const gid = 'dgg' + (seq++);
      const ic = document.createElement('span');
      ic.className = 'drop-ic';
      while (this.firstChild) ic.appendChild(this.firstChild);
      this.innerHTML = `<svg class="drop-svg" viewBox="-80 -80 160 160" aria-hidden="true">
        <defs>
          <radialGradient id="${gid}" cx="50%" cy="50%" r="50%">
            <stop offset="45%" style="stop-color:var(--drop);stop-opacity:.48"/>
            <stop offset="100%" style="stop-color:var(--drop);stop-opacity:0"/>
          </radialGradient>
          <radialGradient id="${id}" cx="35%" cy="28%" r="85%">
            <stop offset="0" style="stop-color:var(--drop-hi, #fff);stop-opacity:.38"/>
            <stop offset=".45" style="stop-color:var(--drop);stop-opacity:0"/>
          </radialGradient>
        </defs>
        <circle class="drop-glow" cx="0" cy="0" r="68" fill="url(#${gid})"/>
        <path class="drop-body"/>
        <path class="drop-sheen" fill="url(#${id})"/>
      </svg>`;
      this.appendChild(ic);
      this.body = this.querySelector('.drop-body');
      this.sheen = this.querySelector('.drop-sheen');
      this.amp = 0;
      this.phase = Math.random() * 10;
      this.press = 0;
      this.acc = 0;
      this.__forceRender = false;
      this.render(0);
      if (!this.hasAttribute('tabindex') && !this.closest('a,button')) this.tabIndex = 0;
      this.addEventListener('pointerdown', () => { this.press = 1; this.__forceRender = true; });
      this.__untick = Sonance.tick(dt => this.update(dt), this);
    }
    disconnectedCallback() { /* 保留：元素可能被移动后重新挂载 */ }
    target() {
      if (!Sonance.motionOn || this.getAttribute('shape') === 'normal') return 0;
      if (this.hasAttribute('playing')) return 0.085;
      if (this.hasAttribute('ambient')) return 0.045;
      return 0;
    }
    update(dt) {
      const tgt = this.target();
      this.amp = Sonance.approach(this.amp, tgt, 4, dt);
      this.press = Sonance.approach(this.press, 0, 9, dt);
      if (this.amp < 0.0005 && tgt === 0 && this.__still && this.press < 0.01) return;
      this.__still = this.amp < 0.0005;
      this.phase += dt;
      this.acc += dt;
      if (this.acc < 1 / 24 && !this.__forceRender) return;
      this.acc = 0;
      this.__forceRender = false;
      this.render(this.phase);
    }
    render(t) {
      const a = this.amp, s = 1 - this.press * 0.08;
      const pts = [];
      for (let i = 0; i < N; i++) {
        const ang = (i / N) * Math.PI * 2 - Math.PI / 2;
        const k = 1 + a * (0.62 * Math.sin(t * 1.35 + i * 1.7) + 0.38 * Math.sin(t * 2.2 + i * 2.9));
        pts.push([Math.cos(ang) * R * k * s, Math.sin(ang) * R * k * s]);
      }
      const d = pathFor(pts);
      this.body.setAttribute('d', d);
      this.sheen.setAttribute('d', d);
    }
  }
  customElements.define('s-drop', SDrop);
})();
