import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from 'recharts'

const COLORS = { Easy: '#45d49a', Medium: '#f1b75b', Hard: '#ff7485' }

function DifficultyDonut({ submissions = [] }) {
  const data = ['Easy', 'Medium', 'Hard'].map((level) => ({
    name: level,
    value: submissions.filter((s) => s.difficulty === level).length,
  })).filter((d) => d.value > 0)

  const total = data.reduce((sum, d) => sum + d.value, 0)

  if (total === 0) return null

  return (
    <div className="difficulty-donut">
      <div className="donut-chart">
        <ResponsiveContainer width={140} height={140}>
          <PieChart>
            <Pie
              data={data}
              cx="50%"
              cy="50%"
              innerRadius={42}
              outerRadius={62}
              paddingAngle={3}
              dataKey="value"
              strokeWidth={0}
            >
              {data.map((entry) => (
                <Cell key={entry.name} fill={COLORS[entry.name]} />
              ))}
            </Pie>
            <Tooltip
              contentStyle={{
                background: 'rgba(13, 17, 28, 0.95)',
                border: '1px solid rgba(188, 200, 226, 0.12)',
                borderRadius: '8px',
                fontSize: '12px',
              }}
            />
          </PieChart>
        </ResponsiveContainer>
        <span className="donut-center">{total}</span>
      </div>
      <div className="donut-legend">
        {data.map((d) => (
          <div key={d.name} className="donut-legend-item">
            <span className="donut-dot" style={{ background: COLORS[d.name] }} />
            <span className="donut-label">{d.name}</span>
            <strong>{d.value}</strong>
            <small>{Math.round((d.value / total) * 100)}%</small>
          </div>
        ))}
      </div>
    </div>
  )
}

export default DifficultyDonut
