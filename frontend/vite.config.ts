import { defineConfig } from 'vitest/config'
import { loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    clearMocks: true,
  },
  server: { host: '127.0.0.1', port: 5173, strictPort: true,
    proxy: (process.env.VITE_CATALOG_ENABLED ?? loadEnv(mode, process.cwd()).VITE_CATALOG_ENABLED) === 'true' ? {
      '/api/global-catalog': { target: 'http://127.0.0.1:8080', changeOrigin: false },
    } : undefined,
  },
  preview: { host: '127.0.0.1', port: 4173, strictPort: true },
}))
