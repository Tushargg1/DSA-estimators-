import { useCallback, useEffect, useState } from 'react'
import { API_BASE_URL, api } from '../api/client.js'

function GitHubIntegration() {
  const [status, setStatus] = useState(null)
  const [repositories, setRepositories] = useState([])
  const [extensionToken, setExtensionToken] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  const load = useCallback(async () => {
    const current = await api.getGitHubStatus()
    setStatus(current)
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
      {status && !status.configured && <div className="integration-panel card">
        <h3>GitHub App setup required</h3>
        <p>The server administrator must configure the GitHub App credentials before accounts can connect.</p>
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

        <div className="integration-panel card">
          <span className="integration-step">Step 3</span>
          <h3>Connect the browser extension</h3>
          <p>Load the repository's <code>extension/</code> folder as an unpacked Chrome or Edge extension, then enter this API URL and a generated token.</p>
          <div className="integration-value"><span>API URL</span><code>{API_BASE_URL}</code></div>
          <button type="button" className="button-secondary" disabled={busy || !status.repositorySelected} onClick={issueToken}>
            {status.extensionTokenIssued ? 'Rotate extension token' : 'Generate extension token'}
          </button>
          {extensionToken && <div className="token-reveal" role="status">
            <strong>Copy this token now—it will not be shown again.</strong>
            <div><code>{extensionToken}</code><button type="button" onClick={copyToken}>Copy</button></div>
          </div>}
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