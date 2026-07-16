import { useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../api/client.js'

const ALL = 'all'
const PAGE_SIZE = 40
const MAX_IMPORT_BYTES = 1_000_000
const MAX_PROGRESS_ENTRIES = 1_000
const MAX_NOTE_LENGTH = 5_000
const storageKey = (userId) => `dsaTracker.patternProgress.${userId}`
const isPlainObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value)

function sanitizeProgress(value, allowedSlugs = null) {
  if (!isPlainObject(value)) throw new Error('Invalid progress data')
  const entries = Object.entries(value)
  if (entries.length > MAX_PROGRESS_ENTRIES) throw new Error('Progress data is too large')

  const sanitized = Object.create(null)
  for (const [slug, state] of entries) {
    if (!/^[a-z0-9-]{1,160}$/.test(slug) || (allowedSlugs && !allowedSlugs.has(slug)) || !isPlainObject(state)) {
      throw new Error('Invalid progress entry')
    }
    if (state.completed !== undefined && typeof state.completed !== 'boolean') throw new Error('Invalid completion state')
    if (state.starred !== undefined && typeof state.starred !== 'boolean') throw new Error('Invalid starred state')
    if (state.note !== undefined && (typeof state.note !== 'string' || state.note.length > MAX_NOTE_LENGTH)) {
      throw new Error('Invalid note')
    }
    sanitized[slug] = {
      ...(typeof state.completed === 'boolean' ? { completed: state.completed } : {}),
      ...(typeof state.starred === 'boolean' ? { starred: state.starred } : {}),
      ...(typeof state.note === 'string' ? { note: state.note } : {}),
    }
  }
  return sanitized
}

function readProgress(userId) {
  try {
    return sanitizeProgress(JSON.parse(localStorage.getItem(storageKey(userId)) || '{}'))
  } catch {
    return {}
  }
}

function formatDate(value) {
  if (!value) return 'Not available'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? 'Not available' : date.toLocaleDateString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric',
  })
}

