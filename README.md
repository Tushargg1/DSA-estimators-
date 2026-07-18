# DSA Progress Tracker

A full-stack dashboard for tracking DSA practice across LeetCode, Codeforces, and GeeksforGeeks. It combines a React/Vite frontend, a Spring Boot API, and MySQL 8, with group leaderboards and live updates.

## Architecture

- `frontend/` — React and Vite web application
- `backend/` — Spring Boot REST and WebSocket API
- `docker-compose.yml` — local MySQL 8 service

## Default ports

| Service | URL or port |
| --- | --- |
| Frontend | `http://localhost:5173` |
| Backend API | `http://localhost:8080/api` |
| MySQL | `localhost:3306` |

## Prerequisites

- Node.js 20
- JDK 17
- Docker with Docker Compose

Verify the installed toolchain:

```powershell
node --version
java -version
docker compose version
```

## Local development

1. Start MySQL from the repository root:

   ```powershell
   docker compose up -d
   ```

2. Start the backend in a separate terminal:

   ```powershell
   .\backend\mvnw.cmd -f backend\pom.xml spring-boot:run
   ```

3. Install frontend dependencies, then start Vite:

   ```powershell
   npm --prefix frontend ci
   npm run dev
   ```

4. Open `http://localhost:5173`.

5. Verify the backend is healthy:

   ```powershell
   Invoke-RestMethod http://localhost:8080/api/health
   ```

The checked-in local defaults connect the API to MySQL on port `3306`. To enable Google sign-in, copy `frontend/.env.example` to `frontend/.env.local`, set `VITE_GOOGLE_CLIENT_ID`, and provide the same value as `GOOGLE_CLIENT_ID` when starting the backend. See [`backend/.env.example`](backend/.env.example) for all backend settings.

If MySQL does not become ready, inspect its status and logs:

```powershell
docker compose ps
docker compose logs mysql
```

## Build

```powershell
npm run build
.\backend\mvnw.cmd -f backend\pom.xml test
```

Preview the built frontend locally at `http://localhost:4173`:

```powershell
npm run preview
```

## Stop local services

Stop MySQL while retaining its Docker volume:

```powershell
docker compose down
```

See [`DEPLOYMENT.md`](DEPLOYMENT.md) for production configuration, security requirements, and deployment order.
