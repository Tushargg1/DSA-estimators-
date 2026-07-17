# DSA Progress Tracker

A full-stack dashboard for tracking DSA practice across LeetCode, Codeforces, and GeeksforGeeks. It combines a React/Vite frontend, a Spring Boot API, and MySQL 8, with group leaderboards and live updates.

## Architecture

- `frontend/` — React and Vite web application
- `backend/` — Spring Boot REST and WebSocket API
- `docker-compose.yml` — local MySQL 8 service

## Prerequisites

- Node.js 20
- JDK 17
- Docker with Docker Compose

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
   npm --prefix frontend install
   npm run dev
   ```

4. Open `http://localhost:5173`.

5. Verify the backend is healthy:

   ```powershell
   Invoke-RestMethod http://localhost:8080/api/health
   ```

The checked-in local defaults connect the API to MySQL on port `3306`. To enable Google sign-in, copy `frontend/.env.example` to `frontend/.env.local`, set `VITE_GOOGLE_CLIENT_ID`, and provide the same value as `GOOGLE_CLIENT_ID` when starting the backend. See [`backend/.env.example`](backend/.env.example) for all backend settings.

## Build

```powershell
npm run build
.\backend\mvnw.cmd -f backend\pom.xml test
```

See [`DEPLOYMENT.md`](DEPLOYMENT.md) for production configuration, security requirements, and deployment order.
