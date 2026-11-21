// 零依赖静态打包脚本：合并并哈希 CSS / JS、为 3D 黑胶页注入版本戳、拷贝运行资源到 website-dist/
// 用法：node website/tools/build.js
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const root = path.resolve(__dirname, '..', '..');
const srcDir = path.join(root, 'website');
const distDir = path.join(root, 'website-dist');

function hash8(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex').slice(0, 8);
}

// 仅剥离字符串引号之外的 /* ... */ 注释，避免误伤引号内的内容
function stripCssComments(css) {
  let out = '';
  let quote = null;
  for (let i = 0; i < css.length; i++) {
    const ch = css[i];
    if (quote) {
      out += ch;
      if (ch === '\\' && i + 1 < css.length) { out += css[++i]; continue; }
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      out += ch;
      continue;
    }
    if (ch === '/' && css[i + 1] === '*') {
      const end = css.indexOf('*/', i + 2);
      i = end === -1 ? css.length : end + 1;
      continue;
    }
    out += ch;
  }
  return out
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{2,}/g, '\n')
    .trim() + '\n';
}

function copyFile(rel) {
  const from = path.join(srcDir, rel);
  const to = path.join(distDir, rel);
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(from, to);
  return fs.statSync(to).size;
}

// 1. 重建 website-dist/
fs.rmSync(distDir, { recursive: true, force: true });
fs.mkdirSync(distDir, { recursive: true });

const html = fs.readFileSync(path.join(srcDir, 'index.html'), 'utf8');

// 2. 按 index.html 顺序提取并合并 CSS
const cssFiles = [...html.matchAll(/<link\s+rel="stylesheet"\s+href="(css\/[^"]+)"\s*>/g)].map(m => m[1]);
if (!cssFiles.length) throw new Error('No stylesheets found in website/index.html');
const bundledCss = stripCssComments(
  cssFiles.map(rel => fs.readFileSync(path.join(srcDir, rel), 'utf8')).join('\n')
);
const cssHash = hash8(bundledCss);
const cssOutRel = `css/app.${cssHash}.css`;
fs.mkdirSync(path.join(distDir, 'css'), { recursive: true });
fs.writeFileSync(path.join(distDir, cssOutRel), bundledCss, 'utf8');

// 3. 拷贝运行资源（显式排除 website/tools/）
const assets = [
  'img/favicon.svg',
  'img/og.png',
  'img/qq-group.jpg',
  'img/tip-qr.jpg',
  'vinyl/index.html',
  'vinyl/three.min.js'
];
assets.forEach(copyFile);

const vinylHash = hash8(fs.readFileSync(path.join(distDir, 'vinyl/index.html')));

// 4. 按 index.html 顺序提取并合并 JS，并给 vinyl/index.html 注入版本戳防缓存
const jsFiles = [...html.matchAll(/<script\s+src="(js\/[^"]+)"\s+defer><\/script>/g)].map(m => m[1]);
if (!jsFiles.length) throw new Error('No deferred scripts found in website/index.html');
let bundledJs = jsFiles
  .map(rel => fs.readFileSync(path.join(srcDir, rel), 'utf8').trim())
  .join('\n;\n') + '\n';
bundledJs = bundledJs.replace(/src="vinyl\/index\.html"/g, `src="vinyl/index.html?v=${vinylHash}"`);
const jsHash = hash8(bundledJs);
const jsOutRel = `js/app.${jsHash}.js`;
fs.mkdirSync(path.join(distDir, 'js'), { recursive: true });
fs.writeFileSync(path.join(distDir, jsOutRel), bundledJs, 'utf8');

// 5. 生成 website-dist/index.html
let distHtml = html
  .replace(
    /(?:\s*<link\s+rel="stylesheet"\s+href="css\/[^"]+"\s*>\s*)+/,
    `\n  <link rel="stylesheet" href="${cssOutRel}">\n`
  )
  .replace(
    /(?:\s*<script\s+src="js\/[^"]+"\s+defer><\/script>\s*)+/,
    `\n  <script src="${jsOutRel}" defer></script>\n`
  );
fs.writeFileSync(path.join(distDir, 'index.html'), distHtml, 'utf8');

// 6. 汇总输出
function listFiles(dir) {
  const res = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) res.push(...listFiles(p));
    else res.push({ rel: path.relative(distDir, p).replace(/\\/g, '/'), size: fs.statSync(p).size });
  }
  return res;
}
const all = listFiles(distDir).sort((a, b) => a.rel.localeCompare(b.rel));
const total = all.reduce((s, f) => s + f.size, 0);
console.log(`Built ${all.length} files into website-dist/ (${(total / 1024 / 1024).toFixed(2)} MB):`);
for (const f of all) {
  console.log(`  ${f.rel.padEnd(38)} ${(f.size / 1024).toFixed(1).padStart(8)} KB`);
}
