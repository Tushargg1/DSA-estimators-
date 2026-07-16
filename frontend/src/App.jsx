import { useCallback, useEffect, useState } from 'react'
import AuthScreen from './components/AuthScreen.jsx'
import GroupInvite from './components/GroupInvite.jsx'
import Leaderboard from './components/Leaderboard.jsx'
import MemberProfile from './components/MemberProfile.jsx'
import GroupTargetPanel from './components/GroupTargetPanel.jsx'
import PatternsCatalog from './components/PatternsCatalog.jsx'
import WorkspaceBar from './components/WorkspaceBar.jsx'
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
  const [profileUserId, setProfileUserId] = useState(null)
  const [catalogRoadmap, setCatalogRoadmap] = useState('all')
  const [error, setError] = useState(null)
  const [restoreAttempt, setRestoreAttempt] = useState(0)
  const [tokenVersion, setTokenVersion] = useState(0)

  const clearSession = useCallback(() => {
    clearAuthToken()
    setUser(null)
    setGroups([])
    setGroup(null)
    setActiveView('dashboard')
    setProfileUserId(null)
    setCatalogRoadmap('all')
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

  const showDashboard = () => {
    setProfileUserId(null)
    setActiveView('dashboard')
  }

  const showCatalog = (roadmap) => {
    setCatalogRoadmap(roadmap)
    setProfileUserId(null)
    setActiveView('patterns')
  }

  const openProfile = (member) => {
    setProfileUserId(member?.userId ?? member?.id ?? user.id)
    setActiveView('profile')
    window.scrollTo({ top: 0, behavior: 'smooth' })
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
      <header className={`app-header${user ? ' app-header-authenticated' : ' app-header-public'}`}>
        <div className="brand-lockup">
          <div className="brand-mark" aria-hidden="true"><span>DP</span></div>
          <div><span className="brand-kicker">Practice intelligence</span><h1>DSA Tracker</h1></div>
        </div>


        {user && <nav className="workspace-nav" aria-label="Primary navigation">
          <button type="button" aria-current={activeView === 'dashboard' ? 'page' : undefined} onClick={showDashboard}>
            <svg aria-hidden="true" viewBox="0 0 24 24"><path d="M4 13h6V4H4v9Zm0 7h6v-4H4v4Zm10 0h6v-9h-6v9Zm0-16v4h6V4h-6Z" /></svg><span>Dashboard</span>
          </button>
          <button type="button" aria-current={activeView === 'patterns' && catalogRoadmap === 'all' ? 'page' : undefined} onClick={() => showCatalog('all')}>
            <svg aria-hidden="true" viewBox="0 0 24 24"><path d="M4 5h2v2H4V5Zm4 0h12v2H8V5ZM4 11h2v2H4v-2Zm4 0h12v2H8v-2ZM4 17h2v2H4v-2Zm4 0h12v2H8v-2Z" /></svg><span>All Questions</span>
          </button>
          <button type="button" aria-current={activeView === 'patterns' && catalogRoadmap === 'beginner' ? 'page' : undefined} onClick={() => showCatalog('beginner')}>
            <svg aria-hidden="true" viewBox="0 0 24 24"><path d="M12 3a6 6 0 0 0-6 6c0 2.2 1.2 4.1 3 5.2V17h6v-2.8A6 6 0 0 0 12 3Zm-2 16h4v2h-4v-2Zm1-4.7-.7-.4A4 4 0 1 1 16 10.3c-.4.7-1 1.3-1.7 1.7l-1.3.8V15h-2v-.7Z" /></svg><span>Beginner</span>
          </button>
          <button type="button" aria-current={activeView === 'patterns' && catalogRoadmap === 'experienced' ? 'page' : undefined} onClick={() => showCatalog('experienced')}>
            <svg aria-hidden="true" viewBox="0 0 24 24"><path d="m12 2 2.1 4.3 4.7.7-3.4 3.3.8 4.7-4.2-2.2L7.8 15l.8-4.7L5.2 7l4.7-.7L12 2Zm-6 14h12v6l-6-3-6 3v-6Z" /></svg><span>Experienced</span>
          </button>
        </nav>}

        {user && <div className="account-cluster">
          <button type="button" className="account-profile" aria-current={activeView === 'profile' && profileUserId === user.id ? 'page' : undefined} onClick={() => openProfile(user)} aria-label="Open your profile">
            <span className="account-avatar" aria-hidden="true">{user.name?.trim()?.charAt(0).toUpperCase() || 'U'}</span>
            <span className="account-copy"><strong>{user.name}</strong><small>View profile</small></span>
          </button>
          <button type="button" className="logout-button" onClick={logout} aria-label="Log out">
            <svg aria-hidden="true" viewBox="0 0 24 24"><path d="M10 17v2H5V5h5V3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h5v2l5-3-5-3Zm3-9 1.4 1.4L12.8 11H21v2h-8.2l1.6 1.6L13 16l-4-4 4-4Z" /></svg><span>Log out</span>
          </button>
        </div>}
      </header>

      <div className="app-content">
        {error && user && <div className="alert-banner" role="alert"><span aria-hidden="true">!</span>{error}</div>}
        {!user ? <AuthScreen onAuthenticated={authenticated} /> : activeView === 'patterns' ? (
          <PatternsCatalog userId={user.id} roadmapId={catalogRoadmap} onRoadmapChange={setCatalogRoadmap} />
        ) : activeView === 'profile' ? (
          <MemberProfile userId={profileUserId ?? user.id} currentUserId={user.id} groupId={group?.id} onBack={showDashboard} />
        ) : <section className="dashboard">
          <WorkspaceBar groups={groups} group={group} onGroupChange={chooseGroup} />

          {group ? <>
            <GroupTargetPanel groupId={group.id} onChanged={() => setTokenVersion((value) => value + 1)} />
            <Leaderboard groupId={group.id} tokenVersion={tokenVersion} onOpenProfile={openProfile} />
          </> : (
            <section className="empty-state">
              <span className="empty-state-icon" aria-hidden="true">+</span>
              <h2>Create your first group</h2>
              <p>Start a group or join one with an invite code to unlock your live leaderboard.</p>
            </section>
          )}

          <details className="group-actions" open={!group}>
            <summary>
              <span className="group-actions-icon" aria-hidden="true">+</span>
              <span><strong>Create or join a group</strong><small>Manage workspaces without leaving your dashboard</small></span>
              <span className="summary-chevron" aria-hidden="true">⌄</span>
            </summary>
            <GroupInvite onCreated={groupAdded} onJoined={groupAdded} />
          </details>
        </section>}
      </div>
    </main>
  )
}

export default App