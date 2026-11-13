# OnFinApp — Specification

Status: v2, 13 November 2026. Version 1 was written from the product interview of 10 November. Version 2 adds
the decisions from the product review of 12 and 13 November and marks what is already built.

Feature sections say **Built** or **Planned**. Built sections describe the code in this repository; the
contract (`app/api/contract/openapi.yaml`), the migrations and the README are authoritative for details.

## 1. Summary

OnFinApp is a cross-platform finance app for people who want insight into the finance world without having to
look for it. Signed-in users scroll a feed of finance news and AI-written insight, follow US stocks and ETFs,
and read an AI-generated analysis and Buy/Hold/Sell rating for each one.

The backend is implemented twice — Java Spring Boot and ASP.NET Core — against one shared contract and one
shared database, so the two stacks can be compared side by side. The UI is a single React Native (Expo) app
for iOS, Android and web that can point at either API. A separate admin console, with its own login, shows
and controls AI spend and generation.

### Who it is for

| Person | Description |
|---|---|
| Reader | Spends a lot of time on their phone and wants that time to be useful. Wants to understand what is happening in finance by scrolling, without searching or doing research. |
| Operator | Runs the service; today that is the owner. Wants to see what the AI costs and what failed, and to change limits without a deployment. |

What follows from the reader:

- The feed is the product. It is useful on first open, before the reader follows anything.
- Insight comes to the reader. Analysis highlights and rating changes appear in the feed, not only on a stock
  page reached by search.
- Cards are short; detail is one tap away.

### Goals

- A usable feed and stock-analysis app on iOS, Android and web.
- Two interchangeable API implementations with identical behaviour.
- Everything runs locally with one `docker compose up`.
- External dependencies (LLM, price data, identity provider) are swappable by configuration.
- Every AI generation records what it cost; the operator can see the spend and cap it.

### Non-goals for version 1

- Trading, brokerage integration, or holding real portfolios.
- Markets other than US-listed stocks and ETFs.
- Training our own prediction models.
- Advice tailored to a person. Every reader sees the same analysis and rating of an instrument.
- Public release. Open sign-up, app stores, hosted deployment and push notifications to devices come after
  version 1 (epic 7).

## 2. Decisions

"v1" decisions come from the interview of 10 November, "v2" from the review of 12 and 13 November.

| Topic | Decision | From |
|---|---|---|
| Platforms | iOS, Android, web — Expo | v1 |
| Instruments | US stocks + ETFs | v1 |
| News, analysis, ratings | LLM with web search, called from the APIs | v1 |
| LLM vendor | Pluggable; chosen by the provider trial (epic 1) | v1, v2 |
| Prices and chart history | Pluggable market-data API; Finnhub is the first candidate, subject to the free-tier check in epic 1 | v1, v2 |
| Sign-in | External OIDC provider; Keycloak now, Microsoft Entra External ID later; swappable | v1 |
| Generation | On demand with cache, plus a scheduled refresh that writes the same cache | v1 |
| Schedule | Configurable cron; default once per trading day before US market open | v1 |
| News feed | Two tabs: Market, My stocks | v1 |
| API parity | One shared OpenAPI contract, one contract test suite | v1 |
| Database | One shared PostgreSQL database for both APIs | v1 |
| Version 1 extras | Price chart, rating history, article bookmarks, stock comparison, in-app notifications | v1, v2 |
| Rating display | Rating, confidence, rationale, sources, generated-at time, horizon, disclaimer | v1, v2 |
| Purpose | Private use now, public release intended. Legal and licensing limits are designed in from now (section 14) | v2 |
| Version 1 | Runs locally with real providers. No date | v2 |
| Reader | Phone-first; wants insight without searching. The feed is the product | v2 |
| Feed content | News plus AI insight cards: rating changes on My stocks, and spotlights (without a rating) on instruments the reader does not follow | v2 |
| Where ratings appear | Buy/Hold/Sell ratings are for instruments the reader follows. They appear on the watchlist and on the My stocks tab. Market news and spotlights never carry a rating | v2 |
| AI generation | Agent-based wherever it helps. How deep the agents go is a setting, chosen in the provider trial by quality and cost | v2 |
| AI verdict | The AI is the only judge; no person reviews a rating. The system runs its own checks (section 6) | v2 |
| Rating horizon | A setting from 1 to 12 months, stored with each rating and shown on it | v2 |
| Stock comparison | Gets an AI-written verdict | v2 |
| Admin console | Separate web service with its own login, for AI spend and app settings | v2 |
| Why two stacks | To compare implementations for the owner's upcoming work | v2 |
| Order of work | Provider trial first: LLM vendor, then the price provider's free tier | v2 |
| Budget | A monthly ceiling is set after the trial; lower is better | v2 |
| Accounts | Created by the operator in version 1; no open sign-up | v2 |
| Push to devices | After version 1: it needs developer accounts, and Apple's is paid | v2 |
| Team | One developer, working in continuous flow, no estimates | v2 |
| Backlog | GitHub Issues; API and UI stories are separate issues with their own definition of done (section 13) | v2 |
| CI | GitHub Actions | v2 |
| UI baseline | React Native Paper | v2 |

