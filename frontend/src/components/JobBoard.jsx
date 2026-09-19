import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from '../api/client.js'

const emptyForm = { title: '', company: '', jobUrl: '' }
const emptyProfileForm = { roleTitle: '', keywords: '', resumeText: '', resumeFileName: '' }
const emptySourceForm = { url: '', label: '' }

function formatPostedAt(value) {
  if (!value) return 'Recently posted'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function hostName(value) {
  try { return new URL(value).hostname.replace(/^www\./, '') } catch { return 'External site' }
}

// How a source's last extraction attempt is presented. NONE/ERROR read as red so
// portals needing a dedicated extractor stay visible instead of looking merely empty.
const EXTRACTION_STATUS = {
  FULL: { label: 'Extracting', tone: 'ok', hint: 'Jobs are being pulled from this portal\u2019s API.' },
  LIMITED: { label: 'Partial', tone: 'warn', hint: 'Only basic job links could be read; details may be missing.' },
  NONE: { label: 'Cannot extract', tone: 'bad', hint: 'This site loads jobs with JavaScript. It needs a dedicated extractor.' },
  ERROR: { label: 'Failed', tone: 'bad', hint: 'The site could not be fetched on the last attempt.' },
}

const JOB_TAB_STORAGE_KEY = 'dsaTracker.jobBoard.tab'
const VALID_JOB_TABS = new Set(['all', 'roles', 'sources'])

function readStoredJobTab() {
  try {
    const value = localStorage.getItem(JOB_TAB_STORAGE_KEY)
    return VALID_JOB_TABS.has(value) ? value : 'all'
  } catch { return 'all' }
}

function storeJobTab(tab) {
  try { localStorage.setItem(JOB_TAB_STORAGE_KEY, tab) } catch { /* Tab still works in memory. */ }
}

function JobBoard() {
  const [tab, setTabState] = useState(() => readStoredJobTab()) // 'all' | 'roles' | 'sources'
  const setTab = (value) => { setTabState(value); storeJobTab(value) }

  // --- All jobs state ---
  const [page, setPage] = useState(null)
  const [pageNumber, setPageNumber] = useState(0)
  const [form, setForm] = useState(emptyForm)
  const [fieldErrors, setFieldErrors] = useState({})
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState('all')
  const [roleFilter, setRoleFilter] = useState('All 0-Exp Jobs')
  const [loading, setLoading] = useState(true)
  const [creating, setCreating] = useState(false)
  const [updatingId, setUpdatingId] = useState(null)
  const [error, setError] = useState(null)
  const [success, setSuccess] = useState(null)

  // --- Profiles state ---
  const [profiles, setProfiles] = useState([])
  const [profileForm, setProfileForm] = useState(emptyProfileForm)
  const [profileFieldErrors, setProfileFieldErrors] = useState({})
  const [profileLoading, setProfileLoading] = useState(false)
  const [profileCreating, setProfileCreating] = useState(false)
  const [profileError, setProfileError] = useState(null)
  const [profileSuccess, setProfileSuccess] = useState(null)
  const [activeProfile, setActiveProfile] = useState(null)
  const [resumeUploading, setResumeUploading] = useState(false)
  const [resumeDragOver, setResumeDragOver] = useState(false)
  const [resumeParseResult, setResumeParseResult] = useState(null)
  const [experienceFilter, setExperienceFilter] = useState('')
  const [expandedCompany, setExpandedCompany] = useState(null)
  const [companyScraping, setCompanyScraping] = useState(null)

  // --- Sources state ---
  const [sources, setSources] = useState([])
  const [sourceQuery, setSourceQuery] = useState('')
  const [editingSource, setEditingSource] = useState(null)
  const [sourceForm, setSourceForm] = useState(emptySourceForm)
  const [sourceFieldErrors, setSourceFieldErrors] = useState({})
  const [sourceLoading, setSourceLoading] = useState(false)
  const [sourceAdding, setSourceAdding] = useState(false)
  const [scrapingId, setScrapingId] = useState(null)
  const [sourceError, setSourceError] = useState(null)
  const [sourceSuccess, setSourceSuccess] = useState(null)
  const [expandedSource, setExpandedSource] = useState(null)
  const [sourceJobs, setSourceJobs] = useState({})
  const [sourceJobsLoading, setSourceJobsLoading] = useState(null)
  const [supportCheck, setSupportCheck] = useState(null)
  const [supportChecking, setSupportChecking] = useState(false)
  const [deletingId, setDeletingId] = useState(null)

  // --- Load jobs ---
  const loadJobs = useCallback(async (requestedPage = pageNumber) => {
    setLoading(true)
    setError(null)
    try {
      const result = await api.getJobs({ page: requestedPage, size: 20 })
      setPage(result)
      setPageNumber(result.page)
    } catch (e) {
      setError(e.message || 'Could not load job opportunities.')
    } finally {
      setLoading(false)
    }
  }, [pageNumber])

  useEffect(() => { void loadJobs(0) }, [])

  // --- Load profiles ---
  const loadProfiles = useCallback(async () => {
    setProfileLoading(true)
    setProfileError(null)
    try {
      const params = {}
      if (experienceFilter !== '') params.experience = parseInt(experienceFilter, 10)
      const result = await api.getJobProfiles(params)
      setProfiles(result)
      if (result.length && !activeProfile) setActiveProfile(result[0].id)
    } catch (e) {
      setProfileError(e.message || 'Could not load profiles.')
    } finally {
      setProfileLoading(false)
    }
  }, [activeProfile, experienceFilter])

  const [groqLimits, setGroqLimits] = useState(null)

  const loadGroqLimits = useCallback(async () => {
    try {
      setGroqLimits(await api.getGroqLimits())
    } catch (e) {
      // Ignore gracefully
    }
  }, [])

  useEffect(() => { if (tab === 'roles') { void loadProfiles(); void loadGroqLimits(); } }, [tab, experienceFilter])

  // --- Load sources ---
  const loadSources = useCallback(async () => {
    setSourceLoading(true)
    setSourceError(null)
    try {
      setSources(await api.getJobSources())
      void loadGroqLimits()
    } catch (e) {
      setSourceError(e.message || 'Could not load sources.')
    } finally {
      setSourceLoading(false)
    }
  }, [])

  useEffect(() => { if (tab === 'sources') void loadSources() }, [tab])

  // Probe a pasted career URL so the user learns whether it can be extracted
  // before saving it, rather than discovering empty results after the fact.
  useEffect(() => {
    const url = sourceForm.url.trim()
    if (!/^https?:\/\/.+\..+/i.test(url)) {
      setSupportCheck(null)
      setSupportChecking(false)
      return
    }
    const controller = new AbortController()
    const timer = setTimeout(async () => {
      setSupportChecking(true)
      try {
        const result = await api.checkJobSource(url, controller.signal)
        setSupportCheck(result)
        // Offer the detected company as the label when the user hasn't typed one.
        if (result?.detectedCompany) {
          setSourceForm((c) => c.label.trim() ? c : { ...c, label: result.detectedCompany })
        }
      } catch (e) {
        if (e.name !== 'AbortError') setSupportCheck(null)
      } finally {
        setSupportChecking(false)
      }
    }, 700)
    return () => { clearTimeout(timer); controller.abort() }
  }, [sourceForm.url])

  // --- Job form handlers ---
  const updateForm = (field) => (event) => {
    setForm((c) => ({ ...c, [field]: event.target.value }))
    setFieldErrors((c) => ({ ...c, [field]: undefined }))
    setSuccess(null)
  }

  const createJob = async (event) => {
    event.preventDefault()
    setCreating(true); setError(null); setSuccess(null); setFieldErrors({})
    try {
      await api.createJob({ title: form.title.trim(), company: form.company.trim(), jobUrl: form.jobUrl.trim() })
      setForm(emptyForm)
      setSuccess('Job opportunity shared with everyone.')
      await loadJobs(0)
    } catch (e) {
      if (e.fieldErrors) setFieldErrors(e.fieldErrors)
      else setError(e.message || 'Could not share this job.')
    } finally { setCreating(false) }
  }

  const toggleApplied = async (job) => {
    setUpdatingId(job.id); setError(null)
    try {
      const updated = job.applied ? await api.unmarkJobApplied(job.id) : await api.markJobApplied(job.id)
      setPage((c) => ({ ...c, content: c.content.map((i) => i.id === updated.id ? updated : i) }))
      setSuccess(updated.applied ? 'Marked as applied.' : 'Moved back to not applied.')
    } catch (e) { setError(e.message || 'Could not update status.') }
    finally { setUpdatingId(null) }
  }

  // --- Profile handlers ---
  const updateProfileForm = (field) => (event) => {
    setProfileForm((c) => ({ ...c, [field]: event.target.value }))
    setProfileFieldErrors((c) => ({ ...c, [field]: undefined }))
    setProfileSuccess(null)
  }

  const createProfile = async (event) => {
    event.preventDefault()
    setProfileCreating(true); setProfileError(null); setProfileSuccess(null); setProfileFieldErrors({})
    try {
      const created = await api.createJobProfile({
        roleTitle: profileForm.roleTitle.trim(),
        keywords: profileForm.keywords.trim(),
        resumeText: profileForm.resumeText.trim() || null,
        resumeFileName: profileForm.resumeFileName || null,
      })
      setProfileForm(emptyProfileForm)
      setResumeParseResult(null)
      setProfileSuccess(`Profile "${created.roleTitle}" created.`)
      setProfiles((c) => [created, ...c])
      setActiveProfile(created.id)
      // Reload with matched jobs
      void loadProfiles()
    } catch (e) {
      if (e.fieldErrors) setProfileFieldErrors(e.fieldErrors)
      else setProfileError(e.message || 'Could not create profile.')
    } finally { setProfileCreating(false) }
  }

  const handleResumeUpload = async (file) => {
    if (!file) return
    if (!file.name.toLowerCase().endsWith('.pdf')) {
      setProfileError('Only PDF files are supported. Please upload a .pdf file.')
      return
    }
    if (file.size > 5 * 1024 * 1024) {
      setProfileError('File too large. Maximum size is 5 MB.')
      return
    }
    setResumeUploading(true); setProfileError(null); setProfileSuccess(null)
    try {
      const result = await api.uploadResume(file)
      if (result.suggestedRole) {
        setProfileForm((c) => ({
          ...c,
          roleTitle: result.suggestedRole,
          keywords: result.detectedKeywords || c.keywords,
          resumeText: result.extractedText || c.resumeText,
          resumeFileName: result.fileName || file.name,
        }))
        setResumeParseResult(result)
        setProfileSuccess(`Resume parsed! Detected profile: ${result.suggestedRole}. Review and create.`)
      } else {
        setProfileError('Could not extract meaningful content from the PDF. Try pasting your resume text instead.')
      }
    } catch (e) {
      setProfileError(e.message || 'Failed to parse resume. Try pasting the text instead.')
    } finally { setResumeUploading(false) }
  }

  const onResumeDrop = (e) => {
    e.preventDefault()
    setResumeDragOver(false)
    const file = e.dataTransfer?.files?.[0]
    if (file) void handleResumeUpload(file)
  }

  const onResumeFileSelect = (e) => {
    const file = e.target?.files?.[0]
    if (file) void handleResumeUpload(file)
    e.target.value = '' // Reset so the same file can be re-selected
  }

  const scrapeCompanySource = async (sourceId) => {
    if (!sourceId) return
    setCompanyScraping(sourceId); setProfileError(null); setProfileSuccess(null)
    try {
      const result = await api.scrapeJobSource(sourceId)
      if (result.error) setProfileError(result.error)
      else setProfileSuccess(`Found ${result.newListings} new job listing${result.newListings !== 1 ? 's' : ''}.`)
      void loadProfiles()
    } catch (e) { setProfileError(e.message || 'Scrape failed.') }
    finally { setCompanyScraping(null) }
  }

  const deleteProfile = async (id) => {
    try {
      await api.deleteJobProfile(id)
      setProfiles((c) => c.filter((p) => p.id !== id))
      if (activeProfile === id) setActiveProfile(null)
    } catch (e) { setProfileError(e.message || 'Could not delete profile.') }
  }

  // --- Source handlers ---
  const updateSourceForm = (field) => (event) => {
    setSourceForm((c) => ({ ...c, [field]: event.target.value }))
    setSourceFieldErrors((c) => ({ ...c, [field]: undefined }))
    setSourceSuccess(null)
  }

  const addSource = async (event) => {
    event.preventDefault()
    setSourceAdding(true); setSourceError(null); setSourceSuccess(null); setSourceFieldErrors({})
    try {
      const added = await api.addJobSource({ url: sourceForm.url.trim(), label: sourceForm.label.trim() || null })
      setSourceForm(emptySourceForm)
      setSupportCheck(null)
      setSources((c) => [added, ...c])
      // The backend attempts extraction immediately, so report what it found.
      const outcome = EXTRACTION_STATUS[added.extractionStatus]
      if (outcome?.tone === 'bad') {
        setSourceError(`Added, but nothing could be extracted. ${outcome.hint}`)
      } else {
        setSourceSuccess(outcome?.tone === 'warn'
          ? 'Added. Only basic job links could be read from this site.'
          : 'Added and jobs are loading. The rest of this board fills in automatically.')
      }
      void loadJobs(0)
    } catch (e) {
      if (e.fieldErrors) setSourceFieldErrors(e.fieldErrors)
      else setSourceError(e.message || 'Could not add source.')
    } finally { setSourceAdding(false) }
  }

  const scrapeSource = async (id, profileId = null) => {
    setScrapingId(id); setSourceError(null); setSourceSuccess(null)
    try {
      const result = await api.scrapeJobSource(id, profileId)
      if (result.error) {
        setSourceError(result.error)
      } else if (result.newListings === 0) {
        setSourceSuccess('No new jobs in this batch — everything fetched is already on the board.')
      } else {
        setSourceSuccess(`Added ${result.newListings} new job${result.newListings !== 1 ? 's' : ''}. `
          + 'The rest of this board keeps loading automatically in the background.')
      }
      void loadSources()
      void loadJobs(0)
      // Freshly scraped jobs invalidate whatever was cached for this source.
      setSourceJobs((c) => { const next = { ...c }; delete next[id]; return next })
      if (expandedSource === id) void loadSourceJobs(id)
    } catch (e) { setSourceError(e.message || 'Scrape failed.') }
    finally { setScrapingId(null) }
  }

  const loadSourceJobs = async (sourceId) => {
    setSourceJobsLoading(sourceId)
    try {
      const jobs = await api.getSourceListings(sourceId)
      setSourceJobs((prev) => ({ ...prev, [sourceId]: jobs }))
    } catch (e) {
      setSourceError(e.message || 'Could not load jobs for this source.')
    } finally { setSourceJobsLoading(null) }
  }

  const toggleSourceExpand = (sourceId) => {
    if (expandedSource === sourceId) {
      setExpandedSource(null)
    } else {
      setExpandedSource(sourceId)
      if (!sourceJobs[sourceId]) void loadSourceJobs(sourceId)
    }
  }

  const deleteSource = async (id) => {
    const target = sources.find((s) => s.id === id)
    const name = target?.label || (target ? hostName(target.url) : 'this source')
    if (!window.confirm(`Remove ${name}? Jobs already fetched from it stay on the board.`)) return

    setDeletingId(id); setSourceError(null); setSourceSuccess(null)
    try {
      await api.deleteJobSource(id)
      setSources((c) => c.filter((s) => s.id !== id))
      // Drop any cached listings and collapse the panel if it was open.
      setSourceJobs((c) => { const next = { ...c }; delete next[id]; return next })
      if (expandedSource === id) setExpandedSource(null)
      setSourceSuccess(`Removed ${name}.`)
    } catch (e) {
      setSourceError(e.message || 'Failed to remove source')
    } finally {
      setDeletingId(null)
    }
  }

  const updateSource = async (id) => {
    setSourceError(null); setSourceSuccess(null)
    try {
      const updated = await api.updateJobSource(id, sourceForm.url, sourceForm.label)
      setSources((c) => c.map((s) => s.id === id ? updated : s))
      setEditingSource(null)
      setSourceSuccess('Source updated successfully.')
    } catch (e) {
      setSourceError(e.message || 'Failed to update source')
    }
  }

  // --- Filtered jobs ---
  const visibleJobs = useMemo(() => {
    const needle = query.trim().toLowerCase()
    return (page?.content || []).filter((job) => {
      const matchesStatus = filter === 'all' || (filter === 'applied' ? job.applied : !job.applied)
      const matchesQuery = !needle || job.title.toLowerCase().includes(needle) || job.company.toLowerCase().includes(needle)
      const matchesRole = tab !== 'roles' || (roleFilter === 'All 0-Exp Jobs' ? job.detectedRole != null : job.detectedRole === roleFilter)
      return matchesStatus && matchesQuery && matchesRole
    })
  }, [page, query, filter, roleFilter, tab])

  const appliedCount = page?.content?.filter((job) => job.applied).length || 0
  const currentProfile = profiles.find((p) => p.id === activeProfile)

  return (
    <section className="job-board" aria-label="Community job board">
      <header className="jobs-hero">
        <div>
          <span className="eyebrow">Community opportunities</span>
          <h2>Find your next role</h2>
          <p>Share openings, create role profiles to auto-match jobs, and add career sites to scrape.</p>
        </div>
        <div className="jobs-hero-stats" aria-label="Job board summary">
          <span><strong>{page?.totalElements ?? 0}</strong><small>shared roles</small></span>
          <span><strong>{profiles.length || '—'}</strong><small>my profiles</small></span>
          <span>
            <strong style={{ fontSize: groqLimits?.requestsLeft >= 0 ? '1.25rem' : undefined }}>
              {groqLimits?.requestsLeft >= 0 ? `${groqLimits.requestsLeft} req` : '—'}
            </strong>
            <small>Groq API limit</small>
          </span>
          <span><strong>{appliedCount}</strong><small>applied</small></span>
        </div>
      </header>

      {/* Tab bar */}
      <nav className="jobs-tabs" aria-label="Job board sections">
        {[['all', 'All Jobs'], ['roles', 'My Roles'], ['sources', 'Sources']].map(([key, label]) =>
          <button key={key} type="button" className="button-secondary"
            aria-pressed={tab === key} onClick={() => setTab(key)}>{label}</button>
        )}
      </nav>

      {/* ===== ALL JOBS TAB ===== */}
      {tab === 'all' && <div className="jobs-layout">
        <aside className="job-share-card card">
          <span className="eyebrow">Add an opportunity</span>
          <h3>Share a job link</h3>
          <p>Post a direct application or official job listing. It becomes visible to every logged-in member.</p>
          <form onSubmit={createJob} aria-busy={creating}>
            <label className="field"><span>Job title</span>
              <input value={form.title} onChange={updateForm('title')} required maxLength="200" placeholder="Software Engineer" aria-invalid={Boolean(fieldErrors.title)} />
              {fieldErrors.title && <small className="field-error">{fieldErrors.title}</small>}
            </label>
            <label className="field"><span>Company</span>
              <input value={form.company} onChange={updateForm('company')} required maxLength="200" placeholder="Company name" aria-invalid={Boolean(fieldErrors.company)} />
              {fieldErrors.company && <small className="field-error">{fieldErrors.company}</small>}
            </label>
            <label className="field"><span>Application link</span>
              <input type="url" value={form.jobUrl} onChange={updateForm('jobUrl')} required maxLength="2048" placeholder="https://company.com/jobs/..." aria-invalid={Boolean(fieldErrors.jobUrl)} />
              {fieldErrors.jobUrl && <small className="field-error">{fieldErrors.jobUrl}</small>}
            </label>
            <button type="submit" className="job-share-submit" disabled={creating}>
              {creating ? <><span className="button-spinner" />Sharing…</> : 'Share opportunity'}
            </button>
          </form>
        </aside>

        <div className="jobs-feed">
          <div className="jobs-toolbar">
            <label className="jobs-search"><span className="visually-hidden">Search jobs</span><span aria-hidden="true">⌕</span>
              <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search role or company…" />
            </label>
            <div className="jobs-filter" role="group" aria-label="Filter application status">
              {[['all', 'All'], ['open', 'Not applied'], ['applied', 'Applied']].map(([v, l]) =>
                <button key={v} type="button" className="button-secondary" aria-pressed={filter === v} onClick={() => setFilter(v)}>{l}</button>)}
            </div>
          </div>
          
          {error && <div className="form-error" role="alert"><span>!</span>{error}</div>}
          {success && <p className="job-success" role="status">{success}</p>}
          {loading ? <div className="jobs-loading"><span className="button-spinner" />Loading opportunities…</div>
            : visibleJobs.length === 0 ? <div className="empty-state jobs-empty">
              <span className="empty-state-icon" aria-hidden="true">⌕</span>
              <h3>{page?.totalElements ? 'No matching opportunities' : 'No jobs shared yet'}</h3>
              <p>{page?.totalElements ? 'Try another search or filter.' : 'Be the first to share an opportunity.'}</p>
            </div> : <ol className="job-list">
              {visibleJobs.map((job) => <JobCard key={job.id} job={job} updatingId={updatingId} onToggle={toggleApplied} />)}
            </ol>}
          {page && page.totalPages > 1 && <nav className="jobs-pagination" aria-label="Job listing pages">
            <button type="button" className="button-secondary" disabled={page.first || loading} onClick={() => loadJobs(page.page - 1)}>← Newer</button>
            <span>Page {page.page + 1} of {page.totalPages}</span>
            <button type="button" className="button-secondary" disabled={page.last || loading} onClick={() => loadJobs(page.page + 1)}>Older →</button>
          </nav>}
        </div>
      </div>}

      {/* ===== MY ROLES TAB ===== */}
      {tab === 'roles' && <div className="jobs-roles-layout">
        <aside className="job-share-card card">
          <span className="eyebrow">Target Roles</span>
          <h3>Add a new target role</h3>
          <p>Define a role title and keywords. Jobs from sources will be matched using regex and the Groq AI API against these rules.</p>
          <form onSubmit={createProfile} aria-busy={profileCreating}>
            <div className="source-edit-fields">
              <label className="field"><span>Role Title</span>
                <input value={profileForm.roleTitle} onChange={updateProfileForm('roleTitle')} required placeholder="e.g. AI Engineer" aria-invalid={Boolean(profileFieldErrors.roleTitle)} />
                {profileFieldErrors.roleTitle && <small className="field-error">{profileFieldErrors.roleTitle}</small>}
              </label>
              <label className="field"><span>Keywords (comma separated)</span>
                <input value={profileForm.keywords} onChange={updateProfileForm('keywords')} required placeholder="python, machine learning, gcp" aria-invalid={Boolean(profileFieldErrors.keywords)} />
                {profileFieldErrors.keywords && <small className="field-error">{profileFieldErrors.keywords}</small>}
              </label>
            </div>
            {profileError && <div className="form-error" role="alert"><span>!</span>{profileError}</div>}
            {profileSuccess && <p className="job-success" role="status">{profileSuccess}</p>}
            <button type="submit" className="job-share-submit" disabled={profileCreating}>
              {profileCreating ? <><span className="button-spinner" />Adding...</> : 'Add target role'}
            </button>
          </form>
        </aside>

        <div className="jobs-feed">
          <div className="jobs-toolbar">
            <label className="jobs-search"><span className="visually-hidden">Search jobs</span><span aria-hidden="true">⌕</span>
              <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search role or company…" />
            </label>
            <div className="jobs-filter" role="group" aria-label="Filter application status">
              {[['all', 'All'], ['open', 'Not applied'], ['applied', 'Applied']].map(([v, l]) =>
                <button key={v} type="button" className="button-secondary" aria-pressed={filter === v} onClick={() => setFilter(v)}>{l}</button>)}
            </div>
          </div>
          
          <div className="jobs-filter" style={{ marginTop: '0.5rem', marginBottom: '1rem', flexWrap: 'wrap' }} role="group" aria-label="Filter by Job Role">
             <button type="button" className="button-secondary" aria-pressed={roleFilter === 'All 0-Exp Jobs'} onClick={() => setRoleFilter('All 0-Exp Jobs')}>All 0-Exp Jobs</button>
             {profiles.map((profile) => (
                <button key={profile.id} type="button" className="button-secondary" aria-pressed={roleFilter === profile.roleTitle} onClick={() => setRoleFilter(profile.roleTitle)}>
                  {profile.roleTitle}
                </button>
             ))}
          </div>
          
          {error && <div className="form-error" role="alert"><span>!</span>{error}</div>}
          {loading ? <div className="jobs-loading"><span className="button-spinner" />Loading opportunities…</div>
            : visibleJobs.length === 0 ? <div className="empty-state jobs-empty">
              <span className="empty-state-icon" aria-hidden="true">⌕</span>
              <h3>No matching opportunities</h3>
              <p>Try selecting a different role or wait for more jobs to be scraped.</p>
            </div> : <ol className="job-list">
              {visibleJobs.map((job) => <JobCard key={job.id} job={job} updatingId={updatingId} onToggle={toggleApplied} />)}
            </ol>}
        </div>
      </div>}


      {/* ===== SOURCES TAB ===== */}
      {tab === 'sources' && <div className="jobs-sources-layout">
        <aside className="job-share-card card">
          <span className="eyebrow">Add a career site</span>
          <h3>Fetch jobs from a company</h3>
          <p>Paste a careers URL and we'll tell you straight away whether it can be extracted.
            Boards on Greenhouse, Lever, Ashby and Accenture are fully supported. Large boards
            keep loading in the background until every job is stored, then refresh daily.</p>
          <form onSubmit={addSource} aria-busy={sourceAdding}>
            <div className="source-edit-fields">
              <label className="field"><span>Career page URL</span>
                <input type="url" value={sourceForm.url} onChange={updateSourceForm('url')} required maxLength="2048" placeholder="https://company.com/careers" aria-invalid={Boolean(sourceFieldErrors.url)} />
                {sourceFieldErrors.url && <small className="field-error">{sourceFieldErrors.url}</small>}
              </label>
              <label className="field"><span>Company name / label</span>
                <input value={sourceForm.label} onChange={updateSourceForm('label')} maxLength="200" placeholder="e.g. Google Careers" />
              </label>
            </div>

            {/* Extraction verdict for the pasted URL */}
            {supportChecking && (
              <div className="support-check checking">
                <span className="button-spinner" />Checking whether this portal can be extracted…
              </div>
            )}
            {!supportChecking && supportCheck && (
              <div className={`support-check level-${supportCheck.level.toLowerCase()}`} role="status">
                <div className="support-check-head">
                  <span className="support-check-icon" aria-hidden="true">
                    {supportCheck.level === 'FULL' ? '✓'
                      : supportCheck.level === 'LIMITED' ? '!'
                        : supportCheck.level === 'ERROR' ? '×' : '×'}
                  </span>
                  <strong>
                    {supportCheck.level === 'FULL' ? 'Fully supported'
                      : supportCheck.level === 'LIMITED' ? 'Partly supported'
                        : supportCheck.level === 'ERROR' ? 'Could not verify' : 'Not supported'}
                  </strong>
                  {supportCheck.adapter && <span className="support-check-tag">{supportCheck.adapter}</span>}
                </div>
                <p>{supportCheck.message}</p>
                {supportCheck.sampleTitles?.length > 0 && (
                  <ul className="support-check-samples">
                    {supportCheck.sampleTitles.map((t) => <li key={t}>{t}</li>)}
                  </ul>
                )}
              </div>
            )}

            <button type="submit" className="job-share-submit" disabled={sourceAdding}>
              {sourceAdding ? <><span className="button-spinner" />Adding…</> : 'Add source'}
            </button>
          </form>
        </aside>

        <div className="jobs-feed">
          <div className="jobs-toolbar">
            <label className="jobs-search"><span className="visually-hidden">Search sources</span><span aria-hidden="true">⌕</span>
              <input value={sourceQuery} onChange={(e) => setSourceQuery(e.target.value)} placeholder="Search sources by name or URL…" />
            </label>
          </div>
          {sourceError && <div className="form-error" role="alert"><span>!</span>{sourceError}</div>}
          {sourceSuccess && <p className="job-success" role="status">{sourceSuccess}</p>}
          {sourceLoading ? <div className="jobs-loading"><span className="button-spinner" />Loading sources…</div>
            : sources.length === 0 ? <div className="empty-state jobs-empty">
              <span className="empty-state-icon" aria-hidden="true">🔗</span>
              <h3>No career sites added</h3>
              <p>Add a careers page URL and scrape it to discover job listings for the community.</p>
            </div> : <div className="company-groups">
              {sources
                .filter(s => !sourceQuery || (s.label || '').toLowerCase().includes(sourceQuery.toLowerCase()) || (s.url || '').toLowerCase().includes(sourceQuery.toLowerCase()))
                .map((source) => {
                const status = EXTRACTION_STATUS[source.extractionStatus]
                return (
                <div key={source.id} className={`company-group-card${expandedSource === source.id ? ' expanded' : ''}${status ? ` status-${status.tone}` : ''}`}>
                  <div className="company-group-header" onClick={() => toggleSourceExpand(source.id)}>
                    <div className="company-group-info">
                      <div className="company-group-mark" aria-hidden="true">
                        {(source.label || hostName(source.url)).charAt(0).toUpperCase()}
                      </div>
                      <div>
                        <h4>
                          {source.label || hostName(source.url)}
                          {status && (
                            <span className={`extraction-badge tone-${status.tone}`} title={status.hint}>
                              {status.label}
                            </span>
                          )}
                        </h4>
                        <div className="source-url-wrapper">
                          <a href={source.url} target="_blank" rel="noopener noreferrer" className="source-url-link" title={source.url} onClick={(e) => e.stopPropagation()}>{source.url}</a>
                          <button type="button" className="copy-url-btn" title="Copy URL" onClick={(e) => {
                            e.stopPropagation();
                            navigator.clipboard.writeText(source.url);
                          }}>
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                          </button>
                        </div>
                        <div className="source-meta">
                          <span>Added by {source.addedByName}</span>
                          {source.lastScrapedAt && <span>Last extracted: {formatPostedAt(source.lastScrapedAt)}</span>}
                          {source.adapter && <span className="source-adapter-tag">API sync</span>}
                          
                          {source.lastScrapeTotalJobs != null && (
                              <span title="Total jobs seen by the scraper in the latest run" className="source-stat-pill">
                                  <strong>{source.lastScrapeTotalJobs}</strong> jobs seen
                              </span>
                          )}
                          <span title="Jobs matching your target roles and 0-experience criteria" className="source-stat-pill success">
                              <strong>{source.storedListings}</strong> job{source.storedListings === 1 ? '' : 's'} related to you
                          </span>

                          {source.syncCursor > 0 &&
                            <span title="Continues automatically every few minutes">
                              Still scraping… (checked {source.syncCursor} so far)
                            </span>}
                          {source.sweepCompletedAt && source.syncCursor === 0 &&
                            <span>Fully scraped as of: {formatPostedAt(source.sweepCompletedAt)}</span>}
                          {source.lastError && <span className="source-error-note">Error: {source.lastError}</span>}
                        </div>
                      </div>
                    </div>
                    <div className="company-group-actions">
                      <select 
                        id={`scrape-profile-select-${source.id}`} 
                        className="source-profile-select button-quiet"
                        style={{ padding: '0.4rem', border: '1px solid var(--line-strong)', borderRadius: 'var(--radius-sm)' }}
                        onClick={(e) => e.stopPropagation()}
                      >
                        <option value="">All target roles</option>
                        {profiles.map(p => <option key={p.id} value={p.id}>{p.roleTitle}</option>)}
                      </select>
                      <button type="button" className="button-secondary company-scrape-btn"
                        disabled={scrapingId === source.id}
                        onClick={(e) => { 
                          e.stopPropagation(); 
                          const pid = document.getElementById(`scrape-profile-select-${source.id}`).value;
                          scrapeSource(source.id, pid ? parseInt(pid) : null) 
                        }}>
                        {scrapingId === source.id ? <><span className="button-spinner" />Scraping...</> : 'Scrape now'}
                      </button>
                      <button type="button" className="button-quiet"
                        onClick={(e) => { e.stopPropagation(); setEditingSource(source.id); setSourceForm({ url: source.url, label: source.label || '' }) }}>
                        Edit
                      </button>
                      <button type="button" className="button-quiet" disabled={deletingId === source.id}
                        onClick={(e) => { e.stopPropagation(); deleteSource(source.id) }}>
                        {deletingId === source.id ? 'Removing…' : 'Remove'}
                      </button>
                      <span className="company-expand-icon" aria-hidden="true">{expandedSource === source.id ? '▾' : '▸'}</span>
                    </div>
                  </div>
                  {editingSource === source.id && (
                      <div className="source-jobs-panel source-edit-panel">
                          <form onSubmit={(e) => {
                              e.preventDefault();
                              updateSource(source.id);
                          }}>
                              <div className="source-edit-fields">
                                  <label className="field"><span>Career page URL</span>
                                      <input type="url" value={sourceForm.url} onChange={updateSourceForm('url')} required />
                                  </label>
                                  <label className="field"><span>Company name / label</span>
                                      <input value={sourceForm.label} onChange={updateSourceForm('label')} />
                                  </label>
                              </div>
                              <div className="source-edit-actions">
                                  <button type="button" className="button-quiet" onClick={() => setEditingSource(null)}>Cancel</button>
                                  <button type="submit" className="button-secondary">Save Changes</button>
                              </div>
                          </form>
                      </div>
                  )}
                  {expandedSource === source.id && editingSource !== source.id && (
                    <div className="source-jobs-panel">
                      {sourceJobsLoading === source.id ? (
                        <div className="jobs-loading"><span className="button-spinner" />Loading jobs…</div>
                      ) : (!sourceJobs[source.id] || sourceJobs[source.id].length === 0) ? (
                        <div className="empty-state jobs-empty source-empty">
                          <span className="empty-state-icon" aria-hidden="true">⌕</span>
                          <h3>{status?.tone === 'bad' ? 'Nothing could be extracted' : 'No jobs yet'}</h3>
                          <p>{status?.tone === 'bad'
                            ? status.hint + ' Until then, this source will stay empty.'
                            : 'Click "Scrape now" to fetch listings from this site.'}</p>
                        </div>
                      ) : (
                        <ol className="job-list company-job-list">
                          {sourceJobs[source.id].map((job) => (
                            <JobCard key={job.id} job={job} updatingId={updatingId} onToggle={toggleApplied} />
                          ))}
                        </ol>
                      )}
                    </div>
                  )}
                </div>
                )
              })}
            </div>}
        </div>
      </div>}
    </section>
  )
}

// Portals can list a role across dozens of cities; show a few and summarise the rest.
function summarizeLocation(location, max = 3) {
  if (!location) return null
  const cities = location.split(',').map((c) => c.trim()).filter(Boolean)
  if (cities.length <= max) return cities.join(' · ')
  return `${cities.slice(0, max).join(' · ')} +${cities.length - max} more`
}

function JobCard({ job, updatingId, onToggle }) {
  const locationLabel = summarizeLocation(job.location)
  // Structured portal metadata when available, otherwise fall back to who shared it.
  const facts = [job.detectedRole, locationLabel, job.employmentType, job.careerLevel].filter(Boolean)
  const hasPortalMeta = facts.length > 0

  return (
    <li className={job.applied ? 'is-applied' : ''}>
      <article className="job-card">
        <div className="job-company-mark" aria-hidden="true">{job.company.trim().charAt(0).toUpperCase()}</div>
        <div className="job-card-main">
          <div className="job-card-heading">
            <div><span>{job.company}</span><h3>{job.title}</h3></div>
            <div className="job-badges">
              {job.experienceRequired != null && (
                <span className="job-exp-badge" title={`Requires about ${job.experienceRequired}+ years experience`}>
                  {job.experienceRequired}+ yrs
                </span>
              )}
              {job.applied && <span className="job-applied-badge">Applied</span>}
            </div>
          </div>

          {hasPortalMeta && (
            <div className="job-facts" title={job.location || undefined}>
              {facts.map((fact) => <span key={fact}>{fact}</span>)}
            </div>
          )}

          {job.qualification && (
            <div className="job-qualification">
              <span className="job-qualification-label">Qualification</span>
              {job.qualification}
            </div>
          )}

          <div className="job-meta">
            {job.postedText
              ? <span>{job.postedText}</span>
              : <span>{formatPostedAt(job.createdAt)}</span>}
            {!hasPortalMeta && <span>Shared by {job.postedByName}</span>}
            <span>{hostName(job.jobUrl)}</span>
          </div>
        </div>
        <div className="job-actions">
          <a href={job.jobUrl} target="_blank" rel="noopener noreferrer" className="job-apply-btn">
            {job.applied ? '↗ View listing' : '↗ Apply now'}
          </a>
          <button type="button" className={`job-applied-toggle${job.applied ? ' is-applied' : ''}`}
            disabled={updatingId === job.id} onClick={() => onToggle(job)}>
            {updatingId === job.id ? 'Saving…' : job.applied ? '✓ Already applied' : 'Mark applied'}
          </button>
        </div>
      </article>
    </li>
  )
}

export default JobBoard
