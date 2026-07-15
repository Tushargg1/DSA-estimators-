# Implementation Plan

## Overview

Build order for the DSA Progress Tracker: scaffold both apps, stand up the database layer, implement the three platform adapters, then layer on backfill, the polling job, time/streak logic, the REST API, WebSocket delivery, and finally the React frontend, testing, and deployment. Each task references requirements from `requirements.md`. Keep `04-api-integration-reference.md` open during section 3 (adapters).

## Task Dependency Graph

Tasks are grouped into waves. Every task in a wave may run in parallel; each wave depends on all prior waves.

```json
{
  "waves": [
    { "wave": 1, "tasks": ["1"], "dependsOn": [] },
    { "wave": 2, "tasks": ["2"], "dependsOn": ["1"] },
    { "wave": 3, "tasks": ["3", "6"], "dependsOn": ["2"] },
    { "wave": 4, "tasks": ["4", "5", "7"], "dependsOn": ["3", "6"] },
    { "wave": 5, "tasks": ["8", "9"], "dependsOn": ["4", "5", "7"] },
    { "wave": 6, "tasks": ["10"], "dependsOn": ["8", "9"] },
    { "wave": 7, "tasks": ["11"], "dependsOn": ["10"] },
    { "wave": 8, "tasks": ["12"], "dependsOn": ["11"] }
  ]
}
```

- Section 6 (IST handling) is scheduled in wave 3 alongside adapters because task 5.5 depends on it.
- Section 7 (streaks) sits in wave 4 with backfill and polling since it reads `daily_counts` produced by the polling job.
- Section 3.6 (adapter tests) can run in parallel with waves 4 once 3.1–3.5 exist.

## Tasks

- [x] 1. Project scaffolding
  - [x] 1.1 Initialize Spring Boot project (Spring Web, Spring Data JPA, Spring WebSocket, MySQL Connector/J, Spring Scheduling)
  - [x] 1.2 Initialize React project (Vite), set up REST client and STOMP/WebSocket client (`@stomp/stompjs`, `sockjs-client`)
  - [x] 1.3 Set up MySQL 8 locally (docker-compose with a pinned `mysql:8.0.40` service) and connect Spring Boot via `application.yml`

- [x] 2. Database layer
  - [x] 2.1 Create JPA entities: `User`, `Submission`, `Group`, `GroupMember`, `DailyCount`, `PollStatus` matching schema in design.md section 3 (_Requirements: 1, 3, 4, 6, 9_)
  - [x] 2.2 Write Flyway or Liquibase migration scripts for all tables, indexes, and unique constraints
  - [x] 2.3 Create repositories: `UserRepository`, `SubmissionRepository`, `GroupRepository`, `DailyCountRepository`, `PollStatusRepository`

