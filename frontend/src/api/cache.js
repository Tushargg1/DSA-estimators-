/**
 * IndexedDB-backed stale-while-revalidate cache for API responses.
 *
 * Strategy:
 * - If cached data exists and is fresh (within maxAge), return it immediately — no network call.
 * - If cached data exists but is stale, return it immediately AND revalidate in the background.
 * - If no cached data exists, fetch from network, cache, and return.
 *
 * This eliminates redundant loading spinners for data that changes infrequently
 * (user profile, groups, catalog, job profiles, sources, GitHub status).
 */

const DB_NAME = 'dsaTrackerCache'
const DB_VERSION = 1
const STORE_NAME = 'responses'

let dbPromise = null

function openDB() {
  if (dbPromise) return dbPromise
  dbPromise = new Promise((resolve, reject) => {
    try {
      const request = indexedDB.open(DB_NAME, DB_VERSION)
      request.onupgradeneeded = () => {
        const db = request.result
        if (!db.objectStoreNames.contains(STORE_NAME)) {
          db.createObjectStore(STORE_NAME, { keyPath: 'key' })
        }
      }
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    } catch (error) {
      // IndexedDB unavailable (private browsing, etc.)
      reject(error)
    }
  })
  return dbPromise
}

async function getFromStore(key) {
  try {
    const db = await openDB()
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readonly')
      const store = tx.objectStore(STORE_NAME)
      const request = store.get(key)
      request.onsuccess = () => resolve(request.result || null)
      request.onerror = () => resolve(null)
    })
  } catch {
    return null
  }
}

async function putToStore(key, data, timestamp) {
  try {
    const db = await openDB()
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const store = tx.objectStore(STORE_NAME)
      store.put({ key, data, timestamp })
      tx.oncomplete = () => resolve()
      tx.onerror = () => resolve()
    })
  } catch {
    // Silently fail — cache is a performance optimization, not critical.
  }
}

async function deleteFromStore(key) {
  try {
    const db = await openDB()
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const store = tx.objectStore(STORE_NAME)
      store.delete(key)
      tx.oncomplete = () => resolve()
      tx.onerror = () => resolve()
    })
  } catch { /* no-op */ }
}

/**
 * Wraps an async fetcher with stale-while-revalidate caching.
 *
 * @param {string} key - Unique cache key for this data.
 * @param {() => Promise<any>} fetcher - The network fetch function.
 * @param {object} options
 * @param {number} options.maxAge - Max age in ms before data is considered stale (default: 5 min).
 * @param {number} options.staleAge - Max age in ms before stale data is still usable (default: 1 hour).
 * @param {(data: any) => void} [options.onRevalidated] - Called when background revalidation completes with fresh data.
 * @returns {Promise<{ data: any, fromCache: boolean, stale: boolean }>}
 */
export async function cachedFetch(key, fetcher, options = {}) {
  const { maxAge = 5 * 60 * 1000, staleAge = 60 * 60 * 1000, onRevalidated } = options
  const now = Date.now()

  const cached = await getFromStore(key)

  if (cached) {
    const age = now - cached.timestamp
    if (age < maxAge) {
      // Fresh — no network call needed
      return { data: cached.data, fromCache: true, stale: false }
    }
    if (age < staleAge) {
      // Stale — return cached immediately, revalidate in background
      revalidate(key, fetcher, onRevalidated)
      return { data: cached.data, fromCache: true, stale: true }
    }
    // Expired — fall through to network
  }

  // No cache or expired — fetch from network
  const data = await fetcher()
  await putToStore(key, data, now)
  return { data, fromCache: false, stale: false }
}

/** Background revalidation — doesn't throw. */
async function revalidate(key, fetcher, onRevalidated) {
  try {
    const data = await fetcher()
    await putToStore(key, data, Date.now())
    onRevalidated?.(data)
  } catch {
    // Background refresh failed — stale data remains. No user impact.
  }
}

/**
 * Invalidate specific cache entries. Call after mutations.
 * @param {...string} keys - Cache keys to invalidate.
 */
export async function invalidateCache(...keys) {
  for (const key of keys) {
    await deleteFromStore(key)
  }
}

/**
 * Clear entire cache (e.g. on logout).
 */
export async function clearCache() {
  try {
    const db = await openDB()
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const store = tx.objectStore(STORE_NAME)
      store.clear()
      tx.oncomplete = () => resolve()
      tx.onerror = () => resolve()
    })
  } catch { /* no-op */ }
}

// Pre-defined cache keys for consistency
export const CACHE_KEYS = {
  ME: 'auth:me',
  GROUPS: 'groups:list',
  CATALOG: 'catalog',
  LEADERBOARD: (groupId) => `groups:${groupId}:leaderboard`,
  GROUP_TARGET: (groupId) => `groups:${groupId}:target`,
  USER: (id) => `users:${id}`,
  JOBS: (page, size) => `jobs:list:${page}:${size}`,
  JOB_PROFILES: 'jobs:profiles',
  JOB_SOURCES: 'jobs:sources',
  GITHUB_STATUS: 'github:status',
  GITHUB_SCHEDULE: 'github:schedule',
  GITHUB_PUSHES: 'github:pushes',
  POLL_STATUS: 'status:poll',
}

// TTL presets (in ms)
export const TTL = {
  SHORT: 30 * 1000,       // 30 seconds — leaderboard, things that update often
  MEDIUM: 5 * 60 * 1000,  // 5 minutes — user profile, groups, jobs
  LONG: 30 * 60 * 1000,   // 30 minutes — catalog (rarely changes)
  STALE_MAX: 2 * 60 * 60 * 1000, // 2 hours — max stale tolerance
}
