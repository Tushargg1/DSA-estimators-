import axios from 'axios'

/**
 * REST client for the Spring Boot backend.
 *
 * Base URL is configurable via the `VITE_API_BASE_URL` env var so the same
 * build can point at local dev, staging, or prod without code changes.
 * Falls back to the local Spring Boot default (`http://localhost:8080/api`).
 */
export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api'

const http = axios.create({
  baseURL: API_BASE_URL,
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Surface a clean error message; screens decide how to render it.
//
// The backend returns 422 with a per-field body `{ errors: { field: message } }`
// on validation failures (see ErrorResponse / ValidationException). A plain
// `new Error(message)` would discard that map, so we preserve the response body
// and expose the field errors on the thrown error. Screens (e.g. OnboardingForm)
// read `err.fieldErrors` to render inline, per-platform validation messages.
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const data = error.response?.data
    const message =
      data?.message ??
      error.message ??
      'Request failed'
    const err = new Error(message)
    err.status = error.response?.status
    if (data && typeof data === 'object') {
      err.data = data
      // 422 validation failures carry a { errors: { field: msg } } map.
      if (data.errors && typeof data.errors === 'object') {
        err.fieldErrors = data.errors
      }
    }
    return Promise.reject(err)
  },
)

/**
 * Thin wrappers around the REST API surface documented in design.md.
 * Only the endpoints needed by the frontend are exposed here; each returns
 * the parsed response body.
 */
export const api = {
  // Users
  createUser: (payload) => http.post('/users', payload).then((r) => r.data),
  getUser: (id) => http.get(`/users/${id}`).then((r) => r.data),
  updateTarget: (id, target) =>
    http.put(`/users/${id}/target`, { target }).then((r) => r.data),
  getSubmissions: (id, params) =>
    http.get(`/users/${id}/submissions`, { params }).then((r) => r.data),

  // Groups
  createGroup: (payload) => http.post('/groups', payload).then((r) => r.data),
  // Backend JoinGroupRequest expects { inviteCode, userId }; callers pass the
  // full payload so the joining user is identified.
  joinGroup: (payload) =>
    http.post('/groups/join', payload).then((r) => r.data),
  getLeaderboard: (groupId) =>
    http.get(`/groups/${groupId}/leaderboard`).then((r) => r.data),
  getGroupHistory: (groupId, date) =>
    http.get(`/groups/${groupId}/history`, { params: { date } }).then((r) => r.data),

  // Status
  getPollStatus: () => http.get('/status/poll').then((r) => r.data),
}

export default http
