// 从 App 的 Skin.kt 抽出 31 套皮肤颜色，生成 website/js/data/skins.js
// 用法：node website/tools/extract-skins.js
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..', '..');
const src = fs.readFileSync(path.join(root, 'app/src/main/java/com/sonance/player/ui/theme/Skin.kt'), 'utf8');

const blocks = src.split(/\n\s*val\s+\w+\s*=\s*Skin\(/).slice(1);
const hex = (b, k) => {
  const m = b.match(new RegExp('\\b' + k + '\\s*=\\s*c\\(0x([0-9A-Fa-f]{8})\\)'));
  return m ? '#' + m[1].slice(2).toUpperCase() : null;
};
const skins = [];
for (const b of blocks) {
  const id = (b.match(/id\s*=\s*"([^"]+)"/) || [])[1];
  const name = (b.match(/name\s*=\s*"([^"]+)"/) || [])[1];
  if (!id || !name) continue;
  const tagline = (b.match(/tagline\s*=\s*"([^"]+)"/) || [])[1] || '';
  const light = /isLight\s*=\s*true/.test(b.split('gradients')[0]);
  const grad = [...(b.split('gradients')[1] || '').matchAll(/c\(0x([0-9A-Fa-f]{8})\)/g)]
    .slice(0, 2).map(m => '#' + m[1].slice(2).toUpperCase());
  skins.push({
    id, name, tagline, light,
    bg: hex(b, 'bg'), surface: hex(b, 'surface'), surface2: hex(b, 'surface2'),
    text: hex(b, 'text'), textDim: hex(b, 'textDim'),
    accent: hex(b, 'accent'), onAccent: hex(b, 'onAccent'),
    grad
  });
}
const out = path.join(__dirname, '..', 'js', 'data', 'skins.js');
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, '// 由 tools/extract-skins.js 从 Skin.kt 自动生成，请勿手改\nwindow.SONANCE_SKINS = ' + JSON.stringify(skins, null, 1) + ';\n');
console.log('skins:', skins.length);
