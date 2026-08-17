import { AreaChart, Area, ResponsiveContainer, Tooltip } from 'recharts'

function MiniChart({ data = [], dataKey = 'value', color = '#8b7cff', height = 60 }) {
  if (!data.length) return null

  return (
    <div className="w-full" style={{ height }}>
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 4, right: 0, bottom: 0, left: 0 }}>
          <defs>
            <linearGradient id={`gradient-${color.replace('#', '')}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={color} stopOpacity={0.3} />
              <stop offset="95%" stopColor={color} stopOpacity={0} />
            </linearGradient>
          </defs>
          <Tooltip
            contentStyle={{
              background: 'rgba(13, 17, 28, 0.95)',
              border: '1px solid rgba(188, 200, 226, 0.12)',
              borderRadius: '8px',
              fontSize: '12px',
              boxShadow: '0 8px 24px rgba(0,0,0,0.3)',
            }}
            labelStyle={{ color: '#7f899d', fontSize: '11px' }}
            itemStyle={{ color: '#f4f7fb' }}
          />
          <Area
            type="monotone"
            dataKey={dataKey}
            stroke={color}
            strokeWidth={2}
            fill={`url(#gradient-${color.replace('#', '')})`}
            dot={false}
            activeDot={{ r: 3, fill: color, stroke: '#fff', strokeWidth: 1.5 }}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}

export default MiniChart
