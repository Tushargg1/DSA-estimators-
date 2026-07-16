import axios from 'axios'

const localApi = 'http://localhost:8080/api'
const configuredApi = import.meta.env.VITE_API_BASE_URL
const localBrowser = ['localhost', '127.0.0.1'].includes(globalThis.location?.hostname)
if (import.meta.env.PROD && !configuredApi && !localBrowser) {
  throw new Error('VITE_API_BASE_URL is required for production deployments')
}
if (configuredApi) {
  let parsed
  try { parsed = new URL(configuredApi) } catch { throw new Error('VITE_API_BASE_URL must be an absolute URL') }
  if (import.meta.env.PROD && !localBrowser && parsed.protocol !== 'https:') {
    throw new Error('VITE_API_BASE_URL must use HTTPS in production')
  }
}
export const API_BASE_URL = configuredApi || localApi
const TOKEN_KEY = 'dsaTracker.jwt'
let memoryToken = null
let unauthorizedHandler = null

export function getAuthToken() {
  try { return sessionStorage.getItem(TOKEN_KEY) || memoryToken } catch { return memoryToken }
}

export function setAuthToken(token) {
  memoryToken = token || null
  try {
    if (token) sessionStorage.setItem(TOKEN_KEY, token)
    else sessionStorage.removeItem(TOKEN_KEY)
  } catch { /* Restricted storage falls back to this tab's memory. */ }
}

export function clearAuthToken() {
  memoryToken = null
  try { sessionStorage.removeItem(TOKEN_KEY) } catch { /* Already cleared in memory. */ }
}

export function onUnauthorized(handler) {
  unauthorizedHandler = handler
  return () => {
    if (unauthorizedHandler === handler) unauthorizedHandler = null
  }
}

const http = axios.create({
  baseURL: API_BASE_URL,
  // Render may need close to a minute to wake a sleeping free instance.
  timeout: 90000,
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.request.use((config) => {
  const token = getAuthToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

const stringMessage = (data) => {
  for (const value of [data?.detail, data?.message]) {
    if (typeof value === 'string' && value.trim()) return value.trim()
  }
  return null
}

export function normalizeApiError(error) {
  if (axios.isCancel(error)) {
    const aborted = new Error('Request cancelled')
    aborted.name = 'AbortError'
    aborted.kind = 'aborted'
    aborted.retryable = false
    return aborted
  }

  const status = error.response?.status
  const data = error.response?.data
  const timedOut = ['ECONNABORTED', 'ETIMEDOUT'].includes(error.code)
  let kind = 'http'
  let retryable = false
  let message = stringMessage(data)

  if (timedOut) {
    kind = 'timeout'
    retryable = true
    message = 'The server is taking longer than expected to start. Please wait a moment and try again.'
  } else if (!error.response) {
    kind = 'network'
    retryable = true
    message = 'Unable to reach the server. Check your connection, wait a moment, and try again.'
  } else if ([502, 503, 504].includes(status)) {
    kind = 'unavailable'
    retryable = true
    message = 'The server is temporarily unavailable or starting up. Please try again shortly.'
  } else if (status === 429) {
    retryable = true
    message = message || 'Too many requests. Please wait a moment and try again.'
  } else if (status >= 500) {
    retryable = true
    message = 'Something went wrong on the server. Please try again shortly.'
  } else if (!message) {
    message = status === 401 ? 'Your session is invalid or has expired. Please log in again.'
      : status === 403 ? 'You do not have permission to perform this action.'
        : status === 404 ? 'The requested information was not found.'
          : 'The request could not be completed. Please check the form and try again.'
  }

  const wrapped = new Error(message)
  wrapped.status = status
  wrapped.code = error.code
  wrapped.kind = data?.errors ? 'validation' : kind
  wrapped.retryable = retryable
  wrapped.data = data
  if (data?.errors && typeof data.errors === 'object') wrapped.fieldErrors = data.errors
  return wrapped
}

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const wrapped = normalizeApiError(error)
    if (wrapped.status === 401 && !error.config?.skipAuthReset) {
      clearAuthToken()
      unauthorizedHandler?.()
    }
    return Promise.reject(wrapped)
  },
)

export const api = {
  register: (payload) => http.post('/auth/register', payload).then((response) => response.data),
  login: (payload) => http.post('/auth/login', payload).then((response) => response.data),
  googleLogin: (credential) =>
    http.post('/auth/google', { credential }, { skipAuthReset: true }).then((response) => response.data),
  activateLegacy: (payload) => http.post('/auth/legacy-activate', payload).then((response) => response.data),
  me: () => http.get('/auth/me').then((response) => response.data),
  logout: () => http.post('/auth/logout', null, { skipAuthReset: true }),

  getUser: (id, signal) => http.get(`/users/${id}`, { signal }).then((response) => response.data),
  updateTarget: (id, target) =>
    http.put(`/users/${id}/target`, { target }).then((response) => response.data),
  getSubmissions: (id, params, signal) =>
    http.get(`/users/${id}/submissions`, { params, signal }).then((response) => response.data),

  getGroups: () => http.get('/groups').then((response) => response.data),
  createGroup: (payload) => http.post('/groups', payload).then((response) => response.data),
  joinGroup: (payload) => http.post('/groups/join', payload).then((response) => response.data),
  getLeaderboard: (groupId) =>
    http.get(`/groups/${groupId}/leaderboard`).then((response) => response.data),
  getGroupHistory: (groupId, date) =>
    http.get(`/groups/${groupId}/history`, { params: { date } }).then((response) => response.data),
  getGroupTarget: (groupId, signal) =>
    http.get(`/groups/${groupId}/target`, { signal }).then((response) => response.data),
  selectAutoGroupTarget: (groupId) =>
    http.post(`/groups/${groupId}/target/auto`).then((response) => response.data),
  startGroupTargetPoll: (groupId) =>
    http.post(`/groups/${groupId}/target/poll`).then((response) => response.data),
  castGroupTargetVote: (groupId, target) =>
    http.put(`/groups/${groupId}/target/poll/vote`, { target }).then((response) => response.data),
  getMemberActivity: (groupId, userId, params, signal) =>
    http.get(`/groups/${groupId}/members/${userId}/activity`, { params, signal })
      .then((response) => response.data),
  getPollStatus: () => http.get('/status/poll').then((response) => response.data),
  getPatternCatalog: () => http.get('/catalog').then((response) => response.data),
}

export default http
