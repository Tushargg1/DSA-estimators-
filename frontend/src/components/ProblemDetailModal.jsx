import { useEffect, useRef } from 'react'

/**
 * ProblemDetailModal (task 10.5, Requirements 8.1, 8.2).
 *
 * Shows a single submission's detail: problem name, platform, difficulty, tags,
 * and solve timestamp. Rendered from a submission row
 * (GET /api/users/{id}/submissions -> content[]):
 *   { problemId, problemName, platform, difficulty, tags, solvedAtUtc,
 *     isFirstAttempt, countedForTarget }
 *
 * Requirement 8.2: when the platform did not provide difficulty or tags (e.g. a
 * GFG scrape gap) the value is null/missing — we render "Not available" rather
 * than a blank or an error.
 *
 * Closeable via the close button, the backdrop, or the Escape key.
 *
 * @param {{ submission: object|null, onClose: () => void }} props
 */
const NOT_AVAILABLE = 'Not available'

function formatTimestamp(value) {
  if (!value) return NOT_AVAILABLE
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  return d.toLocaleString()
}

function formatTags(tags) {
  if (!Array.isArray(tags) || tags.length === 0) return NOT_AVAILABLE
  return tags.join(', ')
}

function ProblemDetailModal({ submission, onClose }) {
  const modalRef = useRef(null)
  const closeRef = useRef(null)

  useEffect(() => {
    if (!submission) return undefined
    const previousFocus = document.activeElement
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    closeRef.current?.focus()

    const onKeyDown = (event) => {
      if (event.key === 'Escape') onClose?.()
      if (event.key !== 'Tab' || !modalRef.current) return
      const focusable = [...modalRef.current.querySelectorAll('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])')]
      if (focusable.length === 0) return
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = previousOverflow
      previousFocus?.focus?.()
    }
  }, [submission, onClose])

  if (!submission) return null

  const { problemName, platform, difficulty, tags, solvedAtUtc } = submission

  return (
    <div className="modal-backdrop" onMouseDown={onClose} role="presentation">
      <div ref={modalRef} className="modal" role="dialog" aria-modal="true"
        aria-labelledby="problem-detail-title" onMouseDown={(event) => event.stopPropagation()}>
        <div className="modal-accent" aria-hidden="true" />
        <div className="modal-header">
          <div>
            <span className="eyebrow">Latest accepted problem</span>
            <h2 id="problem-detail-title" className="modal-title">{problemName ?? NOT_AVAILABLE}</h2>
          </div>
          <button ref={closeRef} type="button" className="modal-close" aria-label="Close problem details" onClick={onClose}>×</button>
        </div>
        <dl className="modal-body">
          <div><dt>Platform</dt><dd>{platform ?? NOT_AVAILABLE}</dd></div>
          <div><dt>Difficulty</dt><dd>{difficulty ?? NOT_AVAILABLE}</dd></div>
          <div className="modal-wide"><dt>Tags</dt><dd>{formatTags(tags)}</dd></div>
          <div className="modal-wide"><dt>Solved at</dt><dd>{formatTimestamp(solvedAtUtc)}</dd></div>
        </dl>
      </div>
    </div>
  )
}

export default ProblemDetailModal
