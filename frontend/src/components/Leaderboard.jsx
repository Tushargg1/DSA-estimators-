import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../api/client.js'
import useGroupSocket from '../hooks/useGroupSocket.js'
import UserCard from './UserCard.jsx'
import ProblemDetailModal from './ProblemDetailModal.jsx'
import SyncStatus from './SyncStatus.jsx'

/**
 * Leaderboard (task 10.3, Requirements 6.4, 7.1).
 *
 * Live group view. Responsibilities:
 *   - Initial full state + delta-merge + reconnect-resync are delegated to
 *     useGroupSocket: it fetches GET /api/groups/{id}/leaderboard on mount and
 *     on every (re)connect (task 10.7), subscribes to /topic/group/{groupId},
 *     and folds each delta ({ userId, newDailyCount, ... }) into member state
 *     via mergeDelta (task 10.3).
 *   - If a delta references a user not in the current board, we trigger a full
 *     resync here (safer than trusting the guessed stub row mergeDelta appends).
 *   - Renders a UserCard per member (sorted by today's count, descending).
 *   - Clicking a member fetches their recent submissions and opens the
 *     ProblemDetailModal on the most recent one (task 10.5 wiring).
 *   - Shows the per-platform "last synced" indicator (task 10.6).
 *
 * @param {{ groupId: string|number }} props
 */
function Leaderboard({ groupId }) {
  const [selected, setSelected] = useState(null)
  const [loadingUserId, setLoadingUserId] = useState(null)
  const [cardError, setCardError] = useState(null)

  // Track the ids currently on the board and the latest resync fn in refs, so
  // the onDelta callback can stay stable (no re-subscribe) while still seeing
  // fresh values.
  const knownIdsRef = useRef(new Set())
  const resyncRef = useRef(null)

  // When a delta arrives for a user we don't have, pull authoritative state
  // rather than rendering the placeholder stub indefinitely.
  const onDelta = useCallback((delta) => {
    const id = delta?.userId
    if (id != null && !knownIdsRef.current.has(id)) {
      void resyncRef.current?.()
    }
  }, [])

  const { connected, leaderboard, resync } = useGroupSocket(groupId, onDelta)

  useEffect(() => {
    resyncRef.current = resync
  }, [resync])

  const members = useMemo(() => leaderboard?.members ?? [], [leaderboard])

  useEffect(() => {
    knownIdsRef.current = new Set(members.map((m) => m.userId))
  }, [members])

  const sortedMembers = useMemo(
    () => [...members].sort((a, b) => (b.todayCount ?? 0) - (a.todayCount ?? 0)),
    [members],
  )

  const handleCardClick = useCallback(async (member) => {
    setCardError(null)
    setLoadingUserId(member.userId)
    try {
      // Pull the user's most recent submission and open the modal on it.
      const page = await api.getSubmissions(member.userId, { page: 0, size: 1 })
      const submission = page?.content?.[0]
      if (submission) {
        setSelected(submission)
      } else {
        setCardError(`${member.userName} has no recorded submissions yet.`)
      }
    } catch (err) {
      setCardError(err.message ?? 'Failed to load submissions')
    } finally {
      setLoadingUserId(null)
    }
  }, [])

  if (groupId == null) {
    return <p className="leaderboard-empty">No group selected.</p>
  }

  return (
    <section className="leaderboard">
      <header className="leaderboard-header">
        <h2>{leaderboard?.groupName ?? 'Leaderboard'}</h2>
        <span
          className={`conn-dot ${connected ? 'conn-live' : 'conn-offline'}`}
          title={connected ? 'Live connection active' : 'Reconnecting…'}
        >
          {connected ? '● Live' : '○ Offline'}
        </span>
      </header>

      <SyncStatus />

      {cardError && <p className="leaderboard-error">{cardError}</p>}

      {sortedMembers.length === 0 ? (
        <p className="leaderboard-empty">No members in this group yet.</p>
      ) : (
        <ul className="leaderboard-list">
          {sortedMembers.map((member) => (
            <li key={member.userId}>
              <button
                type="button"
                className="leaderboard-card-button"
                onClick={() => handleCardClick(member)}
                disabled={loadingUserId === member.userId}
                aria-label={`View ${member.userName}'s recent problem`}
              >
                <UserCard member={member} />
              </button>
            </li>
          ))}
        </ul>
      )}

      <ProblemDetailModal
        submission={selected}
        onClose={() => setSelected(null)}
      />
    </section>
  )
}

export default Leaderboard
