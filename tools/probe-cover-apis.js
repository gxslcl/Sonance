// 实测几个「免鉴权 / 免费」的专辑封面接口，看看能不能用、返回什么
const https = require('https');
const http = require('http');

function get(url, headers = {}) {
  return new Promise((resolve) => {
    const mod = url.startsWith('https') ? https : http;
    const req = mod.get(url, {
      timeout: 20000,
      headers: { 'User-Agent': 'Mozilla/5.0 (compatible; SonanceResearch)', Accept: '*/*', ...headers },
    }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        resolve(get(res.headers.location, headers));
        return;
      }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, type: res.headers['content-type'], body: Buffer.concat(chunks) }));
    });
    req.on('error', (e) => resolve({ status: 0, error: e.message }));
    req.on('timeout', () => { req.destroy(); resolve({ status: 0, error: 'timeout' }); });
  });
}

const enc = encodeURIComponent;

async function probe(name, url, parse) {
  const r = await get(url);
  if (r.status !== 200) {
    console.log(`✗ ${name}  HTTP ${r.status} ${r.error || ''}`);
    return;
  }
  console.log(`✓ ${name}  HTTP 200  ${r.type || ''}  ${(r.body.length / 1024).toFixed(1)}KB`);
  try { parse(r.body.toString('utf8')); } catch (e) { console.log('   parse err: ' + e.message); }
}

(async () => {
  console.log('════ 1. iTunes Search API ════');
  await probe('iTunes song', `https://itunes.apple.com/search?term=${enc('周杰伦 七里香')}&entity=song&limit=3`, (t) => {
    const j = JSON.parse(t);
    console.log(`   resultCount=${j.resultCount}`);
    (j.results || []).slice(0, 3).forEach((x) => {
      console.log(`   · ${x.trackName} / ${x.artistName} / ${x.collectionName}`);
      console.log(`     artworkUrl100 = ${x.artworkUrl100}`);
    });
  });

  console.log('\n════ 2. Deezer API ════');
  await probe('Deezer search', `https://api.deezer.com/search?q=${enc('周杰伦 七里香')}&limit=3`, (t) => {
    const j = JSON.parse(t);
    console.log(`   total=${j.total}`);
    (j.data || []).slice(0, 3).forEach((x) => {
      console.log(`   · ${x.title} / ${x.artist && x.artist.name} / ${x.album && x.album.title}`);
      console.log(`     cover_xl = ${x.album && x.album.cover_xl}`);
    });
  });

  console.log('\n════ 3. MusicBrainz + Cover Art Archive ════');
  await probe('MB release search', `https://musicbrainz.org/ws/2/release/?query=${enc('七里香')}%20AND%20artist:${enc('周杰伦')}&fmt=json&limit=3`, (t) => {
    const j = JSON.parse(t);
    console.log(`   count=${j.count}`);
    (j.releases || []).slice(0, 3).forEach((x) => {
      console.log(`   · ${x.title} / ${x.id}`);
    });
    const first = (j.releases || [])[0];
    if (first) {
      console.log(`   → 拿封面: https://coverartarchive.org/release/${first.id}/front`);
    }
  });

  console.log('\n════ 4. Last.fm（需要 key，只探一下连通）════');
  await probe('Last.fm', 'https://ws.audioscrobbler.com/2.0/?method=artist.getinfo&artist=Cher&api_key=test&format=json', (t) => {
    console.log('   ' + t.slice(0, 200));
  });

  console.log('\n════ 5. 验证 iTunes 封面图能放大 ════');
  const big = 'https://is1-ssl.mzstatic.com/image/thumb/Music115/v4/00/00/00/mzaf_0/0/source/600x600bb.jpg';
  const r = await get(big);
  console.log(`   600x600 示例 URL（占位）→ HTTP ${r.status}`);
})();
