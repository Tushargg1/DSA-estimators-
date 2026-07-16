import { useEffect, useMemo, useState } from 'react'
import { api } from '../api/client.js'

const istToday = () => {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date())
  const value = (type) => parts.find((part) => part.type === type)?.value
  return `${value('year')}-${value('month')}-${value('day')}`
}
const formatAnchor = (value, period) => new Intl.DateTimeFormat(undefined,
  period === 'YEAR' ? { year: 'numeric', timeZone: 'UTC' } : { month: 'long', year: 'numeric', timeZone: 'UTC' })
  .format(new Date(`${value}T00:00:00Z`))

function shiftAnchor(value, period, delta) {
  const date = new Date(`${value}T00:00:00Z`)
  if (period === 'YEAR') date.setUTCFullYear(date.getUTCFullYear() + delta, 0, 1)
  else date.setUTCMonth(date.getUTCMonth() + delta, 1)
  return date.toISOString().slice(0, 10)
}

function ActivityHeatmap({ groupId, userId }) {
  const [period, setPeriod] = useState('MONTH')
  const [anchor, setAnchor] = useState(istToday)
  const [activity, setActivity] = useState(null)
  const [error, setError] = useState(null)
  const today = istToday()
  const atCurrentPeriod = period === 'YEAR'
    ? anchor.slice(0, 4) >= today.slice(0, 4)
    : anchor.slice(0, 7) >= today.slice(0, 7)

  useEffect(() => {
    if (!groupId || !userId) return undefined
    const controller = new AbortController()
    setActivity(null)
    setError(null)
    api.getMemberActivity(groupId, userId, { period, anchor }, controller.signal)
      .then(setActivity)
      .catch((requestError) => {
        if (requestError.name !== 'AbortError') setError(requestError.message)
      })
    return () => controller.abort()
  }, [groupId, userId, period, anchor])

  const cells = useMemo(() => {
    if (!activity?.days?.length) return []
    const offset = new Date(`${activity.days[0].date}T00:00:00Z`).getUTCDay()
    return [...Array(offset).fill(null), ...activity.days]
  }, [activity])

  const changePeriod = (nextPeriod) => {
    setPeriod(nextPeriod)
    setAnchor(istToday())
  }

  return (
    <section className="activity-map" aria-labelledby="activity-map-title">
      <header>
        <div><span className="eyebrow">Group consistency</span><h3 id="activity-map-title">Target activity map</h3>
          <p>Only days since this member joined the group are scored.</p></div>
        <div className="heatmap-tabs" role="group" aria-label="Activity period">
          {['MONTH', 'YEAR'].map((value) => <button type="button" key={value} className="button-secondary"
            aria-pressed={period === value} onClick={() => changePeriod(value)}>{value === 'MONTH' ? 'Monthly' : 'Yearly'}</button>)}
        </div>
      </header>
      <div className="heatmap-toolbar">
        <button type="button" className="button-quiet" onClick={() => setAnchor((value) => shiftAnchor(value, period, -1))} aria-label="Previous period">←</button>
        <strong>{formatAnchor(anchor, period)}</strong>
        <button type="button" className="button-quiet" disabled={atCurrentPeriod}
          onClick={() => setAnchor((value) => shiftAnchor(value, period, 1))} aria-label="Next period">→</button>
        {activity && <span>Target <strong>{activity.target}</strong> · {activity.targetMode === 'AUTO' ? 'Automatic' : 'Member vote'}</span>}
      </div>
      {error ? <div className="leaderboard-error" role="alert">{error}</div> : !activity ? <div className="heatmap-loading"><span className="button-spinner" />Loading activity…</div> : <>
        <div className={`heatmap-grid heatmap-${period.toLowerCase()}`} role="img" aria-label={`${period.toLowerCase()} target activity`}>
          {cells.map((day, index) => day ? <span key={day.date} className={`heatmap-day status-${day.status.toLowerCase()}`}
            title={`${day.date}: ${day.count} solved, target ${activity.target} — ${day.status.toLowerCase().replace('_', ' ')}`}
            aria-label={`${day.date}, ${day.count} solved, ${day.status.toLowerCase().replace('_', ' ')}`} />
            : <span className="heatmap-day heatmap-blank" key={`blank-${index}`} />)}
        </div>
        <div className="heatmap-legend"><span><i className="status-achieved" />Target achieved</span><span><i className="status-missed" />Target missed</span><span><i className="status-in_progress" />Today in progress</span><span><i className="status-not_applicable" />Not in group</span></div>
        <small className="heatmap-joined">Member since {activity.joinedOn}</small>
      </>}
    </section>
  )
}

export default ActivityHeatmap