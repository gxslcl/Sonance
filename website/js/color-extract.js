/* 颜色工具 + 封面取色（复刻 App 的莫兰迪取色思路）
 * - 32×32 降采样，过滤过亮/过暗像素
 * - 36 个色相桶按「饱和度 × 数量」加权取主色
 * - 灰度封面锁饱和度 0（对应 App「黑白封面锁 0」）
 */
(function () {
  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));

  function hexToRgb(hex) {
    let h = hex.replace('#', '');
    if (h.length === 3) h = h.split('').map(c => c + c).join('');
    const n = parseInt(h.slice(0, 6), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  function rgbToHex([r, g, b]) {
    return '#' + [r, g, b].map(v => clamp(Math.round(v), 0, 255).toString(16).padStart(2, '0')).join('');
  }
  function rgbToHsl([r, g, b]) {
    r /= 255; g /= 255; b /= 255;
    const max = Math.max(r, g, b), min = Math.min(r, g, b);
    let h = 0, s = 0; const l = (max + min) / 2;
    if (max !== min) {
      const d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      if (max === r) h = (g - b) / d + (g < b ? 6 : 0);
      else if (max === g) h = (b - r) / d + 2;
      else h = (r - g) / d + 4;
      h /= 6;
    }
    return [h * 360, s, l];
  }
  function hslToRgb([h, s, l]) {
    h = ((h % 360) + 360) % 360 / 360;
    if (s === 0) return [l * 255, l * 255, l * 255];
    const q = l < 0.5 ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
    const f = t => {
      if (t < 0) t += 1; if (t > 1) t -= 1;
      if (t < 1 / 6) return p + (q - p) * 6 * t;
      if (t < 1 / 2) return q;
      if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
      return p;
    };
    return [f(h + 1 / 3) * 255, f(h) * 255, f(h - 1 / 3) * 255];
  }

  // OKLab 插值：颜色过渡中间不会发灰
  const toLin = c => { c /= 255; return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
  const toSrgb = c => 255 * (c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055);
  function rgbToOklab([r, g, b]) {
    r = toLin(r); g = toLin(g); b = toLin(b);
    const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
    const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
    const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
    return [0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
      1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
      0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s];
  }
  function oklabToRgb([L, a, b]) {
    const l = Math.pow(L + 0.3963377774 * a + 0.2158037573 * b, 3);
    const m = Math.pow(L - 0.1055613458 * a - 0.0638541728 * b, 3);
    const s = Math.pow(L - 0.0894841775 * a - 1.2914855480 * b, 3);
    return [toSrgb(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
      toSrgb(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
      toSrgb(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)];
  }
  function mix(hexA, hexB, t) {
    if (t <= 0) return hexA; if (t >= 1) return hexB;
    const A = rgbToOklab(hexToRgb(hexA)), B = rgbToOklab(hexToRgb(hexB));
    return rgbToHex(oklabToRgb([A[0] + (B[0] - A[0]) * t, A[1] + (B[1] - A[1]) * t, A[2] + (B[2] - A[2]) * t]));
  }

  /** 明亮晕染用：压饱和、钉亮度 */
  function pastel(hex, l = 0.82, sMax = 0.45) {
    const [h, s] = rgbToHsl(hexToRgb(hex));
    return rgbToHex(hslToRgb([h, Math.min(s, sMax), l]));
  }
  /** 按钮/强调用：加深 */
  function deep(hex, l = 0.42, sMin = 0.25) {
    const [h, s] = rgbToHsl(hexToRgb(hex));
    return rgbToHex(hslToRgb([h, s < 0.06 ? 0 : clamp(Math.max(s, sMin), 0, 0.7), l]));
  }
  function shiftHue(hex, deg) {
    const [h, s, l] = rgbToHsl(hexToRgb(hex));
    return rgbToHex(hslToRgb([h + deg, s, l]));
  }
  function luminance(hex) {
    const [r, g, b] = hexToRgb(hex).map(toLin);
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  }

  /** 从图片提取主色（返回 { main, isGray }） */
  function extract(img) {
    const N = 32;
    const cv = document.createElement('canvas');
    cv.width = cv.height = N;
    const ctx = cv.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(img, 0, 0, N, N);
    const data = ctx.getImageData(0, 0, N, N).data;
    const buckets = Array.from({ length: 36 }, () => ({ w: 0, r: 0, g: 0, b: 0 }));
    let satSum = 0, cnt = 0, fr = 0, fg = 0, fb = 0;
    for (let i = 0; i < data.length; i += 4) {
      const rgb = [data[i], data[i + 1], data[i + 2]];
      const [h, s, l] = rgbToHsl(rgb);
      fr += rgb[0]; fg += rgb[1]; fb += rgb[2];
      if (l > 0.92 || l < 0.08) continue;
      satSum += s; cnt++;
      const bk = buckets[Math.floor(h / 10) % 36];
      const w = s * s + 0.02;
      bk.w += w; bk.r += rgb[0] * w; bk.g += rgb[1] * w; bk.b += rgb[2] * w;
    }
    const total = data.length / 4;
    const avg = [fr / total, fg / total, fb / total];
    if (!cnt || satSum / cnt < 0.08) {
      const [, , l] = rgbToHsl(avg);
      return { main: rgbToHex(hslToRgb([0, 0, l])), isGray: true };
    }
    // 相邻桶合并，避免色相边界把主色劈开
    let best = 0, bestW = -1;
    for (let i = 0; i < 36; i++) {
      const w = buckets[i].w + 0.5 * (buckets[(i + 35) % 36].w + buckets[(i + 1) % 36].w);
      if (w > bestW) { bestW = w; best = i; }
    }
    const b = buckets[best];
    return { main: rgbToHex([b.r / b.w, b.g / b.w, b.b / b.w]), isGray: false };
  }

  window.SColor = { hexToRgb, rgbToHex, rgbToHsl, hslToRgb, mix, pastel, deep, shiftHue, luminance, extract, clamp };
})();