## 3. Architecture

```
Expo app (iOS / Android / web)
   │  OIDC code flow + PKCE ──────────► Identity provider (Keycloak → Entra External ID)
   │  Bearer JWT
   ▼
Java API  ─or─  .NET API        (same OpenAPI contract, same database)
   │              │
   ├── LlmClient ──────────► LLM vendor with web search   (pluggable; mock today)
   ├── PriceProvider ──────► Market-data API               (pluggable; mock today)
   ├── PushSender ─────────► Expo push service             (after version 1)
   ├── OpenTelemetry ──────► Elastic stack                 (local)
   ▼
PostgreSQL (shared schema, SQL migrations in the repo)
```

The admin console (planned, section 5.8) is a separate web service with its own login. Which stack it is built
in, and whether it calls the APIs or reads the database, is open (section 15).

### Repository layout

```
app/
  api/
    contract/openapi.yaml     source of truth for both APIs
    contract-tests/           one test suite, run against either API
    shared/                   files both APIs read: mock price fixtures, LLM prompts and schemas
    java/                     Spring Boot
    dotnet/                   ASP.NET Core
  database/migrations/        plain SQL, versioned; neither API owns the schema
  ui/react-native/            Expo app
deployment/
  local/                      docker compose: postgres, migrate, keycloak, both APIs, Elastic stack
  azure/                      later
diagrams/                     login flow
```

### Rules that keep the two APIs comparable

- Both implement `openapi.yaml` exactly: paths, status codes, field names, error format.
- Neither API runs migrations. A one-shot `migrate` container applies `app/database/migrations` at startup.
- Prompts and JSON output schemas for the LLM live in `app/api/shared/llm` and are loaded by both APIs, so
  differences in output come from the stack, not the prompt.
- Every generated record stores which API produced it (`java` or `dotnet`), the model, duration and token
  usage.
- Errors use `application/problem+json` (RFC 9457). Lists use cursor pagination (`?cursor=&limit=`).
- Both APIs are organised the same way: by feature, each with an HTTP layer, a service and a repository.
- The contract and the stories never name a stack. A story is done only when every implementation passes the
  same contract tests (section 13).

## 4. Authentication — Built

- The app signs in with OpenID Connect, authorization code flow with PKCE (`expo-auth-session`).
- The APIs are resource servers only: they validate the JWT signature (JWKS), issuer, audience and expiry.
  They never see passwords and issue no tokens.
- Configuration per API: `OIDC_ISSUER`, `OIDC_AUDIENCE`, and an optional `OIDC_JWKS_URI` for when the issuer
  URL is not reachable from the API. The app has `OIDC_ISSUER`, `OIDC_CLIENT_ID`.
  Swapping Keycloak for Entra External ID is a configuration change only.
- Only standard claims are used: `sub`, `email`, `name`. No provider-specific roles or claims in v1.
- A `users` row is created on a user's first authenticated request, keyed by (`issuer`, `subject`).
- Local: Keycloak runs in docker compose with an imported realm, a public client for the app, a client for the
  contract tests, and two seeded users. Sign-up is switched off: in version 1 the operator creates accounts.
