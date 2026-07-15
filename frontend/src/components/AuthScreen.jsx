import { useState } from 'react'
import { api } from '../api/client.js'

const empty = {
  name: '', email: '', password: '', confirmPassword: '', setupCode: '',
  leetcodeUsername: '', codeforcesUsername: '', gfgUsername: '',
}

function AuthScreen({ onAuthenticated }) {
  const [mode, setMode] = useState('login')
  const [form, setForm] = useState(empty)
  const [error, setError] = useState(null)
  const [fieldErrors, setFieldErrors] = useState({})
  const [busy, setBusy] = useState(false)

  const update = (key) => (event) => {
    setForm((current) => ({ ...current, [key]: event.target.value }))
  }

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
      onAuthenticated(response)
    } catch (requestError) {
      if (requestError.fieldErrors) setFieldErrors(requestError.fieldErrors)
      else setError(requestError.message || 'Authentication failed')
    } finally {
      setBusy(false)
    }
  }

  const commonEmail = (
    <div className="field">
      <label htmlFor="auth-email">Email</label>
      <input id="auth-email" type="email" autoComplete="email" value={form.email}
        onChange={update('email')} aria-invalid={fieldErrors.email ? 'true' : undefined} />
      {fieldErrors.email && <span className="field-error">{fieldErrors.email}</span>}
    </div>
  )

  return (
    <section className="auth-panel">
      <nav className="auth-tabs" aria-label="Account access">
        <button type="button" onClick={() => switchMode('login')} aria-pressed={mode === 'login'}>Login</button>
        <button type="button" onClick={() => switchMode('register')} aria-pressed={mode === 'register'}>Register</button>
        <button type="button" onClick={() => switchMode('activate')} aria-pressed={mode === 'activate'}>Activate existing account</button>
      </nav>
      <form className="card" onSubmit={submit} noValidate>
        <h2>{mode === 'login' ? 'Welcome back' : mode === 'register' ? 'Create account' : 'Activate existing account'}</h2>
        {mode === 'activate' && <p className="muted">Use the temporary setup code provided by the operator.</p>}
        {mode === 'register' && (
          <div className="field">
            <label htmlFor="auth-name">Display name</label>
            <input id="auth-name" value={form.name} onChange={update('name')} />
            {fieldErrors.name && <span className="field-error">{fieldErrors.name}</span>}
          </div>
        )}
        {commonEmail}
        {mode === 'register' && ['leetcodeUsername', 'codeforcesUsername', 'gfgUsername'].map((key) => (
          <div className="field" key={key}>
            <label htmlFor={key}>{key === 'leetcodeUsername' ? 'LeetCode username' : key === 'codeforcesUsername' ? 'Codeforces handle' : 'GeeksforGeeks username'}</label>
            <input id={key} value={form[key]} onChange={update(key)} placeholder="optional" />
            {fieldErrors[key] && <span className="field-error">{fieldErrors[key]}</span>}
          </div>
        ))}
        <div className="field">
          <label htmlFor="auth-password">Password</label>
          <input id="auth-password" type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'} value={form.password} onChange={update('password')} />
          {fieldErrors.password && <span className="field-error">{fieldErrors.password}</span>}
        </div>
        {mode !== 'login' && <div className="field">
          <label htmlFor="auth-confirm">Confirm password</label>
          <input id="auth-confirm" type="password" autoComplete="new-password" value={form.confirmPassword} onChange={update('confirmPassword')} />
          {fieldErrors.confirmPassword && <span className="field-error">{fieldErrors.confirmPassword}</span>}
        </div>}
        {mode === 'activate' && <div className="field">
          <label htmlFor="setup-code">Setup code</label>
          <input id="setup-code" type="password" autoComplete="one-time-code" value={form.setupCode} onChange={update('setupCode')} />
        </div>}
        {error && <p className="form-error" role="alert">{error}</p>}
        <button type="submit" disabled={busy}>{busy ? 'Please wait…' : mode === 'login' ? 'Login' : mode === 'register' ? 'Register' : 'Activate account'}</button>
      </form>
    </section>
  )
}

export default AuthScreen
