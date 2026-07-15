import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, getAuthToken } from '../api/client.js'
import useGroupSocket from '../hooks/useGroupSocket.js'
import UserCard from './UserCard.jsx'
import ProblemDetailModal from './ProblemDetailModal.jsx'
import SyncStatus from './SyncStatus.jsx'

function Leaderboard({ groupId, tokenVersion }) {
  const [selected, setSelected] = useState(null)
  const [loadingUserId, setLoadingUserId] = useState(null)
  const [cardError, setCardError] = useState(null)
  const [refreshKey, setRefreshKey] = useState(0)
  const knownIdsRef = useRef(new Set())
  const resyncRef = useRef(null)
  const detailRequest = useRef({ generation: 0, controller: null })

  const onDelta = useCallback((delta) => {
    setRefreshKey((value) => value + 1)
    const id = delta?.userId
    if (id != null && !knownIdsRef.current.has(id)) {
      void resyncRef.current?.().catch(() => {})
    }
  }, [])

  const { connected, connectionGeneration, leaderboard, resync, error: socketError } =
    useGroupSocket(groupId, getAuthToken(), onDelta, tokenVersion)

  useEffect(() => { resyncRef.current = resync }, [resync])
  useEffect(() => {
    if (connectionGeneration > 0) setRefreshKey((value) => value + 1)
  }, [connectionGeneration])

  const members = useMemo(() => leaderboard?.members ?? [], [leaderboard])
  useEffect(() => { knownIdsRef.current = new Set(members.map((member) => member.userId)) }, [members])
  const sortedMembers = useMemo(
    () => [...members].sort((a, b) => (b.todayCount ?? 0) - (a.todayCount ?? 0)),
    [members],
  )

  useEffect(() => () => detailRequest.current.controller?.abort(), [])

  const handleCardClick = useCallback(async (member) => {
    detailRequest.current.controller?.abort()
    const controller = new AbortController()
    const generation = detailRequest.current.generation + 1
    detailRequest.current = { generation, controller }
    setCardError(null)
    setLoadingUserId(member.userId)
    try {
      const page = await api.getSubmissions(member.userId, { page: 0, size: 1 }, controller.signal)
      if (detailRequest.current.generation !== generation) return
      const submission = page?.content?.[0]
      if (submission) setSelected(submission)
      else setCardError(`${member.userName} has no recorded submissions yet.`)
    } catch (requestError) {
      if (requestError.name !== 'AbortError' && detailRequest.current.generation === generation) {
        setCardError(requestError.message || 'Failed to load submissions')
      }
    } finally {
      if (detailRequest.current.generation === generation) setLoadingUserId(null)
    }
  }, [])

  const retry = async () => {
    try {
      await resync()
      setRefreshKey((value) => value + 1)
    } catch { /* Hook renders the request error. */ }
  }

  if (groupId == null) return <p className="leaderboard-empty">No group selected.</p>

  return (
    <section className="leaderboard">
      <header className="leaderboard-header">
        <h2>{leaderboard?.groupName ?? 'Leaderboard'}</h2>
        <span className={`conn-dot ${connected ? 'conn-live' : 'conn-offline'}`}>
          {connected ? '● Live' : '○ Offline'}
        </span>
      </header>
      <SyncStatus refreshKey={refreshKey} />
      {socketError && <div className="leaderboard-error" role="alert">
        <span>{socketError.message || 'Live updates are unavailable.'}</span>{' '}
        <button type="button" onClick={retry}>Retry</button>
      </div>}
      {cardError && <p className="leaderboard-error" role="alert">{cardError}</p>}
      {!leaderboard && !socketError ? <p>Loading leaderboard…</p> : sortedMembers.length === 0 ? (
        <p className="leaderboard-empty">No members in this group yet.</p>
      ) : <ul className="leaderboard-list">
        {sortedMembers.map((member) => <li key={member.userId}>
          <button type="button" className="leaderboard-card-button"
            onClick={() => handleCardClick(member)} disabled={loadingUserId === member.userId}
            aria-label={`View ${member.userName}'s recent problem`}>
            <UserCard member={member} />
          </button>
        </li>)}
      </ul>}
      <ProblemDetailModal submission={selected} onClose={() => setSelected(null)} />
    </section>
  )
}

export default Leaderboard
