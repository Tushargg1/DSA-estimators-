# Deployment Guide — DSA Progress Tracker

This covers deploying the three pieces: **Postgres** (managed), the **backend**
(Docker), and the **frontend** (Docker/nginx or Vercel/Netlify), plus the
**environment variables** that wire them together.

---

## 1. Database — managed Postgres (task 12.3)

**Decision: use a managed free/low-cost Postgres tier — Neon, Supabase, or
Railway — NOT self-managed AWS RDS.**

Rationale (per the tasks.md note): this is a small side project, and there is an
existing AWS billing dispute. Standing up RDS drags in a VPC, a NAT Gateway,
and/or an Elastic IP, each of which bills hourly whether or not anything is
using them — exactly the kind of surprise cost to avoid here. A managed serverless
Postgres tier gives a connection string, scales to near-zero, and has a free
tier suitable for this workload.

| Option   | Free tier | Notes                                              |
|----------|-----------|----------------------------------------------------|
| **Neon** | Yes       | Serverless Postgres, scales to zero, branch DBs.   |
| Supabase | Yes       | Postgres + extras (auth/storage) we don't need.    |
| Railway  | Trial/low | Simple provisioning, usage-based pricing.          |

Neon is the recommended default (serverless, generous free tier, plain Postgres).

### Pointing the backend at the managed DB

The backend reads its datasource entirely from env vars (see `application.yml`),
so no code or image change is needed — just set:

```bash
DB_URL=jdbc:postgresql://<host>:5432/<database>?sslmode=require
DB_USERNAME=<user>
DB_PASSWORD=<password>
```

Notes:
- Managed providers require TLS — keep `?sslmode=require` (or `verify-full`) on
  the JDBC URL. Copy the exact host/db/params from the provider's dashboard.
- **Flyway migrations run automatically on startup.** The app owns the schema
  via Flyway (`spring.jpa.hibernate.ddl-auto=validate`); on first boot against
  an empty managed database, Flyway applies `V1__init_schema.sql` (and any later
  `V*` migrations) before the app serves traffic. No manual schema step needed.
- Provision a fresh empty database and let the app migrate it — do not hand-run
  the SQL.

---

## 2. Backend — Docker (task 12.1)

Multi-stage build in `backend/Dockerfile`:
- **Build stage:** `eclipse-temurin:21-jdk` + the Maven wrapper. Dependencies
  are cached (`dependency:go-offline`) before source is copied.
- **Runtime stage:** `eclipse-temurin:21-jre-alpine`, runs as a non-root `app`
  user, exposes `8080`.

**Java version:** the image builds and runs on **Java 21** (spec intent). The
pom targets `--release 17` (only a JDK 17 is installed on the dev host); Java 17
bytecode compiles cleanly under JDK 21 and runs unmodified on the Java 21
runtime. Bump `<java.version>` to 21 in `pom.xml` once JDK 21 is the standard
build environment — no Dockerfile change required.

```bash
# from repo root
docker build -t dsa-tracker-backend ./backend

docker run -p 8080:8080 \
  -e DB_URL="jdbc:postgresql://<host>:5432/<db>?sslmode=require" \
  -e DB_USERNAME="<user>" \
  -e DB_PASSWORD="<password>" \
  -e CORS_ALLOWED_ORIGINS="https://your-frontend.example.com" \
  -e WS_ALLOWED_ORIGINS="https://your-frontend.example.com" \
  dsa-tracker-backend
```

---

## 3. Frontend — Docker/nginx or Vercel/Netlify (task 12.2)

Multi-stage build in `frontend/Dockerfile`: Node builds the Vite bundle, nginx
serves the static `dist/`. `nginx.conf` provides the SPA fallback
(`try_files ... /index.html`) and an optional same-origin `/api` + `/ws` proxy.

Vite inlines `VITE_*` vars at **build time**, so the API/WS endpoints are passed
as build args:

```bash
docker build \
  --build-arg VITE_API_BASE_URL="https://api.example.com/api" \
  --build-arg VITE_WS_URL="https://api.example.com/ws" \
  -t dsa-tracker-frontend ./frontend

docker run -p 8080:80 dsa-tracker-frontend
```

**Alternative (per task 12.2):** deploy the frontend to **Vercel/Netlify** —
build command `npm run build`, publish directory `dist/`, and set the
`VITE_API_BASE_URL` / `VITE_WS_URL` environment variables in the provider's
build settings. The nginx image is the self-hosted equivalent and the primary
deliverable.

---

## 4. Environment variables (task 12.4)

### Backend

| Variable               | Default (local)              | Purpose                                              |
|------------------------|------------------------------|------------------------------------------------------|
| `DB_URL`               | `jdbc:postgresql://localhost:5432/dsatracker` | JDBC URL (use `?sslmode=require` for managed DBs).   |
| `DB_USERNAME`          | `dsatracker`                 | DB user.                                             |
| `DB_PASSWORD`          | `dsatracker`                 | DB password.                                         |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173`      | Comma-separated origins allowed to call `/api/**`.  |
| `WS_ALLOWED_ORIGINS`   | `http://localhost:5173`      | Comma-separated origins allowed for the `/ws` handshake. |

- CORS is applied to `/api/**` (`CorsConfig`) with credentials enabled; the
  WebSocket handshake origins are set on the `/ws` endpoint (`WebSocketConfig`).
- **Security:** the `localhost:5173` defaults are for local development only.
  Production MUST set explicit origin(s) for both `CORS_ALLOWED_ORIGINS` and
  `WS_ALLOWED_ORIGINS`. Because CORS credentials are allowed, a wildcard is not
  used — set the real frontend origin(s), comma-separated for multiple.

Example (production):
```bash
CORS_ALLOWED_ORIGINS=https://tracker.example.com,https://www.tracker.example.com
WS_ALLOWED_ORIGINS=https://tracker.example.com,https://www.tracker.example.com
```

See `backend/.env.example` for a copy-paste template.

### Frontend (build-time)

| Variable             | Default (local)              | Purpose                          |
|----------------------|------------------------------|----------------------------------|
| `VITE_API_BASE_URL`  | `http://localhost:8080/api`  | Backend REST base URL.           |
| `VITE_WS_URL`        | `http://localhost:8080/ws`   | STOMP/SockJS WebSocket endpoint. |

See `frontend/.env.example`. These are baked into the bundle at build time — set
them as Docker build args or in the Vercel/Netlify build environment.
