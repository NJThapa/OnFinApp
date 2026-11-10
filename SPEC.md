# OnFinApp — Specification

Status: draft v1, written from the product interview on 2026-11-10.

## 1. Summary

OnFinApp is a cross-platform finance app. Signed-in users scroll finance news, follow US stocks and ETFs,
and read an AI-generated analysis and buy/hold/sell rating for each one.

The backend is implemented twice — Java Spring Boot and ASP.NET Core — against one shared contract and one
shared database, so the two stacks can be compared side by side. The UI is a single React Native (Expo) app
for iOS, Android and web that can point at either API.

### Goals

- A usable news + stock-analysis app on iOS, Android and web.
- Two interchangeable API implementations with identical behaviour.
- Everything runs locally with one `docker compose up`.
- External dependencies (LLM, price data, identity provider) are swappable by configuration.

### Non-goals for v1

- Trading, brokerage integration, or holding real portfolios.
- Markets other than US-listed stocks and ETFs.
- Training our own prediction models.
- Azure deployment (folder reserved; specified later).

## 2. Decisions from the interview

| Topic | Decision |
|---|---|
| Platforms | iOS, Android, web — Expo |
| Instruments | US stocks + ETFs |
| News, analysis, ratings | LLM with web search, called from the APIs |
| LLM vendor | Pluggable; chosen later |
| Prices and chart history | Pluggable market-data API (Finnhub as first implementation) |
| Sign-in | External OIDC provider; Keycloak now, Microsoft Entra External ID later; swappable |
| Generation | On demand with cache, plus a scheduled refresh that writes the same cache |
| Schedule | Configurable cron; default once per trading day before US market open |
| News feed | Two tabs: Market, My stocks |
| API parity | One shared OpenAPI contract, one contract test suite |
| Database | One shared PostgreSQL database for both APIs |
| v1 extras | Price chart, push notifications, rating history, article bookmarks, stock comparison |
| Rating display | Rating, confidence, rationale, sources, generated-at time, disclaimer |

## 3. Architecture

```
Expo app (iOS / Android / web)
   │  OIDC code flow + PKCE ──────────► Identity provider (Keycloak → Entra External ID)
   │  Bearer JWT
   ▼
Java API  ─or─  .NET API        (same OpenAPI contract, same database)
   │              │
   ├── LlmClient ──────────► LLM vendor with web search   (pluggable)
   ├── PriceProvider ──────► Market-data API               (pluggable)
   ├── PushSender ─────────► Expo push service
   ▼
PostgreSQL (shared schema, SQL migrations in the repo)
```

### Repository layout

```
app/
  api/
    contract/openapi.yaml     source of truth for both APIs
    contract-tests/           one test suite, run against either API
    java/                     Spring Boot
    dotnet/                   ASP.NET Core
  database/migrations/        plain SQL, versioned; neither API owns the schema
  ui/react-native/            Expo app
deployment/
  local/                      docker compose: postgres, migrate, keycloak, api-java, api-dotnet
  azure/                      later
```

`app/api/contract`, `app/api/contract-tests` and `app/database` are additions to the layout agreed earlier.

### Rules that keep the two APIs comparable

- Both implement `openapi.yaml` exactly: paths, status codes, field names, error format.
- Neither API runs migrations. A one-shot `migrate` container applies `app/database/migrations` at startup.
  This replaces the current two-database setup (`app_java`, `app_dotnet`) with a single `onfinapp` database.
- Prompts and JSON output schemas for the LLM live in one shared folder and are loaded by both APIs, so
  differences in output come from the stack, not the prompt.
- Every generated record stores which API produced it (`java` or `dotnet`), duration, and token usage.
- Errors use `application/problem+json` (RFC 9457). Lists use cursor pagination (`?cursor=&limit=`).

## 4. Authentication

- The app signs in with OpenID Connect, authorization code flow with PKCE (`expo-auth-session`).
- The APIs are resource servers only: they validate the JWT signature (JWKS), issuer, audience and expiry.
  They never see passwords and issue no tokens.
- Configuration per API: `OIDC_ISSUER`, `OIDC_AUDIENCE`. The app has `OIDC_ISSUER`, `OIDC_CLIENT_ID`.
  Swapping Keycloak for Entra External ID is a configuration change only.
- Only standard claims are used: `sub`, `email`, `name`. No provider-specific roles or claims in v1.
- A `users` row is created on a user's first authenticated request, keyed by (`issuer`, `subject`).
- Local: Keycloak runs in docker compose with an imported realm, a public client for the app, and a test user.
- Every endpoint requires authentication except `GET /api/health`.

## 5. Features

### 5.1 Instruments and watchlist

- Search US stocks and ETFs by ticker or name. The instrument list is synced from the price provider.
- Follow / unfollow an instrument. The watchlist shows ticker, name, latest price, day change, current rating.
- Limit: 50 followed instruments per user (configurable).

