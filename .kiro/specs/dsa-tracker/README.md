# DSA Progress Tracker — Kiro Project Spec

This folder is a complete spec-driven package for Kiro to build this project end-to-end.

## Files (read in this order)

1. **requirements.md** — EARS-format requirements (what the system must do, testable acceptance criteria)
2. **design.md** — architecture, database schema, polling job design, WebSocket design, REST API surface, known fragility table
3. **tasks.md** — checkbox implementation task list, in build order (Kiro executes this)
4. **04-api-integration-reference.md** — concrete request/response shapes for LeetCode, Codeforces, GFG, plus rate-limit/backoff guidance and GFG's likely client-side-rendering complication

## How to use this with Kiro

- These files already live under `.kiro/specs/dsa-tracker/`. Kiro's spec workflow looks for `requirements.md`, `design.md`, and `tasks.md` in a spec folder — those are the canonical filenames it consumes.
- Open `tasks.md` in Kiro and run the tasks in order; Kiro checks each box as it completes and verifies that task.
- Keep `04-api-integration-reference.md` referenced during adapter implementation (tasks 3.x) since those are the parts most likely to need live verification against current platform behavior.

## Decisions already locked in (don't relitigate mid-build)

- Poll every 5 minutes, not faster — LeetCode/GFG rate-limit and ban risk.
- Only first-ever-solved problems count toward the daily target of 5; repeats are logged but don't increment the count.
- Reset boundary: 12:01 AM IST, computed by converting stored UTC timestamps.
- GFG is included despite being the least reliable source — scraper failures must be visible (via `poll_status` table / status endpoint), not silent.
- No OAuth — usernames/profiles are public, stored directly.

## One flag before you deploy

Given the ongoing AWS billing dispute (NAT Gateway, Elastic IPs, RDS snapshot charges), don't provision this project's Postgres on RDS or run it behind a NAT Gateway. Use a managed free-tier Postgres (Neon, Supabase, Railway) and a simple container host (Railway/Render/Fly.io) instead. Keep this one cheap; it's not worth repeating the same billing mistake.
