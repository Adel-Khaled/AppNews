import { config } from './config.js';
import { extractJson, fetchJson, HttpError } from './util.js';

const LANG = { en: 'English', ar: 'Arabic', fr: 'French', es: 'Spanish', de: 'German', it: 'Italian', pt: 'Portuguese', tr: 'Turkish' };
const CATEGORIES = 'politics, economy, business, technology, ai, science, sports, health, entertainment, celebrities, movies, international, local, automotive, education, environment, travel, gaming, security, general';

async function complete(system, user, maxTokens = 900) {
  if (!config.anthropicKey) throw new HttpError(501, 'AI is not configured on the backend (ANTHROPIC_API_KEY).');
  const r = await fetchJson('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    timeoutMs: 60000,
    headers: {
      'content-type': 'application/json',
      'x-api-key': config.anthropicKey,
      'anthropic-version': '2023-06-01',
    },
    body: JSON.stringify({ model: config.aiModel, max_tokens: maxTokens, system, messages: [{ role: 'user', content: user }] }),
  });
  const text = (r.content || []).filter((c) => c.type === 'text').map((c) => c.text).join('\n');
  return extractJson(text);
}

const RULES = `Rules you must follow:
- Use ONLY the text provided. Never add names, numbers, quotes, dates or events that are not in it.
- If the text does not support a statement, leave it out.
- Do not present a claim as confirmed when only one outlet reports it, when it is attributed ("officials said"), or when outlets disagree.
- Keep the publishers' attributions ("according to X").
- Reply with a single JSON object and nothing else.`;

export async function analyze({ title, text, source, lang = 'en' }) {
  const language = LANG[lang] || 'English';
  const system = `You summarise news articles for a news reader app. ${RULES}
Write in ${language}.
JSON schema: {"summary": string (2-4 sentences), "keyPoints": string[] (3-5 short points), "entities": string[] (people, organisations, places), "category": one of [${CATEGORIES}], "breaking": boolean (true only if the text itself describes a fast-developing event happening now)}`;
  const user = `Source: ${source}\nHeadline: ${title}\n\nText:\n${String(text || '').slice(0, 7000)}`;
  const j = await complete(system, user);
  return {
    summary: String(j.summary || ''),
    keyPoints: Array.isArray(j.keyPoints) ? j.keyPoints.map(String).slice(0, 6) : [],
    entities: Array.isArray(j.entities) ? j.entities.map(String).slice(0, 15) : [],
    category: j.category ? String(j.category) : null,
    breaking: j.breaking === true,
    model: config.aiModel,
  };
}

export async function explain({ headline, articles = [], lang = 'en' }) {
  const language = LANG[lang] || 'English';
  const system = `You explain a news story to a curious reader, based on several publishers' reports of the same event. ${RULES}
You may add brief, widely established background context (history, who an organisation is) but it must go in "background" and must not introduce new claims about this event.
Write in ${language}.
JSON schema: {"explanation": string (what happened, 3-6 sentences, attributing claims to outlets where they differ), "background": string, "whyItMatters": string, "differences": string[] (each a specific point where the outlets' accounts differ, naming the outlets; empty if none), "unconfirmed": string[] (claims reported by only one outlet or only attributed to unnamed sources)}`;
  const user = `Story: ${headline}\n\n` + articles.slice(0, 8).map((a, i) =>
    `[${i + 1}] ${a.source} — ${a.publishedAt}\nHeadline: ${a.title}\n${String(a.description || '').slice(0, 1200)}`).join('\n\n');
  const j = await complete(system, user, 1400);
  const arr = (x) => (Array.isArray(x) ? x.map(String) : []);
  return {
    explanation: String(j.explanation || ''),
    background: String(j.background || ''),
    whyItMatters: String(j.whyItMatters || ''),
    differences: arr(j.differences),
    unconfirmed: arr(j.unconfirmed),
    model: config.aiModel,
  };
}
