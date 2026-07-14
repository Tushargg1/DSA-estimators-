# API Integration Reference

This file gives Kiro concrete request/response shapes to implement the adapters from design.md section 5. These are reference shapes based on publicly known, unofficial (LeetCode/GFG) or official (Codeforces) endpoints. **Verify against live responses during implementation** since unofficial endpoints can change without notice.

## 1. LeetCode (unofficial GraphQL)

- Endpoint: `POST https://leetcode.com/graphql`
- Headers: `Content-Type: application/json`, `Referer: https://leetcode.com`

Query — recent accepted submissions:

```json
{
  "operationName": "recentAcSubmissions",
  "variables": { "username": "TARGET_USERNAME", "limit": 20 },
  "query": "query recentAcSubmissions($username: String!, $limit: Int!) { recentAcSubmissionList(username: $username, limit: $limit) { id title titleSlug timestamp } }"
}
```

Expected response shape:

```json
{
  "data": {
    "recentAcSubmissionList": [
      { "id": "123456", "title": "Two Sum", "titleSlug": "two-sum", "timestamp": "1752345600" }
    ]
  }
}
```

Metadata query (difficulty/tags) — call once per new unique problem, not every poll:

```json
{
  "operationName": "questionData",
  "variables": { "titleSlug": "two-sum" },
  "query": "query questionData($titleSlug: String!) { question(titleSlug: $titleSlug) { difficulty topicTags { name } } }"
}
```

Failure modes to handle:

- HTTP 429 → treat as `RateLimitException`, trigger cooldown.
- HTTP 403 → likely Cloudflare/bot-detection block, log distinctly (may need to review headers/User-Agent).
- Empty `recentAcSubmissionList` for a valid user → treat as "no new activity," not an error.

## 2. Codeforces (official API)

- Endpoint: `GET https://codeforces.com/api/user.status?handle={handle}&from=1&count=20`

Expected response shape:

```json
{
  "status": "OK",
  "result": [
    {
      "id": 123456789,
      "creationTimeSeconds": 1752345600,
      "verdict": "OK",
      "problem": {
        "contestId": 1500,
        "index": "A",
        "name": "Problem Name",
        "rating": 1200,
        "tags": ["greedy", "math"]
      }
    }
  ]
}
```

- Only rows with `verdict == "OK"` count as solved.
- `problem_id = contestId + index` (e.g. `"1500A"`).
- If `status != "OK"` at the top level, treat as adapter failure (log + skip), don't crash.
- Documented rate limit: keep to roughly 1 request per 2 seconds across the whole app if polling many users back-to-back — batch/stagger calls in the scheduled job rather than firing them all simultaneously.

## 3. GeeksforGeeks (HTML scrape — best effort)

No official API. Scrape the public profile practice/problems section (URL pattern: `https://www.geeksforgeeks.org/user/{username}/`).

Approach (Java, using Jsoup):

```java
Document doc = Jsoup.connect("https://www.geeksforgeeks.org/user/" + username + "/")
    .userAgent("Mozilla/5.0")
    .timeout(10000)
    .get();

// Selector will need to be verified against live page structure at implementation time —
// GFG profile pages are React-rendered in places, so some data may only be available
// via an embedded JSON blob (look for a <script> tag with window.__NEXT_DATA__ or similar)
// rather than plain HTML elements. Inspect the live page before finalizing the selector.
```

**Important implementation note for Kiro:** GFG's profile page may be client-side rendered (React/Next.js), meaning a plain Jsoup GET could return an HTML shell without the actual solved-problems list (it gets filled in by JavaScript after page load). Two possible fixes, in order of preference:

1. Check if the page embeds a `<script>` tag with a JSON payload (common in Next.js apps as `__NEXT_DATA__`) containing the solved list — parse that JSON directly instead of scraping rendered HTML.
2. If no embedded JSON exists, this adapter will require a headless browser (e.g. Playwright/Selenium) to render JS before scraping — a heavier dependency than Jsoup. **Flag this to the user if it turns out to be necessary**, since it changes the deployment footprint (headless browser needs more memory/CPU than a plain HTTP call).

Failure handling:

- Wrap the whole adapter call in try/catch. On any selector mismatch or missing element, throw `ScrapeException` with a clear message, log to `poll_status`, and return an empty list rather than propagating a raw NullPointerException.

## 4. Rate-Limit & Backoff Summary

| Platform    | Official limit                          | App-side safeguard                                                              |
|-------------|-----------------------------------------|---------------------------------------------------------------------------------|
| LeetCode    | None documented (unofficial)            | Treat 429/403 as rate-limit signal, 15-min cooldown per user-platform pair      |
| Codeforces  | ~1 req/2 sec system-wide (documented)   | Stagger requests in the scheduled job; don't fire all users in parallel         |
| GFG         | None (scraping)                         | Add random delay (1-3 sec) between requests to reduce ban risk; still expect occasional blocks |

## 5. Testing Without Hitting Live APIs

Save sample JSON/HTML fixtures from each platform (one real user's real response, sanitized) into `src/test/resources/fixtures/` and write adapter unit tests against those fixtures. Do not make live API calls in the CI test suite — live unofficial endpoints can change or rate-limit CI runs, causing flaky tests unrelated to actual code bugs.
