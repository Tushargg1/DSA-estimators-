import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api/client.js'

function minutesSince(status, now, fetchedAt) {
  if (status.lastSuccessAt) {
    const timestamp = new Date(status.lastSuccessAt).getTime()
    if (!Number.isNaN(timestamp)) return Math.max(0, Math.floor((now - timestamp) / 60000))
  }
  if (typeof status.minutesSinceLastSuccess === 'number') {
    return status.minutesSinceLastSuccess + Math.max(0, Math.floor((now - fetchedAt) / 60000))
  }
  return null
}

function describe(status, now, fetchedAt) {
  const minutes = minutesSince(status, now, fetchedAt)
  if (minutes == null) return { text: 'never synced', tone: 'stale' }
  const failed = status.lastFailureAt && (!status.lastSuccessAt
    || new Date(status.lastFailureAt) > new Date(status.lastSuccessAt))
  const age = minutes === 0 ? 'last synced just now' : `last synced ${minutes} min ago`
  if (failed) return { text: `${age} · sync failing`, tone: 'error', detail: status.lastFailureReason }
  return { text: age, tone: minutes > 15 ? 'stale' : 'ok' }
}

function SyncStatus({ intervalMs = 45000, refreshKey = 0 }) {
  const [statuses, setStatuses] = useState([])
  const [error, setError] = useState(null)
  const [fetchedAt, setFetchedAt] = useState(Date.now())
  const [now, setNow] = useState(Date.now())
  const mounted = useRef(true)
  const inFlight = useRef(false)
  const queued = useRef(false)
  const generation = useRef(0)

  const load = useCallback(async () => {
    if (inFlight.current) {
      generation.current += 1
      queued.current = true
      return
    }
    inFlight.current = true
    const request = ++generation.current
    try {
      const data = await api.getPollStatus()
      if (mounted.current && request === generation.current) {
        setStatuses(Array.isArray(data) ? data : [])
        setFetchedAt(Date.now())
        setNow(Date.now())
        setError(null)
      }
    } catch (requestError) {
      if (mounted.current && request === generation.current) setError(requestError.message)
    } finally {
      inFlight.current = false
      if (queued.current) {
        queued.current = false
        void load()
      }
    }
  }, [])

  useEffect(() => {
    mounted.current = true
    void load()
    const polling = setInterval(load, intervalMs)
    const ticking = setInterval(() => setNow(Date.now()), 60000)
    const visible = () => { if (document.visibilityState === 'visible') void load() }
    document.addEventListener('visibilitychange', visible)
    return () => {
      mounted.current = false
      generation.current += 1
      clearInterval(polling)
      clearInterval(ticking)
      document.removeEventListener('visibilitychange', visible)
    }
  }, [intervalMs, load])

  useEffect(() => {
    if (refreshKey > 0) void load()
  }, [refreshKey, load])

  return (
    <section className="sync-status" aria-label="Platform sync status">
      <div className="sync-status-heading">
        <span className="sync-status-label">Platform sync</span>
        <span className="sync-status-note">Status checks about every minute</span>
      </div>
      {error && <div className="sync-status-error" role="alert"><span aria-hidden="true">!</span>{error}</div>}
      <ul className="sync-status-list">
        {statuses.map((status) => {
          const item = describe(status, now, fetchedAt)
          return <li key={status.platform} className={`sync-chip sync-${item.tone}`} title={item.detail}>
            <span className="sync-dot" aria-hidden="true" />
            <span><strong>{status.platform}</strong><small>{item.text}</small></span>
          </li>
        })}
        {!error && statuses.length === 0 && <li className="sync-chip sync-stale"><span className="sync-dot" aria-hidden="true" /><span><strong>Waiting for sync</strong><small>No platform data yet</small></span></li>}
      </ul>
    </section>
  )
}

export default SyncStatus
