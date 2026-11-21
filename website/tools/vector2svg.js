// 把 App 的 53 款图标（res/drawable/logo_*.xml 矢量图 + AppIcons.kt 底色/名称）转成 SVG
// 生成 website/js/data/icons.js
// 用法：node website/tools/vector2svg.js
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..', '..');
const drawDir = path.join(root, 'app/src/main/res/drawable');
const iconsKt = fs.readFileSync(path.join(root, 'app/src/main/java/com/sonance/player/ui/theme/AppIcons.kt'), 'utf8');

// ---- 极简 XML 解析（矢量图都是规整的自闭合/成对标签）----
function parseXml(src) {
  src = src.replace(/<\?xml[^>]*\?>/g, '').replace(/<!--[\s\S]*?-->/g, '');
  const rootNode = { tag: '#root', attrs: {}, children: [] };
  const stack = [rootNode];
  const re = /<\/?([\w:.-]+)((?:\s+[\w:.-]+\s*=\s*"[^"]*")*)\s*(\/?)>/g;
  let m;
  while ((m = re.exec(src))) {
    const [full, tag, attrStr, selfClose] = m;
    if (full.startsWith('</')) { stack.pop(); continue; }
    const attrs = {};
    for (const a of attrStr.matchAll(/([\w:.-]+)\s*=\s*"([^"]*)"/g)) attrs[a[1]] = a[2];
    const node = { tag, attrs, children: [] };
    stack[stack.length - 1].children.push(node);
    if (!selfClose) stack.push(node);
  }
  return rootNode.children[0];
}

function color(c, alphaMul = 1) {
  if (!c) return null;
  if (c.startsWith('@')) return null;
  let h = c.replace('#', '');
  if (h.length === 3) h = 'F' + h;
  if (h.length === 4) h = h.split('').map(x => x + x).join('');
  if (h.length === 6) h = 'FF' + h;
  const a = (parseInt(h.slice(0, 2), 16) / 255) * alphaMul;
  const rgb = '#' + h.slice(2).toLowerCase();
  return { rgb, a: Math.round(a * 1000) / 1000 };
}

let gid = 0;
function convert(file, prefix) {
  const v = parseXml(fs.readFileSync(file, 'utf8'));
  const A = (n, k) => n.attrs['android:' + k];
  const vw = A(v, 'viewportWidth') || 108, vh = A(v, 'viewportHeight') || 108;
  const defs = [];

  function gradientFor(node) {
    const g = node.children.find(c => c.tag === 'gradient');
    if (!g) return null;
    const id = prefix + 'g' + (gid++);
    const items = g.children.filter(c => c.tag === 'item');
    let stops = items.map(it => {
      const c = color(A(it, 'color'));
      return `<stop offset="${A(it, 'offset')}" stop-color="${c.rgb}"${c.a < 1 ? ` stop-opacity="${c.a}"` : ''}/>`;
    });
    if (!stops.length) {
      const sc = color(A(g, 'startColor')), ec = color(A(g, 'endColor')), cc = color(A(g, 'centerColor'));
      stops = [[0, sc], cc ? [0.5, cc] : null, [1, ec]].filter(Boolean)
        .map(([o, c]) => `<stop offset="${o}" stop-color="${c.rgb}"${c.a < 1 ? ` stop-opacity="${c.a}"` : ''}/>`);
    }
    const type = A(g, 'type') || 'linear';
    if (type === 'radial') {
      defs.push(`<radialGradient id="${id}" gradientUnits="userSpaceOnUse" cx="${A(g, 'centerX') || 0}" cy="${A(g, 'centerY') || 0}" r="${A(g, 'gradientRadius') || 1}">${stops.join('')}</radialGradient>`);
    } else {
      defs.push(`<linearGradient id="${id}" gradientUnits="userSpaceOnUse" x1="${A(g, 'startX') || 0}" y1="${A(g, 'startY') || 0}" x2="${A(g, 'endX') || 0}" y2="${A(g, 'endY') || 0}">${stops.join('')}</linearGradient>`);
    }
    return `url(#${id})`;
  }

  function paint(node, key, alphaKey) {
    const alphaMul = parseFloat(A(node, alphaKey) || '1');
    const aapt = node.children.find(c => c.tag === 'aapt:attr' && c.attrs.name === 'android:' + key);
    if (aapt) {
      const url = gradientFor(aapt);
      return url ? { v: url, op: alphaMul < 1 ? alphaMul : null } : null;
    }
    const c = color(A(node, key), alphaMul);
    if (!c) return null;
    return { v: c.rgb, op: c.a < 1 ? c.a : null };
  }

  function walk(node) {
    let out = '';
    for (const n of node.children) {
      if (n.tag === 'group') {
        const tx = +(A(n, 'translateX') || 0), ty = +(A(n, 'translateY') || 0);
        const sx = +(A(n, 'scaleX') || 1), sy = +(A(n, 'scaleY') || 1);
        const rot = +(A(n, 'rotation') || 0), px = +(A(n, 'pivotX') || 0), py = +(A(n, 'pivotY') || 0);
        // Android 顺序：先绕 pivot 缩放与旋转，再平移
        const t = `translate(${tx + px} ${ty + py}) rotate(${rot}) scale(${sx} ${sy}) translate(${-px} ${-py})`;
        const clip = n.children.find(c => c.tag === 'clip-path');
        let clipAttr = '';
        if (clip) {
          const cid = prefix + 'c' + (gid++);
          defs.push(`<clipPath id="${cid}"><path d="${A(clip, 'pathData')}"/></clipPath>`);
          clipAttr = ` clip-path="url(#${cid})"`;
        }
        out += `<g transform="${t}"${clipAttr}>${walk(n)}</g>`;
      } else if (n.tag === 'path') {
        const fill = paint(n, 'fillColor', 'fillAlpha');
        const stroke = paint(n, 'strokeColor', 'strokeAlpha');
        let a = `d="${A(n, 'pathData')}" fill="${fill ? fill.v : 'none'}"`;
        if (fill && fill.op != null) a += ` fill-opacity="${fill.op}"`;
        if (A(n, 'fillType') === 'evenOdd') a += ' fill-rule="evenodd"';
        if (stroke) {
          a += ` stroke="${stroke.v}" stroke-width="${A(n, 'strokeWidth') || 1}"`;
          if (stroke.op != null) a += ` stroke-opacity="${stroke.op}"`;
          if (A(n, 'strokeLineCap')) a += ` stroke-linecap="${A(n, 'strokeLineCap')}"`;
          if (A(n, 'strokeLineJoin')) a += ` stroke-linejoin="${A(n, 'strokeLineJoin')}"`;
        }
        out += `<path ${a}/>`;
      } else if (n.tag === 'clip-path') {
        // 已在 group 中处理
      }
    }
    return out;
  }

  const body = walk(v);
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${vw} ${vh}">${defs.length ? '<defs>' + defs.join('') + '</defs>' : ''}${body}</svg>`;
}

const icons = [];
for (const m of iconsKt.matchAll(/v\("([^"]+)",\s*"([^"]+)",\s*R\.drawable\.(\w+),\s*R\.mipmap\.\w+,\s*0x([0-9A-Fa-f]{8})\)/g)) {
  const [, id, name, drawable, bg] = m;
  const file = path.join(drawDir, drawable + '.xml');
  if (!fs.existsSync(file)) { console.warn('missing', file); continue; }
  try {
    icons.push({ id, name, bg: '#' + bg.slice(2), svg: convert(file, 'i' + icons.length) });
  } catch (e) { console.warn('fail', id, e.message); }
}
const out = path.join(__dirname, '..', 'js', 'data', 'icons.js');
fs.writeFileSync(out, '// 由 tools/vector2svg.js 从 res/drawable/logo_*.xml 自动生成，请勿手改\nwindow.SONANCE_ICONS = ' + JSON.stringify(icons) + ';\n');
console.log('icons:', icons.length, 'bytes:', fs.statSync(out).size);
