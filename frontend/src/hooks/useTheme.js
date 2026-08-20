import { useCallback, useEffect, useState } from 'react'

const STORAGE_KEY = 'dsaTracker.theme'
const VALID = new Set(['light', 'dark'])

/**
 * Read the saved preference, falling back to the operating system setting so a
 * first-time visitor gets the theme they already expect.
 */
function initialTheme() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (VALID.has(saved)) return saved
  } catch { /* Storage can be blocked; fall through to the system preference. */ }
  try {
    return globalThis.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
  } catch { return 'dark' }
}

/**
 * Applies the theme as a `data-theme` attribute on <html>, which is what the CSS
 * token overrides key off. Setting it on the document (rather than a wrapper element)
 * means the page background follows the theme too.
 */
export function useTheme() {
  const [theme, setTheme] = useState(initialTheme)

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme)
    try { localStorage.setItem(STORAGE_KEY, theme) } catch { /* Preference is still applied. */ }
  }, [theme])

  const toggleTheme = useCallback(() => {
    setTheme((current) => (current === 'dark' ? 'light' : 'dark'))
  }, [])

  return { theme, toggleTheme }
}

export default useTheme
