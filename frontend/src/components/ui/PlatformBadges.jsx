function PlatformBadges({ submissions = [] }) {
  const counts = submissions.reduce((acc, s) => {
    const platform = (s.platform || 'UNKNOWN').toUpperCase()
    acc[platform] = (acc[platform] || 0) + 1
    return acc
  }, {})

  const platforms = [
    { key: 'LEETCODE', label: 'LeetCode', short: 'LC', color: '#f89f1b', bg: 'rgba(248,159,27,0.1)', border: 'rgba(248,159,27,0.25)' },
    { key: 'CODEFORCES', label: 'Codeforces', short: 'CF', color: '#318ce7', bg: 'rgba(49,140,231,0.1)', border: 'rgba(49,140,231,0.25)' },
    { key: 'GFG', label: 'GeeksforGeeks', short: 'GFG', color: '#45d49a', bg: 'rgba(69,212,154,0.1)', border: 'rgba(69,212,154,0.25)' },
  ]

  const active = platforms.filter((p) => counts[p.key] > 0)
  if (active.length === 0) return null

  return (
    <div className="platform-badges">
      {active.map((p) => (
        <span
          key={p.key}
          className="platform-badge"
          style={{ color: p.color, background: p.bg, borderColor: p.border }}
          title={`${counts[p.key]} problems on ${p.label}`}
        >
          <strong>{p.short}</strong>
          <span>{counts[p.key]}</span>
        </span>
      ))}
    </div>
  )
}

export default PlatformBadges
