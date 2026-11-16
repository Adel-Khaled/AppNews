import http from 'node:http';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { config, providerStatus } from './config.js';
import { HttpError, TtlCache } from './util.js';
import { NEWS_PROVIDERS } from './providers/news.js';
import { facebookPosts, liveStreams, matchVideos } from './providers/media.js';
import { analyze, explain } from './ai.js';

export const VERSION = '1.0.0';
const cache = new TtlCache(5000);
const MIN = 60e3;

// ---- tiny rate limiter (per IP, per bucket) ----
const hits = new Map();
function rateLimited(ip, bucket, limit, windowMs) {
  const key = `${bucket}:${ip}`;
  const now = Date.now();
  const arr = (hits.get(key) || []).filter((t) => now - t < windowMs);
  arr.push(now);
  hits.set(key, arr);
  return arr.length > limit;
}

function tokenOk(req) {
  if (!config.appToken) return true;
  const got = Buffer.from(String(req.headers['x-app-token'] || ''));
  const want = Buffer.from(config.appToken);
  return got.length === want.length && crypto.timingSafeEqual(got, want);
}

const hash = (o) => crypto.createHash('sha1').update(JSON.stringify(o)).digest('hex');

async function readJson(req, max = 200_000) {
  let size = 0;
  const chunks = [];
  for await (const c of req) {
    size += c.length;
    if (size > max) throw new HttpError(413, 'Request too large');
    chunks.push(c);
  }
  if (!chunks.length) return {};
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw new HttpError(400, 'Invalid JSON'); }
}

function send(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  res.end(data);
}

/** Aggregates every configured news provider; one failing provider never fails the request. */
export async function aggregateNews(params) {
  const entries = Object.entries(NEWS_PROVIDERS);
  const settled = await Promise.allSettled(entries.map(([name, fn]) =>
    cache.wrap(`news:${name}:${hash(params)}`, 10 * MIN, () => fn(params))));
  const providers = [];
  const seen = new Set();
  const items = [];
  settled.forEach((r, i) => {
    const name = entries[i][0];
    if (r.status === 'rejected') { providers.push({ provider: name, ok: false, error: r.reason?.message || String(r.reason) }); return; }
    if (r.value == null) return; // provider not configured
    providers.push({ provider: name, ok: true, count: r.value.length });
    for (const a of r.value) {
      const key = a.url.replace(/[?#].*$/, '').replace(/\/$/, '');
      const tkey = a.title.toLowerCase();
      if (seen.has(key) || seen.has(tkey)) continue;
      seen.add(key); seen.add(tkey);
      items.push(a);
    }
  });
  items.sort((a, b) => (Date.parse(b.publishedAt) || 0) - (Date.parse(a.publishedAt) || 0));
  return { items, providers };
}

async function route(req, res) {
  const url = new URL(req.url, 'http://localhost');
  const ip = req.headers['x-forwarded-for']?.split(',')[0].trim() || req.socket.remoteAddress || 'unknown';
  const q = Object.fromEntries(url.searchParams);

  if (url.pathname === '/health' || url.pathname === '/') {
    return send(res, 200, { ok: true, version: VERSION, providers: providerStatus() });
  }
  if (!tokenOk(req)) return send(res, 401, { error: 'Missing or wrong X-App-Token' });
  if (rateLimited(ip, 'all', 300, 10 * MIN)) return send(res, 429, { error: 'Too many requests, slow down' });

  if (req.method === 'GET' && url.pathname === '/api/news') {
    const params = {
      category: q.category || null, q: q.q || null, from: q.from || null, to: q.to || null,
      lang: q.lang || 'en', country: q.country || 'US',
    };
    return send(res, 200, await aggregateNews(params));
  }

  if (req.method === 'POST' && url.pathname === '/api/videos') {
    const b = await readJson(req);
    if (!b.title) throw new HttpError(400, 'title is required');
    const key = `videos:${hash([b.title, b.publishedAt?.slice(0, 13), b.lang])}`;
    const items = await cache.wrap(key, 6 * 60 * MIN, () => matchVideos(b));
    return send(res, 200, { items });
  }

  if (req.method === 'GET' && url.pathname === '/api/live') {
    const items = await cache.wrap(`live:${q.q}:${q.lang}`, 10 * MIN, () => liveStreams({ q: q.q, lang: q.lang || 'en' }));
    return send(res, 200, { items });
  }

  if (req.method === 'POST' && url.pathname === '/api/analyze') {
    if (rateLimited(ip, 'ai', 120, 10 * MIN)) return send(res, 429, { error: 'AI rate limit reached, try again later' });
    const b = await readJson(req);
    if (!b.title) throw new HttpError(400, 'title is required');
    return send(res, 200, await cache.wrap(`analyze:${hash(b)}`, 7 * 24 * 60 * MIN, () => analyze(b)));
  }

  if (req.method === 'POST' && url.pathname === '/api/explain') {
    if (rateLimited(ip, 'ai', 120, 10 * MIN)) return send(res, 429, { error: 'AI rate limit reached, try again later' });
    const b = await readJson(req);
    if (!Array.isArray(b.articles) || !b.articles.length) throw new HttpError(400, 'articles are required');
    return send(res, 200, await cache.wrap(`explain:${hash(b)}`, 24 * 60 * MIN, () => explain(b)));
  }

  if (req.method === 'GET' && url.pathname === '/api/facebook/posts') {
    if (!q.page) throw new HttpError(400, 'page is required');
    const items = await cache.wrap(`fb:${q.page}`, 15 * MIN, () => facebookPosts(q.page));
    return send(res, 200, { items, providers: [{ provider: 'facebook', ok: true, count: items.length }] });
  }

  return send(res, 404, { error: 'Not found' });
}

export function createServer() {
  return http.createServer((req, res) => {
    route(req, res).catch((e) => {
      const status = e instanceof HttpError ? (e.status >= 400 && e.status < 600 ? e.status : 502) : 500;
      // Upstream provider errors are reported as 502 so the app can tell them apart from its own mistakes.
      const code = e instanceof HttpError && ![400, 401, 413, 429, 501].includes(e.status) ? 502 : status;
      if (code >= 500) console.error(`[${new Date().toISOString()}] ${req.method} ${req.url}: ${e.message}`);
      send(res, code, { error: e.message });
    });
  });
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
  createServer().listen(config.port, () => {
    const p = providerStatus();
    console.log(`SmartNews backend ${VERSION} on :${config.port}`);
    console.log('Providers:', Object.entries(p).map(([k, v]) => `${k}=${v ? 'on' : 'off'}`).join(' '));
    if (!config.appToken) console.warn('WARNING: APP_TOKEN is not set — anyone who finds this URL can use your API quotas.');
  });
}
