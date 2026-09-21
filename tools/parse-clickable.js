// 从 uiautomator 导出的 XML 里挑出所有可点击节点，用来确认某个区域到底有没有挂上点击
const fs = require('fs');
const xml = fs.readFileSync(process.argv[2] || 'shots/ui-dump.xml', 'utf8');
const re = /<node[^>]*>/g;
let m;
while ((m = re.exec(xml))) {
  const tag = m[0];
  if (!/clickable="true"/.test(tag)) continue;
  const cls = (tag.match(/class="([^"]*)"/) || [])[1] || '';
  const bounds = (tag.match(/bounds="([^"]*)"/) || [])[1] || '';
  const text = (tag.match(/text="([^"]*)"/) || [])[1] || '';
  const desc = (tag.match(/content-desc="([^"]*)"/) || [])[1] || '';
  console.log(`${bounds.padEnd(26)} ${cls.replace('android.widget.', '').padEnd(16)} ${text} ${desc}`);
}
