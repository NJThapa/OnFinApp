# Login flow (OIDC authorization code + PKCE)

How a user signs in to OnFinApp and makes the first authenticated API call. The same flow runs on iOS, Android
and web, against either API. Rendered copies: [login-flow.png](login-flow.png), [login-flow.svg](login-flow.svg).

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant App as Expo app<br/>(iOS / Android / web)
    participant Browser as System browser<br/>(popup on web)
    participant KC as Keycloak<br/>(identity provider)
    participant API as Java or .NET API
    participant DB as PostgreSQL

    Note over App,KC: Preparation, before the user taps anything
    App->>KC: GET /.well-known/openid-configuration
    KC-->>App: Endpoints (authorization, token, end session)
    App->>App: PKCE: create random code_verifier,<br/>code_challenge = BASE64URL(SHA-256(code_verifier)),<br/>and a random state

    Note over User,KC: Authorization request
    User->>App: Tap "Sign in"
    App->>Browser: Open authorization URL
    Browser->>KC: GET /auth?response_type=code&client_id=onfinapp-app<br/>&redirect_uri&scope=openid profile email&state<br/>&code_challenge&code_challenge_method=S256
    KC->>KC: Remember code_challenge for this login
    KC-->>Browser: Login page
    User->>Browser: Enter username and password
    Browser->>KC: Submit credentials (the app never sees them)
    KC-->>Browser: Redirect to redirect_uri?code&state
    Browser-->>App: Return code and state
    App->>App: Check state matches

    Note over App,KC: Token exchange
    App->>KC: POST /token grant_type=authorization_code<br/>code, redirect_uri, client_id, code_verifier
    KC->>KC: PKCE check: SHA-256(code_verifier)<br/>must equal the stored code_challenge
    KC-->>App: access token, refresh token, ID token
    App->>App: Save session: Keychain (iOS),<br/>Keystore (Android), localStorage (web)

    Note over App,DB: First authenticated call
    App->>API: GET /api/v1/me<br/>Authorization: Bearer access token
    API->>KC: GET signing keys (JWKS), cached
    KC-->>API: Public keys
    API->>API: Validate signature, issuer,<br/>audience (onfinapp-api), expiry
    API->>DB: Upsert users row by (issuer, subject)
    DB-->>API: id, email, name
    API-->>App: 200 User
    App-->>User: Profile screen
```

## Why PKCE

The app is a public client: it cannot keep a client secret. PKCE replaces the secret with a one-time proof.
The app sends only the hash (`code_challenge`) in step 6 and reveals the original (`code_verifier`) in step 14,
over a direct connection to Keycloak. Anyone who intercepts the authorization code in steps 11-12 cannot
exchange it, because they do not have the verifier.

## Where this lives in the code

- App: `app/ui/react-native/src/auth/AuthContext.tsx` (steps 1-17), `src/api/client.ts` (step 18)
- Java API: `app/api/java/src/main/java/com/example/api/auth/SecurityConfig.java`, `user/UserController.java`
- .NET API: `app/api/dotnet/Program.cs`
- Keycloak realm: `deployment/local/keycloak/onfinapp-realm.json`

The diagram source is the Mermaid block above. After editing it, re-render the PNG and SVG.
