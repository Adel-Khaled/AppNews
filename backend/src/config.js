const env = process.env;

export const config = {
  port: Number(env.PORT || 8787),
  appToken: env.APP_TOKEN || '',
  newsApiKey: env.NEWSAPI_KEY || '',
  gnewsKey: env.GNEWS_KEY || '',
  guardianKey: env.GUARDIAN_KEY || '',
  rssFeeds: (env.BACKEND_RSS_FEEDS || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((pair) => {
      const i = pair.indexOf('|');
      return i > 0 ? { category: pair.slice(0, i), url: pair.slice(i + 1) } : { category: 'general', url: pair };
    }),
  youtubeKey: env.YOUTUBE_API_KEY || '',
  anthropicKey: env.ANTHROPIC_API_KEY || '',
  aiModel: env.AI_MODEL || 'claude-haiku-5-5',
  facebookToken: env.FACEBOOK_ACCESS_TOKEN || '',
  facebookVersion: env.FACEBOOK_GRAPH_VERSION || 'v21.0',
};

export function providerStatus() {
  return {
    newsapi: !!config.newsApiKey,
    gnews: !!config.gnewsKey,
    guardian: !!config.guardianKey,
    rss: config.rssFeeds.length > 0,
    youtube: !!config.youtubeKey,
    ai: !!config.anthropicKey,
    facebook: !!config.facebookToken,
  };
}
