import { useState } from 'react'
import { api } from '../api/client.js'

/**
 * OnboardingForm (task 10.1, Requirements 1.1, 1.3).
 *
 * Collects the onboarding fields — display name, email, and the LeetCode /
 * Codeforces / GFG usernames — and creates the user via `POST /api/users`.
 *
 * On a 422 validation failure the backend returns a per-field error map
 * (`{ errors: { field: message } }`); the axios client surfaces it as
 * `err.fieldErrors`. Those map onto the exact input names used here
 * (name, email, leetcodeUsername, codeforcesUsername, gfgUsername), so the
 * errors render inline next to the offending field / platform.
 *
 * On success the created user (UserResponse) is passed to `onCreated`.
 *
 * @param {{ onCreated?: (user: object) => void }} props
 */
function OnboardingForm({ onCreated }) {
  const [form, setForm] = useState({
    name: '',
    email: '',
    leetcodeUsername: '',
    codeforcesUsername: '',
    gfgUsername: '',
  })
  const [fieldErrors, setFieldErrors] = useState({})
  const [formError, setFormError] = useState(null)
  const [submitting, setSubmitting] = useState(false)
  const [createdUser, setCreatedUser] = useState(null)

  const update = (key) => (e) => {
    setForm((prev) => ({ ...prev, [key]: e.target.value }))
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    setSubmitting(true)
    setFieldErrors({})
    setFormError(null)

    // Send only non-blank platform usernames; blanks mean "not linked".
    const payload = {
      name: form.name.trim(),
      email: form.email.trim(),
    }
    for (const key of ['leetcodeUsername', 'codeforcesUsername', 'gfgUsername']) {
      const value = form[key].trim()
      if (value) payload[key] = value
    }

    try {
      const user = await api.createUser(payload)
      setCreatedUser(user)
      onCreated?.(user)
    } catch (err) {
      if (err.fieldErrors) {
        setFieldErrors(err.fieldErrors)
      } else {
        setFormError(err.message ?? 'Something went wrong. Please try again.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  const fields = [
    { key: 'name', label: 'Display name', type: 'text', placeholder: 'Ada Lovelace' },
    { key: 'email', label: 'Email', type: 'email', placeholder: 'ada@example.com' },
    { key: 'leetcodeUsername', label: 'LeetCode username', type: 'text', placeholder: 'optional' },
    { key: 'codeforcesUsername', label: 'Codeforces handle', type: 'text', placeholder: 'optional' },
    { key: 'gfgUsername', label: 'GeeksforGeeks username', type: 'text', placeholder: 'optional' },
  ]

  if (createdUser) {
    return (
      <div className="card onboarding-form" role="status">
        <h2>You&apos;re all set, {createdUser.name}!</h2>
        <p className="muted">
          We&apos;re backfilling your solved problems in the background. Your
          daily target is {createdUser.dailyTarget}.
        </p>
        <button type="button" onClick={() => {
          setCreatedUser(null)
          setForm({
            name: '',
            email: '',
            leetcodeUsername: '',
            codeforcesUsername: '',
            gfgUsername: '',
          })
        }}>
          Add another account
        </button>
      </div>
    )
  }

  return (
    <form className="card onboarding-form" onSubmit={handleSubmit} noValidate>
      <h2>Link your accounts</h2>
      <p className="muted">
        Add your platform usernames so we can track your solved problems.
      </p>

      {fields.map(({ key, label, type, placeholder }) => (
        <div className="field" key={key}>
          <label htmlFor={key}>{label}</label>
          <input
            id={key}
            name={key}
            type={type}
            value={form[key]}
            onChange={update(key)}
            placeholder={placeholder}
            aria-invalid={fieldErrors[key] ? 'true' : undefined}
          />
          {fieldErrors[key] && (
            <span className="field-error" role="alert">
              {fieldErrors[key]}
            </span>
          )}
        </div>
      ))}

      {formError && (
        <p className="form-error" role="alert">
          {formError}
        </p>
      )}

      <button type="submit" disabled={submitting}>
        {submitting ? 'Creating…' : 'Create account'}
      </button>
    </form>
  )
}

export default OnboardingForm
