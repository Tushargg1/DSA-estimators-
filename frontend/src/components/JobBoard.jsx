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

function JobBoard() {
  const [tab, setTab] = useState('all') // 'all' | 'roles' | 'sources'

  // --- All jobs state ---
  const [page, setPage] = useState(null)
  const [pageNumber, setPageNumber] = useState(0)
  const [form, setForm] = useState(emptyForm)
  const [fieldErrors, setFieldErrors] = useState({})
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState('all')
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

  // --- Sources state ---
  const [sources, setSources] = useState([])
  const [sourceForm, setSourceForm] = useState(emptySourceForm)
  const [sourceFieldErrors, setSourceFieldErrors] = useState({})
  const [sourceLoading, setSourceLoading] = useState(false)
  const [sourceAdding, setSourceAdding] = useState(false)
  const [scrapingId, setScrapingId] = useState(null)
  const [sourceError, setSourceError] = useState(null)
  const [sourceSuccess, setSourceSuccess] = useState(null)

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
      const result = await api.getJobProfiles()
      setProfiles(result)
      if (result.length && !activeProfile) setActiveProfile(result[0].id)
    } catch (e) {
      setProfileError(e.message || 'Could not load profiles.')
    } finally {
      setProfileLoading(false)
    }
  }, [activeProfile])

  useEffect(() => { if (tab === 'roles') void loadProfiles() }, [tab])

  // --- Load sources ---
  const loadSources = useCallback(async () => {
    setSourceLoading(true)
    setSourceError(null)
    try {
      setSources(await api.getJobSources())
    } catch (e) {
      setSourceError(e.message || 'Could not load sources.')
    } finally {
      setSourceLoading(false)
    }
  }, [])

  useEffect(() => { if (tab === 'sources') void loadSources() }, [tab])

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
      setSources((c) => [added, ...c])
      setSourceSuccess('Source added. Click "Scrape" to find jobs.')
    } catch (e) {
      if (e.fieldErrors) setSourceFieldErrors(e.fieldErrors)
      else setSourceError(e.message || 'Could not add source.')
    } finally { setSourceAdding(false) }
  }

  const scrapeSource = async (id) => {
    setScrapingId(id); setSourceError(null); setSourceSuccess(null)
    try {
      const result = await api.scrapeJobSource(id)
      if (result.error) setSourceError(result.error)
      else setSourceSuccess(`Found ${result.newListings} new job listing${result.newListings !== 1 ? 's' : ''}.`)
      void loadSources()
      void loadJobs(0)
    } catch (e) { setSourceError(e.message || 'Scrape failed.') }
    finally { setScrapingId(null) }
  }

  const deleteSource = async (id) => {
    try { await api.deleteJobSource(id); setSources((c) => c.filter((s) => s.id !== id)) }
    catch (e) { setSourceError(e.message || 'Could not delete source.') }
  }

  // --- Filtered jobs ---
  const visibleJobs = useMemo(() => {
    const needle = query.trim().toLowerCase()
    return (page?.content || []).filter((job) => {
      const matchesStatus = filter === 'all' || (filter === 'applied' ? job.applied : !job.applied)
      const matchesQuery = !needle || job.title.toLowerCase().includes(needle) || job.company.toLowerCase().includes(needle)
      return matchesStatus && matchesQuery
    })
  }, [page, query, filter])

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
          <span className="eyebrow">Create a role profile</span>
          <h3>Auto-match jobs to your skills</h3>
          <p>Upload your resume (PDF) or enter details manually. We'll detect your skills and auto-match jobs.</p>

          {/* Resume upload drop zone */}
          <div
            className={`resume-upload-zone${resumeDragOver ? ' drag-over' : ''}${resumeUploading ? ' uploading' : ''}${resumeParseResult ? ' has-file' : ''}`}
            onDragOver={(e) => { e.preventDefault(); setResumeDragOver(true) }}
            onDragLeave={() => setResumeDragOver(false)}
            onDrop={onResumeDrop}
            aria-label="Resume upload area"
          >
            {resumeUploading ? (
              <div className="resume-upload-status"><span className="button-spinner" />Parsing your resume…</div>
            ) : resumeParseResult ? (
              <div className="resume-upload-status resume-parsed">
                <span className="resume-file-icon" aria-hidden="true">📄</span>
                <span>{profileForm.resumeFileName}</span>
                <button type="button" className="button-quiet" onClick={() => {
                  setResumeParseResult(null)
                  setProfileForm(emptyProfileForm)
                }}>Remove</button>
              </div>
            ) : (
              <>
                <span className="resume-upload-icon" aria-hidden="true">⬆</span>
                <span>Drag & drop your resume PDF here</span>
                <span className="resume-upload-or">or</span>
                <label className="resume-upload-btn">
                  <span>Browse file</span>
                  <input type="file" accept=".pdf,application/pdf" onChange={onResumeFileSelect} hidden />
                </label>
              </>
            )}
          </div>

          {resumeParseResult?.categoryScores && Object.keys(resumeParseResult.categoryScores).length > 0 && (
            <div className="resume-categories">
              <span className="field-label">Detected skills</span>
              <div className="resume-category-chips">
                {Object.entries(resumeParseResult.categoryScores)
                  .sort(([, a], [, b]) => b - a)
                  .map(([cat, score]) => (
                    <span key={cat} className="resume-category-chip" title={`${score} keyword matches`}>
                      {cat} <small>({score})</small>
                    </span>
                  ))}
              </div>
            </div>
          )}

          <form onSubmit={createProfile} aria-busy={profileCreating}>
            <label className="field"><span>Role title</span>
              <input value={profileForm.roleTitle} onChange={updateProfileForm('roleTitle')} required maxLength="200" placeholder="e.g. Java Developer, ML Engineer" aria-invalid={Boolean(profileFieldErrors.roleTitle)} />
              {profileFieldErrors.roleTitle && <small className="field-error">{profileFieldErrors.roleTitle}</small>}
            </label>
            <label className="field"><span>Keywords (comma-separated)</span>
              <input value={profileForm.keywords} onChange={updateProfileForm('keywords')} required maxLength="2000" placeholder="java, spring, microservices, aws" aria-invalid={Boolean(profileFieldErrors.keywords)} />
              {profileFieldErrors.keywords && <small className="field-error">{profileFieldErrors.keywords}</small>}
            </label>
            <label className="field"><span>Resume text (optional — extra keywords auto-extracted)</span>
              <textarea value={profileForm.resumeText} onChange={updateProfileForm('resumeText')} rows={4} maxLength={50000} placeholder="Paste your resume content here…" />
            </label>
            <button type="submit" className="job-share-submit" disabled={profileCreating}>
              {profileCreating ? <><span className="button-spinner" />Creating…</> : 'Create profile'}
            </button>
          </form>
        </aside>

        <div className="jobs-feed">
          {profileError && <div className="form-error" role="alert"><span>!</span>{profileError}</div>}
          {profileSuccess && <p className="job-success" role="status">{profileSuccess}</p>}
          {profileLoading ? <div className="jobs-loading"><span className="button-spinner" />Loading profiles…</div>
            : profiles.length === 0 ? <div className="empty-state jobs-empty">
              <span className="empty-state-icon" aria-hidden="true">👤</span>
              <h3>No role profiles yet</h3>
              <p>Create a profile on the left to see auto-matched jobs from the community board.</p>
            </div> : <>
              <div className="profile-tabs" role="tablist" aria-label="Your role profiles">
                {profiles.map((p) => <button key={p.id} role="tab" type="button"
                  className="button-secondary" aria-selected={activeProfile === p.id}
                  onClick={() => setActiveProfile(p.id)}>
                  {p.roleTitle}
                  <span className="profile-match-count">{p.matchedJobs?.length || 0}</span>
                </button>)}
              </div>
              {currentProfile && <div className="profile-detail" role="tabpanel">
                <div className="profile-detail-header">
                  <div>
                    <h3>{currentProfile.roleTitle}</h3>
                    <p className="profile-keywords">{currentProfile.keywords}</p>
                  </div>
                  <button type="button" className="button-quiet profile-delete" onClick={() => deleteProfile(currentProfile.id)}>Delete</button>
                </div>
                {currentProfile.matchedJobs?.length === 0
                  ? <div className="empty-state jobs-empty"><span className="empty-state-icon" aria-hidden="true">⌕</span><h3>No matching jobs yet</h3><p>Jobs with titles containing your keywords will appear here automatically.</p></div>
                  : <ol className="job-list">{currentProfile.matchedJobs.map((job) => <JobCard key={job.id} job={job} updatingId={updatingId} onToggle={toggleApplied} />)}</ol>}
              </div>}
            </>}
        </div>
      </div>}

      {/* ===== SOURCES TAB ===== */}
      {tab === 'sources' && <div className="jobs-sources-layout">
        <aside className="job-share-card card">
          <span className="eyebrow">Add a career site</span>
          <h3>Scrape jobs from a website</h3>
          <p>Paste a careers page URL. We'll scan for job links and add matching positions to the board for everyone.</p>
          <form onSubmit={addSource} aria-busy={sourceAdding}>
            <label className="field"><span>Career page URL</span>
              <input type="url" value={sourceForm.url} onChange={updateSourceForm('url')} required maxLength="2048" placeholder="https://company.com/careers" aria-invalid={Boolean(sourceFieldErrors.url)} />
              {sourceFieldErrors.url && <small className="field-error">{sourceFieldErrors.url}</small>}
            </label>
            <label className="field"><span>Label (optional)</span>
              <input value={sourceForm.label} onChange={updateSourceForm('label')} maxLength="200" placeholder="e.g. Google Careers" />
            </label>
            <button type="submit" className="job-share-submit" disabled={sourceAdding}>
              {sourceAdding ? <><span className="button-spinner" />Adding…</> : 'Add source'}
            </button>
          </form>
        </aside>

        <div className="jobs-feed">
          {sourceError && <div className="form-error" role="alert"><span>!</span>{sourceError}</div>}
          {sourceSuccess && <p className="job-success" role="status">{sourceSuccess}</p>}
          {sourceLoading ? <div className="jobs-loading"><span className="button-spinner" />Loading sources…</div>
            : sources.length === 0 ? <div className="empty-state jobs-empty">
              <span className="empty-state-icon" aria-hidden="true">🔗</span>
              <h3>No career sites added</h3>
              <p>Add a careers page URL and scrape it to discover job listings for the community.</p>
            </div> : <ul className="source-list">
              {sources.map((source) => <li key={source.id} className="source-card">
                <div className="source-card-main">
                  <h4>{source.label || hostName(source.url)}</h4>
                  <a href={source.url} target="_blank" rel="noopener noreferrer" className="source-url">{source.url}</a>
                  <div className="source-meta">
                    <span>Added by {source.addedByName}</span>
                    {source.lastScrapedAt && <span>Last scraped: {formatPostedAt(source.lastScrapedAt)}</span>}
                    {source.lastError && <span className="source-error-note">Error: {source.lastError}</span>}
                  </div>
                </div>
                <div className="source-actions">
                  <button type="button" disabled={scrapingId === source.id} onClick={() => scrapeSource(source.id)}>
                    {scrapingId === source.id ? <><span className="button-spinner" />Scraping…</> : 'Scrape now'}
                  </button>
                  <button type="button" className="button-quiet" onClick={() => deleteSource(source.id)}>Remove</button>
                </div>
              </li>)}
            </ul>}
        </div>
      </div>}
    </section>
  )
}

function JobCard({ job, updatingId, onToggle }) {
  return (
    <li className={job.applied ? 'is-applied' : ''}>
      <article className="job-card">
        <div className="job-company-mark" aria-hidden="true">{job.company.trim().charAt(0).toUpperCase()}</div>
        <div className="job-card-main">
          <div className="job-card-heading">
            <div><span>{job.company}</span><h3>{job.title}</h3></div>
            {job.applied && <span className="job-applied-badge">Applied</span>}
          </div>
          <div className="job-meta">
            <span>Shared by {job.postedByName}</span>
            <span>{formatPostedAt(job.createdAt)}</span>
            <span>{hostName(job.jobUrl)}</span>
          </div>
        </div>
        <div className="job-actions">
          <a href={job.jobUrl} target="_blank" rel="noopener noreferrer">Apply now ↗</a>
          <button type="button" className={job.applied ? 'button-secondary' : ''}
            disabled={updatingId === job.id} onClick={() => onToggle(job)}>
            {updatingId === job.id ? 'Saving…' : job.applied ? 'Undo applied' : 'Mark applied'}
          </button>
        </div>
      </article>
    </li>
  )
}

export default JobBoard
