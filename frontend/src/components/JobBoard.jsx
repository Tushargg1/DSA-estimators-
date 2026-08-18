import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from '../api/client.js'

const emptyForm = { title: '', company: '', jobUrl: '' }

function formatPostedAt(value) {
  if (!value) return 'Recently posted'
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium', timeStyle: 'short',
  }).format(new Date(value))
}

function hostName(value) {
  try { return new URL(value).hostname.replace(/^www\./, '') } catch { return 'External site' }
}

function JobBoard() {
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

  const load = useCallback(async (requestedPage = pageNumber) => {
    setLoading(true)
    setError(null)
    try {
      const result = await api.getJobs({ page: requestedPage, size: 20 })
      setPage(result)
      setPageNumber(result.page)
    } catch (requestError) {
      setError(requestError.message || 'Could not load job opportunities.')
    } finally {
      setLoading(false)
    }
  }, [pageNumber])

  useEffect(() => { void load(0) }, [])

  const updateForm = (field) => (event) => {
    setForm((current) => ({ ...current, [field]: event.target.value }))
    setFieldErrors((current) => ({ ...current, [field]: undefined }))
    setSuccess(null)
  }

  const createJob = async (event) => {
    event.preventDefault()
    setCreating(true)
    setError(null)
    setSuccess(null)
    setFieldErrors({})
    try {
      await api.createJob({
        title: form.title.trim(),
        company: form.company.trim(),
        jobUrl: form.jobUrl.trim(),
      })
      setForm(emptyForm)
      setSuccess('Job opportunity shared with everyone.')
      await load(0)
    } catch (requestError) {
      if (requestError.fieldErrors) setFieldErrors(requestError.fieldErrors)
      else setError(requestError.message || 'Could not share this job.')
    } finally {
      setCreating(false)
    }
  }

  const toggleApplied = async (job) => {
    setUpdatingId(job.id)
    setError(null)
    try {
      const updated = job.applied
        ? await api.unmarkJobApplied(job.id)
        : await api.markJobApplied(job.id)
      setPage((current) => ({
        ...current,
        content: current.content.map((item) => item.id === updated.id ? updated : item),
      }))
      setSuccess(updated.applied ? 'Marked as applied.' : 'Moved back to not applied.')
    } catch (requestError) {
      setError(requestError.message || 'Could not update your application status.')
    } finally {
      setUpdatingId(null)
    }
  }

  const visibleJobs = useMemo(() => {
    const needle = query.trim().toLowerCase()
    return (page?.content || []).filter((job) => {
      const matchesStatus = filter === 'all'
        || (filter === 'applied' ? job.applied : !job.applied)
      const matchesQuery = !needle
        || job.title.toLowerCase().includes(needle)
        || job.company.toLowerCase().includes(needle)
      return matchesStatus && matchesQuery
    })
  }, [page, query, filter])

  const appliedCount = page?.content?.filter((job) => job.applied).length || 0

  return (
    <section className="job-board" aria-label="Community job board">
      <header className="jobs-hero">
        <div>
          <span className="eyebrow">Community opportunities</span>
          <h2>Find your next role</h2>
          <p>Share useful job openings with the community, apply on the company site, and keep your own application list up to date.</p>
        </div>
        <div className="jobs-hero-stats" aria-label="Job board summary">
          <span><strong>{page?.totalElements ?? 0}</strong><small>shared roles</small></span>
          <span><strong>{appliedCount}</strong><small>applied on this page</small></span>
        </div>
      </header>

      <div className="jobs-layout">
        <aside className="job-share-card card">
          <span className="eyebrow">Add an opportunity</span>
          <h3>Share a job link</h3>
          <p>Post a direct application or official job listing. It becomes visible to every logged-in member.</p>
          <form onSubmit={createJob} aria-busy={creating}>
            <label className="field">
              <span>Job title</span>
              <input value={form.title} onChange={updateForm('title')} required maxLength="200"
                placeholder="Software Engineer" aria-invalid={Boolean(fieldErrors.title)} />
              {fieldErrors.title && <small className="field-error">{fieldErrors.title}</small>}
            </label>
            <label className="field">
              <span>Company</span>
              <input value={form.company} onChange={updateForm('company')} required maxLength="200"
                placeholder="Company name" aria-invalid={Boolean(fieldErrors.company)} />
              {fieldErrors.company && <small className="field-error">{fieldErrors.company}</small>}
            </label>
            <label className="field">
              <span>Application link</span>
              <input type="url" value={form.jobUrl} onChange={updateForm('jobUrl')} required maxLength="2048"
                placeholder="https://company.com/jobs/..." aria-invalid={Boolean(fieldErrors.jobUrl)} />
              {fieldErrors.jobUrl && <small className="field-error">{fieldErrors.jobUrl}</small>}
            </label>
            <button type="submit" className="job-share-submit" disabled={creating}>
              {creating ? <><span className="button-spinner" />Sharing…</> : 'Share opportunity'}
            </button>
          </form>
        </aside>

        <div className="jobs-feed">
          <div className="jobs-toolbar">
            <label className="jobs-search">
              <span className="visually-hidden">Search jobs</span>
              <span aria-hidden="true">⌕</span>
              <input value={query} onChange={(event) => setQuery(event.target.value)}
                placeholder="Search role or company…" />
            </label>
            <div className="jobs-filter" role="group" aria-label="Filter application status">
              {[
                ['all', 'All'], ['open', 'Not applied'], ['applied', 'Applied'],
              ].map(([value, label]) => <button type="button" key={value}
                className="button-secondary" aria-pressed={filter === value}
                onClick={() => setFilter(value)}>{label}</button>)}
            </div>
          </div>

          {error && <div className="form-error" role="alert"><span>!</span>{error}</div>}
          {success && <p className="job-success" role="status">{success}</p>}
          {loading ? <div className="jobs-loading"><span className="button-spinner" />Loading opportunities…</div>
            : visibleJobs.length === 0 ? <div className="empty-state jobs-empty">
              <span className="empty-state-icon" aria-hidden="true">⌕</span>
              <h3>{page?.totalElements ? 'No matching opportunities' : 'No jobs shared yet'}</h3>
              <p>{page?.totalElements ? 'Try another search or status filter.' : 'Be the first member to share an opportunity.'}</p>
            </div> : <ol className="job-list">
              {visibleJobs.map((job) => <li key={job.id} className={job.applied ? 'is-applied' : ''}>
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
                      disabled={updatingId === job.id} onClick={() => toggleApplied(job)}>
                      {updatingId === job.id ? 'Saving…' : job.applied ? 'Undo applied' : 'Mark applied'}
                    </button>
                  </div>
                </article>
              </li>)}
            </ol>}

          {page && page.totalPages > 1 && <nav className="jobs-pagination" aria-label="Job listing pages">
            <button type="button" className="button-secondary" disabled={page.first || loading}
              onClick={() => load(page.page - 1)}>← Newer</button>
            <span>Page {page.page + 1} of {page.totalPages}</span>
            <button type="button" className="button-secondary" disabled={page.last || loading}
              onClick={() => load(page.page + 1)}>Older →</button>
          </nav>}
        </div>
      </div>
    </section>
  )
}

export default JobBoard