function PatternsCatalog({ userId }) {
  const [catalog, setCatalog] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [roadmapId, setRoadmapId] = useState(ALL)
  const [phaseFilter, setPhaseFilter] = useState(ALL)
  const [difficulty, setDifficulty] = useState(ALL)
  const [pattern, setPattern] = useState(ALL)
  const [company, setCompany] = useState(ALL)
  const [query, setQuery] = useState('')
  const [starredOnly, setStarredOnly] = useState(false)
  const [visibleLimit, setVisibleLimit] = useState(PAGE_SIZE)
  const [noteSlug, setNoteSlug] = useState(null)
  const [progress, setProgress] = useState(() => readProgress(userId))
  const importRef = useRef(null)

  const load = async () => {
    setLoading(true)
    setError(null)
    try {
      setCatalog(await api.getPatternCatalog())
    } catch (requestError) {
      setError(requestError.message || 'Could not load the patterns catalog.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { void load() }, [])
  useEffect(() => {
    try {
      localStorage.setItem(storageKey(userId), JSON.stringify(progress))
    } catch {
      setError('Progress changed, but this browser could not save it. Export a backup before leaving this page.')
    }
  }, [progress, userId])

  const questions = catalog?.questions ?? []
  const roadmaps = catalog?.roadmaps ?? []
  const patterns = useMemo(() => [...new Set(questions.flatMap((item) => item.patterns ?? []))].sort(), [questions])
  const companies = useMemo(() => [...new Set(questions.flatMap((item) => item.companies?.map((entry) => entry.name) ?? []))].sort(), [questions])

  const roadmapContext = useMemo(() => {
    if (roadmapId === ALL) return null
    const roadmap = roadmaps.find((item) => item.id === roadmapId)
    if (!roadmap) return null
    const phaseBySlug = new Map()
    const orderedSlugs = []
    for (const phase of roadmap.phases) {
      for (const slug of phase.questionSlugs) {
        if (!phaseBySlug.has(slug)) orderedSlugs.push(slug)
        phaseBySlug.set(slug, phase.title)
      }
    }
    return { roadmap, phaseBySlug, orderedSlugs }
  }, [roadmapId, roadmaps])

  const filtered = useMemo(() => {
    const bySlug = new Map(questions.map((item) => [item.slug, item]))
    const base = roadmapContext
      ? roadmapContext.orderedSlugs.map((slug) => bySlug.get(slug)).filter(Boolean)
      : questions
    const needle = query.trim().toLowerCase()
    return base.filter((item) => {
      const entry = progress[item.slug] ?? {}
      return (difficulty === ALL || item.difficulty === difficulty)
        && (pattern === ALL || item.patterns?.includes(pattern))
        && (company === ALL || item.companies?.some((value) => value.name === company))
        && (!roadmapContext || phaseFilter === ALL || roadmapContext.phaseBySlug.get(item.slug) === phaseFilter)
        && (!starredOnly || entry.starred)
        && (!needle || item.title?.toLowerCase().includes(needle)
          || item.slug?.includes(needle)
          || item.patterns?.some((value) => value.toLowerCase().includes(needle)))
    })
  }, [questions, roadmapContext, phaseFilter, difficulty, pattern, company, query, starredOnly, progress])

  useEffect(() => { setVisibleLimit(PAGE_SIZE) }, [roadmapId, phaseFilter, difficulty, pattern, company, query, starredOnly])

  const completed = questions.filter((item) => progress[item.slug]?.completed).length
  const roadmapTotal = roadmapContext?.orderedSlugs.length ?? questions.length
  const roadmapCompleted = roadmapContext
    ? roadmapContext.orderedSlugs.filter((slug) => progress[slug]?.completed).length
    : completed
  const completionPercent = roadmapTotal ? Math.round((roadmapCompleted / roadmapTotal) * 100) : 0

  const updateProgress = (slug, patch) => {
    setProgress((current) => ({ ...current, [slug]: { ...(current[slug] ?? {}), ...patch } }))
  }

  const openRandom = () => {
    if (!filtered.length) return
    const item = filtered[Math.floor(Math.random() * filtered.length)]
    window.open(`https://leetcode.com/problems/${item.slug}/`, '_blank', 'noopener,noreferrer')
  }

  const exportProgress = () => {
    const payload = JSON.stringify({ version: 1, exportedAt: new Date().toISOString(), progress }, null, 2)
    const url = URL.createObjectURL(new Blob([payload], { type: 'application/json' }))
    const link = document.createElement('a')
    link.href = url
    link.download = 'dsa-pattern-progress.json'
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }

  const importProgress = async (event) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    try {
      if (file.size > MAX_IMPORT_BYTES) throw new Error('Progress file is too large')
      const parsed = JSON.parse(await file.text())
      if (parsed?.version !== 1) throw new Error('Unsupported progress version')
      const allowedSlugs = new Set(questions.map((question) => question.slug))
      setProgress(sanitizeProgress(parsed.progress, allowedSlugs))
      setError(null)
    } catch {
      setError('That progress file is invalid. Export a fresh file and try again.')
    }
  }

  if (loading) return <section className="patterns-state" aria-live="polite"><span className="app-loader"><span /></span><p>Loading curated questions…</p></section>
  if (error && !catalog) return <section className="patterns-state"><span className="status-icon" aria-hidden="true">!</span><h2>Catalog unavailable</h2><p>{error}</p><button type="button" onClick={load}>Retry</button></section>

  return (
    <section className="patterns-catalog">
      <header className="patterns-hero">
        <div>
          <span className="eyebrow">Interview preparation library</span>
          <h2>LeetCode Patterns</h2>
          <p>Master recurring problem-solving patterns instead of memorizing isolated answers.</p>
        </div>
        <div className="patterns-overall">
          <span><strong>{completed}</strong><small>of {questions.length} solved</small></span>
          <div className="patterns-progress"><span style={{ width: `${questions.length ? Math.round((completed / questions.length) * 100) : 0}%` }} /></div>
        </div>
      </header>

      <nav className="roadmap-tabs" aria-label="Question roadmap">
        <button type="button" aria-pressed={roadmapId === ALL} onClick={() => { setRoadmapId(ALL); setPhaseFilter(ALL) }}>All questions <small>{questions.length}</small></button>
        {roadmaps.map((roadmap) => {
          const count = new Set(roadmap.phases.flatMap((phase) => phase.questionSlugs)).size
          return <button type="button" key={roadmap.id} aria-pressed={roadmapId === roadmap.id} onClick={() => { setRoadmapId(roadmap.id); setPhaseFilter(ALL) }}>{roadmap.name} <small>{count}</small></button>
        })}
      </nav>

      <div className="patterns-summary">
        <div><span className="summary-value">{completionPercent}%</span><span><strong>{roadmapContext?.roadmap.name ?? 'Overall progress'}</strong><small>{roadmapCompleted} of {roadmapTotal} completed</small></span></div>
        <div><span className="summary-value">{patterns.length}</span><span><strong>Patterns</strong><small>Across every difficulty</small></span></div>
        <div><span className="summary-value">{companies.length}</span><span><strong>Companies</strong><small>Six-month frequency data</small></span></div>
      </div>

      {roadmapContext && <div className="roadmap-phases" aria-label={`${roadmapContext.roadmap.name} phases`}>
        {roadmapContext.roadmap.phases.map((phase) => {
          const done = phase.questionSlugs.filter((slug) => progress[slug]?.completed).length
          return <button type="button" key={`${phase.position}-${phase.title}`} aria-pressed={phaseFilter === phase.title}
            onClick={() => setPhaseFilter((current) => current === phase.title ? ALL : phase.title)}>
            <span>{phase.position}</span><strong>{phase.title}</strong><small>{done}/{phase.questionSlugs.length}</small>
          </button>
        })}
      </div>}

      <div className="patterns-toolbar">
        <label className="patterns-search">
          <span className="visually-hidden">Search questions</span>
          <span aria-hidden="true">⌕</span>
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search questions or patterns…" />
        </label>
        <select aria-label="Filter by difficulty" value={difficulty} onChange={(event) => setDifficulty(event.target.value)}>
          <option value={ALL}>All difficulties</option>
          <option value="Easy">Easy</option><option value="Medium">Medium</option><option value="Hard">Hard</option>
        </select>
        <select aria-label="Filter by pattern" value={pattern} onChange={(event) => setPattern(event.target.value)}>
          <option value={ALL}>All patterns</option>
          {patterns.map((value) => <option key={value}>{value}</option>)}
        </select>
        <select aria-label="Filter by company" value={company} onChange={(event) => setCompany(event.target.value)}>
          <option value={ALL}>All companies</option>
          {companies.map((value) => <option key={value}>{value}</option>)}
        </select>
      </div>

      <div className="patterns-actions">
        <span><strong>{filtered.length}</strong> matching questions</span>
        <div>
          <button type="button" className={`button-secondary${starredOnly ? ' active' : ''}`} onClick={() => setStarredOnly((value) => !value)}>★ Starred</button>
          <button type="button" className="button-secondary" onClick={openRandom} disabled={!filtered.length}>Random</button>
          <button type="button" className="button-secondary" onClick={exportProgress}>Export</button>
          <button type="button" className="button-secondary" onClick={() => importRef.current?.click()}>Import</button>
          <input ref={importRef} className="visually-hidden" type="file" accept="application/json" onChange={importProgress} />
        </div>
      </div>

      {error && <div className="alert-banner" role="alert"><span aria-hidden="true">!</span>{error}</div>}

      <ol className="pattern-question-list">
        {filtered.slice(0, visibleLimit).map((question, index) => {
          const state = progress[question.slug] ?? {}
          const phase = roadmapContext?.phaseBySlug.get(question.slug)
          const topCompanies = [...(question.companies ?? [])].sort((a, b) => b.frequency - a.frequency).slice(0, 4)
          return <li key={question.slug} className={state.completed ? 'is-complete' : ''}>
            <div className="question-check">
              <input id={`done-${question.slug}`} type="checkbox" checked={Boolean(state.completed)} onChange={(event) => updateProgress(question.slug, { completed: event.target.checked })} />
              <label htmlFor={`done-${question.slug}`}><span className="visually-hidden">Mark {question.title} complete</span></label>
            </div>
            <div className="question-main">
              <div className="question-title-row">
                <span className="question-number">{roadmapContext ? index + 1 : question.id}</span>
                <a href={`https://leetcode.com/problems/${question.slug}/`} target="_blank" rel="noopener noreferrer">{question.title}{question.premium && <span className="premium-lock" title="LeetCode Premium">◆</span>}</a>
                <span className={`difficulty-badge difficulty-${question.difficulty.toLowerCase()}`}>{question.difficulty}</span>
              </div>
              {phase && <span className="phase-label">{phase}</span>}
              <div className="question-patterns">{question.patterns?.map((value) => <button type="button" key={value} onClick={() => setPattern(value)}>{value}</button>)}</div>
              <div className="question-companies">
                {topCompanies.length > 0 ? topCompanies.map((entry) => <button type="button" key={entry.slug} onClick={() => setCompany(entry.name)} title={`${entry.name}: ${entry.frequency} appearances in the source's six-month window`}><strong>{entry.name}</strong><span>{entry.frequency}</span></button>) : <span>No recent company frequency data</span>}
              </div>
              {noteSlug === question.slug && <label className="question-note">
                <span>Personal note</span>
                <textarea value={state.note ?? ''} maxLength={MAX_NOTE_LENGTH} onChange={(event) => updateProgress(question.slug, { note: event.target.value })} placeholder="Write your approach, complexity, or what to review next…" autoFocus />
              </label>}
            </div>
            <div className="question-actions">
              <button type="button" className={`star-button${state.starred ? ' active' : ''}`} onClick={() => updateProgress(question.slug, { starred: !state.starred })} aria-label={`${state.starred ? 'Remove' : 'Add'} star for ${question.title}`}>★</button>
              <button type="button" className={`note-button${state.note ? ' has-note' : ''}`} onClick={() => setNoteSlug((current) => current === question.slug ? null : question.slug)} aria-expanded={noteSlug === question.slug}>Note</button>
            </div>
          </li>
        })}
      </ol>

      {filtered.length === 0 && <div className="patterns-empty"><span aria-hidden="true">◇</span><h3>No questions match</h3><p>Clear a filter or try a different search.</p></div>}
      {visibleLimit < filtered.length && <button type="button" className="load-more button-secondary" onClick={() => setVisibleLimit((value) => value + PAGE_SIZE)}>Show {Math.min(PAGE_SIZE, filtered.length - visibleLimit)} more questions</button>}

      <footer className="catalog-attribution">
        <div><strong>Source updated {formatDate(catalog?.sourceUpdatedAt)}</strong><span>{catalog?.syncHealthy ? `Checked ${formatDate(catalog.checkedAt)}` : 'Latest check failed; showing the last available snapshot.'}</span></div>
        <p>Question metadata and roadmap ordering adapted from <a href={catalog?.sourceUrl} target="_blank" rel="noopener noreferrer">{catalog?.sourceName}</a> under <a href={catalog?.licenseUrl} target="_blank" rel="noopener noreferrer">{catalog?.licenseName}</a>. Changes include a new interface and local progress tools. Non-commercial use only. Company frequencies originate from the source’s LeetCode Premium-derived six-month data. This app is not affiliated with LeetCode.</p>
      </footer>
    </section>
  )
}

export default PatternsCatalog
