import { useMemo } from 'react'
import { BarChart, Bar, XAxis, Tooltip, ResponsiveContainer, Cell } from 'recharts'

function getHourIST(utcString) {
  if (!utcString) return null
  const d = new Date(utcString)
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Kolkata', hour: 'numeric', hour12: false,
  }).formatToParts(d)
  const hour = parts.find((p) => p.type === 'hour')?.value
  return hour != null ? parseInt(hour) : null
}

const PERIODS = [
  { label: '12a-6a', range: [0, 5], emoji: '\u{1F319}' },
  { label: '6a-12p', range: [6, 11], emoji: '\u{1F305}' },
  { label: '12p-6p', range: [12, 17], emoji: '\u2600\uFE0F' },
  { label: '6p-12a', range: [18, 23], emoji: '\u{1F303}' },
]

function SolveTimeChart({ submissions = [] }) {
  const data = useMemo(() => {
    const hours = new Array(24).fill(0)
    for (const s of submissions) {
      const h = getHourIST(s.solvedAtUtc)
      if (h != null) hours[h]++
    }

    return PERIODS.map((p) => {
      let count = 0
      for (let h = p.range[0]; h <= p.range[1]; h++) count += hours[h]
      return { ...p, count }
    })
  }, [submissions])

  const total = data.reduce((s, d) => s + d.count, 0)
  if (total === 0) return null

  const peak = data.reduce((best, d) => d.count > best.count ? d : best, data[0])
  const colors = ['#6366f1', '#f59e0b', '#ef4444', '#8b5cf6']

  return (
    <div className="solve-time-chart">
      <div className="solve-time-header">
        <strong>Solve Time Pattern</strong>
        <small>Most active: {peak.emoji} {peak.label} IST</small>
      </div>
      <ResponsiveContainer width="100%" height={100}>
        <BarChart data={data} barCategoryGap="25%">
          <XAxis dataKey="label" axisLine={false} tickLine={false} tick={{ fill: '#7f899d', fontSize: 10 }} />
          <Tooltip
            contentStyle={{
              background: 'rgba(13, 17, 28, 0.95)',
              border: '1px solid rgba(188, 200, 226, 0.12)',
              borderRadius: '8px',
              fontSize: '12px',
            }}
            formatter={(value) => [`${value} solved`, 'Problems']}
          />
          <Bar dataKey="count" radius={[5, 5, 0, 0]}>
            {data.map((_, i) => <Cell key={i} fill={colors[i]} />)}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

export default SolveTimeChart
