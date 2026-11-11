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
    java/                   Spring Boot API (Java 21, Maven)
    dotnet/                 ASP.NET Core API (.NET 10)
  database/migrations/      plain SQL, versioned; neither API owns the schema
  ui/react-native/          Expo app (iOS, Android, web)
deployment/
  local/                    Docker Compose for local runs
  azure/                    Azure pipelines and infrastructure (later)
```

## Run locally

```bash
docker compose -f deployment/local/docker-compose.yml up --build
```

| Service    | URL                                                          |
|------------|--------------------------------------------------------------|
| Java API   | http://localhost:8080/api/health                             |
| .NET API   | http://localhost:8081/api/health                             |
| Keycloak   | http://localhost:8082 (admin console: `admin` / `admin`)     |
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

## Contract tests

With the stack running (needs Node 20+, no dependencies to install):

```bash
cd app/api/contract-tests
npm run test:java     # against http://localhost:8080
npm run test:dotnet   # against http://localhost:8081
```

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
