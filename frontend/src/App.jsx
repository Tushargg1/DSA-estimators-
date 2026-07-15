import { useCallback, useEffect, useState } from 'react'
import AuthScreen from './components/AuthScreen.jsx'
import GroupInvite from './components/GroupInvite.jsx'
import Leaderboard from './components/Leaderboard.jsx'
import { api, clearAuthToken, getAuthToken, onUnauthorized, setAuthToken } from './api/client.js'

const groupStorageKey = (userId) => `dsaTracker.lastGroup.${userId}`

function App() {
  const [loading, setLoading] = useState(true)
  const [user, setUser] = useState(null)
  const [groups, setGroups] = useState([])
  const [group, setGroup] = useState(null)
  const [error, setError] = useState(null)
  const [tokenVersion, setTokenVersion] = useState(0)

  const clearSession = useCallback(() => {
    clearAuthToken()
    setUser(null)
    setGroups([])
    setGroup(null)
    setTokenVersion((value) => value + 1)
  }, [])

  useEffect(() => onUnauthorized(clearSession), [clearSession])

  const loadGroups = useCallback(async (currentUser) => {
    const available = await api.getGroups()
    setGroups(available)
    const saved = localStorage.getItem(groupStorageKey(currentUser.id))
    const restored = available.find((item) => String(item.id) === saved) ?? available[0] ?? null
    setGroup(restored)
    if (restored) localStorage.setItem(groupStorageKey(currentUser.id), String(restored.id))
    return available
  }, [])

  useEffect(() => {
    let active = true
    const bootstrap = async () => {
      if (!getAuthToken()) {
        if (active) setLoading(false)
        return
      }
      try {
        const restoredUser = await api.me()
        if (!active) return
        setUser(restoredUser)
        await loadGroups(restoredUser)
      } catch (requestError) {
        if (active) {
          clearSession()
          if (requestError.status !== 401) setError(requestError.message)
        }
      } finally {
        if (active) setLoading(false)
      }
    }
    void bootstrap()
    return () => { active = false }
  }, [clearSession, loadGroups])

  const authenticated = async (response) => {
    setAuthToken(response.token)
    setTokenVersion((value) => value + 1)
    setUser(response.user)
    setError(null)
    await loadGroups(response.user)
  }

  const chooseGroup = (selected) => {
    setGroup(selected)
    localStorage.setItem(groupStorageKey(user.id), String(selected.id))
  }

  const groupAdded = (added) => {
    setGroups((current) => current.some((item) => item.id === added.id) ? current : [...current, added])
    chooseGroup(added)
  }

  const logout = async () => {
    try { await api.logout() } catch { /* Stateless logout always completes locally. */ }
    clearSession()
  }

  if (loading) return <main className="app-shell"><p>Restoring session…</p></main>

  return (
    <main className="app-shell">
      <header className="app-header">
        <h1>DSA Progress Tracker</h1>
        {user && <button type="button" onClick={logout}>Logout</button>}
      </header>
      {error && <p className="form-error" role="alert">{error}</p>}
      {!user ? <AuthScreen onAuthenticated={authenticated} /> : (
        <>
          <p className="muted">Signed in as {user.name}</p>
          {groups.length > 0 && <div className="group-switcher field">
            <label htmlFor="group-select">Current group</label>
            <select id="group-select" value={group?.id ?? ''}
              onChange={(event) => chooseGroup(groups.find((item) => String(item.id) === event.target.value))}>
              {groups.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}
            </select>
          </div>}
          <details className="group-actions" open={!group}>
            <summary>Create or join a group</summary>
            <GroupInvite onCreated={groupAdded} onJoined={groupAdded} />
          </details>
          {group && <Leaderboard groupId={group.id} tokenVersion={tokenVersion} />}
        </>
      )}
    </main>
  )
}

export default App
