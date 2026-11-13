# OnFinApp

A cross-platform finance app: one React Native UI and two interchangeable API implementations
(Java Spring Boot and ASP.NET Core) against one shared contract and one shared PostgreSQL database.
See [SPEC.md](SPEC.md) for the full specification.

## Layout

```
app/
  api/
    contract/openapi.yaml   source of truth for both APIs
    contract-tests/         one test suite, run against either API
    shared/                 files both APIs read: mock price fixtures, LLM prompts and schemas
    java/                   Spring Boot API (Java 21, Maven)
    dotnet/                 ASP.NET Core API (.NET 10)
  database/migrations/      plain SQL, versioned; neither API owns the schema
  ui/react-native/          Expo app (iOS, Android, web)
deployment/
  local/                    Docker Compose for local runs
  azure/                    Azure pipelines and infrastructure (later)
```

## Backlog

Work is tracked in [GitHub Issues](https://github.com/NJThapa/OnFinApp/issues), with one milestone per epic; the
plan is in [SPEC.md](SPEC.md), section 13. API and UI stories are separate issues, each with its own definition
of done, and a story never names a stack. New stories start from the issue templates. Stories intended for
later epics are listed in [BACKLOG.md](BACKLOG.md).

## Run locally

```bash
docker compose -f deployment/local/docker-compose.yml up --build
```

| Service    | URL                                                          |
|------------|--------------------------------------------------------------|
| Java API   | http://localhost:8080/api/health                             |
| .NET API   | http://localhost:8081/api/health                             |
| Keycloak   | http://localhost:8082 (admin console: `admin` / `admin`)     |
| Kibana     | http://localhost:5601 (traces, logs and metrics; no sign-in) |
| PostgreSQL | localhost:5432, database `onfinapp` (user/password `app`/`app`) |

Both APIs use the same `onfinapp` database. A one-shot `migrate` container (Flyway) applies
`app/database/migrations` before the APIs start; to change the schema, add a new `V<n>__name.sql` file.

## Sign-in

The APIs are OIDC resource servers: they validate bearer JWTs and issue no tokens. Each API is configured with
`OIDC_ISSUER` and `OIDC_AUDIENCE`, plus an optional `OIDC_JWKS_URI` for when the issuer URL is not reachable
from the API (as inside docker compose).

Locally, Keycloak imports the `onfinapp` realm from `deployment/local/keycloak/onfinapp-realm.json`:

- client `onfinapp-app`: public, authorization code flow with PKCE, for the Expo app;
- client `onfinapp-tests`: password grant, for the contract tests only;
- users: `testuser` / `testpass` (used by the contract tests) and `demo` / `demopass` (for signing in to the app).

Get a token and call an API by hand:

```bash
TOKEN=$(curl -s http://localhost:8082/realms/onfinapp/protocol/openid-connect/token \
  -d grant_type=password -d client_id=onfinapp-tests \
  -d username=testuser -d password=testpass -d scope=openid | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/me
```

## Instruments and prices

Each API copies the price provider's instrument list into the `instruments` table at startup and then once a
day. Quotes and chart history go through the shared `price_cache` table, so the two APIs reuse each other's
fetches: quotes for 60 seconds, intraday charts (1D, 1W) for 5 minutes, daily charts until the next New York day.

The only price provider so far is `mock` (`PRICE_PROVIDER=mock`, the default). **Its prices are invented**: they
are generated from `app/api/shared/mock/instruments.json` by the formulas in `app/api/shared/mock/README.md`,
identically in both APIs, and are not market data. A real provider comes with epic 2, after the provider trial
in epic 1 (see the delivery plan in [SPEC.md](SPEC.md), section 13).

A user can follow up to 50 instruments (`WATCHLIST_LIMIT`).

## AI analysis and ratings

Each instrument has one analysis, shared by all users: a summary, bull and bear points, risks, key figures,
sources, and a rating (Buy, Hold or Sell) with a confidence and a rationale. Every generation also adds a row
to the rating history.

- **On demand.** `GET /api/v1/instruments/{symbol}/analysis` returns the stored analysis while it is valid
  (24 hours, `ANALYSIS_TTL_HOURS`). Otherwise it queues a generation and answers `202` with status
  `GENERATING`; the app asks again every two seconds. Generation never runs inside a request.
- **Job queue.** Jobs are rows in `generation_jobs`. Each API runs a worker that claims a job with
  `FOR UPDATE SKIP LOCKED`, so with both APIs running a job still runs once, and only one generation per
  instrument is queued or running at a time. Set `JAVA_ANALYSIS_WORKER_ENABLED=false` or
  `DOTNET_ANALYSIS_WORKER_ENABLED=false` to make one API queue jobs without running them.
- **Failures.** A failed or invalid answer is retried once. After that the job is marked failed, the last valid
  analysis stays visible with status `FAILED`, and no new attempt starts on demand for 5 minutes
  (`ANALYSIS_RETRY_AFTER_MINUTES`). An API that shuts down in the middle of a job hands it back to the queue.
- **Refresh.** `POST .../analysis/refresh` queues a new generation. It is ignored while the analysis is
  younger than an hour (`ANALYSIS_REFRESH_MIN_AGE_MINUTES`), and each user can queue 5 refreshes in any
  24 hours (`ANALYSIS_REFRESH_LIMIT`); beyond that the answer is `429`.
- **Checks.** The prompt and the JSON schema of the answer are in `app/api/shared/llm/` and are loaded by both
  APIs. An answer is stored only if it passes the checks listed in `app/api/shared/llm/README.md`; in
  particular, a rating must cite at least one source that the search tool returned.
- **Records.** Each analysis stores which API generated it, the model, the duration and the token usage. The
  worker's log lines carry the job ID and the correlation ID of the request that queued the job.

The only LLM so far is `mock` (`LLM_PROVIDER=mock`, the default). **Its analyses and ratings are invented**:
they are built from fixed rules, say so in their text, and cite `example.com` addresses that do not exist. The
mock rating changes from day to day so the history shows changes. `LLM_MOCK_DELAY_MS` (default 1500) sets how
long it takes to answer and `LLM_MOCK_FAIL_SYMBOLS` lists tickers for which it fails. A real vendor comes with
epic 2, after the provider trial in epic 1.

Not built yet: the scheduled refresh (epic 5) and the daily cap on generations (epic 2).

## Errors, logging and telemetry

**Errors.** Each API has one global exception handler, and the response depends on the type of exception:
an error the code raises on purpose (unknown ticker, watchlist limit, bad cursor) keeps its own status and
message; an invalid request is a 400; the database being unreachable or timing out is a 503; anything else is a
500 with a generic message, so internals never reach the client. Every error body is `application/problem+json`
with `type`, `title`, `status`, `detail` and `instance`, identical in both APIs.

**Correlation ID.** Send `X-Correlation-ID` with a request (letters, digits, `.`, `_`, `-`; at most 64
characters) and the API returns it in the same response header and writes it on every log line for that
request. Without one, or with one that does not fit, the API generates a UUID. The app sends a new ID with every
call and keeps the one from a failed call on the error (`ApiError.correlationId`).

**Logs.** Both APIs write one JSON object per line to the console: a line per request (method, path, status,
duration), a line for each thing that changes (user created, followed, unfollowed, instrument sync), why a
token was rejected, and failures with their stack trace. Tokens, emails and names are never logged. Set
`LOG_LEVEL=DEBUG` before `docker compose up` to also see cache misses, searches and health checks.

**Token validation.** A request is accepted only with a bearer JWT that is signed by one of the identity
provider's keys, issued by `OIDC_ISSUER`, meant for `OIDC_AUDIENCE`, and not expired (one minute of clock
difference is allowed). Anything else is a 401.