- Every endpoint requires authentication except `GET /api/health`.
- Planned: the admin console has its own login (section 5.8).

## 5. Features

### 5.1 Instruments and watchlist — Built

- Search US stocks and ETFs by ticker or name. The instrument list is synced from the price provider.
- Follow / unfollow an instrument. The watchlist shows ticker, name, latest price, day change, current rating.
- Limit: 50 followed instruments per user (configurable).

### 5.2 Feed — Planned (epic 3)

Today the News tab shows sample data.

The feed has two tabs. **Market** is useful with nothing followed. **My stocks** covers followed instruments
only, and prompts the reader to add stocks when they follow none.

| Card | Shows | Appears on |
|---|---|---|
| News | Headline, 2–3 sentence summary, source name, source URL, published time, related tickers | Market: general US market news. My stocks: news for followed instruments |
| Rating change | Ticker, old and new rating, one-sentence reason, time | My stocks, when the rating of a followed instrument changes |
| Spotlight | A short AI-written take on an instrument that is in the news. It carries no rating | Market, so readers meet instruments they do not follow |

- Ratings are for instruments the reader follows. A Buy/Hold/Sell rating appears on the My stocks tab only:
  on rating-change cards, and next to the ticker of a followed instrument. Nothing on the Market tab shows a
  rating, including the tickers on a news card and spotlights.
- Infinite scroll, newest first, pull to refresh.
- A news card opens the source article in the browser. A rating change or spotlight opens the stock page, and
  so does a ticker chip on any card.
- News items are produced by the LLM with web search and stored. Rules:
  - Every item must carry a source URL that the search tool actually returned; items without one are dropped.
  - Deduplicated by normalised URL.
  - Market news is generated on demand with a cache until the scheduled refresh exists (epic 5), then on its
    own schedule (default hourly, configurable). Stock news refreshes with the stock's analysis.
- How spotlights are chosen is defined in epic 3.

### 5.3 Stock page — Built, except where marked

- Header: ticker, name, latest price, day change (price provider; quote cached about 60 seconds).
- Price chart: ranges 1D, 1W, 1M, 1Y, 5Y (price provider; history cached).
- Analysis: summary, bull points, bear points, key risks, key figures with as-of dates, source links.
  For ETFs the analysis covers holdings focus, cost and trend instead of company fundamentals.
- Rating card: Buy / Hold / Sell, confidence (Low / Medium / High), rationale, sources, generated-at time.
  Planned (epic 2): the horizon the rating applies to.
- Rating history: list of past ratings with date and rationale, newest first.
- Refresh button: asks for a new generation; limited per user (default 5 in 24 hours) and ignored if the
  current analysis is younger than the minimum age (default 1 hour).
- A fixed notice on the rating card and analysis: AI-generated, may be wrong, not financial advice.
- Planned (epic 3): a news tab for that instrument.
- Open (section 15, question 11): today the page generates and shows an analysis and rating for any
  instrument, followed or not. Ratings are meant for followed instruments, so this may change.

### 5.4 Stock comparison — Planned (epic 6)

- Pick two instruments; show them side by side: price, day change, 1Y performance, rating, confidence,
  key figures, bull and bear points.
- Uses the stored analysis of each instrument, generating it on demand if missing. Whether instruments the
  reader does not follow can be compared depends on question 11 (section 15).
- Adds an AI-written verdict comparing the two. (Version 1 of this document had no extra LLM call here.)
  Proposed, to control cost: the verdict is stored and reused while both analyses are unchanged.

### 5.5 Bookmarks — Planned (epic 3)

- Bookmark / unbookmark any news item. A Bookmarks screen lists saved items, newest first.

### 5.6 Notifications — Planned (epic 5)

- An in-app notification list on all platforms, including web, when:
  - the rating of a followed instrument changes (e.g. Hold → Buy);
  - a news item for a followed instrument is flagged high-importance by the LLM.
- Per-user settings: rating changes on/off, important news on/off.
- Notifications are written to an outbox table and delivered once, even with both APIs running.
- Push to iOS and Android devices (Expo push) comes after version 1 (epic 7). Web push is out of scope.

### 5.7 Settings — Partly built

