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
let unauthorizedHandler = null

export function getAuthToken() {
  return sessionStorage.getItem(TOKEN_KEY)
}

export function setAuthToken(token) {
  if (token) sessionStorage.setItem(TOKEN_KEY, token)
  else sessionStorage.removeItem(TOKEN_KEY)
}

export function clearAuthToken() {
  sessionStorage.removeItem(TOKEN_KEY)
}

export function onUnauthorized(handler) {
  unauthorizedHandler = handler
  return () => {
    if (unauthorizedHandler === handler) unauthorizedHandler = null
  }
}

const http = axios.create({
  baseURL: API_BASE_URL,
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.request.use((config) => {
  const token = getAuthToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (axios.isCancel(error)) {
      const aborted = new Error('Request cancelled')
      aborted.name = 'AbortError'
      return Promise.reject(aborted)
    }
    const data = error.response?.data
    const message = data?.detail ?? data?.message ?? data?.error ?? error.message ?? 'Request failed'
    const wrapped = new Error(message)
    wrapped.status = error.response?.status
    wrapped.data = data
    if (data?.errors && typeof data.errors === 'object') wrapped.fieldErrors = data.errors
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
  activateLegacy: (payload) => http.post('/auth/legacy-activate', payload).then((response) => response.data),
  me: () => http.get('/auth/me').then((response) => response.data),
  logout: () => http.post('/auth/logout', null, { skipAuthReset: true }),

  getUser: (id) => http.get(`/users/${id}`).then((response) => response.data),
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
  getPollStatus: () => http.get('/status/poll').then((response) => response.data),
}

export default http
