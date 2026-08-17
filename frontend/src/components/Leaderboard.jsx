import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, getAuthToken } from '../api/client.js'
import useGroupSocket from '../hooks/useGroupSocket.js'
import UserCard from './UserCard.jsx'
import SyncStatus from './SyncStatus.jsx'
import Confetti from './ui/Confetti.jsx'
import { useToast } from './ui/Toast.jsx'

function Leaderboard({ groupId, tokenVersion, onOpenProfile, onMembersChange }) {
  const [profiles, setProfiles] = useState({})
  const [refreshKey, setRefreshKey] = useState(0)
  const [showConfetti, setShowConfetti] = useState(false)
  const knownIdsRef = useRef(new Set())
  const resyncRef = useRef(null)
  const { addToast } = useToast()

  const onDelta = useCallback((delta) => {
    setRefreshKey((value) => value + 1)
    const id = delta?.userId
    if (id != null && !knownIdsRef.current.has(id)) {
      void resyncRef.current?.().catch(() => {})
    }
    // Celebrate when someone hits their target
    if (delta && delta.target > 0 && delta.newDailyCount >= delta.target) {
      const name = delta.userName || 'A member'
      addToast(`${name} hit their daily target! \u{1F389}`, { type: 'success', duration: 5000 })
      setShowConfetti(true)
      setTimeout(() => setShowConfetti(false), 3500)
    } else if (delta && delta.newDailyCount > 0) {
      const name = delta.userName || 'Someone'
      addToast(`${name} solved a problem \u{1F4AA}`, { type: 'info', duration: 3000 })
    }
  }, [addToast])

  const { connected, connectionGeneration, leaderboard, resync, error: socketError } =
    useGroupSocket(groupId, getAuthToken(), onDelta, tokenVersion)

  useEffect(() => { resyncRef.current = resync }, [resync])
  useEffect(() => {
    if (connectionGeneration > 0) setRefreshKey((value) => value + 1)
  }, [connectionGeneration])

  const members = useMemo(() => leaderboard?.members ?? [], [leaderboard])
  const memberIds = useMemo(() => members.map((member) => member.userId).join(','), [members])
  useEffect(() => { knownIdsRef.current = new Set(members.map((member) => member.userId)) }, [members])
  useEffect(() => { onMembersChange?.(members) }, [members, onMembersChange])
  const sortedMembers = useMemo(
    () => [...members].sort((a, b) => (b.todayCount ?? 0) - (a.todayCount ?? 0)),
    [members],
  )

  useEffect(() => {
    if (!memberIds) {
      setProfiles({})
      return undefined
    }
    const controller = new AbortController()
    Promise.allSettled(members.map((member) => api.getUser(member.userId, controller.signal)))
      .then((results) => {
        if (controller.signal.aborted) return
        const next = {}
        results.forEach((result) => {
          if (result.status === 'fulfilled') next[result.value.id] = result.value
        })
        setProfiles(next)
      })
    return () => controller.abort()
  }, [memberIds])

  const retry = async () => {
    try {
      await resync()
      setRefreshKey((value) => value + 1)
    } catch { /* Hook renders the request error. */ }
  }

  if (groupId == null) return <p className="leaderboard-empty">No group selected.</p>

  return (
    <section className="leaderboard" aria-busy={!leaderboard && !socketError}>
      <Confetti active={showConfetti} />
      <header className="leaderboard-header">
        <div>
          <span className="eyebrow">Today’s standings</span>
          <h2>{leaderboard?.groupName ?? 'Leaderboard'}</h2>
          <p>Select any member to see linked platforms and their complete solved-question history.</p>
        </div>
        <span className={`conn-dot ${connected ? 'conn-live' : 'conn-offline'}`} role="status">
          <span aria-hidden="true" />{connected ? 'Live updates' : 'Reconnecting'}
        </span>
      </header>
      <SyncStatus refreshKey={refreshKey} />
      {socketError && <div className="leaderboard-error" role="alert">
        <span>{socketError.message || 'Live updates are unavailable.'}</span>
        <button type="button" className="button-secondary" onClick={retry}>Retry</button>
      </div>}
      {!leaderboard && !socketError ? <div className="leaderboard-loading"><span className="button-spinner" aria-hidden="true" />Loading leaderboard…</div> : sortedMembers.length === 0 ? (
        <div className="leaderboard-empty"><span aria-hidden="true">◇</span><strong>No activity yet</strong><p>Solved problems will appear here after the next sync.</p></div>
      ) : <ol className="leaderboard-list">
        {sortedMembers.map((member, index) => <li key={member.userId}>
          <button type="button" className="leaderboard-card-button" onClick={() => onOpenProfile(member)}>
            <UserCard member={member} rank={index + 1} profile={profiles[member.userId]} />
          </button>
        </li>)}
      </ol>}
    </section>
  )
}

export default Leaderboard