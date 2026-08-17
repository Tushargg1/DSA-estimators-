import { useEffect, useMemo, useState } from 'react'
import { api } from '../api/client.js'
import ContributionGraph from './ui/ContributionGraph.jsx'

function istDateKey(value) {
  if (!value) return null
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date(value))
  const part = (type) => parts.find((item) => item.type === type)?.value
  return `${part('year')}-${part('month')}-${part('day')}`
}

function ProfileProgress({ userId, groupId }) {
  const [submissions, setSubmissions] = useState([])
  const [pushHistory, setPushHistory] = useState([])
  const [dsaYear, setDsaYear] = useState(new Date().getFullYear())
  const [ghYear, setGhYear] = useState(new Date().getFullYear())
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let active = true
    setLoading(true)

    const fetchAll = async () => {
      try {
        // Fetch all submissions (paginated)
        let allSubmissions = []
        let page = 0
        let last = false
        while (!last) {
          const result = await api.getSubmissions(userId, { page, size: 100, groupId })
          allSubmissions = [...allSubmissions, ...(result.content || [])]
          last = result.last
          page++
          if (page > 50) break // safety
        }

        // Fetch push history
        let pushes = []
        try {
          pushes = await api.getGitHubProgressPushes()
        } catch {
          // Not fatal - user may not have GitHub connected
        }

        if (active) {
          setSubmissions(allSubmissions)
          setPushHistory(pushes)
        }
      } catch {
        // Silently fail - graphs just won't show data
      } finally {
        if (active) setLoading(false)
      }
    }

    void fetchAll()
    return () => { active = false }
  }, [userId, groupId])

  const dsaData = useMemo(() => {
    const counts = {}
    for (const sub of submissions) {
      const date = istDateKey(sub.solvedAtUtc)
      if (date) counts[date] = (counts[date] || 0) + 1
    }
    return Object.entries(counts).map(([date, count]) => ({ date, count }))
  }, [submissions])

  const ghData = useMemo(() => {
    const counts = {}
    for (const push of pushHistory) {
      if (push.status !== 'SUCCEEDED' || !push.completedAt) continue
      const date = istDateKey(push.completedAt)
      if (date && push.changedFiles > 0) {
        counts[date] = (counts[date] || 0) + (push.changedFiles || 1)
      }
    }
    return Object.entries(counts).map(([date, count]) => ({ date, count }))
  }, [pushHistory])

  const currentYear = new Date().getFullYear()
  const dsaYears = useMemo(() => {
    const years = new Set([currentYear])
    for (const d of dsaData) years.add(parseInt(d.date.slice(0, 4)))
    return [...years].sort((a, b) => b - a)
  }, [dsaData, currentYear])

  const ghYears = useMemo(() => {
    const years = new Set([currentYear])
    for (const d of ghData) years.add(parseInt(d.date.slice(0, 4)))
    return [...years].sort((a, b) => b - a)
  }, [ghData, currentYear])

  if (loading) {
    return (
      <section className="profile-progress-section" aria-label="Progress activity">
        <span className="eyebrow">Activity graphs</span>
        <div className="contribution-graph">
          <div style={{ height: 120, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text-muted)', fontSize: '0.82rem' }}>
            <span className="button-spinner" /> Loading activity data...
          </div>
        </div>
      </section>
    )
  }

  return (
    <section className="profile-progress-section" aria-label="Progress activity">
      <span className="eyebrow">Activity overview</span>

      <div className="progress-graphs">
        {/* DSA Solve Activity */}
        <div>
          <div className="progress-graph-label">
            <span className="graph-icon dsa" aria-hidden="true">{'\u{1F4CA}'}</span>
            <div>
              <strong>DSA Problem Solving</strong>
              <small>Problems solved per day across all platforms</small>
            </div>
          </div>
          <ContributionGraph
            data={dsaData}
            year={dsaYear}
            title="problems solved"
            totalLabel="problems solved"
            colorScheme="purple"
            availableYears={dsaYears}
            onYearChange={setDsaYear}
          />
        </div>

        {/* GitHub Push Activity */}
        {ghData.length > 0 && (
          <div>
            <div className="progress-graph-label">
              <span className="graph-icon github" aria-hidden="true">{'\u{1F4BB}'}</span>
              <div>
                <strong>GitHub Repository Activity</strong>
                <small>Files pushed to your DSA repository</small>
              </div>
            </div>
            <ContributionGraph
              data={ghData}
              year={ghYear}
              title="files pushed"
              totalLabel="files pushed"
              colorScheme="green"
              availableYears={ghYears}
              onYearChange={setGhYear}
            />
          </div>
        )}
      </div>
    </section>
  )
}

export default ProfileProgress