**OpenTelemetry.** Both APIs export traces (each request with its database statements), metrics and logs over
OTLP to the `apm-server` container, which stores them in Elasticsearch; Kibana shows them. This is the Elastic
Stack on its free Basic license, set up for local use only: security is off, and the data lives in the
`elasticsearch` container and is lost when that container is removed.

Open http://localhost:5601 and go to Observability, then Applications for the two services
(`onfinapp-api-java`, `onfinapp-api-dotnet`), their requests and traces, or Logs for the log lines. To find one
request, search for its correlation ID: it is stored as `labels.app_correlation_id` on the trace and as
`labels.correlationId` (Java) or `labels.CorrelationId` (.NET) on its logs.

The Java API is instrumented by the OpenTelemetry Java agent in its image, the .NET API by the OpenTelemetry
SDK. Any OTLP backend can replace Elastic by changing `OTEL_EXPORTER_OTLP_ENDPOINT` in the compose file. Outside
compose nothing is exported unless that variable is set (and, for the Java image, `OTEL_SDK_DISABLED=false`).

## API code layout

Both APIs are organised the same way, by feature (users, instruments, prices, watchlist, llm, analysis), each with three layers:
an HTTP layer (controllers in Java, endpoint classes in .NET) that only reads the request and picks the status
code, a service with the rules, and a repository with the SQL. Errors are thrown as exceptions and turned into
RFC 9457 problem responses in one place per API.

- Java: `app/api/java/src/main/java/com/onfinapp/api/<feature>/`
- .NET: `app/api/dotnet/src/Api/<Feature>/`

## Unit tests

```bash
# .NET (needs the .NET 10 SDK)
dotnet test app/api/dotnet/OnFinApp.Api.slnx

# Java (no local JDK needed; runs Maven in a container and keeps its downloads in a volume)
docker run --rm -v "$PWD/app/api":/work -v onfinapp-m2:/root/.m2 -w /work/java \
  maven:3.9-eclipse-temurin-21 mvn -q -B test
```

The mock price provider's tests assert the same numbers in both suites, computed from
`app/api/shared/mock/README.md`. The mock LLM's tests in both suites compare against the same files in
`app/api/shared/llm/mock-examples/`, which is why the Java command mounts `app/api` rather than `app/api/java`.

## Contract tests

With the stack running (needs Node 20+, no dependencies to install):

```bash
cd app/api/contract-tests
npm run test:java     # against http://localhost:8080
npm run test:dotnet   # against http://localhost:8081
```

Analyses are kept for 24 hours and shared by both APIs, so the analysis tests see `202` and a generation only
on the first run of the day; later runs find the analyses already stored.

The token-validation tests ask Keycloak for a token from its built-in `admin-cli` client, which carries no
`onfinapp-api` audience, to check that both APIs refuse it.

## App (Expo)

With the stack running:

```bash
cd app/ui/react-native
npm install
npm run web       # browser, http://localhost:8090
npm run ios       # iOS simulator (needs Xcode)
npm run android   # Android emulator (needs the Android SDK and a running emulator)
```

Sign in as `demo` / `demopass`. The dev server uses port 8090 because the .NET API holds Expo's default, 8081.

The app talks to the Java API by default. To point it at the .NET API, copy `.env.example` to `.env.local` and
set `EXPO_PUBLIC_API_BASE_URL=http://localhost:8081`.

Everything is addressed as `localhost`. The iOS simulator shares the Mac's network, so that works as is. Inside the
Android emulator `localhost` is the emulator itself, so `npm run android` first forwards ports 8080-8082 to the
Mac with `adb reverse`. Physical phones are not set up yet.

Tokens are kept in the Keychain on iOS, Keystore-encrypted storage on Android, and `localStorage` on web.