- Built: a Profile tab with name and email from the identity provider, the API in use, and sign out.
- Planned: notification toggles (epic 5), and in developer builds a switch between the Java and .NET API
  (epic 6). Today the API is chosen at build time with `EXPO_PUBLIC_API_BASE_URL`.

### 5.8 Admin console — Planned (epic 4)

A separate web service with its own login, for the operator. It is not part of the reader app.

First version:

- **See AI spend:** tokens and cost per day, per model, per kind of generation and per API implementation.
- **See generation jobs:** queued, running, done and failed, with the error; retry a failed job.
- **Change settings without a deployment:** daily generation cap, monthly budget, refresh limits, schedules,
  rating horizon, agent depth. Every change is recorded with who made it, when, and the old and new value.
- **See how ratings performed** once ratings start reaching the end of their horizon (section 6).

Settings the console can change are stored in the shared database and read by every API implementation;
environment variables give the defaults.

Later: a user list, and switching off a rating or feed card that should not be shown (needed before public
release).

## 6. AI generation

### Built (milestone 3)

- **LLM abstraction.** Both APIs define the same interface: given a prompt, a JSON schema and a web-search
  flag, return JSON, the list of sources the search tool returned, token usage and the model name. The only
  implementation is `mock` (deterministic; the default for local runs and tests).
- **Shared prompt and schema.** `app/api/shared/llm/` holds the analysis prompt, the answer schema and the
  mock's rules. Both APIs load them.
- **Checks before storing.** An answer is stored only if it matches the schema and cites at least one source
  that the search tool returned. A rating without sources is never stored.
- **On demand with cache.** An analysis is valid for a TTL (default 24 hours). Opening a stock page returns the
  stored analysis if it is valid; otherwise it queues a generation and returns `202` with a status the app
  polls. Generation never runs inside a request.
- **Job queue.** Jobs are rows in `generation_jobs`, claimed with `FOR UPDATE SKIP LOCKED`, so the two APIs
  can run together without duplicating work. Only one generation per instrument is queued or running at a
  time.
- **Failures.** Failed or schema-invalid output is retried once, then the job is marked failed and the last
  valid analysis stays visible.
- **Ratings.** A new rating row is appended on every generation; ratings are never updated in place.
- **Records.** Each analysis stores which API generated it, the model, the duration and the token usage.

### Planned

The AI is the only judge of a rating: no person reviews it. Instead the system runs three checks of its own,
marked ✓ below.

| Change | What it means | Epic |
|---|---|---|
| Real LLM vendor | One vendor behind the abstraction, chosen by the trial. It must offer web search with citations, schema-constrained JSON and tool use for agents, at an acceptable cost and with terms that allow this use | 1, 2 |
| Agent-based generation | The analysis is produced by an agent that plans its research and uses tools (web search, price data) instead of one call. How deep it goes is a setting. Agents are built in every API implementation, like the rest of the API (to be confirmed, section 15) | 2 |
| ✓ Source verification | A second pass checks each claim and key figure against the source it cites. Unsupported claims are removed, or the answer is rejected | 2 |
| ✓ Rating stability | The previous rating and rationale are given to the model. A change of rating must cite information newer than the previous rating; otherwise the previous rating stands. This also prevents false "rating changed" notifications | 2 |
| ✓ Performance tracking | When a rating reaches the end of its horizon, the instrument's price change is compared with the market's and recorded, so the operator can see how good the ratings are | 2, 4 |
| Rating horizon | A setting from 1 to 12 months (proposed default: 12). The prompt states it, each rating stores it, and the rating card shows it. Changing the setting affects new ratings only | 2 |
| Cost and caps | Every generation records its cost in money as well as tokens. A daily generation cap and a monthly budget bound the spend; when a cap is reached, stored data is served with its age shown | 2, 4 |
| Scheduled refresh | A scheduled job (cron, default `0 8 * * MON-FRI` America/New_York) regenerates every instrument that at least one user follows and writes the same cache. `SCHEDULER_ENABLED` per API controls which instance runs the schedule | 5 |
| More kinds of generation | Market news, stock news, spotlights and the comparison verdict use the same queue, checks and records | 3, 6 |

