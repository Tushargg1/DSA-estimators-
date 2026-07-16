import { useCallback, useEffect, useState } from 'react'
import AuthScreen from './components/AuthScreen.jsx'
import GroupInvite from './components/GroupInvite.jsx'
import Leaderboard from './components/Leaderboard.jsx'
import PatternsCatalog from './components/PatternsCatalog.jsx'
import { api, clearAuthToken, getAuthToken, onUnauthorized, setAuthToken } from './api/client.js'

const groupStorageKey = (userId) => `dsaTracker.lastGroup.${userId}`

function readStoredGroup(userId) {
  try { return localStorage.getItem(groupStorageKey(userId)) } catch { return null }
}

function storeGroup(userId, groupId) {
  try { localStorage.setItem(groupStorageKey(userId), String(groupId)) } catch { /* Selection still works in memory. */ }
}

function App() {
  const [loading, setLoading] = useState(true)
  const [user, setUser] = useState(null)
  const [groups, setGroups] = useState([])
  const [group, setGroup] = useState(null)
  const [activeView, setActiveView] = useState('dashboard')
  const [error, setError] = useState(null)
  const [restoreAttempt, setRestoreAttempt] = useState(0)
  const [tokenVersion, setTokenVersion] = useState(0)

  const clearSession = useCallback(() => {
    clearAuthToken()
    setUser(null)
    setGroups([])
    setGroup(null)
    setActiveView('dashboard')
    setError(null)
    setTokenVersion((value) => value + 1)
  }, [])

  useEffect(() => onUnauthorized(clearSession), [clearSession])

  const loadGroups = useCallback(async (currentUser) => {
    const available = await api.getGroups()
    setGroups(available)
    const saved = readStoredGroup(currentUser.id)
    const restored = available.find((item) => String(item.id) === saved) ?? available[0] ?? null
    setGroup(restored)
    if (restored) storeGroup(currentUser.id, restored.id)
    return available
  }, [])

  useEffect(() => {
    let active = true
    const bootstrap = async () => {
      if (!getAuthToken()) {
        if (active) setLoading(false)
        return
      }
      setLoading(true)
      setError(null)
      try {
        const restoredUser = await api.me()
        if (!active) return
        setUser(restoredUser)
        await loadGroups(restoredUser)
      } catch (requestError) {
        if (!active) return
        if (requestError.status === 401) clearSession()
        else setError(requestError.message)
      } finally {
        if (active) setLoading(false)
      }
    }
    void bootstrap()
    return () => { active = false }
  }, [clearSession, loadGroups, restoreAttempt])

  const authenticated = async (response) => {
    setAuthToken(response.token)
    setTokenVersion((value) => value + 1)
    setUser(response.user)
    setError(null)
    try {
      await loadGroups(response.user)
    } catch (requestError) {
      setError(requestError.message)
    }
  }

  const chooseGroup = (selected) => {
    if (!selected || !user) return
    setGroup(selected)
    storeGroup(user.id, selected.id)
  }

  const groupAdded = (added) => {
    setGroups((current) => current.some((item) => item.id === added.id) ? current : [...current, added])
    chooseGroup(added)
  }

  const logout = async () => {
    try { await api.logout() } catch { /* Stateless logout always completes locally. */ }
    clearSession()
  }

  if (loading) return (
    <main className="app-shell loading-shell" aria-live="polite">
      <div className="app-loader" aria-hidden="true"><span /></div>
      <p>Connecting to your workspace…</p>
    </main>
  )
  if (!user && getAuthToken() && error) return (
    <main className="app-shell status-shell">
      <section className="status-card" aria-live="polite">
        <div className="status-icon" aria-hidden="true">!</div>
        <span className="eyebrow">Connection interrupted</span>
        <h1>Server temporarily unavailable</h1>
        <p className="form-error" role="alert">{error}</p>
        <p className="muted">Your session is safe. The server may need a moment to wake up.</p>
        <div className="button-row">
          <button type="button" onClick={() => setRestoreAttempt((value) => value + 1)}>Retry connection</button>
          <button type="button" className="button-secondary" onClick={clearSession}>Return to login</button>
        </div>
      </section>
    </main>
  )

  return (
    <main className="app-shell">
      <header className="app-header">
        <div className="brand-lockup">
          <div className="brand-mark" aria-hidden="true"><span>&lt;/&gt;</span></div>
          <div>
            <span className="brand-kicker">Build consistency</span>
            <h1>DSA Progress Tracker</h1>
          </div>
        </div>
        {user && <button type="button" className="button-secondary logout-button" onClick={logout}>Log out</button>}
      </header>
      {error && user && <div className="alert-banner" role="alert"><span aria-hidden="true">!</span>{error}</div>}
      {!user ? <AuthScreen onAuthenticated={authenticated} /> : (
        <>
          <nav className="workspace-nav" aria-label="Workspace views">
            <button type="button" aria-pressed={activeView === 'dashboard'} onClick={() => setActiveView('dashboard')}>
              <span aria-hidden="true">◎</span> Dashboard
            </button>
            <button type="button" aria-pressed={activeView === 'patterns'} onClick={() => setActiveView('patterns')}>
              <span aria-hidden="true">⌘</span> LeetCode Patterns
            </button>
          </nav>
          {activeView === 'patterns' ? <PatternsCatalog userId={user.id} /> : (
            <section className="dashboard">
              <div className="dashboard-toolbar">
                <div className="welcome-block">
                  <span className="eyebrow">Your workspace</span>
                  <h2>Welcome back, {user.name}</h2>
                  <p>Stay consistent, hit today’s goal, and climb the leaderboard.</p>
                </div>
                {groups.length > 0 && <div className="group-switcher field">
                  <label htmlFor="group-select">Active group</label>
                  <select id="group-select" value={group?.id ?? ''}
                    onChange={(event) => chooseGroup(groups.find((item) => String(item.id) === event.target.value))}>
                    {groups.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}
                  </select>
                </div>}
              </div>
              <details className="group-actions" open={!group}>
                <summary>
                  <span><strong>Create or join a group</strong><small>Invite friends and track progress together</small></span>
                  <span className="summary-chevron" aria-hidden="true">⌄</span>
                </summary>
                <GroupInvite onCreated={groupAdded} onJoined={groupAdded} />
              </details>
              {group ? <Leaderboard groupId={group.id} tokenVersion={tokenVersion} /> : (
                <section className="empty-state">
                  <span className="empty-state-icon" aria-hidden="true">◎</span>
                  <h2>Create your first group</h2>
                  <p>Start a group or join one with an invite code to see the leaderboard.</p>
                </section>
              )}
            </section>
          )}
        </>
      )}
    </main>
  )
}

export default App
