import { config } from '../config.js';
import { entities as extractEntities, fetchJson, HttpError, isNewsChannel, isoDurationToSeconds, scoreVideo, stripHtml, tokens } from '../util.js';

const YT = 'https://www.googleapis.com/youtube/v3';

async function ytDetails(ids) {
  if (!ids.length) return {};
  const p = new URLSearchParams({ key: config.youtubeKey, part: 'contentDetails,status,snippet', id: ids.join(',') });
  const r = await fetchJson(`${YT}/videos?${p}`);
  return Object.fromEntries((r.items || []).map((v) => [v.id, v]));
}

function toVideo(s, d) {
  const id = s.id?.videoId || s.id;
  const sn = d?.snippet || s.snippet || {};
  return {
    videoId: id,
    url: `https://www.youtube.com/watch?v=${id}`,
    title: stripHtml(sn.title),
    channel: sn.channelTitle || '',
    publishedAt: sn.publishedAt || null,
    thumbnailUrl: sn.thumbnails?.high?.url || sn.thumbnails?.medium?.url || sn.thumbnails?.default?.url || null,
    durationSec: isoDurationToSeconds(d?.contentDetails?.duration),
    // Only YouTube's own "live" state counts as live. "upcoming" and recordings are not live.
    isLive: (d?.snippet?.liveBroadcastContent || sn.liveBroadcastContent) === 'live',
    embeddable: d?.status?.embeddable !== false,
    description: stripHtml(sn.description || ''),
    relevance: 0,
  };
}

/**
 * Finds YouTube videos about one specific event. Query is built from the event's entities,
 * the search is limited to a window around the publication date, and every result is scored
 * so videos that merely share a keyword are dropped.
 */
export async function matchVideos({ title, description = '', entities = [], publishedAt, lang = 'en', maxResults = 8 }) {
  if (!config.youtubeKey) throw new HttpError(501, 'YouTube is not configured on the backend (YOUTUBE_API_KEY).');
  const ents = (entities.length ? entities : extractEntities(`${title}. ${description}`)).map((e) => String(e).toLowerCase());
  const t = Date.parse(publishedAt) || Date.now();
  const storyTokens = tokens(`${title} ${description}`);
  const query = ents.length >= 2 ? ents.slice(0, 4).join(' ') : title.split(/\s+/).slice(0, 10).join(' ');
  const p = new URLSearchParams({
    key: config.youtubeKey, part: 'snippet', type: 'video', order: 'relevance', maxResults: '20', q: query,
    publishedAfter: new Date(t - 2 * 86400e3).toISOString(),
    publishedBefore: new Date(Math.min(Date.now(), t + 7 * 86400e3)).toISOString(),
    relevanceLanguage: lang, safeSearch: 'moderate',
  });
  const r = await fetchJson(`${YT}/search?${p}`);
  const ids = (r.items || []).map((i) => i.id?.videoId).filter(Boolean);
  const details = await ytDetails(ids);
  const scored = (r.items || []).filter((i) => i.id?.videoId).map((i) => {
    const v = toVideo(i, details[i.id.videoId]);
    v.relevance = Number(scoreVideo({ storyTokens, storyEntities: ents, storyTime: t }, v).toFixed(3));
    return v;
  });
  return scored.filter((v) => v.relevance >= 0.35)
    .sort((a, b) => b.relevance - a.relevance || (isNewsChannel(b.channel) - isNewsChannel(a.channel)))
    .slice(0, maxResults);
}

export async function liveStreams({ q, lang = 'en' }) {
  if (!config.youtubeKey) throw new HttpError(501, 'YouTube is not configured on the backend (YOUTUBE_API_KEY).');
  const p = new URLSearchParams({
    key: config.youtubeKey, part: 'snippet', type: 'video', eventType: 'live', maxResults: '25',
    q: q || (lang === 'ar' ? 'بث مباشر أخبار' : 'news live'), relevanceLanguage: lang, order: 'viewCount',
  });
  const r = await fetchJson(`${YT}/search?${p}`);
  const ids = (r.items || []).map((i) => i.id?.videoId).filter(Boolean);
  const details = await ytDetails(ids);
  return (r.items || []).filter((i) => i.id?.videoId).map((i) => toVideo(i, details[i.id.videoId]))
    .filter((v) => v.isLive)
    .map((v) => ({ ...v, relevance: isNewsChannel(v.channel) ? 1 : 0.5 }))
    .sort((a, b) => b.relevance - a.relevance);
}

/** Public posts of a Facebook Page through the official Graph API. */
export async function facebookPosts(page) {
  if (!config.facebookToken) {
    throw new HttpError(501, 'Facebook is not configured on the backend. It needs a Page access token (FACEBOOK_ACCESS_TOKEN) ' +
      'with permission to read this Page. Scraping Facebook is not supported.');
  }
  const p = new URLSearchParams({ access_token: config.facebookToken, fields: 'message,permalink_url,created_time,full_picture', limit: '25' });
  const r = await fetchJson(`https://graph.facebook.com/${config.facebookVersion}/${encodeURIComponent(page)}/posts?${p}`);
  return (r.data || []).filter((x) => x.permalink_url && x.message).map((x) => ({
    title: x.message.split('\n')[0].slice(0, 160),
    url: x.permalink_url,
    source: `Facebook: ${page}`,
    publishedAt: x.created_time,
    description: x.message.slice(0, 1500),
    imageUrl: x.full_picture || null,
    provider: 'facebook',
  }));
}
