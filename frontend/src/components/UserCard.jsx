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
function UserCard({ member, rank }) {
  const {
    userName,
    todayCount = 0,
    dailyTarget = 0,
    currentStreak = 0,
    longestStreak = 0,
    totalSolved = 0,
  } = member ?? {}

  const pct = dailyTarget > 0 ? Math.min(100, Math.round((todayCount / dailyTarget) * 100)) : 0
  const hitTarget = dailyTarget > 0 && todayCount >= dailyTarget

  return (
    <article className={`user-card${hitTarget ? ' hit' : ''}`}>
      <div className={`rank-badge rank-${Math.min(rank, 3)}`} aria-label={`Rank ${rank}`}>{rank}</div>
      <div className="user-card-content">
        <div className="user-card-header">
          <div><span className="user-name">{userName}</span><small>{hitTarget ? 'Daily target complete' : 'Today’s progress'}</small></div>
          <span className="today-count"><strong>{todayCount}</strong><small> / {dailyTarget}</small></span>
        </div>
        <div className="progress" role="progressbar" aria-valuenow={todayCount} aria-valuemin={0}
          aria-valuemax={Math.max(dailyTarget, todayCount, 1)} aria-label={`${userName} progress: ${todayCount} of ${dailyTarget}`}>
          <div className="progress-bar" style={{ width: `${pct}%` }} />
        </div>
        <div className="user-card-stats">
          <span><i aria-hidden="true">🔥</i><small>Current streak</small><strong>{currentStreak} days</strong></span>
          <span><i aria-hidden="true">🏆</i><small>Best streak</small><strong>{longestStreak} days</strong></span>
          <span><i aria-hidden="true">✓</i><small>Total solved</small><strong>{totalSolved}</strong></span>
        </div>
      </div>
      <span className="card-arrow" aria-hidden="true">→</span>
    </article>
  )
}

export default UserCard
