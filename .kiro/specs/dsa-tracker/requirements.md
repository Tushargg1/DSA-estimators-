# Requirements Document

## Introduction

A web application that tracks DSA (Data Structures & Algorithms) practice progress for a friend group. It pulls submission data from LeetCode, Codeforces, and GeeksforGeeks every 5 minutes, counts only genuinely new (first-time-ever) solved problems toward each user's daily target, and shows a near-live group leaderboard.

## Glossary

- **First attempt**: The first time ever that a specific user solves a specific problem on a specific platform, across all recorded history.
- **Counted for target**: A submission that increments a user's daily count. Only post-onboarding first attempts qualify.
- **Backfill**: The one-time historical import of a user's existing submissions at signup. Backfilled rows never count toward a daily target.
- **Counting day**: The window from 12:01 AM IST to 11:59:59 PM IST, computed by converting stored UTC timestamps to IST.
- **Hit day**: A counting day on which a user's counted submissions meet or exceed their daily target.
- **Streak**: The number of consecutive hit days ending today or yesterday.

## Requirements

### Requirement 1: User Onboarding

**User Story:** As a new user, I want to link my LeetCode, Codeforces, and GFG usernames, so that the app can track my submissions.

#### Acceptance Criteria

1. WHEN a user signs up THEN the system SHALL allow entry of a display name, LeetCode username, Codeforces username, and GFG profile URL/username.
2. WHEN a user submits their platform usernames THEN the system SHALL validate that each username/profile exists by making a test call to the respective platform.
3. IF a username does not exist or the profile is private THEN the system SHALL reject that field with a specific error message per platform.
4. WHEN a user is successfully created THEN the system SHALL immediately trigger a one-time backfill job to pull their full existing submission history.
5. WHEN backfill runs THEN the system SHALL insert all historical submissions with `is_first_attempt` computed normally but `counted_for_target = false` for every backfilled row.
6. WHEN backfill completes THEN the system SHALL mark the user as `onboarding_complete = true`, after which new submissions are eligible for `counted_for_target = true`.

### Requirement 2: Submission Polling

**User Story:** As a system, I want to poll each linked platform every 5 minutes, so that new solves are detected close to real time.

#### Acceptance Criteria

1. WHEN the scheduled job runs (every 5 minutes) THEN the system SHALL fetch recent accepted/correct submissions for every active user across all their linked platforms.
2. WHEN a fetched submission's platform-specific unique key (platform + problem slug/id + user) does not already exist in the database THEN the system SHALL insert it as a new row.
3. WHEN a fetched submission already exists in the database THEN the system SHALL skip it (no duplicate insert).
4. IF a platform API call fails (timeout, rate-limit, structure change) THEN the system SHALL log the failure with platform + user + timestamp and continue polling other users/platforms without crashing the job.
5. WHEN a platform returns a rate-limit response THEN the system SHALL back off that platform's polling for a configurable cooldown period (default 15 minutes) before retrying.
6. THE system SHALL NOT poll any single platform for a given user more frequently than once per 5 minutes.

### Requirement 3: First-Attempt & Target Counting Logic

**User Story:** As a user, I want only genuinely new problems to count toward my daily target, so re-solving old problems doesn't let me game my streak.

#### Acceptance Criteria

1. WHEN a new submission is inserted THEN the system SHALL check whether this user has ever solved this exact problem (same platform + problem id) before, across all history.
2. IF this is the first time ever THEN the system SHALL set `is_first_attempt = true` on that row.
3. IF the user has solved this problem before (on any prior date) THEN the system SHALL set `is_first_attempt = false` on that row, and this row SHALL NOT increment the daily count.
4. IF a submission is part of the initial onboarding backfill THEN `counted_for_target` SHALL always be false, regardless of `is_first_attempt`.
5. WHEN a post-onboarding submission has `is_first_attempt = true` THEN the system SHALL set `counted_for_target = true`.
6. THE daily count for a user on a given day SHALL be defined as: count of submissions WHERE `counted_for_target = true` AND `solved_at` (converted to IST) falls within that day's window (12:01 AM to 11:59:59 PM IST).

### Requirement 4: Daily Target & Reset

**User Story:** As a user, I want a daily target of 5 new problems that resets each day at 12:01 AM IST.

#### Acceptance Criteria

1. THE system SHALL store a per-user configurable `daily_target` (default value: 5).
2. THE system SHALL treat 12:01 AM IST as the start of a new counting day.
3. WHEN computing "today's count" THEN the system SHALL convert all UTC submission timestamps to IST before bucketing by day.
4. WHEN a new day starts (12:01 AM IST) THEN the system SHALL NOT carry over incomplete counts from the previous day; each day starts at 0.
5. THE system SHALL retain historical daily counts (not just today's) to support streak calculation.

### Requirement 5: Streaks

**User Story:** As a user, I want to see my current streak of consecutive days hitting my target, so I stay motivated.

#### Acceptance Criteria

1. WHEN a user's daily count for a given day meets or exceeds their `daily_target` THEN that day SHALL count as a "hit" day.
2. WHEN a day passes without the target being met THEN the streak SHALL reset to 0 starting the next day.
3. THE system SHALL display current streak and longest streak per user.

### Requirement 6: Groups

**User Story:** As a user, I want to be part of a friend group so we can see each other's progress.

#### Acceptance Criteria

1. WHEN a user creates a group THEN the system SHALL generate a unique invite code.
2. WHEN another user enters a valid invite code THEN the system SHALL add them to that group.
3. THE system SHALL allow a user to belong to more than one group.
4. WHEN viewing a group THEN the system SHALL display every member's: today's count / target, current streak, and total problems solved (first-attempts only).

### Requirement 7: Live Updates

**User Story:** As a user, I want the leaderboard to update without manually refreshing, so it feels close to real-time.

#### Acceptance Criteria

1. WHEN a new first-attempt submission is detected during a poll cycle THEN the system SHALL push the update to all connected clients viewing that group, via WebSocket.
2. IF a client's WebSocket connection drops THEN the frontend SHALL fall back to a manual fetch on reconnect/page load to resync state.
3. THE system SHALL NOT claim or imply true real-time (sub-minute) detection anywhere in the UI; the UI SHALL indicate "last synced X minutes ago" per platform.

### Requirement 8: Problem Detail View

**User Story:** As a user, I want to click into a friend's solved problem to see its details.

#### Acceptance Criteria

1. WHEN a user clicks a logged submission THEN the system SHALL display problem name, platform, difficulty, tags (if available), and solve timestamp.
2. IF the platform did not provide tags/difficulty (e.g., GFG scrape gap) THEN the system SHALL display "Not available" rather than a blank or error.

### Requirement 9: GFG Scraper Resilience

**User Story:** As the system owner, I want GFG scraping failures to be visible and non-fatal.

#### Acceptance Criteria

1. IF the GFG scraper fails to parse a profile page (structure changed) THEN the system SHALL log an explicit "GFG_PARSE_FAILURE" event with timestamp and NOT crash the polling job for other users/platforms.
2. THE system SHALL expose a simple internal status page/endpoint showing last-successful-poll-time per platform, so breakage is visible without digging through logs.
