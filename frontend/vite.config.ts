import { readFileSync } from 'node:fs'
import { Agent } from 'node:https'
import { defineConfig } from 'vitest/config'
import { loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const env = { ...loadEnv(mode, process.cwd(), ''), ...process.env }
  const connected = env.VITE_CONNECTED_PILOT === 'true'
  const catalog = env.VITE_CATALOG_ENABLED === 'true'
  const proxy: Record<string, object> = catalog ? {
    '/api/global-catalog': { target: 'http://127.0.0.1:8080', changeOrigin: false },
  } : {}
  let https
  if (connected) {
    const { LS_PILOT_TLS_PFX: pfx, LS_PILOT_TLS_CA: ca, LS_PILOT_TLS_PASSWORD: passphrase, LS_PILOT_BACKEND_URL: target } = env
    if (!pfx || !ca || !passphrase || !target) { throw new Error('El piloto requiere certificado, CA, contraseña TLS y backend HTTPS local explícitos.') }
    const backend = new URL(target)
    if (backend.protocol !== 'https:' || !['127.0.0.1', 'localhost', '[::1]'].includes(backend.hostname)
      || backend.username || backend.password || backend.search || backend.hash || backend.pathname !== '/') {
      throw new Error('El backend del piloto debe ser un origen HTTPS de loopback.')
    }
    https = { pfx: readFileSync(pfx), passphrase }
    proxy['/api/auth'] = { target: backend.origin, changeOrigin: false, secure: true,
      agent: new Agent({ ca: readFileSync(ca), rejectUnauthorized: true }) }
  }
  return {
    plugins: [react()],
    test: { environment: 'jsdom', setupFiles: ['./src/test/setup.ts'], clearMocks: true },
    server: { host: '127.0.0.1', port: connected ? 5174 : 5173, strictPort: true, https, proxy },
    preview: { host: '127.0.0.1', port: 4173, strictPort: true },
  }
})
