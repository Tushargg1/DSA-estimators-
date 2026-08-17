function StreakFlame({ streak = 0, size = 'md' }) {
  const intensity = Math.min(streak, 30) / 30 // 0-1 scale, maxes at 30 days
  const sizes = { sm: 24, md: 36, lg: 52 }
  const px = sizes[size] || sizes.md

  if (streak === 0) {
    return (
      <span className="streak-flame streak-dead" style={{ width: px, height: px }}>
        <svg viewBox="0 0 24 24" fill="none" width={px * 0.6} height={px * 0.6}>
          <path d="M12 2C6.5 9.5 3 13 3 16a9 9 0 0 0 18 0c0-3-3.5-6.5-9-14Z" fill="#3a3f4b" opacity="0.5" />
        </svg>
      </span>
    )
  }

  return (
    <span
      className={`streak-flame streak-active`}
      style={{ width: px, height: px }}
      title={`${streak} day streak`}
    >
      <svg viewBox="0 0 24 24" fill="none" width={px * 0.65} height={px * 0.65}>
        <defs>
          <linearGradient id={`flame-grad-${streak}`} x1="0" y1="1" x2="0" y2="0">
            <stop offset="0%" stopColor="#ff4500" />
            <stop offset="50%" stopColor="#ff8c00" />
            <stop offset="100%" stopColor="#ffd700" />
          </linearGradient>
        </defs>
        <path
          d="M12 2C6.5 9.5 3 13 3 16a9 9 0 0 0 18 0c0-3-3.5-6.5-9-14Z"
          fill={`url(#flame-grad-${streak})`}
        />
        <path
          d="M12 9c-2 4-4 6-4 8a4 4 0 0 0 8 0c0-2-2-4-4-8Z"
          fill="#fff3"
        />
      </svg>
      <span
        className="streak-flame-glow"
        style={{ opacity: 0.15 + intensity * 0.45, transform: `scale(${0.8 + intensity * 0.5})` }}
      />
    </span>
  )
}

export default StreakFlame
