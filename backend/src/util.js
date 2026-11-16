// Small shared helpers: TTL cache, HTTP fetch with timeout, text analysis.

export class TtlCache {
  constructor(maxEntries = 2000) {
    this.map = new Map();
    this.max = maxEntries;
  }
  get(key) {
    const e = this.map.get(key);
    if (!e) return undefined;
    if (Date.now() > e.exp) { this.map.delete(key); return undefined; }
    return e.value;
  }
  set(key, value, ttlMs) {
    if (this.map.size >= this.max) this.map.delete(this.map.keys().next().value);
    this.map.set(key, { value, exp: Date.now() + ttlMs });
  }
  async wrap(key, ttlMs, fn) {
    const hit = this.get(key);
    if (hit !== undefined) return hit;
    const v = await fn();
    this.set(key, v, ttlMs);
    return v;
  }
}

export class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

export async function fetchJson(url, { timeoutMs = 15000, ...init } = {}) {
  const res = await fetch(url, { ...init, signal: AbortSignal.timeout(timeoutMs) });
  const text = await res.text();
  let body;
  try { body = text ? JSON.parse(text) : {}; } catch { body = { raw: text.slice(0, 300) }; }
  if (!res.ok) {
    const msg = body?.message || body?.error?.message || body?.errors?.[0] || body?.raw || res.statusText;
    throw new HttpError(res.status, `HTTP ${res.status}: ${typeof msg === 'string' ? msg : JSON.stringify(msg)}`);
  }
  return body;
}

export async function fetchText(url, { timeoutMs = 15000 } = {}) {
  const res = await fetch(url, {
    signal: AbortSignal.timeout(timeoutMs),
    headers: { 'User-Agent': 'SmartNewsBackend/1.0 (+personal news reader)' },
  });
  if (!res.ok) throw new HttpError(res.status, `HTTP ${res.status}`);
  return res.text();
}

const STOP = new Set(('a an the and or but of to in on at for with by from as is are was were be been it its this that these those after before ' +
  'over under into about than then has have had will would can could says say said new news live update updates video watch how why what who ' +
  'when where not no up out more amid first his her their they he she we you via في من على إلى الى عن مع أن ان إن هذا هذه التي الذي بعد قبل حول خلال بين').split(' '));

export function normalize(s) {
  return String(s || '').toLowerCase().normalize('NFKD').replace(/\p{Mn}+/gu, '')
    .replace(/[^\p{L}\p{N}\s]/gu, ' ').replace(/\s+/g, ' ').trim();
}

export function tokens(s) {
  return new Set(normalize(s).split(' ').map((w) => (w.length > 4 && w.startsWith('ال') ? w.slice(2) : w))
    .filter((w) => w.length > 2 && !STOP.has(w) && !/^\d+$/.test(w)));
}

export function overlap(a, b) {
  if (!a.size || !b.size) return 0;
  let n = 0;
  for (const x of a) if (b.has(x)) n++;
  return n / Math.min(a.size, b.size);
}

/** Capitalised phrases and acronyms — a cheap named-entity heuristic. */
export function entities(text) {
  const out = new Set();
  let cur = [];
  const flush = () => {
    if (cur.length) {
      const p = cur.join(' ').toLowerCase();
      if (p.length > 2 && !STOP.has(p)) out.add(p);
    }
    cur = [];
  };
  for (const raw of String(text || '').replace(/["“”‘’()[\]:;,!?|]/g, ' ').split(/\s+/)) {
    const w = raw.replace(/^[.'-]+|[.'-]+$/g, '');
    const cap = w.length > 1 && /\p{Lu}/u.test(w[0]);
    const acr = w.length >= 2 && w.length <= 6 && /^[\p{Lu}\d]+$/u.test(w);
    if (cap || acr) cur.push(w); else flush();
  }
  flush();
  return [...out].slice(0, 15);
}

export const NEWS_CHANNELS = [
  'bbc news', 'al jazeera english', 'reuters', 'associated press', 'cnn', 'sky news', 'dw news', 'france 24 english',
  'cnbc', 'bloomberg television', 'abc news', 'nbc news', 'cbs news', 'guardian news', 'channel 4 news', 'wion',
  'trt world', 'pbs newshour', 'euronews', 'ndtv', 'al arabiya', 'sky news arabia', 'الجزيرة', 'bbc news عربي',
  'cbc news', 'abc news (australia)', 'the telegraph', 'the economist', 'financial times', 'wall street journal',
];

export function isNewsChannel(name) {
  const n = String(name || '').toLowerCase();
  return NEWS_CHANNELS.some((c) => n === c || n.startsWith(c));
}

/**
 * How well a video matches one specific news event (0..1+): shared entities, shared terms,
 * publication-time proximity, and a bonus for established news channels.
 */
export function scoreVideo({ storyTokens, storyEntities, storyTime }, video) {
  const text = `${video.title} ${video.description || ''}`;
  const lower = text.toLowerCase();
  const vt = tokens(text);
  const hits = storyEntities.filter((e) => lower.includes(e)).length;
  const entityScore = storyEntities.length ? Math.min(1, hits / Math.min(5, storyEntities.length)) : 0;
  const termScore = overlap(storyTokens, vt);
  let timeScore = 0.3;
  if (video.publishedAt && storyTime) {
    const h = (Date.parse(video.publishedAt) - storyTime) / 3.6e6;
    timeScore = h < -48 ? 0 : Math.exp(-Math.abs(h) / 72);
  }
  const channel = isNewsChannel(video.channel) ? 0.1 : 0;
  return 0.4 * entityScore + 0.3 * termScore + 0.2 * timeScore + channel;
}

export function isoDurationToSeconds(iso) {
  const m = /^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(iso || '');
  if (!m) return null;
  const [, d, h, mi, s] = m.map((x) => Number(x || 0));
  return d * 86400 + h * 3600 + mi * 60 + s;
}

/** Pulls the first JSON object out of an LLM reply. */
export function extractJson(text) {
  const s = String(text || '');
  const start = s.indexOf('{');
  const end = s.lastIndexOf('}');
  if (start < 0 || end <= start) throw new Error('Model did not return JSON');
  return JSON.parse(s.slice(start, end + 1));
}

export function stripHtml(s) {
  return String(s || '').replace(/<[^>]*>/g, ' ').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/\s+/g, ' ').trim();
}
