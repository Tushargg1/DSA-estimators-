/**
 * UserCard (task 10.4, Requirements 5.3, 6.4).
 *
 * Renders one leaderboard member row from LeaderboardResponse.MemberEntry:
 *   { userId, userName, todayCount, dailyTarget, currentStreak, longestStreak, totalSolved }
 *
 * Shows today's count against the daily target as a progress bar, plus current
 * and longest streaks and the all-time total of first-attempt solves.
 *
 * @param {{ member: object }} props
 */
function UserCard({ member }) {
  const {
    userName,
    todayCount = 0,
    dailyTarget = 0,
    currentStreak = 0,
    longestStreak = 0,
    totalSolved = 0,
  } = member ?? {}

  const pct =
    dailyTarget > 0
      ? Math.min(100, Math.round((todayCount / dailyTarget) * 100))
      : 0
  const hitTarget = dailyTarget > 0 && todayCount >= dailyTarget

  return (
    <div className={`user-card${hitTarget ? ' hit' : ''}`}>
      <div className="user-card-header">
        <span className="user-name">{userName}</span>
        <span className="today-count">
          {todayCount} / {dailyTarget}
        </span>
      </div>

      <div
        className="progress"
        role="progressbar"
        aria-valuenow={todayCount}
        aria-valuemin={0}
        aria-valuemax={dailyTarget}
        aria-label={`${userName} progress: ${todayCount} of ${dailyTarget}`}
      >
        <div className="progress-bar" style={{ width: `${pct}%` }} />
      </div>

      <div className="user-card-stats">
        <span title="Current streak">🔥 {currentStreak}d</span>
        <span title="Longest streak">🏆 {longestStreak}d</span>
        <span title="Total solved (first attempts)">✅ {totalSolved}</span>
      </div>
    </div>
  )
}

export default UserCard
