import { useEffect, useState } from 'react'
import { api } from '../api/client.js'

/**
 * SyncStatus (task 10.6, Requirement 7.3).
 *
 * Polls GET /api/status/poll on an interval and shows, per platform, how long
 * ago the last successful sync was ("last synced X min ago"). Sourced from
 * PollStatus rows:
 *   { platform, lastSuccessAt, lastFailureAt, lastFailureReason,
 *     minutesSinceLastSuccess }
 *
 * Requirement 7.3: the UI must NOT imply true real-time. This component makes
 * the 5-minute polling model explicit by surfacing the sync age per platform,
 * and flags platforms that have never synced or recently failed.
 *
 * @param {{ intervalMs?: number }} props - poll cadence (default 45s).
 */

// Prefer the server-computed age; fall back to computing from the timestamp so
// the indicator still works if the backend omits minutesSinceLastSuccess.
function minutesSince(status) {
  if (typeof status.minutesSinceLastSuccess === 'number') {
    return status.minutesSinceLastSuccess
  }
  if (status.lastSuccessAt) {
    const then = new Date(status.lastSuccessAt).getTime()
    if (!Number.isNaN(then)) {
      return Math.max(0, Math.floor((Date.now() - then) / 60000))
    }
  }
  return null
}

function describe(status) {
  const mins = minutesSince(status)
  if (mins == null) {
    return { text: 'never synced', tone: 'stale' }
  }
  // A failure newer than the last success means the platform is currently
  // broken even if it synced successfully earlier.
  const failedRecently =
    status.lastFailureAt &&
    (!status.lastSuccessAt ||
      new Date(status.lastFailureAt).getTime() >
        new Date(status.lastSuccessAt).getTime())

  const agoText =
    mins <= 0 ? 'last synced just now' : `last synced ${mins} min ago`

  if (failedRecently) {
    return {
      text: `${agoText} · sync failing`,
      tone: 'error',
      detail: status.lastFailureReason ?? undefined,
    }
  }
  // 5-minute polling: anything much older than a cycle or two is worth flagging.
  const tone = mins > 15 ? 'stale' : 'ok'
  return { text: agoText, tone }
}

function SyncStatus({ intervalMs = 45000 }) {
  const [statuses, setStatuses] = useState([])
  const [error, setError] = useState(null)

  useEffect(() => {
    let cancelled = false

    const load = async () => {
      try {
        const data = await api.getPollStatus()
        if (!cancelled) {
          setStatuses(Array.isArray(data) ? data : [])
          setError(null)
        }
      } catch (err) {
        if (!cancelled) setError(err.message ?? 'Failed to load sync status')
      }
    }

    void load()
    const id = setInterval(load, intervalMs)
    return () => {
      cancelled = true
      clearInterval(id)
    }
  }, [intervalMs])

  return (
    <section className="sync-status" aria-label="Platform sync status">
      <span className="sync-status-label">Sync status</span>
      {error && <span className="sync-status-error">{error}</span>}
      <ul className="sync-status-list">
        {statuses.map((s) => {
          const { text, tone, detail } = describe(s)
          return (
            <li
              key={s.platform}
              className={`sync-chip sync-${tone}`}
              title={detail}
            >
              <strong>{s.platform}</strong>: {text}
            </li>
          )
        })}
        {!error && statuses.length === 0 && (
          <li className="sync-chip sync-stale">No sync data yet</li>
        )}
      </ul>
      <p className="sync-status-note">
        Updates are polled every ~5 minutes, not real-time.
      </p>
    </section>
  )
}

export default SyncStatus
