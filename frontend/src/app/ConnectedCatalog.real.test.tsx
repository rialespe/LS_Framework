import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { App } from './App'

// Explicit local opt-in only. CI/unit runs skip this test; all responses are real HTTP.
describe.runIf(process.env.LS_CATALOG_E2E === 'true')('Docker catalog real React DOM E2E', () => {
  it('renders application and module names, codes and states from the real Vite/API response', async () => {
    const nativeFetch = globalThis.fetch
    const base = 'http://127.0.0.1:5173'
    const applicationsResponse = await nativeFetch(`${base}/api/global-catalog/applications?limit=100&offset=0`)
    expect(applicationsResponse.status).toBe(200)
    const applications = await applicationsResponse.json() as { id: string; code: string; name: string; status: string }[]
    expect(applications.length).toBeGreaterThan(0)
    const application = applications[0]
    const modulesResponse = await nativeFetch(`${base}/api/global-catalog/applications/${application.id}/modules?limit=100&offset=0`)
    expect(modulesResponse.status).toBe(200)
    const modules = await modulesResponse.json() as { id: string; code: string; name: string; status: string }[]
    expect(modules.length).toBeGreaterThan(0)
    // URL resolution adapter for jsdom's Node fetch; no mock server or fabricated data.
    globalThis.fetch = (input, init) => nativeFetch(typeof input === 'string' ? new URL(input, base) : input, init)
    try {
      render(<App />)
      fireEvent.click(within(screen.getByRole('navigation')).getByRole('button', { name: 'Aplicaciones' }))
      expect(screen.getByText('LS Operaciones')).toBeVisible()
      fireEvent.click(screen.getByRole('button', { name: 'Abrir catálogo global conectado' }))
      expect(screen.getByText('Consultando PostgreSQL Docker…')).toBeVisible()
      const applicationButton = await screen.findByRole('button', { name: application.name })
      expect(applicationButton).toBeVisible()
      expect(screen.getByText(`${application.code} · ${application.status}`)).toBeVisible()
      expect(screen.queryByText('LS Operaciones')).not.toBeInTheDocument()
      fireEvent.click(applicationButton)
      const moduleRegion = screen.getByRole('region', { name: 'Módulos desde PostgreSQL' })
      for (const module of modules) {
        expect(await within(moduleRegion).findByRole('heading', { name: module.name })).toBeVisible()
        expect(within(moduleRegion).getByText(`${module.code} · ${module.status}`)).toBeVisible()
      }
      expect(screen.getByText('Esta pantalla consulta PostgreSQL Docker local · Solo lectura')).toBeVisible()
      console.info(JSON.stringify({ evidence: 'REAL_REACT_DOM_HTTP_MATCH', application, modules }))
    } finally {
      globalThis.fetch = nativeFetch
    }
  })
})
