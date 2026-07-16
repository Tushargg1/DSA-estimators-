import { useState } from 'react'
import { api } from '../api/client.js'

const empty = {
  name: '', email: '', password: '', confirmPassword: '', setupCode: '',
  leetcodeUsername: '', codeforcesUsername: '', gfgUsername: '',
}

const modeCopy = {
  login: { title: 'Welcome back', subtitle: 'Continue building your problem-solving streak.', action: 'Log in' },
  register: { title: 'Create your account', subtitle: 'Connect your profiles and start tracking progress.', action: 'Create account' },
  activate: { title: 'Activate existing account', subtitle: 'Set a password using the temporary code from the operator.', action: 'Activate account' },
}

function AuthScreen({ onAuthenticated }) {
  const [mode, setMode] = useState('login')
  const [form, setForm] = useState(empty)
  const [error, setError] = useState(null)
  const [fieldErrors, setFieldErrors] = useState({})
  const [busy, setBusy] = useState(false)

  const update = (key) => (event) => setForm((current) => ({ ...current, [key]: event.target.value }))
  const switchMode = (next) => {
    setMode(next)
    setError(null)
    setFieldErrors({})
    setForm(empty)
  }

  const submit = async (event) => {
    event.preventDefault()
    setError(null)
    setFieldErrors({})
    if (mode !== 'login' && form.password !== form.confirmPassword) {
      setFieldErrors({ confirmPassword: 'Passwords do not match.' })
      return
    }
    setBusy(true)
    try {
      let response
      if (mode === 'login') {
        response = await api.login({ email: form.email.trim(), password: form.password })
      } else if (mode === 'activate') {
        response = await api.activateLegacy({
          email: form.email.trim(), password: form.password, setupCode: form.setupCode,
        })
      } else {
        const payload = { name: form.name.trim(), email: form.email.trim(), password: form.password }
        for (const key of ['leetcodeUsername', 'codeforcesUsername', 'gfgUsername']) {
          if (form[key].trim()) payload[key] = form[key].trim()
        }
        response = await api.register(payload)
      }
      await onAuthenticated(response)
    } catch (requestError) {
      if (requestError.fieldErrors) setFieldErrors(requestError.fieldErrors)
      else setError(requestError.message || 'Authentication failed')
    } finally {
      setBusy(false)
    }
  }

  const errorProps = (key) => ({
    'aria-invalid': fieldErrors[key] ? 'true' : undefined,
    'aria-describedby': fieldErrors[key] ? `${key}-error` : undefined,
  })
  const copy = modeCopy[mode]

  return (
    <section className="auth-panel">
      <aside className="auth-hero">
        <span className="eyebrow">Practice with purpose</span>
        <h2>Turn daily DSA practice into visible progress.</h2>
        <p>Bring your coding profiles together, stay accountable with friends, and make consistency your competitive advantage.</p>
        <div className="feature-list" aria-label="Tracker benefits">
          <div><span aria-hidden="true">01</span><strong>One unified view</strong><small>LeetCode, Codeforces, and GeeksforGeeks.</small></div>
          <div><span aria-hidden="true">02</span><strong>Daily momentum</strong><small>Targets and streaks that keep you moving.</small></div>
          <div><span aria-hidden="true">03</span><strong>Friendly competition</strong><small>Live group leaderboards with your peers.</small></div>
        </div>
      </aside>

      <div className="auth-card-shell">
        <nav className="auth-tabs" aria-label="Account access">
          <button type="button" onClick={() => switchMode('login')} aria-pressed={mode === 'login'}>Login</button>
          <button type="button" onClick={() => switchMode('register')} aria-pressed={mode === 'register'}>Register</button>
          <button type="button" onClick={() => switchMode('activate')} aria-pressed={mode === 'activate'}>Activate</button>
        </nav>
        <form className="card auth-card" onSubmit={submit} noValidate>
          <div className="auth-card-heading">
            <span className="eyebrow">{mode === 'login' ? 'Account access' : mode === 'register' ? 'Get started' : 'Legacy account'}</span>
            <h2>{copy.title}</h2>
            <p>{copy.subtitle}</p>
          </div>

          {mode === 'register' && <div className="field">
            <label htmlFor="auth-name">Display name</label>
            <input id="auth-name" value={form.name} onChange={update('name')} required placeholder="How others will see you" {...errorProps('name')} />
            {fieldErrors.name && <span id="name-error" className="field-error">{fieldErrors.name}</span>}
          </div>}

          <div className="field">
            <label htmlFor="auth-email">Email address</label>
            <input id="auth-email" type="email" autoComplete="email" value={form.email} onChange={update('email')} required placeholder="you@example.com" {...errorProps('email')} />
            {fieldErrors.email && <span id="email-error" className="field-error">{fieldErrors.email}</span>}
          </div>

          {mode === 'register' && <div className="platform-fields">
            {['leetcodeUsername', 'codeforcesUsername', 'gfgUsername'].map((key) => (
              <div className="field" key={key}>
                <label htmlFor={key}>{key === 'leetcodeUsername' ? 'LeetCode' : key === 'codeforcesUsername' ? 'Codeforces' : 'GeeksforGeeks'}</label>
                <input id={key} value={form[key]} onChange={update(key)} placeholder="Username (optional)" {...errorProps(key)} />
                {fieldErrors[key] && <span id={`${key}-error`} className="field-error">{fieldErrors[key]}</span>}
              </div>
            ))}
          </div>}

          <div className="field">
            <label htmlFor="auth-password">Password</label>
            <input id="auth-password" type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'} value={form.password} onChange={update('password')} required placeholder="At least 8 characters" {...errorProps('password')} />
            {fieldErrors.password && <span id="password-error" className="field-error">{fieldErrors.password}</span>}
          </div>

          {mode !== 'login' && <div className="field">
            <label htmlFor="auth-confirm">Confirm password</label>
            <input id="auth-confirm" type="password" autoComplete="new-password" value={form.confirmPassword} onChange={update('confirmPassword')} required placeholder="Enter your password again" {...errorProps('confirmPassword')} />
            {fieldErrors.confirmPassword && <span id="confirmPassword-error" className="field-error">{fieldErrors.confirmPassword}</span>}
          </div>}

          {mode === 'activate' && <div className="field">
            <label htmlFor="setup-code">Temporary setup code</label>
            <input id="setup-code" type="password" autoComplete="one-time-code" value={form.setupCode} onChange={update('setupCode')} required placeholder="Code from the operator" />
          </div>}

          {error && <div className="form-error" role="alert"><span aria-hidden="true">!</span>{error}</div>}
          <button className="auth-submit" type="submit" disabled={busy}>
            {busy ? <><span className="button-spinner" aria-hidden="true" /> Please wait…</> : copy.action}
          </button>
        </form>
      </div>
    </section>
  )
}

export default AuthScreen
