import { useState } from 'react'
import { api } from '../api/client.js'

/**
 * GroupInvite (task 10.2, Requirements 6.1, 6.2).
 *
 * Two independent actions:
 *   (a) Create a group — sends `{ name, createdByUserId }` to `POST /api/groups`
 *       and displays the returned invite code prominently (copyable).
 *   (b) Join a group — sends `{ inviteCode, userId }` to `POST /api/groups/join`
 *       and reports success / failure (e.g. an invalid code -> 404).
 *
 * ASSUMPTION: there is no auth yet, so the acting user's id is supplied by the
 * parent via the `userId` prop. When auth lands this prop should be sourced
 * from the session instead. Both actions are disabled until a `userId` exists.
 *
 * @param {{ userId?: string | number,
 *           onCreated?: (group: object) => void,
 *           onJoined?: (group: object) => void }} props
 */
function GroupInvite({ userId, onCreated, onJoined }) {
  // --- Create group state ---
  const [groupName, setGroupName] = useState('')
  const [creating, setCreating] = useState(false)
  const [createError, setCreateError] = useState(null)
  const [createdGroup, setCreatedGroup] = useState(null)
  const [copied, setCopied] = useState(false)

  // --- Join group state ---
  const [inviteCode, setInviteCode] = useState('')
  const [joining, setJoining] = useState(false)
  const [joinError, setJoinError] = useState(null)
  const [joinedGroup, setJoinedGroup] = useState(null)

  const missingUser = userId === undefined || userId === null || userId === ''

  const handleCreate = async (e) => {
    e.preventDefault()
    if (missingUser) {
      setCreateError('No user selected — cannot create a group yet.')
      return
    }
    setCreating(true)
    setCreateError(null)
    setCreatedGroup(null)
    setCopied(false)
    try {
      const group = await api.createGroup({
        name: groupName.trim(),
        createdByUserId: userId,
      })
      setCreatedGroup(group)
      onCreated?.(group)
    } catch (err) {
      setCreateError(err.message ?? 'Could not create the group. Please try again.')
    } finally {
      setCreating(false)
    }
  }

  const handleJoin = async (e) => {
    e.preventDefault()
    if (missingUser) {
      setJoinError('No user selected — cannot join a group yet.')
      return
    }
    setJoining(true)
    setJoinError(null)
    setJoinedGroup(null)
    try {
      const group = await api.joinGroup({
        inviteCode: inviteCode.trim(),
        userId,
      })
      setJoinedGroup(group)
      onJoined?.(group)
    } catch (err) {
      const message =
        err.status === 404
          ? 'That invite code doesn\u2019t match any group. Double-check and try again.'
          : err.message ?? 'Could not join the group. Please try again.'
      setJoinError(message)
    } finally {
      setJoining(false)
    }
  }

  const handleCopy = async () => {
    if (!createdGroup?.inviteCode) return
    try {
      await navigator.clipboard.writeText(createdGroup.inviteCode)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Clipboard API unavailable (e.g. insecure context) — leave the code
      // visible for manual copy; no hard failure needed.
      setCopied(false)
    }
  }

  return (
    <div className="group-invite">
      <form className="card" onSubmit={handleCreate} noValidate>
        <h2>Create a group</h2>
        <p className="muted">Start a group and share the invite code with friends.</p>

        <div className="field">
          <label htmlFor="group-name">Group name</label>
          <input
            id="group-name"
            name="groupName"
            type="text"
            value={groupName}
            onChange={(e) => setGroupName(e.target.value)}
            placeholder="Weekend Grinders"
          />
        </div>

        {createError && (
          <p className="form-error" role="alert">
            {createError}
          </p>
        )}

        <button type="submit" disabled={creating || missingUser}>
          {creating ? 'Creating\u2026' : 'Create group'}
        </button>

        {createdGroup && (
          <div className="invite-code" role="status">
            <span className="muted">Invite code</span>
            <div className="invite-code-row">
              <code>{createdGroup.inviteCode}</code>
              <button type="button" onClick={handleCopy}>
                {copied ? 'Copied!' : 'Copy'}
              </button>
            </div>
          </div>
        )}
      </form>

      <form className="card" onSubmit={handleJoin} noValidate>
        <h2>Join a group</h2>
        <p className="muted">Have an invite code? Enter it to join.</p>

        <div className="field">
          <label htmlFor="invite-code">Invite code</label>
          <input
            id="invite-code"
            name="inviteCode"
            type="text"
            value={inviteCode}
            onChange={(e) => setInviteCode(e.target.value)}
            placeholder="e.g. AB12CD"
            aria-invalid={joinError ? 'true' : undefined}
          />
        </div>

        {joinError && (
          <p className="form-error" role="alert">
            {joinError}
          </p>
        )}

        <button type="submit" disabled={joining || missingUser}>
          {joining ? 'Joining\u2026' : 'Join group'}
        </button>

        {joinedGroup && (
          <p className="form-success" role="status">
            Joined <strong>{joinedGroup.name}</strong>. Welcome aboard!
          </p>
        )}
      </form>
    </div>
  )
}

export default GroupInvite
