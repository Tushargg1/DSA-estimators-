import { useState } from 'react'
import { api } from '../api/client.js'

function GroupInvite({ onCreated, onJoined }) {
  const [groupName, setGroupName] = useState('')
  const [inviteCode, setInviteCode] = useState('')
  const [creating, setCreating] = useState(false)
  const [joining, setJoining] = useState(false)
  const [createError, setCreateError] = useState(null)
  const [joinError, setJoinError] = useState(null)
  const [createdGroup, setCreatedGroup] = useState(null)
  const [copied, setCopied] = useState(false)

  const handleCreate = async (event) => {
    event.preventDefault()
    setCreating(true)
    setCreateError(null)
    try {
      const created = await api.createGroup({ name: groupName.trim() })
      setCreatedGroup(created)
      setGroupName('')
      onCreated?.(created)
    } catch (error) {
      setCreateError(error.message || 'Could not create the group.')
    } finally {
      setCreating(false)
    }
  }

  const handleJoin = async (event) => {
    event.preventDefault()
    setJoining(true)
    setJoinError(null)
    try {
      const joined = await api.joinGroup({ inviteCode: inviteCode.trim() })
      setInviteCode('')
      onJoined?.(joined)
    } catch (error) {
      setJoinError(error.status === 404 ? 'That invite code is invalid.' : error.message)
    } finally {
      setJoining(false)
    }
  }

  const copyCode = async () => {
    try {
      await navigator.clipboard.writeText(createdGroup.inviteCode)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      setCopied(false)
      setCreateError('Could not copy the code. Select it and copy manually.')
    }
  }

  return (
    <div className="group-invite">
      <form className="card group-card" onSubmit={handleCreate}>
        <div className="group-card-heading">
          <span className="group-card-icon" aria-hidden="true">+</span>
          <div><h2>Create a group</h2><p>Start a fresh leaderboard for your circle.</p></div>
        </div>
        <div className="field">
          <label htmlFor="group-name">Group name</label>
          <input id="group-name" value={groupName} onChange={(event) => setGroupName(event.target.value)} required placeholder="e.g. Placement Prep Squad" />
        </div>
        {createError && <div className="form-error" role="alert"><span aria-hidden="true">!</span>{createError}</div>}
        <button className="group-submit" type="submit" disabled={creating || !groupName.trim()}>{creating ? 'Creating…' : 'Create group'}</button>
        {createdGroup && <div className="invite-code" role="status" aria-live="polite">
          <span className="muted">Share this invite code</span>
          <div className="invite-code-row">
            <code>{createdGroup.inviteCode}</code>
            <button className="button-secondary" type="button" onClick={copyCode}>{copied ? 'Copied!' : 'Copy code'}</button>
          </div>
        </div>}
      </form>
      <form className="card group-card" onSubmit={handleJoin}>
        <div className="group-card-heading">
          <span className="group-card-icon join" aria-hidden="true">→</span>
          <div><h2>Join a group</h2><p>Enter an invite code from a friend.</p></div>
        </div>
        <div className="field">
          <label htmlFor="invite-code">Invite code</label>
          <input id="invite-code" value={inviteCode} onChange={(event) => setInviteCode(event.target.value)} required placeholder="Enter invite code" />
        </div>
        {joinError && <div className="form-error" role="alert"><span aria-hidden="true">!</span>{joinError}</div>}
        <button className="group-submit" type="submit" disabled={joining || !inviteCode.trim()}>{joining ? 'Joining…' : 'Join group'}</button>
      </form>
    </div>
  )
}

export default GroupInvite
