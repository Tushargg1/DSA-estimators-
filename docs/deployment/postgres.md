# Deploying Postgres (Managed)

This guide covers provisioning the production Postgres database for the DSA Progress
Tracker. It is a **locked decision** to use a **managed free/low-cost tier** rather than
self-managed AWS RDS.

## Recommendation

**Use [Neon](https://neon.tech) (serverless Postgres, generous free tier).**

Why managed, and why Neon specifically:

- **No AWS footprint.** Avoids provisioning RDS, NAT Gateways, or Elastic IPs — and
  sidesteps the existing AWS billing dispute entirely. There is nothing to leave running
  and accidentally get charged for.
- **Minimal ops for a side project.** No VPC, subnet, security-group, or patching work.
  Create a database, copy a connection string, done.
- **Real Postgres.** The schema uses `TEXT[]` array columns (`submissions.tags` in
  `V1__init_schema.sql`), which is a native Postgres feature — so the provider must be
  genuine Postgres, not a Postgres-compatible clone. Neon qualifies.

**Alternatives** (all real Postgres, all with a free/low-cost tier — pick based on
preference):

- **[Supabase](https://supabase.com)** — Postgres plus extras (auth, storage) you don't
  need here, but the raw DB works fine. Free tier pauses inactive projects.
- **[Railway](https://railway.app)** — simple Postgres provisioning; usage-based pricing
  with a small monthly free credit.

## Setup Steps

1. **Create the project/database** on your chosen provider.
   - Neon: sign up → *Create Project* → pick a region close to where the backend runs →
     a database (default name `neondb`) and a role are created automatically.
2. **Grab the connection string.** Neon shows a ready-made connection string in the
   project dashboard (*Connection Details*). It looks like:

   ```
   postgresql://<user>:<password>@<host>/<db>?sslmode=require
   ```

3. **Map it to the app's env vars.** The backend reads these in `application.yml`
   (`spring.datasource.*`), so convert the provider string into JDBC form:

   | Env var       | Value                                                              |
   | ------------- | ------------------------------------------------------------------ |
   | `DB_URL`      | `jdbc:postgresql://HOST:PORT/DB?sslmode=require`                   |
   | `DB_USERNAME` | the role/user from the connection string                           |
   | `DB_PASSWORD` | the password from the connection string                            |

   Example `DB_URL`:

   ```
   jdbc:postgresql://ep-cool-name-123456.us-east-2.aws.neon.tech:5432/neondb?sslmode=require
   ```

   > **`sslmode=require` is mandatory.** Managed providers only accept TLS connections;
   > omit it and the connection is refused. Note the JDBC URL is `jdbc:postgresql://...`
   > (with the `jdbc:` prefix and the port), which differs from the provider's raw
   > `postgresql://...` string — split the host/port/db out and re-assemble as above.

## Migrations

Flyway runs **automatically on backend startup** against whatever `DB_URL` points at.
On first boot against the managed database it applies `V1__init_schema.sql`, creating all
tables, indexes, and constraints. No manual migration step is required — just point the
env vars at the managed DB and start the app.

Because the schema uses `TEXT[]` (native Postgres arrays), the migration only succeeds on
a real Postgres instance. All three providers above are real Postgres.

## Free-tier caveats

- **Autosuspend / cold starts (Neon).** Neon's free tier suspends compute after inactivity
  and wakes on the next connection, so the first request after idle can be slow. Fine for a
  small side project.
- **Connection limits.** Free tiers cap concurrent connections. Keep the backend's pool
  small so a single instance doesn't exhaust the cap.
- **Advisory pool sizing.** Consider a modest HikariCP pool. You can make it env-tunable,
  e.g. in `application.yml`:

  ```yaml
  spring:
    datasource:
      hikari:
        maximum-pool-size: ${DB_POOL_MAX:5}
  ```

  A value around `5` is a reasonable starting point for a free tier; tune to fit the
  provider's connection limit. This is optional — the default pool works, it's just less
  frugal with connections.

## Related tasks

- **Task 12.4 (environment variables):** `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (and the
  optional `DB_POOL_MAX`) are set there alongside CORS and WebSocket allowed origins.
- **Task 12.1 (backend Dockerfile):** these env vars are supplied to the container at
  runtime (e.g. `docker run -e DB_URL=... -e DB_USERNAME=... -e DB_PASSWORD=...` or via the
  hosting platform's secrets/env config). Do not bake credentials into the image.
- **Local dev** continues to use `docker-compose.yml` (`postgres:16`); the `application.yml`
  defaults point there, so no env vars are needed locally.
