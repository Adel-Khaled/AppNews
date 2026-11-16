import { config } from '../config.js';
import { fetchJson, fetchText, stripHtml } from '../util.js';

/** Normalised article shape returned to the app. */
const item = (o) => ({
  title: stripHtml(o.title),
  url: o.url,
  source: o.source || '',
  publishedAt: o.publishedAt || null,
  description: stripHtml(o.description || ''),
  imageUrl: o.imageUrl || null,
  author: o.author || null,
  provider: o.provider,
  category: o.category || null,
});

// SmartNews category id → each provider's own vocabulary.
const NEWSAPI_CATS = { business: 'business', economy: 'business', entertainment: 'entertainment', celebrities: 'entertainment',
  movies: 'entertainment', general: 'general', health: 'health', science: 'science', sports: 'sports', technology: 'technology', ai: 'technology' };
const GNEWS_CATS = { general: 'general', international: 'world', local: 'nation', business: 'business', economy: 'business',
  technology: 'technology', ai: 'technology', entertainment: 'entertainment', celebrities: 'entertainment', movies: 'entertainment',
  sports: 'sports', science: 'science', health: 'health' };
const GUARDIAN_SECTIONS = { international: 'world', politics: 'politics', business: 'business', economy: 'business', technology: 'technology',
  science: 'science', sports: 'sport', environment: 'environment', movies: 'film', entertainment: 'culture', travel: 'travel',
  education: 'education', gaming: 'games', health: 'society' };
// Keyword fallback for categories a provider has no section for.
const KEYWORDS = { politics: 'politics', ai: 'artificial intelligence', automotive: 'automotive OR cars', education: 'education',
  environment: 'climate', travel: 'travel tourism', gaming: 'video games', security: 'defense military', celebrities: 'celebrity',
  movies: 'film OR tv series', international: 'world', economy: 'economy' };

const day = (iso) => (iso ? String(iso).slice(0, 10) : undefined);

export async function newsApi({ category, q, from, to, lang, country }) {
  if (!config.newsApiKey) return null;
  const p = new URLSearchParams({ apiKey: config.newsApiKey, pageSize: '30' });
  let endpoint = 'top-headlines';
  if (q || from || to || !NEWSAPI_CATS[category]) {
    endpoint = 'everything';
    p.set('q', q || KEYWORDS[category] || category || 'news');
    p.set('sortBy', 'publishedAt');
    if (lang) p.set('language', lang);
    if (from) p.set('from', from);
    if (to) p.set('to', to);
  } else {
    p.set('category', NEWSAPI_CATS[category]);
    if (country) p.set('country', country.toLowerCase());
  }
  const r = await fetchJson(`https://newsapi.org/v2/${endpoint}?${p}`);
  return (r.articles || []).filter((a) => a.url && a.title && a.title !== '[Removed]').map((a) => item({
    title: a.title, url: a.url, source: a.source?.name, publishedAt: a.publishedAt, description: a.description,
    imageUrl: a.urlToImage, author: a.author, provider: 'newsapi', category,
  }));
}

export async function gnews({ category, q, from, to, lang, country }) {
  if (!config.gnewsKey) return null;
  const p = new URLSearchParams({ apikey: config.gnewsKey, max: '25', lang: lang || 'en' });
  let endpoint = 'top-headlines';
  if (q || !GNEWS_CATS[category]) {
    endpoint = 'search';
    p.set('q', q || KEYWORDS[category] || category || 'news');
  } else {
    p.set('category', GNEWS_CATS[category]);
    if (country) p.set('country', country.toLowerCase());
  }
  if (from) p.set('from', from);
  if (to) p.set('to', to);
  const r = await fetchJson(`https://gnews.io/api/v4/${endpoint}?${p}`);
  return (r.articles || []).map((a) => item({
    title: a.title, url: a.url, source: a.source?.name, publishedAt: a.publishedAt, description: a.description,
    imageUrl: a.image, provider: 'gnews', category,
  }));
}

export async function guardian({ category, q, from, to }) {
  if (!config.guardianKey) return null;
  const p = new URLSearchParams({
    'api-key': config.guardianKey, 'page-size': '30', 'order-by': 'newest', 'show-fields': 'thumbnail,trailText,byline',
  });
  const section = GUARDIAN_SECTIONS[category];
  if (q) p.set('q', q);
  else if (section) p.set('section', section);
  else if (category && category !== 'general') p.set('q', KEYWORDS[category] || category);
  if (from) p.set('from-date', day(from));
  if (to) p.set('to-date', day(to));
  const r = await fetchJson(`https://content.guardianapis.com/search?${p}`);
  return (r.response?.results || []).map((a) => item({
    title: a.webTitle, url: a.webUrl, source: 'The Guardian', publishedAt: a.webPublicationDate,
    description: a.fields?.trailText, imageUrl: a.fields?.thumbnail, author: a.fields?.byline, provider: 'guardian', category,
  }));
}

/** Minimal RSS/Atom reader for feeds configured on the server (BACKEND_RSS_FEEDS). */
export function parseFeed(xml) {
  const out = [];
  const blocks = xml.match(/<(item|entry)[\s>][\s\S]*?<\/\1>/g) || [];
  const tag = (b, names) => {
    for (const n of names) {
      const m = new RegExp(`<${n}(?:\\s[^>]*)?>([\\s\\S]*?)</${n}>`, 'i').exec(b);
      if (m) return m[1].replace(/^<!\[CDATA\[|\]\]>$/g, '').trim();
    }
    return '';
  };
  for (const b of blocks) {
    let link = tag(b, ['link']);
    if (!link) link = (/<link[^>]*href="([^"]+)"/i.exec(b) || [])[1] || '';
    const img = (/<media:(?:content|thumbnail)[^>]*url="([^"]+)"/i.exec(b) || /<enclosure[^>]*url="([^"]+)"[^>]*type="image/i.exec(b) || [])[1];
    out.push({
      title: stripHtml(tag(b, ['title'])),
      url: stripHtml(link),
      description: stripHtml(tag(b, ['description', 'summary', 'content'])).slice(0, 1500),
      publishedAt: tag(b, ['pubDate', 'published', 'updated', 'dc:date']) || null,
      imageUrl: img || null,
      author: stripHtml(tag(b, ['dc:creator', 'author'])) || null,
    });
  }
  return out.filter((x) => x.title && x.url.startsWith('http'));
}

export async function serverRss({ category, q }) {
  if (!config.rssFeeds.length) return null;
  const feeds = config.rssFeeds.filter((f) => !category || f.category === category);
  const results = await Promise.allSettled(feeds.map(async (f) => {
    const xml = await fetchText(f.url);
    const title = stripHtml((/<title[^>]*>([\s\S]*?)<\/title>/i.exec(xml) || [])[1] || new URL(f.url).hostname);
    return parseFeed(xml).map((a) => item({ ...a, source: title, provider: 'rss', category: f.category }));
  }));
  let items = results.flatMap((r) => (r.status === 'fulfilled' ? r.value : []));
  if (q) {
    const terms = q.toLowerCase().split(/\s+/).filter(Boolean);
    items = items.filter((a) => terms.every((t) => `${a.title} ${a.description}`.toLowerCase().includes(t)));
  }
  return items;
}

export const NEWS_PROVIDERS = { newsapi: newsApi, gnews, guardian, rss: serverRss };