- [x] 3. Platform adapters
  - [x] 3.1 Define common interface `SubmissionFetcher` with method `List<RawSubmission> fetchRecent(String username)` (_Requirements: 2.1_)
  - [x] 3.2 Implement `LeetCodeAdapter` using the GraphQL `recentAcSubmissionList` query; parse response into `RawSubmission` (problemId, problemName, timestamp)
  - [x] 3.3 Implement `LeetCodeAdapter` secondary metadata fetch (difficulty/tags) — only called once per new unique problem, not on every poll (_Requirements: 8.1_)
  - [x] 3.4 Implement `CodeforcesAdapter` using `user.status` API, filter `verdict == "OK"`, map `contestId+index` as problem_id
  - [x] 3.5 Implement `GfgAdapter` using Jsoup to scrape public profile practice page; handle missing/changed markup gracefully (return empty list + throw a distinguishable `ScrapeException`, don't NPE). NOTE: GFG profile pages are likely React/Next.js client-rendered — a plain Jsoup GET may return an empty shell. Check for an embedded `__NEXT_DATA__` JSON blob first; if none exists, flag that a headless browser is required before proceeding (see 04-api-integration-reference.md section 3). (_Requirements: 9.1_)
  - [x] 3.6 Write unit tests for each adapter using saved sample HTML/JSON fixtures (do not hit live APIs in tests)

- [x] 4. Backfill (onboarding) logic
  - [x] 4.1 On user creation, trigger an async job that uses each adapter's deepest verified history path: complete `user.status` pagination for Codeforces, the verified recent-accepted query for LeetCode (no reliable paging contract in this codebase), and visible profile data for GFG (_Requirements: 1.4_)
  - [x] 4.2 Insert all backfilled submissions with `counted_for_target = false`, `is_first_attempt` computed normally (_Requirements: 1.5, 3.4_)
  - [x] 4.3 Set `onboarding_complete = true` once backfill finishes successfully (_Requirements: 1.6_)
  - [x] 4.4 Handle partial backfill failure (e.g. GFG scrape fails but LeetCode/CF succeed) — still mark onboarding complete, log which platform failed

- [x] 5. Polling job
  - [x] 5.1 Implement `@Scheduled(fixedRate = 300000)` job that iterates all onboarded users (_Requirements: 2.1, 2.6_)
  - [x] 5.2 For each user/platform: fetch, dedupe by `(user_id, platform, problem_id, solved_at_utc)`, insert new rows (_Requirements: 2.2, 2.3_)
  - [x] 5.3 Implement `is_first_attempt` check: query existence of `(user_id, platform, problem_id)` ignoring timestamp (_Requirements: 3.1, 3.2, 3.3_)
  - [x] 5.4 Implement `counted_for_target` logic (true only if `is_first_attempt` AND `onboarding_complete`) (_Requirements: 3.5_)
  - [x] 5.5 Update `daily_counts` table on each new counted submission (upsert by `user_id + date_ist`) (_Requirements: 3.6, 4.5_)
  - [x] 5.6 Implement per-user-per-platform try/catch isolation so one failure doesn't abort the whole job (_Requirements: 2.4, 9.1_)
  - [x] 5.7 Implement `poll_status` table updates (success/failure timestamps + reason) (_Requirements: 9.2_)
  - [x] 5.8 Implement basic rate-limit cooldown (skip platform for a user for 15 min after a detected rate-limit response) (_Requirements: 2.5_)

- [x] 6. IST date/time handling
  - [x] 6.1 Write a single shared utility `TimeUtil.toIstDate(Instant utcTimestamp)` used everywhere date bucketing happens — no scattered timezone math elsewhere in the codebase (_Requirements: 4.2, 4.3_)
  - [x] 6.2 Unit test edge cases: submission at 11:59 PM UTC, submission exactly at IST midnight boundary (_Requirements: 4.4_)

- [x] 7. Streak calculation
  - [x] 7.1 Implement logic: `target_hit = (count >= daily_target)` written to `daily_counts` whenever count updates (_Requirements: 5.1_)
  - [x] 7.2 Implement current streak query: consecutive `target_hit = true` days ending today or yesterday (_Requirements: 5.2, 5.3_)
  - [x] 7.3 Implement longest streak query (max consecutive run in history) (_Requirements: 5.3_)

- [x] 8. REST API
  - [x] 8.1 `POST /api/users` — create user + trigger backfill (section 4) (_Requirements: 1.1, 1.2, 1.3_)
  - [x] 8.2 `GET /api/users/{id}` — profile
  - [x] 8.3 `PUT /api/users/{id}/target` — update daily target (_Requirements: 4.1_)
  - [x] 8.4 `POST /api/groups`, `POST /api/groups/join` (_Requirements: 6.1, 6.2, 6.3_)
  - [x] 8.5 `GET /api/groups/{id}/leaderboard` — join users + daily_counts + streak calc into one response DTO (_Requirements: 6.4_)
  - [x] 8.6 `GET /api/groups/{id}/history?date=X` (_Requirements: 4.5_)
  - [x] 8.7 `GET /api/users/{id}/submissions` — paginated, include `is_first_attempt`, `counted_for_target` flags (_Requirements: 8.1, 8.2_)
  - [x] 8.8 `GET /api/status/poll` — expose `poll_status` table contents (_Requirements: 9.2_)

- [x] 9. WebSocket layer
  - [x] 9.1 Configure STOMP endpoint `/ws` with SockJS fallback (_Requirements: 7.1_)
  - [x] 9.2 On new counted submission in polling job, publish to `/topic/group/{groupId}` for every group the user belongs to (_Requirements: 7.1_)
  - [x] 9.3 Define message DTO: `{userId, userName, problemName, platform, difficulty, newDailyCount, target}`

- [x] 10. Frontend
  - [x] 10.1 Build `OnboardingForm` — collect name, email, LeetCode/CF/GFG usernames, call `POST /api/users`, show validation errors per platform (_Requirements: 1.1, 1.3_)
  - [x] 10.2 Build `GroupInvite` — create group / join via code (_Requirements: 6.1, 6.2_)
  - [x] 10.3 Build `Leaderboard` — fetch initial state via REST, subscribe to WebSocket for live deltas, merge updates into state (_Requirements: 6.4, 7.1_)
  - [x] 10.4 Build `UserCard` — show today's count/target progress bar, current streak, longest streak (_Requirements: 5.3, 6.4_)
  - [x] 10.5 Build `ProblemDetailModal` — show problem name/difficulty/tags/timestamp on click; show "Not available" for missing GFG fields (_Requirements: 8.1, 8.2_)
  - [x] 10.6 Add "last synced X min ago" indicator per platform, sourced from `/api/status/poll` (_Requirements: 7.3_)
  - [x] 10.7 Implement WebSocket reconnect logic: on reconnect, re-fetch leaderboard via REST to resync (don't trust delta-only state after a dropped connection) (_Requirements: 7.2_)

- [x] 11. Testing & validation
  - [x] 11.1 Integration test: simulate a user's first-ever solve of a problem → verify `is_first_attempt = true`, `counted_for_target = true`, `daily_counts` incremented (_Requirements: 3.1, 3.2, 3.5_)
  - [x] 11.2 Integration test: simulate re-solving an already-solved problem → verify count does NOT increment (_Requirements: 3.3_)
  - [x] 11.3 Integration test: backfill scenario → verify historical submissions never increment `daily_counts` (_Requirements: 1.5, 3.4_)
  - [x] 11.4 Integration test: IST midnight boundary — submission at 11:58 PM IST vs 12:02 AM IST land on correct days (_Requirements: 4.3, 4.4_)
  - [x] 11.5 Manual test: disconnect network mid-poll for one platform → verify other platforms/users still process normally (_Requirements: 2.4, 9.1_)

- [x] 12. Deployment
  - [x] 12.1 Dockerize backend (multi-stage build, Java 21 base image)
  - [x] 12.2 Dockerize frontend (build + serve via nginx, or deploy separately to Vercel/Netlify)
  - [x] 12.3 Deploy Aiven MySQL 8 using hosting-platform secrets and TLS; avoid provisioning self-managed RDS/NAT Gateway/Elastic IP infrastructure for a small side project
  - [x] 12.4 Set up environment variables for DB connection, CORS allowed origins, WebSocket allowed origins

## Notes

- **Do not relitigate locked decisions** (see README): 5-min poll, first-ever-solve counting only, 12:01 AM IST reset, GFG included but failures must be visible, no OAuth.
- **GFG is the fragile link.** If task 3.5 reveals the profile is fully client-rendered with no `__NEXT_DATA__` blob, stop and confirm the headless-browser tradeoff with the user before adding Playwright/Selenium — it changes the deployment footprint.
- **Timezone math lives in one place** (`TimeUtil`). Reject any PR/change that does ad-hoc UTC↔IST conversion elsewhere.
- **Tests use fixtures, not live APIs.** Save sanitized real responses under `src/test/resources/fixtures/` so CI doesn't flake on upstream changes or rate limits.
