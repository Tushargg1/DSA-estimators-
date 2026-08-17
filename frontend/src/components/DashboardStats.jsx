import { useMemo } from 'react'
import { Users, Target, Flame, Trophy } from 'lucide-react'
import StatCard from './ui/StatCard.jsx'

function DashboardStats({ members = [], groupName }) {
  const stats = useMemo(() => {
    const total = members.length
    const solvedToday = members.reduce((sum, m) => sum + (m.todayCount ?? 0), 0)
    const hitTarget = members.filter((m) => (m.dailyTarget ?? 0) > 0 && (m.todayCount ?? 0) >= m.dailyTarget).length
    const bestStreak = members.reduce((max, m) => Math.max(max, m.currentStreak ?? 0), 0)
    const topSolver = [...members].sort((a, b) => (b.todayCount ?? 0) - (a.todayCount ?? 0))[0]
    const completionRate = total > 0 ? Math.round((hitTarget / total) * 100) : 0
    return { total, solvedToday, hitTarget, bestStreak, topSolver, completionRate }
  }, [members])

  return (
    <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
      <StatCard
        icon={<Users size={18} />}
        label="Members"
        value={stats.total}
        subtitle={groupName ? `in ${groupName}` : 'active members'}
        accent="brand"
      />
      <StatCard
        icon={<Target size={18} />}
        label="Solved today"
        value={stats.solvedToday}
        subtitle={`${stats.hitTarget}/${stats.total} hit target`}
        accent="aqua"
      />
      <StatCard
        icon={<Trophy size={18} />}
        label="Target rate"
        value={`${stats.completionRate}%`}
        subtitle="reached daily goal"
        accent="success"
      />
      <StatCard
        icon={<Flame size={18} />}
        label="Top streak"
        value={`${stats.bestStreak}d`}
        subtitle={stats.topSolver?.userName ? `led by ${stats.topSolver.userName}` : 'no streak yet'}
        accent="warning"
      />
    </div>
  )
}

export default DashboardStats
