function UserCard({ member, rank, profile }) {
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
  const linked = [
    profile?.leetcodeUsername && ['LC', profile.leetcodeUsername],
    profile?.codeforcesUsername && ['CF', profile.codeforcesUsername],
    profile?.gfgUsername && ['GFG', profile.gfgUsername],
  ].filter(Boolean)

  return (
    <article className={`user-card${hitTarget ? ' hit' : ''}`}>
      <div className={`rank-badge rank-${Math.min(rank, 3)}`} aria-label={`Rank ${rank}`}>{rank}</div>
      <div className="user-card-content">
        <div className="user-card-header">
          <div>
            <span className="user-name">{userName}</span>
            <small>{hitTarget ? 'Daily target complete' : 'Today’s progress'}</small>
            <span className="member-platforms" aria-label={`${userName}'s linked platforms`}>
              {linked.map(([name, username]) => <span key={name} title={`${name}: ${username}`}>{name}<em>@{username}</em></span>)}
              {profile && linked.length === 0 && <span className="no-platforms">No linked platforms</span>}
            </span>
          </div>
          <span className="today-count"><strong>{todayCount}</strong><small> / {dailyTarget}</small></span>
        </div>
        <div className="progress" role="progressbar" aria-valuenow={todayCount} aria-valuemin={0}
          aria-valuemax={Math.max(dailyTarget, todayCount, 1)} aria-label={`${userName} progress: ${todayCount} of ${dailyTarget}`}>
          <div className="progress-bar" style={{ width: `${pct}%` }} />
        </div>
        <div className="user-card-stats">
          <span><small>Current streak</small><strong>{currentStreak} days</strong></span>
          <span><small>Personal best</small><strong>{longestStreak} days</strong></span>
          <span><small>Total solved</small><strong>{totalSolved}</strong></span>
        </div>
      </div>
      <span className="card-arrow" aria-hidden="true">→</span>
      <span className="visually-hidden">View {userName}&apos;s profile and complete solve history</span>
    </article>
  )
}

export default UserCard
