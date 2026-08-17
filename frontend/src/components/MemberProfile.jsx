import { useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../api/client.js'
import ActivityHeatmap from './ActivityHeatmap.jsx'
import ProfileProgress from './ProfileProgress.jsx'

const PAGE_SIZE = 50
const platforms = [
  { key: 'leetcodeUsername', name: 'LeetCode', short: 'LC', url: (value) => `https://leetcode.com/u/${encodeURIComponent(value)}/` },
  { key: 'codeforcesUsername', name: 'Codeforces', short: 'CF', url: (value) => `https://codeforces.com/profile/${encodeURIComponent(value)}` },
  { key: 'gfgUsername', name: 'GeeksforGeeks', short: 'GFG', url: (value) => `https://www.geeksforgeeks.org/user/${encodeURIComponent(value)}/` },
]

function formatDate(value) {
  if (!value) return 'Date unavailable'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function istDateKey(value) {
  if (!value) return 'unknown'
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date(value))
  const part = (type) => parts.find((item) => item.type === type)?.value
  return `${part('year')}-${part('month')}-${part('day')}`
}

function formatHistoryDay(date) {
  if (date === 'unknown') return 'Date unavailable'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'full', timeZone: 'Asia/Kolkata' })
    .format(new Date(`${date}T00:00:00+05:30`))
}

