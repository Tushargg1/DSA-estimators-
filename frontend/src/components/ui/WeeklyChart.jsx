import { useMemo } from 'react'
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell } from 'recharts'

const DAY_NAMES = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

function getWeekDates(offset = 0) {
  const now = new Date()
  const startOfWeek = new Date(now)
  startOfWeek.setDate(now.getDate() - now.getDay() - offset * 7)
  return Array.from({ length: 7 }, (_, i) => {
    const d = new Date(startOfWeek)
    d.setDate(startOfWeek.getDate() + i)
    return d.toISOString().slice(0, 10)
  })
}

function istDateKey(value) {
  if (!value) return null
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date(value))
  const part = (type) => parts.find((item) => item.type === type)?.value
  return `${part('year')}-${part('month')}-${part('day')}`
}

function WeeklyChart({ submissions = [] }) {
  const data = useMemo(() => {
    const thisWeekDates = getWeekDates(0)
    const lastWeekDates = getWeekDates(1)

    const countByDate = {}
    for (const sub of submissions) {
      const date = istDateKey(sub.solvedAtUtc)
      if (date) countByDate[date] = (countByDate[date] || 0) + 1
    }

    return thisWeekDates.map((date, i) => ({
      day: DAY_NAMES[i],
      thisWeek: countByDate[date] || 0,
      lastWeek: countByDate[lastWeekDates[i]] || 0,
    }))
  }, [submissions])

  const thisWeekTotal = data.reduce((s, d) => s + d.thisWeek, 0)
  const lastWeekTotal = data.reduce((s, d) => s + d.lastWeek, 0)
  const change = lastWeekTotal > 0 ? Math.round(((thisWeekTotal - lastWeekTotal) / lastWeekTotal) * 100) : thisWeekTotal > 0 ? 100 : 0

  return (
    <div className="weekly-chart">
      <div className="weekly-chart-header">
        <div>
          <strong>Weekly Progress</strong>
          <small>This week vs last week</small>
        </div>
        <div className="weekly-chart-stats">
          <span className="weekly-total"><strong>{thisWeekTotal}</strong> this week</span>
          {change !== 0 && (
            <span className={`weekly-change ${change >= 0 ? 'positive' : 'negative'}`}>
              {change >= 0 ? '\u2191' : '\u2193'} {Math.abs(change)}%
            </span>
          )}
        </div>
      </div>
      <ResponsiveContainer width="100%" height={160}>
        <BarChart data={data} barGap={2} barCategoryGap="20%">
          <XAxis dataKey="day" axisLine={false} tickLine={false} tick={{ fill: '#7f899d', fontSize: 11 }} />
          <YAxis hide />
          <Tooltip
            contentStyle={{
              background: 'rgba(13, 17, 28, 0.95)',
              border: '1px solid rgba(188, 200, 226, 0.12)',
              borderRadius: '8px',
              fontSize: '12px',
            }}
            cursor={{ fill: 'rgba(139, 124, 255, 0.06)' }}
          />
          <Bar dataKey="lastWeek" radius={[3, 3, 0, 0]} opacity={0.3}>
            {data.map((_, i) => <Cell key={i} fill="#7f899d" />)}
          </Bar>
          <Bar dataKey="thisWeek" radius={[4, 4, 0, 0]}>
            {data.map((entry, i) => <Cell key={i} fill={entry.thisWeek > entry.lastWeek ? '#45d49a' : '#8b7cff'} />)}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

export default WeeklyChart
