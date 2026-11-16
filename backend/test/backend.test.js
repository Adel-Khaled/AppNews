import { test } from 'node:test';
import assert from 'node:assert/strict';

process.env.APP_TOKEN = 'test-token';
// No provider keys: every provider must be skipped or fail gracefully.
for (const k of ['NEWSAPI_KEY', 'GNEWS_KEY', 'GUARDIAN_KEY', 'YOUTUBE_API_KEY', 'ANTHROPIC_API_KEY', 'FACEBOOK_ACCESS_TOKEN', 'BACKEND_RSS_FEEDS']) delete process.env[k];

const { createServer } = await import('../src/server.js');
const { scoreVideo, tokens, entities, isoDurationToSeconds, extractJson } = await import('../src/util.js');
const { parseFeed } = await import('../src/providers/news.js');

async function withServer(fn) {
  const server = createServer();
  await new Promise((r) => server.listen(0, r));
  const base = `http://127.0.0.1:${server.address().port}`;
  try { await fn(base); } finally { server.close(); }
}

test('health is public and lists providers', async () => withServer(async (base) => {
  const r = await fetch(`${base}/health`);
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.equal(j.ok, true);
  assert.equal(j.providers.youtube, false);
}));

test('api requires the app token', async () => withServer(async (base) => {
  const r = await fetch(`${base}/api/news?category=technology`);
  assert.equal(r.status, 401);
}));

test('news with no providers configured returns an empty, well-formed response', async () => withServer(async (base) => {
  const r = await fetch(`${base}/api/news?category=technology`, { headers: { 'X-App-Token': 'test-token' } });
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.deepEqual(j.items, []);
  assert.ok(Array.isArray(j.providers));
}));

test('unconfigured video, AI and Facebook endpoints answer 501 with an explanation', async () => withServer(async (base) => {
  const h = { 'X-App-Token': 'test-token', 'content-type': 'application/json' };
  const v = await fetch(`${base}/api/videos`, { method: 'POST', headers: h, body: JSON.stringify({ title: 'x', publishedAt: new Date().toISOString() }) });
  assert.equal(v.status, 501);
  assert.match((await v.json()).error, /YOUTUBE_API_KEY/);
  const a = await fetch(`${base}/api/analyze`, { method: 'POST', headers: h, body: JSON.stringify({ title: 'x', text: 'y', source: 'z' }) });
  assert.equal(a.status, 501);
  const f = await fetch(`${base}/api/facebook/posts?page=bbcnews`, { headers: h });
  assert.equal(f.status, 501);
  assert.match((await f.json()).error, /Scraping Facebook is not supported/);
}));

test('bad input is rejected', async () => withServer(async (base) => {
  const h = { 'X-App-Token': 'test-token', 'content-type': 'application/json' };
  const r = await fetch(`${base}/api/explain`, { method: 'POST', headers: h, body: '{not json' });
  assert.equal(r.status, 400);
}));

test('video scoring prefers the same event over keyword lookalikes', () => {
  const title = 'Egypt and Sudan sign Nile water agreement in Cairo';
  const story = { storyTokens: tokens(title), storyEntities: entities(title), storyTime: Date.parse('2026-11-10T10:00:00Z') };
  const good = scoreVideo(story, { title: 'Egypt, Sudan sign landmark Nile water deal in Cairo', channel: 'Al Jazeera English', publishedAt: '2026-11-10T14:00:00Z' });
  const old = scoreVideo(story, { title: 'Egypt and Sudan Nile water dispute explained', channel: 'Some Channel', publishedAt: '2021-03-01T00:00:00Z' });
  const unrelated = scoreVideo(story, { title: 'Best water bottles of the year', channel: 'Gadgets', publishedAt: '2026-11-10T12:00:00Z' });
  assert.ok(good > 0.35, `good=${good}`);
  assert.ok(good > old && old > unrelated, `good=${good} old=${old} unrelated=${unrelated}`);
  assert.ok(unrelated < 0.35);
});

test('helpers', () => {
  assert.equal(isoDurationToSeconds('PT1H2M3S'), 3723);
  assert.equal(isoDurationToSeconds('PT45S'), 45);
  assert.deepEqual(extractJson('Here you go:\n{"a":1}\nthanks'), { a: 1 });
  assert.ok(entities('President Biden met NATO leaders in Brussels').includes('nato'));
});

test('server-side RSS parsing handles RSS and Atom', () => {
  const rss = `<rss><channel><title>T</title><item><title><![CDATA[Hello & world]]></title><link>https://ex.com/a</link>
    <pubDate>Mon, 16 Nov 2026 10:00:00 GMT</pubDate><description>&lt;p&gt;Body&lt;/p&gt;</description>
    <media:content url="https://ex.com/a.jpg" medium="image"/></item></channel></rss>`;
  const atom = `<feed><entry><title>Atom one</title><link rel="alternate" href="https://ex.com/b"/><updated>2026-11-16T10:00:00Z</updated></entry></feed>`;
  const a = parseFeed(rss);
  assert.equal(a.length, 1);
  assert.equal(a[0].title, 'Hello & world');
  assert.equal(a[0].imageUrl, 'https://ex.com/a.jpg');
  const b = parseFeed(atom);
  assert.equal(b[0].url, 'https://ex.com/b');
});
