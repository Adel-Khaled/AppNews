# SmartNews: Personal Global News & Multimedia Aggregator

SmartNews is a native Android app written in Kotlin with Jetpack Compose and Material 3. It collects news from publisher RSS feeds, Google News, news APIs and your own sources. Articles from different publishers that cover the same event are grouped into one story, along with related videos, images and interviews. Everything is stored on the phone, so news stays available offline and is never deleted by a refresh.

```
SmartNews/
├── app/                      Android app (Kotlin, Compose, Hilt, Room, Retrofit, WorkManager, Media3)
├── backend/                  Node.js backend that holds every API key (no npm dependencies)
├── .github/workflows/        Cloud build that produces the debug and release APKs
└── README.md
```

---

## 1. What works, and what needs the backend

SmartNews runs in two modes:

| Feature | Without backend | With backend |
|---|---|---|
| News from 21 built-in publisher RSS feeds | ✅ | ✅ |
| Google News search feeds for every category, topic and keyword | ✅ | ✅ |
| Custom sources (websites, RSS, YouTube channels) | ✅ | ✅ |
| NewsAPI / GNews / The Guardian APIs | — | ✅ |
| Grouping and de-duplication of stories | ✅ on device | ✅ |
| Summaries | Extract: the publisher's own sentences, labelled "Extract" | AI summary, labelled "AI-generated" |
| Explain This News (background, why it matters, where sources differ) | — (the app tells you why) | ✅ |
| Videos the publishers embedded in their own pages | ✅ | ✅ |
| YouTube videos matched to the exact event | — | ✅ |
| Live streams with a LIVE badge | — | ✅ (YouTube Data API) |
| Facebook Pages | — | ✅ only with a Graph API Page token |
| Search, date filters, saved articles, history, offline mode, notifications | ✅ | ✅ |

The app keeps no API keys. The backend URL and an app token are entered in **Settings → Backend**.

---

## 2. Build the APK

### Option A: GitHub (no Android Studio needed)
1. Create a new **private** repository on GitHub and upload the whole `SmartNews` folder, including the hidden `.github` folder.
2. Open the **Actions** tab. The **Build APK** workflow runs on every push. You can also start it with **Run workflow**.
3. When the run finishes (about 8–12 minutes), download **SmartNews-apks** from the run page. It contains:
   - `app-debug.apk`: installs as "SmartNews" with the id `com.smartnews.debug`
   - `app-release.apk`: the release build

The workflow also runs the unit tests for the app and the backend.

### Option B: Android Studio
1. Install Android Studio (Ladybug 2024.2 or newer) with SDK 35.
2. **File → Open** the `SmartNews` folder and wait for Gradle sync to finish.
3. To build the debug APK, use **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. You can also run `./gradlew assembleDebug` from a terminal.
4. To build the release APK, run `./gradlew assembleRelease`.

The APKs are written to `app/build/outputs/apk/debug/` and `app/build/outputs/apk/release/`.

### Release signing
If you don't set up a keystore, the release APK is signed with the debug key. It installs fine, but it isn't suitable for the Play Store. To use your own key:

