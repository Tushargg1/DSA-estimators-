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
    }
  }

  return (
    <div className="group-invite">
      <form className="card" onSubmit={handleCreate}>
        <h2>Create a group</h2>
        <div className="field">
          <label htmlFor="group-name">Group name</label>
          <input id="group-name" value={groupName} onChange={(event) => setGroupName(event.target.value)} />
        </div>
        {createError && <p className="form-error" role="alert">{createError}</p>}
        <button type="submit" disabled={creating}>{creating ? 'Creating…' : 'Create group'}</button>
        {createdGroup && <div className="invite-code" role="status">
          <span className="muted">Invite code</span>
          <div className="invite-code-row">
            <code>{createdGroup.inviteCode}</code>
            <button type="button" onClick={copyCode}>{copied ? 'Copied!' : 'Copy'}</button>
          </div>
        </div>}
      </form>
      <form className="card" onSubmit={handleJoin}>
        <h2>Join a group</h2>
        <div className="field">
          <label htmlFor="invite-code">Invite code</label>
          <input id="invite-code" value={inviteCode} onChange={(event) => setInviteCode(event.target.value)} />
        </div>
        {joinError && <p className="form-error" role="alert">{joinError}</p>}
        <button type="submit" disabled={joining}>{joining ? 'Joining…' : 'Join group'}</button>
      </form>
    </div>
  )
}

export default GroupInvite