function MemberProfile({ userId, currentUserId, groupId, onBack }) {
  const [profile, setProfile] = useState(null)
  const [submissions, setSubmissions] = useState([])
  const [page, setPage] = useState(null)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState(null)
  const requestRef = useRef(null)

  useEffect(() => {
    const controller = new AbortController()
    requestRef.current = controller
    setLoading(true)
    setError(null)
    setProfile(null)
    setSubmissions([])
    setPage(null)
    Promise.all([
      api.getUser(userId, controller.signal),
      api.getSubmissions(userId, { page: 0, size: PAGE_SIZE, groupId }, controller.signal),
    ]).then(([userProfile, firstPage]) => {
      setProfile(userProfile)
      setSubmissions(firstPage.content ?? [])
      setPage(firstPage)
    }).catch((requestError) => {
      if (requestError.name !== 'AbortError') setError(requestError.message || 'Could not load this profile.')
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false)
    })
    return () => controller.abort()
  }, [userId, groupId])

  const loadMore = async () => {
    if (!page || page.last || loadingMore) return
    setLoadingMore(true)
    setError(null)
    try {
      const next = await api.getSubmissions(userId, { page: page.page + 1, size: PAGE_SIZE, groupId })
      setSubmissions((current) => [...current, ...(next.content ?? [])])
      setPage(next)
    } catch (requestError) {
      setError(requestError.message || 'Could not load more history.')
    } finally {
      setLoadingMore(false)
    }
  }

  const linkedPlatforms = useMemo(
    () => platforms.filter((platform) => profile?.[platform.key]),
    [profile],
  )
  const platformCounts = useMemo(() => submissions.reduce((counts, item) => {
    counts[item.platform] = (counts[item.platform] ?? 0) + 1
    return counts
  }, {}), [submissions])
  const submissionsByDay = useMemo(() => {
    const grouped = new Map()
    for (const submission of submissions) {
      const date = istDateKey(submission.solvedAtUtc)
      if (!grouped.has(date)) grouped.set(date, [])
      grouped.get(date).push(submission)
    }
    return [...grouped.entries()]
  }, [submissions])

  if (loading) return <section className="profile-state" aria-live="polite"><span className="button-spinner" />Loading profile and solve history…</section>
  if (!profile) return <section className="profile-state"><div className="form-error" role="alert">{error || 'Profile unavailable.'}</div><button type="button" className="button-secondary" onClick={onBack}>Back to group</button></section>

  return (
    <section className="member-profile">
      <button type="button" className="profile-back button-quiet" onClick={onBack}>← Back to group</button>
      <header className="profile-hero">
        <div className="profile-avatar" aria-hidden="true">{profile.name?.trim()?.charAt(0).toUpperCase() || 'U'}</div>
        <div className="profile-heading">
          <span className="eyebrow">{profile.id === currentUserId ? 'Your profile' : 'Member profile'}</span>
          <h2>{profile.name}</h2>
          <p>{page?.totalElements ?? 0} {groupId ? 'group-scoped solutions · History starts on the member’s join date' : 'recorded solutions'}</p>
        </div>
        <div className="profile-kpis">
          <span><strong>{page?.totalElements ?? 0}</strong><small>All solves</small></span>
          <span><strong>{linkedPlatforms.length}</strong><small>Platforms</small></span>
          <span><strong>{submissions.filter((item) => item.countedForTarget).length}</strong><small>Target solves loaded</small></span>
        </div>
      </header>

      <section className="profile-platforms" aria-labelledby="linked-platforms-title">
        <div><span className="eyebrow">Connected accounts</span><h3 id="linked-platforms-title">Linked platforms</h3></div>
        <div className="platform-link-list">
          {linkedPlatforms.map((platform) => <a key={platform.key} href={platform.url(profile[platform.key])} target="_blank" rel="noopener noreferrer">
            <span>{platform.short}</span><div><strong>{platform.name}</strong><small>@{profile[platform.key]}</small></div><em>Open ↗</em>
          </a>)}
          {linkedPlatforms.length === 0 && <p>No coding platforms are linked to this account.</p>}
        </div>
      </section>

      {groupId && <ActivityHeatmap groupId={groupId} userId={userId} />}

      <ProfileProgress userId={userId} groupId={groupId} currentStreak={profile?.currentStreak ?? 0} />

      <section className="submission-history" aria-labelledby="submission-history-title">
        <header>
          <div><span className="eyebrow">Complete activity</span><h3 id="submission-history-title">Solved-question history</h3></div>
          <div className="history-platform-counts">
            {Object.entries(platformCounts).map(([name, count]) => <span key={name}>{name} <strong>{count}</strong></span>)}
          </div>
        </header>
        {error && <div className="leaderboard-error" role="alert">{error}</div>}
        {submissions.length === 0 ? <div className="leaderboard-empty"><span aria-hidden="true">◇</span><strong>No recorded solutions</strong><p>Accepted problems will appear here after platform sync.</p></div> : (
          <div className="submission-days">
            {submissionsByDay.map(([date, daySubmissions]) => <section className="submission-day" key={date} aria-labelledby={`history-day-${date}`}>
              <header><h4 id={`history-day-${date}`}>{formatHistoryDay(date)}</h4><span>{daySubmissions.length} solved</span></header>
              <ol className="submission-list">
                {daySubmissions.map((submission, index) => <li key={`${submission.platform}-${submission.problemId}-${submission.solvedAtUtc}-${index}`}>
                  <span className={`submission-platform platform-${submission.platform?.toLowerCase()}`}>{submission.platform ?? '?'}</span>
                  <div className="submission-main"><strong>{submission.problemName || submission.problemId || 'Untitled problem'}</strong><small>{formatDate(submission.solvedAtUtc)}</small>
                    {submission.tags?.length > 0 && <div>{submission.tags.slice(0, 5).map((tag) => <span key={tag}>{tag}</span>)}</div>}
                  </div>
                  <div className="submission-meta">
                    <span className={`difficulty-badge difficulty-${submission.difficulty?.toLowerCase() || 'unknown'}`}>{submission.difficulty || 'Unrated'}</span>
                    {submission.countedForTarget && <small>Counted</small>}
                  </div>
                </li>)}
              </ol>
            </section>)}
          </div>
        )}
        {page && !page.last && <button type="button" className="load-more button-secondary" onClick={loadMore} disabled={loadingMore}>
          {loadingMore ? 'Loading…' : `Load more (${submissions.length} of ${page.totalElements})`}
        </button>}
        {page?.last && submissions.length > 0 && <p className="history-end">Showing all {page.totalElements} recorded solutions.</p>}
      </section>
    </section>
  )
}

export default MemberProfile