```bash
keytool -genkeypair -v -keystore smartnews.jks -alias smartnews -keyalg RSA -keysize 2048 -validity 10000
```
Then create `keystore.properties` next to `settings.gradle.kts` (this file is git-ignored):
```
storeFile=smartnews.jks
storePassword=…
keyAlias=smartnews
keyPassword=…
```
For GitHub builds, add these repository secrets instead: `KEYSTORE_BASE64` (from `base64 -w0 smartnews.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

---

## 3. Install on your phone
1. Copy the `.apk` to the phone (USB, Google Drive, email…) or download it directly on the phone.
2. Open it. Android will ask you to allow **Install unknown apps** for the app you opened it from (Files, Chrome…). Allow it, then tap **Install**.
3. Open SmartNews. It starts collecting news right away. On Android 13 and later it asks for notification permission when you turn notifications on.

To install over USB with developer mode enabled: `adb install -r app-release.apk`.

Requires Android 8.0 (API 26) or newer.

---

## 4. Set up the backend (optional, adds AI, YouTube and news APIs)

The backend needs Node.js 22.9 or newer and has no npm dependencies.

```bash
cd backend
cp .env.example .env        # fill in the keys you have
npm test                    # 8 tests, no keys needed
npm start                   # listens on :8787
```

Any provider without a key is skipped, and a provider that fails never breaks the others.

### Getting API keys
| Variable | Where | Notes |
|---|---|---|
| `YOUTUBE_API_KEY` | Google Cloud Console → create project → **APIs & Services → Library → YouTube Data API v3 → Enable** → **Credentials → Create API key** | 10,000 units/day free. Each video search costs about 100 units, so SmartNews caches results for 6 h and matches only the 6 biggest new stories per refresh. |
| `ANTHROPIC_API_KEY` | console.anthropic.com → API Keys | Used for summaries and Explain This News. Change the model with `AI_MODEL`. |
| `NEWSAPI_KEY` | newsapi.org → Get API key | The free "Developer" plan is for development only. Check their terms for personal use. |
| `GNEWS_KEY` | gnews.io → sign up | Free tier: 100 requests/day. |
| `GUARDIAN_KEY` | open-platform.theguardian.com → Register developer key | Free for non-commercial use. |
| `FACEBOOK_ACCESS_TOKEN` | developers.facebook.com → create app → Graph API Explorer → Page access token | Reads only Pages your token is allowed to read. Public pages you don't manage need Meta's *Page Public Content Access* approval. |
| `APP_TOKEN` | any long random string | Type the same value into the app. |
| `BACKEND_RSS_FEEDS` | — | Extra server-side feeds as `category|url,category|url` |

### Where to run it
- **On your own computer, at home:** run `npm start`. In the app, enter `http://<your-PC's-LAN-IP>:8787` (for example `http://192.168.1.20:8787`). The phone must be on the same Wi-Fi.
- **In the cloud (works anywhere):** deploy the `backend` folder with its `Dockerfile` to Render, Railway, Fly.io or any VPS, and set the environment variables in that service's dashboard. Use the HTTPS URL it gives you.

Then in the app, open **Settings → Backend**, enter the URL and the token, and tap **Save & test**. The test shows which providers are enabled.

### API
| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Status and enabled providers (no token needed) |
| GET | `/api/news?category=&q=&from=&to=&lang=&country=` | Aggregated, de-duplicated articles from NewsAPI, GNews, Guardian and server RSS |
| POST | `/api/videos` `{title, description, entities[], publishedAt, lang}` | YouTube videos matched to an event |
| GET | `/api/live?q=&lang=` | Broadcasts YouTube reports as live right now |
| POST | `/api/analyze` `{title, text, source, lang}` | AI summary, key points, entities, category |
| POST | `/api/explain` `{headline, articles[], lang}` | Explanation, background, why it matters, differences, unconfirmed claims |
| GET | `/api/facebook/posts?page=` | Facebook Page posts through the Graph API |

---

## 5. How it works

### Architecture (MVVM + Clean Architecture)
```
ui/            Compose screens + Hilt ViewModels (state as StateFlow)
domain/        Models, repository interfaces, pure-Kotlin logic (clustering, matching, classifier, extracts)
data/local     Room: articles, stories, media, sources, categories, history
data/remote    OkHttp/Retrofit: RSS/Atom parser, Google News, page scraper, source discovery, backend API
data/repository  Repository implementations (refresh pipeline, search, AI)
sync/          WorkManager worker and scheduler, notifications, connectivity, shared refresh state
di/            Hilt modules
```
To add a provider, add a feed source (in-app or in `DefaultCatalog`), or add a function to `backend/src/providers/news.js` and register it in `NEWS_PROVIDERS`.

### The refresh pipeline (and why it never deletes news)
1. Every enabled feed, plus a Google News search for each enabled category and topic, is fetched in parallel, six at a time. Each source succeeds or fails independently, and its last error is shown in **My Sources**.
2. If the backend is configured, the news APIs are queried for the same categories.
3. Each article's key is the SHA-1 of its canonical URL, with tracking parameters removed. Articles that are already stored only get their title, description or image updated. Saved, read and summary state is never touched.
4. New articles are compared with the previous five days of stored articles. An identical headline counts as a **duplicate** and is skipped. Matching terms and entities within 72 hours count as the **same story**, and the article joins it.
5. Images and videos published in the feeds are attached to the story. For the biggest fresh stories, matched YouTube videos are fetched through the backend.
6. A story is marked **breaking** when three or more outlets report it within 6 hours, or when the headline is marked breaking/عاجل.

Refreshing never runs `DELETE`. The only thing that deletes news is **Settings → Storage → Clean up old news**, which asks for confirmation and keeps saved and opened articles.

### Video matching
For each video, the backend combines four signals: the event's entities (people, organisations, places), shared terms, how close the upload time is to the story (videos from more than 2 days before the event score zero), and whether the channel is an established news outlet. Videos scoring under 0.35 are dropped, so a video that only shares a keyword is not shown.

### Playback
- Direct video files and HLS streams from publishers play in **Media3 ExoPlayer**.
- YouTube videos play in YouTube's **official embedded player**. SmartNews never extracts YouTube streams, which would violate YouTube's terms. If the uploader has disabled embedding, use **Open in YouTube**.
- Everything else opens in the official app or the browser.

### Transparency rules built into the UI
- Every article shows its publisher, publication time and the time SmartNews collected it, with a button to open the original.
- On a story page, each publisher's headline and summary are shown separately. They are never merged.
- Summaries are labelled either **AI-generated** or **Extract from publisher text**.
- Explain This News lists **where sources differ** and **what is not yet confirmed**. The prompts tell the model to use only the supplied text.
- **LIVE** appears only when YouTube reports `liveBroadcastContent = live`.

---

## 6. Known limitations
- **Google News links** are Google redirect URLs that open the original article. The publisher's name comes from the feed.
- **Facebook, Instagram, X/TikTok:** scraping is against their terms and isn't done. Facebook works only through the Graph API with a valid token. For the others, add the publisher's website and SmartNews will look for its RSS feed.
- **Built-in feeds** are real publisher URLs, but publishers sometimes move them. A broken feed shows its error in My Sources and can be edited or turned off.
- **Background sync** uses WorkManager, so Android may postpone it to save battery. Opening the app always refreshes if the last sync was more than 20 minutes ago.
- **Full article text:** to respect copyright, SmartNews shows summaries and links to the original. It does not republish full articles.
- **Arabic:** the UI supports RTL and the category keywords have Arabic versions. Use **Settings → Language** to get Arabic Google News results and AI output.

## 7. Tests
- `app/src/test`: clustering, de-duplication, URL canonicalisation, classifier, extractive summary, video matching and date filters. Run with `./gradlew testDebugUnitTest`.
- `backend/test`: auth, provider isolation, 501 responses for unconfigured features, video scoring and RSS parsing. Run with `npm test`.
- Before you rely on the app, do this on a real phone: refresh, then force-close the app, turn on airplane mode and reopen it. The news should still be there.
