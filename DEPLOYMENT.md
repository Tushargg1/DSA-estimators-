# Deployment Guide — DSA Progress Tracker

The deployment consists of **Aiven MySQL 8**, the Spring Boot backend (Render or
another container host), and the React frontend.

## 1. Database — Aiven MySQL 8

Provision a fresh, empty MySQL 8 database. Do not import the former PostgreSQL
schema: Flyway applies the MySQL-native `V1__init_schema.sql` on first startup.
Set these backend secrets in the hosting platform:

```text
DB_URL=jdbc:mysql://<host>:<port>/<database>?sslMode=REQUIRED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
DB_USERNAME=<username>
DB_PASSWORD=<password>
DB_POOL_MAX=5
DB_POOL_MIN_IDLE=1
```

`sslMode=REQUIRED` encrypts the connection and is the baseline Aiven setting.
For stronger production identity verification, install Aiven's CA certificate in
the runtime trust configuration and use Connector/J's CA/hostname-verifying SSL
mode. Test that trust-chain change in staging before enforcing it.

Never put credentials in source, Docker images, build arguments, or logs. Keep
the URL, username, and password in Render's secret environment settings. The
small, tunable Hikari pool defaults reduce pressure on free-tier connection
limits.

Flyway runs automatically before Hibernate validation. The first boot must point
to an empty database and needs schema-creation privileges. No live Aiven access
is required during image build or unit tests.

## 2. Backend — Docker / Render

Both `Dockerfile` (Render repository-root context) and `backend/Dockerfile`
produce a Java 21 runtime image. The project currently compiles Java 17 bytecode,
which runs on Java 21.

```bash
docker build -t dsa-tracker-backend .
docker run -p 8080:8080 \
  -e DB_URL="jdbc:mysql://<host>:<port>/<database>?sslMode=REQUIRED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true" \
  -e DB_USERNAME="<username>" \
  -e DB_PASSWORD="<password>" \
  -e CORS_ALLOWED_ORIGINS="https://your-frontend.example.com" \
  -e WEBSOCKET_ALLOWED_ORIGINS="https://your-frontend.example.com" \
  dsa-tracker-backend
```

Render injects `PORT`; `application.yml` uses it first via
`${PORT:${SERVER_PORT:8080}}`. Do not manually hard-code a Render port.

## 3. Frontend

Vite variables are build-time values:

```text
VITE_API_BASE_URL=https://api.example.com/api
VITE_WS_URL=https://api.example.com/ws
```

Build with `npm run build` and publish `frontend/dist`, or use the frontend
Docker/nginx image. Configure backend CORS and WebSocket origins to the exact
frontend origins; do not use `*` while credentials are enabled.

## 4. Environment reference

| Variable | Local default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/dsatracker?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` | MySQL JDBC URL; use `sslMode=REQUIRED` on Aiven. |
| `DB_USERNAME` | `dsatracker` | Database user. |
| `DB_PASSWORD` | `dsatracker` | Database password. |
| `DB_POOL_MAX` | `5` | Maximum Hikari connections per backend instance. |
| `DB_POOL_MIN_IDLE` | `1` | Minimum idle Hikari connections. |
| `PORT` | unset | Render-injected HTTP port; takes precedence. |
| `SERVER_PORT` | `8080` | Portable HTTP-port fallback. |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Comma-separated REST origins. |
| `WEBSOCKET_ALLOWED_ORIGINS` | `http://localhost:5173` | Comma-separated WebSocket origins. |

See `backend/.env.example` and `docs/deployment/mysql.md` for placeholder-only
templates and Aiven TLS guidance.
