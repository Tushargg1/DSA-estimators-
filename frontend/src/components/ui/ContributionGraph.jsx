import { useMemo, useState } from 'react'

const DAYS_IN_WEEK = 7
const WEEKS_TO_SHOW = 53
const CELL_SIZE = 11
const CELL_GAP = 3
const MONTH_LABELS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const DAY_LABELS = ['', 'Mon', '', 'Wed', '', 'Fri', '']

function getLevel(count, max) {
  if (!count || count === 0) return 0
  if (max <= 0) return 1
  const ratio = count / max
  if (ratio <= 0.25) return 1
  if (ratio <= 0.5) return 2
  if (ratio <= 0.75) return 3
  return 4
}

function buildCalendar(data, year) {
  const end = new Date(year, 11, 31)
  const start = new Date(end)
  start.setDate(end.getDate() - (WEEKS_TO_SHOW * 7 - 1))

  const dayMap = {}
  for (const entry of data) {
    dayMap[entry.date] = (dayMap[entry.date] || 0) + entry.count
  }

  const weeks = []
  let current = new Date(start)
  // Align to Sunday
  current.setDate(current.getDate() - current.getDay())

  while (current <= end || weeks.length < WEEKS_TO_SHOW) {
    const week = []
    for (let d = 0; d < DAYS_IN_WEEK; d++) {
      const dateStr = current.toISOString().slice(0, 10)
      const count = dayMap[dateStr] || 0
      const isInRange = current >= start && current <= end
      week.push({ date: dateStr, count, isInRange })
      current.setDate(current.getDate() + 1)
    }
    weeks.push(week)
    if (weeks.length >= WEEKS_TO_SHOW) break
  }

  return weeks
}

function getMonthLabels(weeks) {
  const labels = []
  let lastMonth = -1
  for (let w = 0; w < weeks.length; w++) {
    const firstDay = weeks[w].find((d) => d.isInRange)
    if (!firstDay) continue
    const month = new Date(firstDay.date).getMonth()
    if (month !== lastMonth) {
      labels.push({ week: w, label: MONTH_LABELS[month] })
      lastMonth = month
    }
  }
  return labels
}

function ContributionGraph({
  data = [],
  year,
  title = 'contributions',
  colorScheme = 'green',
  totalLabel,
  onYearChange,
  availableYears = [],
}) {
  const currentYear = new Date().getFullYear()
  const selectedYear = year || currentYear
  const [tooltip, setTooltip] = useState(null)

  const weeks = useMemo(() => buildCalendar(data, selectedYear), [data, selectedYear])
  const monthLabels = useMemo(() => getMonthLabels(weeks), [weeks])
  const totalCount = useMemo(() => data.reduce((sum, d) => sum + d.count, 0), [data])
  const maxCount = useMemo(() => Math.max(...data.map((d) => d.count), 1), [data])

  const colors = {
    green: ['#161b22', '#0e4429', '#006d32', '#26a641', '#39d353'],
    purple: ['#161b22', '#2d1b4e', '#462278', '#7c3aed', '#a78bfa'],
    blue: ['#161b22', '#0a3069', '#0550ae', '#2f81f7', '#79c0ff'],
  }

  const palette = colors[colorScheme] || colors.green
  const svgWidth = WEEKS_TO_SHOW * (CELL_SIZE + CELL_GAP) + 36
  const svgHeight = DAYS_IN_WEEK * (CELL_SIZE + CELL_GAP) + 28

  return (
    <div className="contribution-graph">
      <div className="contribution-header">
        <h4>{totalCount.toLocaleString()} {totalLabel || title} in {selectedYear === currentYear ? 'the last year' : selectedYear}</h4>
        {availableYears.length > 0 && (
          <div className="contribution-year-select">
            {availableYears.map((y) => (
              <button
                key={y}
                type="button"
                className={`contribution-year-btn${y === selectedYear ? ' active' : ''}`}
                onClick={() => onYearChange?.(y)}
              >
                {y}
              </button>
            ))}
          </div>
        )}
      </div>

      <div className="contribution-chart-wrapper">
        <svg
          width={svgWidth}
          height={svgHeight}
          className="contribution-svg"
          role="img"
          aria-label={`${title} activity chart`}
        >
          {/* Month labels */}
          {monthLabels.map(({ week, label }) => (
            <text
              key={`${label}-${week}`}
              x={week * (CELL_SIZE + CELL_GAP) + 36}
              y={10}
              className="contribution-month-label"
            >
              {label}
            </text>
          ))}

          {/* Day labels */}
          {DAY_LABELS.map((label, i) => label && (
            <text
              key={`day-${i}`}
              x={12}
              y={i * (CELL_SIZE + CELL_GAP) + 28 + CELL_SIZE - 2}
              className="contribution-day-label"
              textAnchor="end"
            >
              {label}
            </text>
          ))}

          {/* Cells */}
          {weeks.map((week, wi) =>
            week.map((day, di) => {
              if (!day.isInRange) return null
              const level = getLevel(day.count, maxCount)
              return (
                <rect
                  key={day.date}
                  x={wi * (CELL_SIZE + CELL_GAP) + 36}
                  y={di * (CELL_SIZE + CELL_GAP) + 18}
                  width={CELL_SIZE}
                  height={CELL_SIZE}
                  rx={2}
                  ry={2}
                  fill={palette[level]}
                  className="contribution-cell"
                  onMouseEnter={(e) => setTooltip({ x: e.clientX, y: e.clientY, date: day.date, count: day.count })}
                  onMouseLeave={() => setTooltip(null)}
                />
              )
            })
          )}
        </svg>

        {tooltip && (
          <div
            className="contribution-tooltip"
            style={{ left: tooltip.x, top: tooltip.y - 40 }}
          >
            <strong>{tooltip.count} {title}</strong> on {new Date(tooltip.date + 'T00:00:00').toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })}
          </div>
        )}
      </div>

      <div className="contribution-legend">
        <span>Less</span>
        {palette.map((color, i) => (
          <span key={i} className="contribution-legend-cell" style={{ background: color }} />
        ))}
        <span>More</span>
      </div>
    </div>
  )
}

export default ContributionGraph
