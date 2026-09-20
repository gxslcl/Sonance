// 给 FullPlayer 底部操作行加「更换封面」按钮，并把回调从外部穿进来
const fs = require('fs');

const p = 'app/src/main/java/com/sonance/player/ui/components/FullPlayer.kt';
const raw = fs.readFileSync(p, 'utf8');
const eol = raw.includes('\r\n') ? '\r\n' : '\n';
const lines = raw.split(/\r?\n/);

// ---- 1) 在 BottomRow 内「音效」按钮块之后插入新按钮 ----
let inserted = false;
for (let i = 0; i < lines.length; i++) {
  if (!/contentDescription = "音效"/.test(lines[i])) continue;
  // 往上找这一块的起始，往下找块结束的 ")"
  let end = i;
  for (let j = i; j < Math.min(lines.length, i + 8); j++) {
    if (/^\s*\)\s*$/.test(lines[j])) { end = j; break; }
  }
  const ind = '        ';
  lines.splice(end + 1, 0,
    `${ind}IconAction(`,
    `${ind}    icon = SonanceIcons.Image,`,
    `${ind}    contentDescription = "更换封面",`,
    `${ind}    onClick = onOpenCover,`,
    `${ind}    iconSize = 20.dp,`,
    `${ind}    tint = skin.textMuted`,
    `${ind})`,
  );
  inserted = true;
  break;
}

let s = lines.join(eol);

// ---- 2) FullPlayer 签名加 onOpenCover ----
s = s.replace(
  /(\r?\n)(    onOpenQueue: \(\) -> Unit,\r?\n)(    modifier: Modifier = Modifier)/,
  `$1$2    onOpenCover: () -> Unit = {},$1$3`
);

// ---- 3) 两处 BottomRow 调用点补实参 ----
s = s.split('BottomRow(isFavorite, volume, onToggleFavorite, onVolume, onOpenQueue)')
     .join('BottomRow(isFavorite, volume, onToggleFavorite, onVolume, onOpenQueue, onOpenCover)');

fs.writeFileSync(p, s);

console.log('按钮插入: ' + inserted);
console.log('签名含 onOpenCover: ' + /onOpenCover: \(\) -> Unit = \{\}/.test(s));
console.log('调用点已补: ' + (s.match(/onVolume, onOpenQueue, onOpenCover\)/g) || []).length + ' 处');
console.log('BottomRow 形参: ' + /private fun BottomRow\([\s\S]{0,300}onOpenCover: \(\) -> Unit/.test(s));
