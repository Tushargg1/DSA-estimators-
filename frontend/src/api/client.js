import axios from 'axios'
import { cachedFetch, invalidateCache, clearCache, CACHE_KEYS, TTL } from './cache.js'

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
  clearCache() // Wipe all cached responses on logout
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
  me: () => cachedFetch(CACHE_KEYS.ME,
    () => http.get('/auth/me').then((r) => r.data),
    { maxAge: TTL.MEDIUM, staleAge: TTL.STALE_MAX }
  ).then((result) => result.data),
  logout: () => http.post('/auth/logout', null, { skipAuthReset: true }),

  getUser: (id, signal) => cachedFetch(CACHE_KEYS.USER(id),
    () => http.get(`/users/${id}`, { signal }).then((r) => r.data),
    { maxAge: TTL.MEDIUM, staleAge: TTL.STALE_MAX }
  ).then((result) => result.data),
  updateTarget: (id, target) =>
    http.put(`/users/${id}/target`, { target }).then((response) => {
      invalidateCache(CACHE_KEYS.USER(id))
      return response.data
    }),
  getSubmissions: (id, params, signal) =>
    http.get(`/users/${id}/submissions`, { params, signal }).then((response) => response.data),

  getGroups: () => cachedFetch(CACHE_KEYS.GROUPS,
    () => http.get('/groups').then((r) => r.data),
    { maxAge: TTL.MEDIUM, staleAge: TTL.STALE_MAX }
  ).then((result) => result.data),
  createGroup: (payload) => http.post('/groups', payload).then((response) => {
    invalidateCache(CACHE_KEYS.GROUPS)
    return response.data
  }),
  joinGroup: (payload) => http.post('/groups/join', payload).then((response) => {
    invalidateCache(CACHE_KEYS.GROUPS)
    return response.data
  }),
  getLeaderboard: (groupId) => cachedFetch(CACHE_KEYS.LEADERBOARD(groupId),
    () => http.get(`/groups/${groupId}/leaderboard`).then((r) => r.data),
    { maxAge: TTL.SHORT, staleAge: TTL.MEDIUM }
  ).then((result) => result.data),
  getGroupHistory: (groupId, date) =>
    http.get(`/groups/${groupId}/history`, { params: { date } }).then((response) => response.data),
  getGroupTarget: (groupId, signal) => cachedFetch(CACHE_KEYS.GROUP_TARGET(groupId),
    () => http.get(`/groups/${groupId}/target`, { signal }).then((r) => r.data),
    { maxAge: TTL.MEDIUM, staleAge: TTL.STALE_MAX }
  ).then((result) => result.data),
  selectAutoGroupTarget: (groupId) =>
    http.post(`/groups/${groupId}/target/auto`).then((response) => {
      invalidateCache(CACHE_KEYS.GROUP_TARGET(groupId))
      return response.data
    }),
  startGroupTargetPoll: (groupId) =>
    http.post(`/groups/${groupId}/target/poll`).then((response) => {
      invalidateCache(CACHE_KEYS.GROUP_TARGET(groupId))
      return response.data
    }),
  castGroupTargetVote: (groupId, target) =>
    http.put(`/groups/${groupId}/target/poll/vote`, { target }).then((response) => {
      invalidateCache(CACHE_KEYS.GROUP_TARGET(groupId))
      return response.data
    }),
  getMemberActivity: (groupId, userId, params, signal) =>
    http.get(`/groups/${groupId}/members/${userId}/activity`, { params, signal })
      .then((response) => response.data),
  getPollStatus: () => cachedFetch(CACHE_KEYS.POLL_STATUS,
    () => http.get('/status/poll').then((r) => r.data),
    { maxAge: TTL.MEDIUM, staleAge: TTL.STALE_MAX }
  ).then((result) => result.data),
  getPatternCatalog: () => cachedFetch(CACHE_KEYS.CATALOG,
    () => http.get('/catalog').then((r) => r.data),
    { maxAge: TTL.LONG, staleAge: TTL.STALE_MAX }
  ).then((result) => result.data),

  getJobs: (params, signal) =>
    http.get('/jobs', { params, signal }).then((response) => response.data),
  createJob: (payload) => http.post('/jobs', payload).then((response) => {
    invalidateCache(CACHE_KEYS.JOB_PROFILES)
    return response.data
  }),
  markJobApplied: (id) =>
    http.put(`/jobs/${id}/applied`).then((response) => {
      invalidateCache(CACHE_KEYS.JOB_PROFILES)
      return response.data
    }),
  unmarkJobApplied: (id) =>
    http.delete(`/jobs/${id}/applied`).then((response) => {
      invalidateCache(CACHE_KEYS.JOB_PROFILES)
      return response.data
    }),

  getJobProfiles: (params) =>
    http.get('/jobs/profiles', { params }).then((r) => r.data),
  createJobProfile: (payload) => http.post('/jobs/profiles', payload).then((response) => {
    invalidateCache(CACHE_KEYS.JOB_PROFILES)
    return response.data
  }),
  uploadResume: (file) => {
    const formData = new FormData()
    formData.append('file', file)
    return http.post('/jobs/profiles/upload-resume', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 60000,
    }).then((response) => response.data)
  },
  deleteJobProfile: (id) => http.delete(`/jobs/profiles/${id}`).then(() => {
    invalidateCache(CACHE_KEYS.JOB_PROFILES)
  }),

  getJobSources: () => cachedFetch(CACHE_KEYS.JOB_SOURCES,
    () => http.get('/jobs/sources').then((r) => r.data),
    { maxAge: TTL.MEDIUM, staleAge: TTL.STALE_MAX }
  ).then((result) => result.data),
  addJobSource: (payload) => http.post('/jobs/sources', payload).then((response) => {
    invalidateCache(CACHE_KEYS.JOB_SOURCES)
    return response.data
  }),
  scrapeJobSource: (id) => http.post(`/jobs/sources/${id}/scrape`).then((response) => {
    invalidateCache(CACHE_KEYS.JOB_SOURCES, CACHE_KEYS.JOB_PROFILES)
    return response.data
  }),
  getSourceListings: (id) =>
    http.get(`/jobs/sources/${id}/listings`).then((response) => response.data),
  checkJobSource: (url, signal) =>
    http.get('/jobs/sources/check', { params: { url }, signal, timeout: 45000 })
      .then((response) => response.data),
  deleteJobSource: (id) => http.delete(`/jobs/sources/${id}`).then(() => {
    invalidateCache(CACHE_KEYS.JOB_SOURCES)
  }),

  getGitHubStatus: () => cachedFetch(CACHE_KEYS.GITHUB_STATUS,
    () => http.get('/github/status').then((r) => r.data),
    { maxAge: TTL.MEDIUM, staleAge: TTL.STALE_MAX }
  ).then((result) => result.data),
  startGitHubConnection: () => http.post('/github/connect').then((response) => {
    invalidateCache(CACHE_KEYS.GITHUB_STATUS)
    return response.data
  }),
  completeGitHubConnection: (payload) =>
    http.post('/github/complete', payload).then((response) => {
      invalidateCache(CACHE_KEYS.GITHUB_STATUS)
      return response.data
    }),
  getGitHubRepositories: () =>
    http.get('/github/repositories').then((response) => response.data),
  selectGitHubRepository: (repositoryId) =>
    http.put('/github/repository', { repositoryId }).then((response) => {
      invalidateCache(CACHE_KEYS.GITHUB_STATUS)
      return response.data
    }),
  issueGitHubExtensionToken: () =>
    http.post('/github/extension-token').then((response) => response.data),
  getGitHubCaptures: () => http.get('/github/captures').then((response) => response.data),
  getGitHubWorkflowSaveStatus: () =>
    http.get('/github/workflow-save').then((response) => response.data),
  requestGitHubWorkflowSave: () =>
    http.post('/github/workflow-save').then((response) => response.data),
  getGitHubProgressPushes: () => cachedFetch(CACHE_KEYS.GITHUB_PUSHES,
    () => http.get('/github/progress-pushes').then((r) => r.data),
    { maxAge: TTL.SHORT, staleAge: TTL.MEDIUM }
  ).then((result) => result.data),
  getGitHubProgressSchedule: () => cachedFetch(CACHE_KEYS.GITHUB_SCHEDULE,
    () => http.get('/github/progress-schedule').then((r) => r.data),
    { maxAge: TTL.MEDIUM, staleAge: TTL.STALE_MAX }
  ).then((result) => result.data),
  updateGitHubProgressSchedule: (payload) =>
    http.put('/github/progress-schedule', payload).then((response) => {
      invalidateCache(CACHE_KEYS.GITHUB_SCHEDULE)
      return response.data
    }),
  disconnectGitHub: () => http.delete('/github').then(() => {
    invalidateCache(CACHE_KEYS.GITHUB_STATUS, CACHE_KEYS.GITHUB_SCHEDULE, CACHE_KEYS.GITHUB_PUSHES)
  }),
}

export default http
