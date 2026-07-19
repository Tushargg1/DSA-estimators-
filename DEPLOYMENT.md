# Deployment Guide — DSA Progress Tracker

The deployment consists of MySQL 8, a Spring Boot backend, and a Vite frontend. Never place backend credentials or JWT secrets in `VITE_*` variables.

## Render backend service

Configure the backend as a **Docker** Web Service using the repository root:

- Branch: `dsa-tracker-implementation`
- Root Directory: leave blank
- Dockerfile Path: `./Dockerfile`

The root Dockerfile expects the complete repository as its build context, including the `backend/` directory.

Set Render's **Health Check Path** to `/api/health`. The endpoint is public and returns `{"status":"ok"}` after the application starts; `/` is not a health endpoint.

After changing the branch, build context, or Dockerfile setting, use **Manual Deploy → Clear build cache & deploy**. Confirm the deploy log checks out the intended commit from `dsa-tracker-implementation`.

Verify a successful deployment from PowerShell:

```powershell
Invoke-RestMethod https://<backend-host>/api/health
```

The response must contain `status: ok` before configuring the frontend to use the backend.

## Database and migrations

Point the backend at MySQL 8 using placeholders like:

```text
DB_URL=jdbc:mysql://<host>:<port>/<database>?sslMode=REQUIRED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
DB_USERNAME=<username>
DB_PASSWORD=<password>
DB_POOL_MAX=5
DB_POOL_MIN_IDLE=1
```

Do not use `localhost` in `DB_URL` on Render: it refers to the backend container, not the managed database. Use the external MySQL hostname and port supplied by the database provider.

Flyway owns the schema. `V1__init_schema.sql` remains the immutable baseline. On upgrade, `V2__add_password_credentials.sql` adds nullable `password_hash` and `credentials_enabled=false`. Therefore every pre-existing account remains locked and cannot log in until explicitly activated; new registrations save a BCrypt hash and enable credentials atomically.

## Backend environment

Set these values in the backend host before deploying:

```text
AUTH_JWT_SECRET=<random-secret-containing-at-least-32-bytes>
AUTH_JWT_ISSUER=dsa-tracker
AUTH_JWT_AUDIENCE=dsa-tracker-web
AUTH_JWT_EXPIRY=PT12H
GOOGLE_CLIENT_ID=<public-web-client-id>.apps.googleusercontent.com
CORS_ALLOWED_ORIGINS=https://<frontend-host>
WEBSOCKET_ALLOWED_ORIGINS=https://<frontend-host>
```

`AUTH_JWT_SECRET` signs stateless tokens and must be random, private, and at least 32 bytes. The source fallback is deliberately local/test-only. Changing the secret logs out all users. Expiry defaults to 12 hours and is capped at 24 hours by startup validation.

The browser stores the JWT in `sessionStorage`, sends it as an HTTP Bearer token, and supplies it in the STOMP CONNECT headers. This avoids unreliable cross-site third-party cookies between frontend and backend hosts. It survives refresh in the same tab but is cleared when the tab closes; like any JavaScript-accessible storage, it makes strong CSP and XSS prevention important. Tokens are never placed in URLs or application logs.

## Optional activation of existing accounts

Activation is operator-assisted and disabled by default. Temporarily set:

```text
LEGACY_ACCOUNT_SETUP_SECRET=<temporary-random-setup-code>
```

Give the code only to the intended users through a separate trusted channel. They use **Activate existing account** with their existing email and a new password. The endpoint compares the setup code in constant time, only enables rows still marked `credentials_enabled=false`, and returns the same failure for missing/already-enabled accounts. After the migration window, remove `LEGACY_ACCOUNT_SETUP_SECRET` from the backend environment and redeploy; the endpoint then returns not found. Never hardcode or commit the setup code.

## Frontend / Vercel

Vercel builds require both build-time variables:

```text
VITE_API_BASE_URL=https://<backend-host>/api
VITE_WS_URL=https://<backend-host>/ws
VITE_GOOGLE_CLIENT_ID=<public-web-client-id>.apps.googleusercontent.com
```

SockJS requires `https://.../ws`, **not** `wss://.../ws`. The build fails with a clear error when either Vercel URL variable is absent, malformed, non-HTTPS, or has the wrong path. Local development still defaults to `http://localhost:8080/api` and `http://localhost:8080/ws`.

## Google sign-in

In Google Cloud Console, create an OAuth 2.0 client with application type **Web application**. Add `http://localhost:5173` and the exact production Vercel origin under **Authorized JavaScript origins**. Set its public client ID as backend `GOOGLE_CLIENT_ID` and frontend `VITE_GOOGLE_CLIENT_ID`; the two values must match.

This Google Identity Services credential flow needs no client secret or redirect URI. The browser sends the ID credential over HTTPS to `POST /api/auth/google`; the backend verifies its signature, RS256 algorithm, issuer, audience, expiry, subject, and verified email before issuing the application JWT. Never configure or expose a Google client secret for this flow.

The root `package.json` delegates to the frontend and pins Node 20.x. `vercel.json` remains root-compatible, serves `index.html` without caching, and gives hashed `/assets/*` one-year immutable caching.

## Required sequence

1. Configure MySQL, CORS/WebSocket origins, and a fresh `AUTH_JWT_SECRET` on the backend host.
2. Deploy the backend so V2 runs before exposing the new frontend.
3. Configure both HTTPS `VITE_*` URLs in Vercel and build the frontend.
4. If old accounts need access, temporarily configure the setup secret, coordinate activation, then remove it and redeploy.
5. Verify login, `/api/auth/me`, group restoration, and a live group subscription from the deployed frontend.

No production service or database access is needed during builds or tests.
