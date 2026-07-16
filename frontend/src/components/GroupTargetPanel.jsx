import { useEffect, useState } from 'react'
import { api } from '../api/client.js'

function GroupTargetPanel({ groupId, onChanged }) {
  const [target, setTarget] = useState(null)
  const [vote, setVote] = useState(3)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState(null)

  useEffect(() => {
    const controller = new AbortController()
    setTarget(null)
    setError(null)
    api.getGroupTarget(groupId, controller.signal).then((value) => {
      setTarget(value)
      setVote(value.poll?.currentUserVote ?? value.dailyTarget ?? 3)
    }).catch((requestError) => {
      if (requestError.name !== 'AbortError') setError(requestError.message)
    })
    return () => controller.abort()
  }, [groupId])

  const perform = async (name, action) => {
    setBusy(name)
    setError(null)
    try {
      const value = await action()
      setTarget(value)
      setVote(value.poll?.currentUserVote ?? value.dailyTarget)
      onChanged?.(value)
    } catch (requestError) {
      setError(requestError.message || 'Could not update the group target.')
    } finally {
      setBusy('')
    }
  }

  if (!target && !error) return <section className="target-panel target-loading"><span className="button-spinner" />Loading group target…</section>

  return (
    <section className="target-panel" aria-labelledby="group-target-title">
      <div className="target-summary">
        <span className="target-value">{target?.dailyTarget ?? '—'}</span>
        <div><span className="eyebrow">Daily group goal</span><h2 id="group-target-title">Questions per member</h2>
          <p>{target?.mode === 'AUTO' ? `Automatic · 30-day average + 1` : 'Chosen by all-member vote'} · Minimum 3</p>
        </div>
      </div>

      {target?.poll?.active ? <div className="target-poll">
        <div className="poll-heading"><strong>Target poll in progress</strong><span>{target.poll.votesCast}/{target.poll.eligibleMembers} voted</span></div>
        <div className="poll-progress" role="progressbar" aria-valuemin="0" aria-valuemax={target.poll.eligibleMembers}
          aria-valuenow={target.poll.votesCast}><span style={{ width: `${target.poll.eligibleMembers ? target.poll.votesCast / target.poll.eligibleMembers * 100 : 0}%` }} /></div>
        <form onSubmit={(event) => { event.preventDefault(); void perform('vote', () => api.castGroupTargetVote(groupId, Number(vote))) }}>
          <label htmlFor={`target-vote-${groupId}`}>Your vote</label>
          <input id={`target-vote-${groupId}`} type="number" min="3" step="1" value={vote} onChange={(event) => setVote(event.target.value)} required />
          <button type="submit" disabled={busy || Number(vote) < 3}>{busy === 'vote' ? 'Saving…' : target.poll.currentUserVote ? 'Update vote' : 'Cast vote'}</button>
        </form>
        <small>The target changes after every member who was present when this poll began has voted.</small>
      </div> : <div className="target-mode-copy">
        <span>Suggested automatic target</span><strong>{target?.autoSuggestedTarget ?? 3}</strong>
      </div>}

      {target?.owner && <div className="target-owner-actions">
        <span>Group owner controls</span>
        <button type="button" className="button-secondary" disabled={busy || target.mode === 'AUTO'}
          onClick={() => perform('auto', () => api.selectAutoGroupTarget(groupId))}>
          {busy === 'auto' ? 'Switching…' : 'Use automatic target'}
        </button>
        <button type="button" disabled={busy || target.poll.active}
          onClick={() => perform('poll', () => api.startGroupTargetPoll(groupId))}>
          {busy === 'poll' ? 'Starting…' : 'Start member poll'}
        </button>
      </div>}
      {error && <div className="leaderboard-error" role="alert">{error}</div>}
    </section>
  )
}

export default GroupTargetPanel