### What is generated

| Artifact | Scope | Trigger | Status |
|---|---|---|---|
| Analysis + rating | per instrument, shared by all users | on demand and user refresh; schedule later | Built, with the mock |
| Market news batch | global | on demand with cache; schedule later (default hourly) | Planned |
| Stock news batch | per instrument | with analysis | Planned |
| Spotlight | per instrument in the news | with market news | Planned |
| Comparison verdict | per pair of instruments | on demand | Planned |

## 7. Price data

- Interface: search/list instruments, latest quote, historical candles for a range.
- Built: `mock`, which generates invented prices from fixtures, identically in both APIs.
- Planned: a real provider. Finnhub is the first candidate; epic 1 checks whether its free tier includes price
  history and whether its terms allow showing prices to the public. The API key is configuration and is never
  sent to the app.
- Quotes are cached about 60 seconds, intraday charts 5 minutes, daily charts until the next New York day.
- If the provider is unavailable, the stock page still shows analysis and news, with the price marked
  unavailable.

## 8. Data model (shared PostgreSQL)

The migrations in `app/database/migrations` are authoritative for built tables.

| Table | Purpose | Status |
|---|---|---|
| `users` | Local user record, keyed by (issuer, subject) | Built |
| `instruments` | Stocks and ETFs, synced from the price provider | Built |
| `watchlist_items` | Follows | Built |
| `price_cache` | Quotes and chart data, shared by both APIs | Built |
| `analyses` | Generated analysis: content, sources, validity, which API and model produced it, duration, tokens | Built |
| `ratings` | Append-only ratings, one per analysis | Built |
| `generation_jobs` | Work queue for generation | Built |
| `news_items`, `news_item_instruments` | Stored news and its tickers | Planned (epic 3) |
| Insight cards | Rating changes and spotlights shown in the feed | Planned (epic 3) |
| `bookmarks` | Saved news | Planned (epic 3) |
| Settings and their change history | Values the admin console can change | Planned (epic 4) |
| `notification_settings`, `notifications` | Per-user toggles; outbox and in-app list | Planned (epic 5) |
| `device_tokens` | Push targets | After version 1 |

Planned changes to built tables (epic 2): `ratings` gains the horizon in months and, later, its measured
performance; generation records gain the cost in money and the prompt version.

## 9. API (v1)

Base path `/api/v1`. Full definitions live in `app/api/contract/openapi.yaml` (version 0.4.0).

| Method and path | Purpose | Status |
|---|---|---|
| `GET /api/health` | Liveness and database check (public) | Built |
| `GET /me` | Current user | Built |
| `GET /instruments?query=` | Search | Built |
| `GET /instruments/{symbol}` | Details and latest quote | Built |
| `GET /instruments/{symbol}/prices?range=` | Chart data | Built |
| `GET /instruments/{symbol}/analysis` | Stored analysis and rating, or `202` while generating | Built |
| `POST /instruments/{symbol}/analysis/refresh` | Request regeneration (rate-limited) | Built |
| `GET /instruments/{symbol}/ratings` | Rating history | Built |
| `GET /watchlist`, `PUT /watchlist/{symbol}`, `DELETE /watchlist/{symbol}` | Follow management | Built |
| `GET /instruments/{symbol}/news` | News for one instrument | Planned (epic 3) |
| `GET /news?tab=market\|mine` | Feed: news and insight cards | Planned (epic 3) |
| `GET /bookmarks`, `PUT /bookmarks/{newsId}`, `DELETE /bookmarks/{newsId}` | Bookmarks | Planned (epic 3) |
| `GET /notifications`, `POST /notifications/{id}/read` | In-app list | Planned (epic 5) |
| `GET /notification-settings`, `PUT /notification-settings` | Toggles | Planned (epic 5) |
| `GET /compare?symbols=A,B` | Side-by-side data and verdict | Planned (epic 6) |
| `POST /devices`, `DELETE /devices/{token}` | Push token registration | After version 1 |

The admin console's endpoints are not part of this contract yet (section 15).

## 10. UI