### 5.2 News feed

- **Market tab:** general US market news. **My stocks tab:** news for followed instruments only.
- Each item: headline, 2–3 sentence summary, source name, source URL, published time, related tickers.
- Infinite scroll, newest first, pull to refresh. Tapping an item opens the source article in the browser.
- News items are produced by the LLM with web search and stored. Rules:
  - Every item must carry a source URL that the search tool actually returned; items without one are dropped.
  - Deduplicated by normalised URL.
  - Market news refreshes on its own schedule (default hourly, configurable). Stock news refreshes with the
    stock's analysis.
- Empty state on My stocks when the user follows nothing: prompt to add stocks.

### 5.3 Stock page

- Header: ticker, name, latest price, day change (price provider; quote cached about 60 seconds).
- Price chart: ranges 1D, 1W, 1M, 1Y, 5Y (price provider; history cached).
- Analysis: summary, bull points, bear points, key risks, key figures with as-of dates, source links.
  For ETFs the analysis covers holdings focus, cost and trend instead of company fundamentals.
- Rating card: Buy / Hold / Sell, confidence (Low / Medium / High), rationale, sources, generated-at time.
- Rating history: list of past ratings with date and rationale, newest first.
- News tab for that instrument.
- Refresh button: asks for a new generation; limited per user (default 5 per day) and ignored if the current
  analysis is younger than the minimum age (default 1 hour).
- A fixed notice on the rating card and analysis: AI-generated, may be wrong, not financial advice.

### 5.4 Stock comparison

- Pick two instruments; show them side by side: price, day change, 1Y performance, rating, confidence,
  key figures, bull and bear points.
- Uses the cached analysis of each instrument (generating it on demand if missing). No extra LLM call in v1.

### 5.5 Bookmarks

- Bookmark / unbookmark any news item. A Bookmarks screen lists saved items, newest first.

### 5.6 Notifications

- Push (iOS and Android, via Expo push) when:
  - the rating of a followed instrument changes (e.g. Hold → Buy);
  - a news item for a followed instrument is flagged high-importance by the LLM.
- An in-app notification list on all platforms, including web. Web push is out of scope for v1.
- Per-user settings: rating changes on/off, important news on/off.
- Notifications are written to an outbox table and sent once, even with both APIs running.

### 5.7 Settings

- Profile (name, email from the identity provider), notification toggles, sign out.
- Developer builds only: switch API base URL between Java and .NET.

## 6. AI generation

### LLM abstraction

Both APIs define the same interface: given a prompt, a JSON schema and a web-search flag, return validated
JSON plus the list of source URLs and usage figures. Requirements on any vendor plugged in:
web search tool with citations, and schema-constrained JSON output.

Implementations in v1: `mock` (deterministic fixtures; default for local dev and tests) and one real vendor,
chosen later.

### What is generated

| Artifact | Scope | Trigger |
|---|---|---|
| Market news batch | global | schedule (default hourly) |
| Stock news batch | per instrument | with analysis |
| Analysis + rating | per instrument, shared by all users | on demand, schedule, user refresh |

### Caching and scheduling

- An analysis is valid for a TTL (default 24 hours). Opening a stock page returns the cached analysis if it is
  valid; otherwise it starts generation and returns `202` with a status the app polls.
- The scheduled job (cron, default `0 8 * * MON-FRI` America/New_York) regenerates every instrument that at
  least one user follows and writes the same cache, so on-demand reads normally hit warm data.
- Only one generation per instrument runs at a time. Jobs are rows in a `generation_jobs` table claimed with
  `FOR UPDATE SKIP LOCKED`, so the two APIs can run together without duplicating work.
- `SCHEDULER_ENABLED` per API controls which instance runs the schedule.
- A daily cap on the number of generations (configurable) bounds LLM cost; when reached, cached data is
  served with its age shown.
- Failed or schema-invalid output is retried once, then the job is marked failed and the last valid analysis
  stays visible.

### Rating rules

- A new rating row is appended on every generation; ratings are never updated in place.
- A rating change is detected by comparing with the previous row and triggers notifications.
- The model must base the rating only on sources it cites; a rating with no sources is rejected.

## 7. Price data

- Interface: search/list instruments, latest quote, historical candles for a range.
- Implementations: `mock` (fixtures) and `finnhub`. The API key is configuration; never sent to the app.
- Quotes cached about 60 seconds; daily candles cached until the next trading day.
- If the provider is unavailable, the stock page still shows analysis and news, with the price marked
  unavailable.

## 8. Data model (shared PostgreSQL)

