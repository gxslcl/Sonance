// 按行补齐 CoverArt 的 songId（兼容 CRLF）
const fs = require('fs');

const edits = [
  {
    file: 'app/src/main/java/com/sonance/player/ui/components/FullPlayer.kt',
    match: /^(\s*)sourceUri = song\.uri\s*$/,
    extra: (ind) => [`${ind}sourceUri = song.uri,`, `${ind}songId = song.id`],
  },
  {
    file: 'app/src/main/java/com/sonance/player/ui/components/PlayerBars.kt',
    match: /^(\s*)sourceUri = coverUri\s*$/,
    extra: (ind) => [`${ind}sourceUri = coverUri,`, `${ind}songId = state.mediaId`],
  },
  {
    file: 'app/src/main/java/com/sonance/player/ui/screens/Screens.kt',
    match: /^(\s*)sourceUri = coverUri\s*$/,
    extra: (ind) => [`${ind}sourceUri = coverUri,`, `${ind}songId = songs.firstOrNull()?.id ?: -1L`],
  },
];

for (const e of edits) {
  const raw = fs.readFileSync(e.file, 'utf8');
  const eol = raw.includes('\r\n') ? '\r\n' : '\n';
  const lines = raw.split(/\r?\n/);
  const out = [];
  let n = 0;
  for (const line of lines) {
    const m = line.match(e.match);
    if (m) {
      n++;
      out.push(...e.extra(m[1]));
    } else {
      out.push(line);
    }
  }
  if (n > 0) {
    fs.writeFileSync(e.file, out.join(eol));
    console.log(`patched ${n}x  ${e.file.split('/').pop()}  (eol=${eol === '\r\n' ? 'CRLF' : 'LF'})`);
  } else {
    console.log(`no match   ${e.file.split('/').pop()}`);
  }
}
