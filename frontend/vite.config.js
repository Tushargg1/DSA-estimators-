import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

function requireDeploymentUrl(name, value, expectedPath) {
  if (!value) throw new Error(`${name} is required for Vercel builds`)
  let url
  try { url = new URL(value) } catch { throw new Error(`${name} must be a valid absolute URL`) }
  if (url.protocol !== 'https:') throw new Error(`${name} must use https:// in production`)
  if (!url.pathname.endsWith(expectedPath)) throw new Error(`${name} must end with ${expectedPath}`)
  return value
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  if (process.env.VERCEL === '1') {
    requireDeploymentUrl('VITE_API_BASE_URL', env.VITE_API_BASE_URL, '/api')
    requireDeploymentUrl('VITE_WS_URL', env.VITE_WS_URL, '/ws')
  }
  return {
    plugins: [react()],
    define: { global: 'globalThis' },
    server: { port: 5173 },
  }
})