- Expo, TypeScript, Expo Router, with React Native Paper as the design baseline.
- Bottom tabs today: News (sample data), Watchlist, Profile. Planned: Compare, Bookmarks, and a notification
  list.
- Stock page reached from watchlist, search, or a ticker chip on a feed card.
- API client types generated from `openapi.yaml`.
- Tokens are stored in the Keychain on iOS, Keystore-encrypted storage on Android, and `localStorage` on web.
  Version 1 of this document said "in memory on web"; which one stands is open (section 15).
- Loading, empty, error and "generating analysis" states on every data screen.

## 11. Non-functional requirements

- Secrets (LLM key, price key) only in server-side configuration; `.env` files are git-ignored.
- Cached reads respond in under 500 ms locally; generation is asynchronous and never blocks a request.
- Built: structured JSON logs with a correlation ID (`X-Correlation-ID`) on every line. Generation logs include
  job ID, API name, duration and token usage. Tokens, emails and names are never logged.
- Built: OpenTelemetry traces, metrics and logs from both APIs, exported locally to an Elastic stack.
- Stock data is user-independent; user data (watchlist, bookmarks, notifications) is always filtered by the
  authenticated user id.
- Tests. Built: unit tests per API, and the shared contract test suite, which must pass against both APIs with
  mock providers. Planned (epic 0): UI component tests for the main screens, and CI that runs all of them.

## 12. Local deployment

`docker compose -f deployment/local/docker-compose.yml up --build` starts PostgreSQL, the migrate job,
Keycloak with the imported realm, both APIs (Java on 8080, .NET on 8081) and an Elastic stack for telemetry
(Kibana on 5601). Defaults use the mock LLM and mock price provider, so no API keys are needed to run. The
Expo app runs on the host with `npm run web`, `npm run ios` or `npm run android` (dev server on port 8090).

## 13. Delivery plan

### Built so far

| Milestone (v1 numbering) | Scope | Committed |
|---|---|---|
| 1 Foundation | Shared database and migrations, OpenAPI contract, Keycloak, JWT validation in both APIs, contract tests, Expo app with sign-in | 11 November |
| 2 Instruments and prices | Instrument sync, search, watchlist, stock page header and chart; layered API structure; logging and telemetry | 12 November |
| 3 AI analysis | LLM abstraction with mock, job queue, on-demand generation, rating and history | 12 November |

### Epics from here

Version 1 is epics 0 to 6, running locally with real providers. Epic 7 is the public release. Milestones 4 to
7 of version 1 of this document are folded into these epics.

| Epic | Scope | Replaces |
|---|---|---|
| 0 Setup | Backlog in GitHub Issues; CI on GitHub Actions running unit and contract tests against both APIs; UI test harness; documentation catch-up | — |
| 1 Provider trial | LLM vendor shortlist and hands-on trial on about ten tickers (quality, cost per analysis, terms of use); price provider free-tier check. Ends with a vendor, a monthly budget and the agent design | part of milestone 7 |
| 2 Real providers and agent-based analysis | Real LLM vendor and price provider behind the existing interfaces; agent-based generation; source verification, rating stability, performance tracking; rating horizon; cost in money and caps | parts of milestones 6 and 7 |
| 3 Feed | Market and stock news, insight cards, bookmarks; replaces the sample data | milestone 4 |
| 4 Admin console | Separate web service and login: AI spend, generation jobs, settings | new |
| 5 Scheduling and notifications | Scheduled refresh, rating-change detection, in-app notification list and settings | milestone 5 |
| 6 Comparison and polish | Compare screen with AI verdict, API switcher in developer builds, error states | milestone 6 |
| 7 Public release | Legal review, market-data and news licensing, privacy policy and terms, account deletion, open sign-up, store accounts, push to devices, Entra External ID, Azure | part of milestone 7 |

The admin console comes before scheduling so that spend is visible and capped before anything runs unattended.

### Backlog rules

- The backlog is GitHub Issues. Each epic is a GitHub milestone. Until the issues are created, the draft
  backlog is in [BACKLOG.md](BACKLOG.md).
- A story describes behaviour that can be observed: through the contract for an API story, on screen for a UI
  story. It never names a stack, framework or library.
