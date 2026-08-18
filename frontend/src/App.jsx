import { useCallback, useEffect, useState } from 'react'
import AuthScreen from './components/AuthScreen.jsx'
import GroupInvite from './components/GroupInvite.jsx'
import Leaderboard from './components/Leaderboard.jsx'
import MemberProfile from './components/MemberProfile.jsx'
import GroupTargetPanel from './components/GroupTargetPanel.jsx'
import GitHubIntegration from './components/GitHubIntegration.jsx'
import JobBoard from './components/JobBoard.jsx'
import PatternsCatalog from './components/PatternsCatalog.jsx'
import WorkspaceBar from './components/WorkspaceBar.jsx'
import DashboardStats from './components/DashboardStats.jsx'
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
  const [activeView, setActiveView] = useState(() =>
    new URLSearchParams(window.location.search).has('installation_id') ? 'integrations' : 'dashboard')
  const [profileUserId, setProfileUserId] = useState(null)
  const [catalogRoadmap, setCatalogRoadmap] = useState('all')
  const [error, setError] = useState(null)
  const [restoreAttempt, setRestoreAttempt] = useState(0)
  const [tokenVersion, setTokenVersion] = useState(0)
  const [dashboardMembers, setDashboardMembers] = useState([])

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

  const showIntegrations = () => {
    setProfileUserId(null)
    setActiveView('integrations')
  }

  const showJobs = () => {
    setProfileUserId(null)
    setActiveView('jobs')
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


        {user && <div className="navigation-stack">
          <nav className="workspace-nav" aria-label="Primary navigation">
            <button type="button" aria-current={activeView === 'jobs' ? 'page' : undefined} onClick={showJobs}>
              <svg aria-hidden="true" viewBox="0 0 24 24"><path d="M9 4V2h6v2h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h4Zm2 0h2V3h-2v1Zm-6 8v6h14v-6a22 22 0 0 1-6 1.8V15h-2v-1.2A22 22 0 0 1 5 12Zm14-2V6H5v4a20 20 0 0 0 14 0Z" /></svg><span>JOB</span>
            </button>
            <button type="button" aria-current={activeView === 'dashboard' ? 'page' : undefined} onClick={showDashboard}>
              <svg aria-hidden="true" viewBox="0 0 24 24"><path d="M4 13h6V4H4v9Zm0 7h6v-4H4v4Zm10 0h6v-9h-6v9Zm0-16v4h6V4h-6Z" /></svg><span>DSA</span>
            </button>
            <button type="button" aria-current={activeView === 'patterns' ? 'page' : undefined}
              aria-expanded={activeView === 'patterns'} onClick={() => showCatalog(catalogRoadmap)}>
              <svg aria-hidden="true" viewBox="0 0 24 24"><path d="M4 5h2v2H4V5Zm4 0h12v2H8V5ZM4 11h2v2H4v-2Zm4 0h12v2H8v-2ZM4 17h2v2H4v-2Zm4 0h12v2H8v-2Z" /></svg><span>MAANG DSA QUES</span>
            </button>
            <button type="button" aria-current={activeView === 'integrations' ? 'page' : undefined} onClick={showIntegrations}>
              <svg aria-hidden="true" viewBox="0 0 24 24"><path d="M12 2C6.5 2 2 6.6 2 12.3c0 4.5 2.9 8.4 6.8 9.7.5.1.7-.2.7-.5v-2c-2.8.6-3.4-1.2-3.4-1.2-.5-1.2-1.1-1.5-1.1-1.5-.9-.7.1-.7.1-.7 1 0 1.6 1.1 1.6 1.1.9 1.6 2.4 1.1 3 .9.1-.7.4-1.1.7-1.4-2.2-.3-4.6-1.2-4.6-5.1 0-1.1.4-2.1 1-2.8-.1-.3-.4-1.3.1-2.8 0 0 .8-.3 2.7 1.1a9 9 0 0 1 4.9 0c1.9-1.4 2.7-1.1 2.7-1.1.5 1.5.2 2.5.1 2.8.7.7 1 1.7 1 2.8 0 4-2.4 4.8-4.6 5.1.4.3.7 1 .7 2v3c0 .3.2.6.7.5A10.2 10.2 0 0 0 22 12.3C22 6.6 17.5 2 12 2Z" /></svg><span>GITHUB</span>
            </button>
          </nav>

          {activeView === 'patterns' && <nav className="workspace-subnav" aria-label="MAANG DSA question levels">
            <button type="button" aria-current={catalogRoadmap === 'all' ? 'page' : undefined} onClick={() => showCatalog('all')}>All Questions</button>
            <button type="button" aria-current={catalogRoadmap === 'beginner' ? 'page' : undefined} onClick={() => showCatalog('beginner')}>Beginner</button>
            <button type="button" aria-current={catalogRoadmap === 'experienced' ? 'page' : undefined} onClick={() => showCatalog('experienced')}>Experienced</button>
          </nav>}
        </div>}

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
        ) : activeView === 'integrations' ? (
          <GitHubIntegration />
        ) : activeView === 'jobs' ? (
          <JobBoard />
        ) : activeView === 'profile' ? (
          <MemberProfile userId={profileUserId ?? user.id} currentUserId={user.id} groupId={group?.id} onBack={showDashboard} />
        ) : <section className="dashboard">
          <WorkspaceBar groups={groups} group={group} onGroupChange={chooseGroup} />

          {group ? <>
            <DashboardStats members={dashboardMembers} groupName={group.name} />
            <GroupTargetPanel groupId={group.id} onChanged={() => setTokenVersion((value) => value + 1)} />
            <Leaderboard groupId={group.id} tokenVersion={tokenVersion} onOpenProfile={openProfile} onMembersChange={setDashboardMembers} />
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