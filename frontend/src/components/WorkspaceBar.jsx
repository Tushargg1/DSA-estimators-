import { useEffect, useState } from 'react'

function WorkspaceBar({ groups, group, onGroupChange }) {
  const [feedback, setFeedback] = useState('')
  const inviteCode = group?.inviteCode ?? ''

  useEffect(() => setFeedback(''), [inviteCode])

  const copyInvite = async () => {
    try {
      await navigator.clipboard.writeText(inviteCode)
      setFeedback('Code copied')
      setTimeout(() => setFeedback(''), 2000)
    } catch {
      setFeedback('Select the code to copy it')
    }
  }

  const shareInvite = async () => {
    const text = `Join ${group.name} on DSA Tracker with invite code: ${inviteCode}`
    if (!navigator.share) {
      await copyInvite()
      return
    }
    try {
      await navigator.share({ title: `Join ${group.name}`, text })
      setFeedback('Invite shared')
    } catch (error) {
      if (error.name !== 'AbortError') setFeedback('Could not share the invite')
    }
  }

  return (
    <section className="workspace-bar" aria-label="Active group">
      <div className="workspace-identity">
        <span className="workspace-icon" aria-hidden="true">#</span>
        <div>
          <small>Active group</small>
          {groups.length > 0 ? <select aria-label="Choose active group" value={group?.id ?? ''}
            onChange={(event) => onGroupChange(groups.find((item) => String(item.id) === event.target.value))}>
            {groups.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}
          </select> : <strong>No group selected</strong>}
        </div>
      </div>
      {group && <div className="workspace-invite">
        <span><small>Join code</small><code>{inviteCode}</code></span>
        <button type="button" className="button-secondary" onClick={copyInvite}>Copy</button>
        <button type="button" onClick={shareInvite}>Share</button>
        <em role="status" aria-live="polite">{feedback}</em>
      </div>}
    </section>
  )
}

export default WorkspaceBar