- API work and UI work are separate issues. Each UI (the reader app, the admin console) has its own issues.
- Size labels S, M and L keep slices small. There are no estimates.
- Full stories are written for the next one or two epics only. Later epics hold a list of intended stories.

### Story types and definition of done

| Type | Used for | Done when |
|---|---|---|
| API | Behaviour behind the contract, including AI generation | The contract is updated first and its version raised. Contract tests cover every acceptance criterion and pass against every API implementation. Unit tests cover the rules in every implementation. Schema changes are a new migration file. Errors use the problem format and logs carry the correlation ID. Shared files (prompts, schemas, fixtures) are read by every implementation. The README and this document are updated. CI is green |
| UI | One screen or flow in one UI | It uses the API only through the client generated from the contract. Loading, empty and error states are handled. It works on every platform that UI targets (reader app: iOS, Android and web). Component tests cover the acceptance criteria. Typecheck and lint pass. It uses the Paper theme. No sample data remains on the screen. CI is green |
| Research | A question to answer before building | There is a written finding with its sources and the date they were checked, and a recommendation. The decision is recorded in section 2. Follow-up stories are created or updated. Trial code stays out of the product |
| Chore | CI, tooling, documentation | It works from a clean clone by following the README. Documentation is updated. CI is green |

An AI story is an API story with two more conditions: the mock and its shared examples are updated so every
implementation still returns the same answer, and the change is run on the trial's ticker set with the real
vendor, with quality and cost per run recorded.

## 14. Legal and licensing

The app is for private use now and is meant to be public later. Nothing in this section is legal advice; a
lawyer reviews the app before public release (epic 7). These limits apply from now, because they are cheaper
to build in than to add later:

- **Ratings are impersonal.** Every reader sees the same analysis and rating of an instrument. Nothing is
  tailored to a reader's holdings, goals or circumstances.
- **The reader is told what they are looking at.** Every analysis and rating carries the notice "AI-generated.
  It may be wrong. This is not financial advice.", its sources, its generation time and (planned) its horizon.
- **Every rating can be traced.** The model and sources behind a rating are stored today; the prompt version
  and the result of each check are planned (epic 2).
- **Vendors are chosen with public use in mind.** The provider trial (epic 1) checks, for each candidate:
  - LLM vendor: whether its usage terms allow AI-written financial commentary for consumers without review by
    a person, and what disclosure they require;
  - price provider: whether its licence allows showing prices and charts to the public, and at what price.

Before public release (epic 7): legal review of the rating wording and of the countries the app is offered in;
market-data licence; what may be shown of a news article (headline, short summary, link); privacy policy and
terms of use; account deletion; app store rules for finance apps.

## 15. Open questions

| # | Question | Answered by |
|---|---|---|
| 1 | Which LLM vendor? It must offer web search with citations, schema-constrained JSON and tool use, at an acceptable cost and with terms that allow this use | Epic 1 |
| 2 | Does Finnhub's free tier include price history, and do its terms allow public display? If not, which provider? | Epic 1 |
| 3 | What is the monthly budget for the LLM and market data? | Epic 1 |
| 4 | Agent design: how deep, which tools, and how it is built in each stack. Are agents implemented in both API implementations? Assumed yes, since the purpose of two stacks is to compare implementations | Epic 1 |
| 5 | Admin console: which stack, built once or in both, how the operator signs in, and whether it calls the APIs or reads the database | Owner, before epic 4 |
| 6 | Rating horizon: one setting for the whole service, changed by the operator (assumed), or chosen by each reader? Every horizon offered multiplies the number of generations | Owner |
| 7 | How are spotlights chosen, and can insight cards be bookmarked? | Epic 3 |
| 8 | How often can market news be generated within the budget? | Epic 1, then epic 3 |
| 9 | Sign-in tokens on web: `localStorage` (built) or memory only (version 1 of this document)? | Owner |
| 10 | Legal review before any public release (section 14) | Epic 7 |
| 11 | Ratings are for instruments the reader follows. For an instrument they do not follow, should the stock page (and the comparison screen) still show an analysis and rating, as built today, or only price, chart and news with a prompt to follow? The second avoids a generation for every ticker tapped in the news | Owner |
