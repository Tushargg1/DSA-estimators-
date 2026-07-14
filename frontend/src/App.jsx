import { useState } from 'react'
import OnboardingForm from './components/OnboardingForm.jsx'
import GroupInvite from './components/GroupInvite.jsx'
import Leaderboard from './components/Leaderboard.jsx'

/**
 * Root application shell (section 10 wiring).
 *
 * Drives a simple linear flow across the frontend screens:
 *   1. onboard  — OnboardingForm creates the user
 *   2. group    — GroupInvite creates or joins a group
 *   3. board    — Leaderboard shows live group progress
 *
 * State is held in memory only; this is intentionally minimal (no router / no
 * persistence) so the components integrate and the production build exercises
 * every screen.
 */
function App() {
  const [user, setUser] = useState(null)
  const [group, setGroup] = useState(null)

  let step = 'onboard'
  if (user && !group) step = 'group'
  else if (user && group) step = 'board'

  return (
    <main className="app-shell">
      <h1>DSA Progress Tracker</h1>

      {step === 'onboard' && <OnboardingForm onCreated={setUser} />}

      {step === 'group' && (
        <>
          <p className="muted">
            Welcome, {user.name}. Create a group or join one to get started.
          </p>
          <GroupInvite
            userId={user.id}
            onCreated={setGroup}
            onJoined={setGroup}
          />
        </>
      )}

      {step === 'board' && <Leaderboard groupId={group.id} />}
    </main>
  )
}

export default App
