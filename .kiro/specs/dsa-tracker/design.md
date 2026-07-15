# Design Document

## Overview

Spring Boot backend + React frontend. Backend polls three external platforms every 5 minutes via a scheduled job, stores submissions in MySQL 8, pushes updates to connected clients over WebSocket (STOMP). No OAuth required — platform usernames are public profile identifiers.

## Architecture

```
┌──────────────┐      ┌───────────────────────────┐      ┌───────────────┐
│   React SPA  │◄────►│   Spring Boot Backend      │◄────►│    MySQL 8    │
│ (WebSocket + │      │  - REST API                │      └───────────────┘
│  REST client)│      │  - Scheduled Poller Job    │
└──────────────┘      │  - WebSocket (STOMP)       │
                      └───────────┬────────────────┘
                                  │
                   ┌──────────────┼──────────────────┐
                   ▼              ▼                   ▼
            LeetCode GraphQL  Codeforces API     GFG Scraper
            (unofficial)      (official)         (HTML parse)
```

## Data Models

### Database Schema (MySQL 8)

```sql
CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(150) NOT NULL UNIQUE,
    leetcode_username VARCHAR(100),
    codeforces_username VARCHAR(100),
    gfg_username VARCHAR(100),
    daily_target INT NOT NULL DEFAULT 5,
    onboarding_complete BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE submissions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    platform VARCHAR(20) NOT NULL,
    problem_id VARCHAR(150) NOT NULL,
    problem_name VARCHAR(255) NOT NULL,
    difficulty VARCHAR(20),
    tags JSON,
    solved_at_utc DATETIME(6) NOT NULL,
    is_first_attempt BOOLEAN NOT NULL,
    counted_for_target BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (user_id) REFERENCES users(id),
    UNIQUE (user_id, platform, problem_id, solved_at_utc),
    INDEX idx_submissions_user_date (user_id, solved_at_utc),
    INDEX idx_submissions_first_attempt (user_id, platform, problem_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tracker_groups (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    invite_code VARCHAR(20) NOT NULL UNIQUE,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE group_members (
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (group_id, user_id),
    FOREIGN KEY (group_id) REFERENCES tracker_groups(id),
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE daily_counts (
    user_id BIGINT NOT NULL,
    date_ist DATE NOT NULL,
    count INT NOT NULL DEFAULT 0,
    target_hit BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (user_id, date_ist),
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE poll_status (
    platform VARCHAR(20) PRIMARY KEY,
    last_success_at DATETIME(6),
    last_failure_at DATETIME(6),
    last_failure_reason TEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> **Note:** `daily_counts` is a denormalized rollup table, updated whenever a new `counted_for_target = true` submission lands. This avoids recomputing streaks from raw submissions every page load.

## Components and Interfaces

### Polling Job Design

- Spring `@Scheduled(fixedRate = 300000)` (5 min) — single job, iterates all users with `onboarding_complete = true`.
- For each user, for each linked platform:
  1. Call platform adapter (`LeetCodeAdapter`, `CodeforcesAdapter`, `GfgAdapter` — common interface `SubmissionFetcher`).
  2. Get list of recent submissions (last ~24h window is enough given 5-min polling; keeps API payload small).
  3. For each submission: check existence by `(user_id, platform, problem_id, solved_at_utc)`.
  4. If new: check `is_first_attempt` by querying whether `(user_id, platform, problem_id)` exists anywhere in history (ignoring timestamp).
  5. Insert row. If `is_first_attempt` and `onboarding_complete`, set `counted_for_target = true` and upsert `daily_counts`.
  6. Push WebSocket event to the user's group channel(s): `{userId, problemName, platform, newCount}`.
- On adapter failure: catch exception per-user-per-platform (do not let one failure abort the whole job), write to `poll_status`, continue loop.
- Rate-limit handling: if adapter throws a `RateLimitException`, skip that platform for that user and set a cooldown timestamp in memory (or a `next_poll_after` column) — default 15 min cooldown.

### Platform Adapters

#### LeetCode (unofficial GraphQL)

- Endpoint: `POST https://leetcode.com/graphql`
- Query: `recentAcSubmissionList(username: $username, limit: 20)`
- Returns: `title`, `titleSlug`, `timestamp` (unix), `statusDisplay`
- Risk: unofficial, no SLA, can break/rate-limit without notice. Wrap in try/catch, treat any non-200 or schema-mismatch as failure, log and move on.
- Difficulty/tags require a second query (`question` query by slug) — only fetch on first-ever solve to reduce calls (don't re-fetch metadata for problems already in DB).

#### Codeforces (official API)

- Endpoint: `GET https://codeforces.com/api/user.status?handle={handle}&from=1&count=20`
- Returns: `verdict`, `problem` (contestId, index, name, rating, tags), `creationTimeSeconds`
- Only count submissions where `verdict == "OK"`.
- Respects documented rate limit (~1 req/2 sec system-wide) — since polling is per-user every 5 min, this is not a concern at small group scale (under ~50 users).

#### GFG (HTML scrape)

- No API. Scrape the public profile page (practice/profile section).
- Use Jsoup (Java HTML parser) to extract solved problem list.
- Expect only problem name reliably; difficulty/tags likely unavailable — store as NULL.
- This adapter WILL break when GFG changes markup. Treat as best-effort: any parse failure logs to `poll_status` and is surfaced on the internal status page, not silently swallowed.

### WebSocket Design

- Spring Boot STOMP over WebSocket (`spring-boot-starter-websocket`).
- Channel per group: `/topic/group/{groupId}`.
- On new counted submission, backend publishes: `{userId, userName, problemName, platform, difficulty, newDailyCount, target}`.
- Frontend subscribes to its active group's channel on page load; falls back to REST `GET /api/groups/{id}/leaderboard` on connect/reconnect to resync full state (WebSocket only carries deltas, not full state).

### REST API Surface

```
POST   /api/users                        - create user, triggers backfill job
GET    /api/users/{id}                   - profile + linked platforms
PUT    /api/users/{id}/target            - update daily target

POST   /api/groups                       - create group, returns invite code
POST   /api/groups/join                  - join via invite code
GET    /api/groups/{id}/leaderboard      - full current state (today count, streak, target per member)
GET    /api/groups/{id}/history?date=X   - historical daily counts for the group

GET    /api/users/{id}/submissions       - paginated submission list (with is_first_attempt flag shown)
GET    /api/status/poll                  - internal: last successful/failed poll per platform
```

### Frontend Structure (React)

```
src/
  components/
    Leaderboard.jsx        - group view, live via WebSocket subscription
    UserCard.jsx           - per-user today count / target / streak
    ProblemDetailModal.jsx
    OnboardingForm.jsx     - link platform usernames
    GroupInvite.jsx
  hooks/
    useGroupSocket.js      - WebSocket subscribe/unsubscribe + fallback resync
  api/
    client.js              - REST calls
```

## Correctness Properties

These invariants must hold regardless of polling order, retries, or partial failures:

### Property 1: No double counting

A given `(user_id, platform, problem_id, solved_at_utc)` tuple is inserted at most once (enforced by the unique constraint).

**Validates: Requirements 2.2, 2.3**

### Property 2: First-attempt is monotonic

Once a `(user_id, platform, problem_id)` exists in history, every later submission of that same problem is `is_first_attempt = false`.

**Validates: Requirements 3.1, 3.2, 3.3**

### Property 3: Backfill never scores

Any row inserted during onboarding backfill has `counted_for_target = false`, even if `is_first_attempt = true`.

**Validates: Requirements 1.5, 3.4**

### Property 4: Counting requires both flags

`counted_for_target = true` implies `is_first_attempt = true` AND the user was `onboarding_complete` at insert time.

**Validates: Requirements 3.5**

### Property 5: Day bucketing is deterministic

A submission's counting day is derived solely from `TimeUtil.toIstDate(solved_at_utc)` — the same input always yields the same day, independent of when the poll ran.

**Validates: Requirements 4.3, 4.4**

### Property 6: Job isolation

A failure polling one user/platform pair never prevents other pairs in the same cycle from being processed.

**Validates: Requirements 2.4, 9.1**

## Error Handling

Known fragility, explicit and not hidden:

| Component         | Risk                                   | Mitigation                                                                                     |
|-------------------|----------------------------------------|------------------------------------------------------------------------------------------------|
| LeetCode GraphQL  | Unofficial, can break/rate-limit anytime | try/catch, log, don't crash job, exponential backoff on repeated failure                       |
| GFG scraper       | Will break on any markup change        | isolated adapter, failures logged and surfaced on status page, no silent failure               |
| 5-min polling     | Not real real-time                     | UI explicitly shows "last synced" timestamp per platform, no false "live" claim                |
| IST conversion    | Off-by-timezone bugs if done wrong     | store everything in UTC, convert only at query/display time, single conversion utility function used everywhere (no scattered timezone math) |

- Adapter exceptions are caught per-user-per-platform; the failure is written to `poll_status` (`last_failure_at`, `last_failure_reason`) and the loop continues.
- Rate-limit responses (HTTP 429/403 for LeetCode) raise `RateLimitException`, triggering a 15-minute cooldown for that platform/user pair.
- GFG parse failures raise a distinguishable `ScrapeException` logged as `GFG_PARSE_FAILURE` and surfaced via `GET /api/status/poll`.
- Missing metadata (GFG difficulty/tags) is stored as NULL and rendered as "Not available" — never a blank or error.

## Testing Strategy

- **Adapter unit tests** run against saved, sanitized fixtures under `src/test/resources/fixtures/` — no live API calls in CI (upstream changes/rate limits would cause flakes unrelated to code bugs).
- **Counting integration tests** cover: first-ever solve (counts), re-solve (does not count), backfill (never counts).
- **Timezone tests** cover the IST midnight boundary (11:58 PM IST vs 12:02 AM IST land on correct days) and the 11:59 PM UTC case.
- **Resilience test** disconnects one platform mid-poll and verifies other users/platforms still process.
