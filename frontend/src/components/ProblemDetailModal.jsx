import { useEffect } from 'react'

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
  // Close on Escape. Registered only while the modal is open.
  useEffect(() => {
    if (!submission) return undefined
    const onKeyDown = (e) => {
      if (e.key === 'Escape') onClose?.()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [submission, onClose])

  if (!submission) return null

  const {
    problemName,
    platform,
    difficulty,
    tags,
    solvedAtUtc,
  } = submission

  return (
    <div
      className="modal-backdrop"
      onClick={onClose}
      role="presentation"
    >
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-label="Problem detail"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal-header">
          <h2 className="modal-title">{problemName ?? NOT_AVAILABLE}</h2>
          <button
            type="button"
            className="modal-close"
            aria-label="Close"
            onClick={onClose}
          >
            ×
          </button>
        </div>

        <dl className="modal-body">
          <dt>Platform</dt>
          <dd>{platform ?? NOT_AVAILABLE}</dd>

          <dt>Difficulty</dt>
          <dd>{difficulty ?? NOT_AVAILABLE}</dd>

          <dt>Tags</dt>
          <dd>{formatTags(tags)}</dd>

          <dt>Solved at</dt>
          <dd>{formatTimestamp(solvedAtUtc)}</dd>
        </dl>
      </div>
    </div>
  )
}

export default ProblemDetailModal
