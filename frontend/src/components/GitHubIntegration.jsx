import { useCallback, useEffect, useState } from 'react'
import { API_BASE_URL, api } from '../api/client.js'

const formatDateTime = (value, timeZone) => value
  ? new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium', timeStyle: 'short', ...(timeZone ? { timeZone } : {}),
  }).format(new Date(value))
  : 'Never'

const pushResult = (push) => {
  if (push.status === 'FAILED') return push.lastError || 'Push failed'
  if (push.status === 'QUEUED') return 'Waiting for the repository workflow'
  if (push.status === 'RUNNING') return 'Repository workflow is running'
  if (push.changedFiles === 0) return 'Completed — no repository changes'
  if (push.changedFiles != null) {
    return `${push.changedFiles} file${push.changedFiles === 1 ? '' : 's'} pushed`
  }
  return 'Push completed'
}

function GitHubIntegration() {
  const [status, setStatus] = useState(null)
  const [repositories, setRepositories] = useState([])
  const [extensionToken, setExtensionToken] = useState(null)
  const [workflowSave, setWorkflowSave] = useState(null)
  const [pushHistory, setPushHistory] = useState([])
  const [schedule, setSchedule] = useState(null)
  const [busy, setBusy] = useState(false)
  const [saveBusy, setSaveBusy] = useState(false)
  const [scheduleBusy, setScheduleBusy] = useState(false)
  const [scheduleSaved, setScheduleSaved] = useState(false)
  const [error, setError] = useState(null)
  const savePending = ['QUEUED', 'RUNNING'].includes(workflowSave?.status)

  const load = useCallback(async () => {
    const [current, currentSave, currentPushes, currentSchedule] = await Promise.all([
      api.getGitHubStatus(),
      api.getGitHubWorkflowSaveStatus(),
      api.getGitHubProgressPushes(),
      api.getGitHubProgressSchedule(),
    ])
    setStatus(current)
    setWorkflowSave(currentSave)
    setPushHistory(currentPushes)
    setSchedule(currentSchedule)
    if (current.connected) setRepositories(await api.getGitHubRepositories())
    else setRepositories([])
  }, [])

  useEffect(() => {
    let active = true
    const initialize = async () => {
      setBusy(true)
      setError(null)
      try {
        const params = new URLSearchParams(window.location.search)
        const installationId = params.get('installation_id')
        const state = params.get('state')
        if (installationId && state) {
          await api.completeGitHubConnection({ installationId: Number(installationId), state })
          for (const key of ['installation_id', 'setup_action', 'state']) params.delete(key)
          const query = params.toString()
          window.history.replaceState({}, '', `${window.location.pathname}${query ? `?${query}` : ''}`)
        }
        if (active) await load()
      } catch (requestError) {
        if (active) setError(requestError.message)
      } finally {
        if (active) setBusy(false)
      }
    }
    void initialize()
    return () => { active = false }
  }, [load])

  useEffect(() => {
    if (!savePending) return undefined
    let active = true
    const refresh = async () => {
      try {
        const [current, pushes] = await Promise.all([
          api.getGitHubWorkflowSaveStatus(),
          api.getGitHubProgressPushes(),
        ])
        if (active) {
          setWorkflowSave(current)
          setPushHistory(pushes)
        }
      } catch (requestError) {
        if (active) setError(requestError.message)
      }
    }
    const timer = window.setInterval(() => { void refresh() }, 15000)
    return () => {
      active = false
      window.clearInterval(timer)
    }
  }, [savePending])

  const connect = async () => {
    setBusy(true)
    setError(null)
    try {
      const response = await api.startGitHubConnection()
      window.location.assign(response.installUrl)
    } catch (requestError) {
      setError(requestError.message)
      setBusy(false)
    }
  }

  const selectRepository = async (event) => {
    const repositoryId = Number(event.target.value)
    if (!repositoryId) return
    setBusy(true)
    setError(null)
    try {
      const current = await api.selectGitHubRepository(repositoryId)
      setStatus(current)
      setRepositories((items) => items.map((item) => ({
        ...item, selected: item.id === repositoryId,
      })))
    } catch (requestError) {
      setError(requestError.message)
    } finally {
      setBusy(false)
    }
  }

  const issueToken = async () => {
    setBusy(true)
    setError(null)
    try {
      const response = await api.issueGitHubExtensionToken()
      setExtensionToken(response.token)
      setStatus((current) => ({ ...current, extensionTokenIssued: true }))
    } catch (requestError) {
      setError(requestError.message)
    } finally {
      setBusy(false)
    }
  }

  const copyToken = async () => {
    try { await navigator.clipboard.writeText(extensionToken) } catch {
      setError('Copy failed. Select and copy the token manually.')
    }
  }

  const requestWorkflowSave = async () => {
    setSaveBusy(true)
    setError(null)
    try {
      const current = await api.requestGitHubWorkflowSave()
      setWorkflowSave(current)
      setPushHistory(await api.getGitHubProgressPushes())
    } catch (requestError) {
      setError(requestError.message)
    } finally {
      setSaveBusy(false)
    }
  }

  const updateSchedule = (field, value) => {
    setScheduleSaved(false)
    setSchedule((current) => ({ ...current, [field]: value }))
  }

  const saveSchedule = async (event) => {
    event.preventDefault()
    setScheduleBusy(true)
    setScheduleSaved(false)
    setError(null)
    try {
      const updated = await api.updateGitHubProgressSchedule({
        enabled: schedule.enabled,
        firstTime: schedule.firstTime,
        secondTime: schedule.secondTime,
      })
      setSchedule(updated)
      setScheduleSaved(true)
    } catch (requestError) {
      setError(requestError.message)
    } finally {
      setScheduleBusy(false)
    }
  }

  const disconnect = async () => {
    if (!window.confirm('Disconnect GitHub and stop future solution exports?')) return
    setBusy(true)
    setError(null)
    try {
      await api.disconnectGitHub()
      setExtensionToken(null)
      await load()
    } catch (requestError) {
      setError(requestError.message)
    } finally {
      setBusy(false)
    }
  }

  if (!status && busy) return <section className="integration-panel card"><p>Loading GitHub integration…</p></section>

  return (
    <section className="integration-page">
      <div className="integration-heading">
        <div><span className="eyebrow">Automatic solution archive</span><h2>GitHub repository export</h2></div>
        <p>Capture accepted source code with the browser extension and organize it by DSA pattern.</p>
      </div>

      {error && <div className="form-error" role="alert">{error}</div>}
      {status && <div className="integration-panel card">
        <span className="integration-step">Push progress</span>
        <h3>Update your repository now</h3>
        <p>Queue a complete topic-wise progress push. The secure workflow checks for the request within a few minutes without exposing repository credentials in your browser.</p>
        <div className="integration-value">
          <span>Last completed push</span>
          <strong>{formatDateTime(workflowSave?.lastSavedAt, schedule?.timezone)}</strong>
        </div>
        <button type="button" disabled={saveBusy || savePending} onClick={requestWorkflowSave}>
          {workflowSave?.status === 'RUNNING' ? 'Pushing progress…'
            : workflowSave?.status === 'QUEUED' ? 'Push queued'
              : saveBusy ? 'Queuing…' : 'Push progress now'}
        </button>
        {savePending && <p className="integration-success" role="status">
          Progress {workflowSave.status === 'RUNNING' ? 'is being pushed' : 'is queued for the next workflow check'}.
        </p>}
        {workflowSave?.status === 'FAILED' && <p className="form-error" role="alert">
          Last push failed: {workflowSave.lastError || 'Repository workflow failed'}
        </p>}
      </div>}

      {status && schedule && <form className="integration-panel card" onSubmit={saveSchedule}>
        <span className="integration-step">Twice daily</span>
        <h3>Automatic progress schedule</h3>
        <p>Choose two times for automatic repository pushes. Times are saved and evaluated in India Standard Time.</p>
        <label className="schedule-toggle">
          <input
            type="checkbox"
            checked={schedule.enabled}
            onChange={(event) => updateSchedule('enabled', event.target.checked)}
          />
          <span>Enable automatic pushes</span>
        </label>
        <div className="schedule-time-grid">
          <label className="field" htmlFor="github-first-push-time">
            <span>First push (IST)</span>
            <input
              id="github-first-push-time"
              type="time"
              value={schedule.firstTime}
              disabled={!schedule.enabled || scheduleBusy}
              required
              onChange={(event) => updateSchedule('firstTime', event.target.value)}
            />
          </label>
          <label className="field" htmlFor="github-second-push-time">
            <span>Second push (IST)</span>
            <input
              id="github-second-push-time"
              type="time"
              value={schedule.secondTime}
              disabled={!schedule.enabled || scheduleBusy}
              required
              onChange={(event) => updateSchedule('secondTime', event.target.value)}
            />
          </label>
        </div>
        <div className="integration-value">
          <span>Next automatic push</span>
          <strong>{schedule.enabled
            ? formatDateTime(schedule.nextRunAt, schedule.timezone)
            : 'Automatic pushes are paused'}</strong>
        </div>
        <button type="submit" disabled={scheduleBusy}>
          {scheduleBusy ? 'Saving schedule…' : 'Save push times'}
        </button>
        {scheduleSaved && <p className="integration-success" role="status">Push schedule saved.</p>}
      </form>}

      {status && <div className="integration-panel card">
        <span className="integration-step">Recent activity</span>
        <h3>Recent progress pushes</h3>
        <p>Your latest manual and scheduled repository workflow runs.</p>
        {pushHistory.length === 0
          ? <p className="push-history-empty">No progress pushes yet.</p>
          : <ol className="push-history">
            {pushHistory.map((push) => <li key={push.id}>
              <div className="push-history-heading">
                <div>
                  <strong>{push.trigger === 'MANUAL' ? 'Manual push' : 'Scheduled push'}</strong>
                  <span>{formatDateTime(push.completedAt || push.startedAt || push.requestedAt, schedule?.timezone)}</span>
                </div>
                <span className={`push-status push-status-${push.status.toLowerCase()}`}>
                  {push.status.toLowerCase()}
                </span>
              </div>
              <p className={push.status === 'FAILED' ? 'form-error' : ''}>{pushResult(push)}</p>
              {push.commitUrl && <a href={push.commitUrl} target="_blank" rel="noreferrer">
                View commit {push.commitSha?.slice(0, 7)}
              </a>}
            </li>)}
          </ol>}
      </div>}

      {status && <div className="integration-panel card">
        <span className="integration-step">Source capture</span>
        <h3>Connect the browser extension</h3>
        <p>Load this DSA repository&apos;s <code>extension/</code> directory as an unpacked Chrome or Edge extension. Enter this API URL and generate a private capture token.</p>
        <div className="integration-value"><span>API URL</span><code>{API_BASE_URL}</code></div>
        <button type="button" className="button-secondary" disabled={busy} onClick={issueToken}>
          {status.extensionTokenIssued ? 'Rotate capture token' : 'Generate capture token'}
        </button>
        {extensionToken && <div className="token-reveal" role="status">
          <strong>Copy this token now—it will not be shown again.</strong>
          <div><code>{extensionToken}</code><button type="button" onClick={copyToken}>Copy</button></div>
        </div>}
        <p className="integration-success">Accepted code is captured securely and included in the next manual or scheduled progress push.</p>
      </div>}

      {status?.configured && !status.connected && <div className="integration-panel card">
        <span className="integration-step">Step 1</span>
        <h3>Connect your GitHub account</h3>
        <p>Install the DSA Tracker GitHub App and grant it access only to repositories you want to use.</p>
        <button type="button" disabled={busy} onClick={connect}>Connect GitHub</button>
      </div>}

      {status?.connected && <>
        <div className="integration-panel card">
          <span className="integration-step">Step 2</span>
          <h3>Choose a repository</h3>
          <p>Connected as <strong>{status.accountLogin}</strong>. New first-accepted solutions will be committed to the selected repository.</p>
          <label className="field" htmlFor="github-repository">
            <span>Destination repository</span>
            <select id="github-repository" value={repositories.find((item) => item.selected)?.id ?? ''} onChange={selectRepository} disabled={busy}>
              <option value="">Select a repository</option>
              {repositories.map((repository) => <option key={repository.id} value={repository.id}>{repository.fullName}</option>)}
            </select>
          </label>
          {status.repositorySelected && <p className="integration-success">Exporting to {status.repositoryFullName} on {status.defaultBranch}.</p>}
        </div>

        <div className="integration-panel integration-danger card">
          <h3>Disconnect integration</h3>
          <p>This removes the selected repository and invalidates the extension token. Existing GitHub files remain untouched.</p>
          <button type="button" disabled={busy} onClick={disconnect}>Disconnect GitHub</button>
        </div>
      </>}
    </section>
  )
}

export default GitHubIntegration