| Table | Purpose | Key fields |
|---|---|---|
| `users` | Local user record | id, issuer, subject, email, name; unique (issuer, subject) |
| `instruments` | Stocks and ETFs | id, symbol (unique), name, type (STOCK/ETF), exchange |
| `watchlist_items` | Follows | user_id, instrument_id, created_at; unique pair |
| `news_items` | Stored news | id, headline, summary, source_name, url (unique), published_at, scope (MARKET/STOCK), importance |
| `news_item_instruments` | News ↔ tickers | news_item_id, instrument_id |
| `bookmarks` | Saved news | user_id, news_item_id, created_at |
| `analyses` | Generated analysis | id, instrument_id, content (jsonb), sources (jsonb), generated_at, expires_at, generated_by, duration_ms, tokens |
| `ratings` | Append-only ratings | id, instrument_id, analysis_id, rating, confidence, rationale, created_at |
| `generation_jobs` | Work queue | id, kind, instrument_id, status, trigger (ON_DEMAND/SCHEDULED/USER), claimed_by, attempts, error |
| `price_cache` | Quotes and candles | instrument_id, range, payload (jsonb), fetched_at |
| `device_tokens` | Push targets | user_id, expo_token, platform |
| `notification_settings` | Per-user toggles | user_id, rating_changes, important_news |
| `notifications` | Outbox + in-app list | id, user_id, type, payload, created_at, sent_at, read_at |

## 9. API (v1)

Base path `/api/v1`. Full definitions live in `app/api/contract/openapi.yaml`.

| Method and path | Purpose |
|---|---|
| `GET /api/health` | Liveness and database check (public) |
| `GET /me` | Current user |
| `GET /instruments?query=` | Search |
| `GET /instruments/{symbol}` | Details and latest quote |
| `GET /instruments/{symbol}/prices?range=` | Chart data |
| `GET /instruments/{symbol}/analysis` | Cached analysis and rating, or `202` while generating |
| `POST /instruments/{symbol}/analysis/refresh` | Request regeneration (rate-limited) |
| `GET /instruments/{symbol}/ratings` | Rating history |
| `GET /instruments/{symbol}/news` | News for one instrument |
| `GET /compare?symbols=A,B` | Side-by-side data |
| `GET /watchlist`, `PUT /watchlist/{symbol}`, `DELETE /watchlist/{symbol}` | Follow management |
| `GET /news?tab=market\|mine` | Feed |
| `GET /bookmarks`, `PUT /bookmarks/{newsId}`, `DELETE /bookmarks/{newsId}` | Bookmarks |
| `GET /notifications`, `POST /notifications/{id}/read` | In-app list |
| `GET /notification-settings`, `PUT /notification-settings` | Toggles |
| `POST /devices`, `DELETE /devices/{token}` | Push token registration |

## 10. UI

- Expo, TypeScript, Expo Router. Bottom tabs: News, Watchlist, Compare, Bookmarks, Settings.
- Stock page reached from watchlist, search, or a ticker chip on a news item.
- API client generated from `openapi.yaml`.
- Tokens stored in secure storage on devices; in memory on web.
- Loading, empty, error and "generating analysis" states on every data screen.

## 11. Non-functional requirements

- Secrets (LLM key, price key) only in server-side configuration; `.env` files are git-ignored.
- Cached reads respond in under 500 ms locally; generation is asynchronous and never blocks a request.
- Structured logs with a request id; generation logs include job id, API name, duration and token usage.
- Stock data is user-independent; user data (watchlist, bookmarks, notifications) is always filtered by the
  authenticated user id.
- Tests: unit tests per API; the shared contract test suite must pass against both APIs with mock providers;
  UI component tests for the main screens.

## 12. Local deployment

`docker compose -f deployment/local/docker-compose.yml up --build` starts PostgreSQL, the migrate job,
Keycloak with the imported realm, and both APIs (Java on 8080, .NET on 8081). Defaults use the mock LLM and
mock price provider, so no API keys are needed to run. The Expo app runs on the host with `npx expo start`.

## 13. Milestones

1. **Foundation:** shared database and migrations, OpenAPI contract, Keycloak, JWT validation in both APIs,
   contract tests, Expo app with sign-in.
2. **Instruments and prices:** instrument sync, search, watchlist, stock page header and chart.
3. **AI analysis:** LLM abstraction with mock, job queue, on-demand generation, rating and history.
4. **News:** market and stock news generation, two-tab feed, bookmarks.
5. **Scheduling and notifications:** scheduled refresh, rating-change detection, push and in-app list.
6. **Comparison and polish:** compare screen, API switcher, cost caps, error states.
7. **Real providers:** plug in the chosen LLM vendor and Finnhub; later, Entra External ID and Azure.

## 14. Open questions

1. Which LLM vendor first? It must offer web search with citations and schema-constrained JSON.
2. Does Finnhub's free tier still include historical candles? If not, another price provider is needed for
   the chart.
3. Is hourly market-news generation acceptable for LLM cost, or should it be less frequent?
4. Should the comparison screen later get its own AI-written verdict?
5. Legal review of the "not financial advice" wording before any public release.
