import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ConnectedCatalog } from './ConnectedCatalog'
import { App } from './App'

const application = { id: '3bc09866-f9f0-573c-82a0-fd72ec6be41f', code: 'api-app', name: 'API Application', description: 'From API', status: 'PUBLISHED' }
const module = { id: 'module-id', applicationId: application.id, code: 'api-module', name: 'API Module', description: 'Module from API', status: 'PUBLISHED' }
const response = (items: unknown[]) => ({ ok: true, json: async () => items }) as Response

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllEnvs() })

describe('Connected global catalog', () => {
  it('labels the real catalog context and preserves preview labels elsewhere', async () => {
    vi.stubEnv('DEV', true); vi.stubEnv('VITE_CATALOG_ENABLED', 'true')
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(response([]))
    render(<App />)
    expect(screen.getByText('Previsualización visual')).toBeInTheDocument()
    fireEvent.click(within(screen.getByRole('navigation')).getByRole('button', { name: 'Aplicaciones' }))
    expect(screen.getByText('Previsualización visual')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Abrir catálogo global conectado/ }))
    expect(screen.getByText('Catálogo real · Solo lectura')).toBeInTheDocument()
    expect(screen.queryByText('Previsualización visual')).not.toBeInTheDocument()
    await screen.findByText('Sin resultados en esta página del catálogo.')
    fireEvent.click(screen.getByRole('button', { name: 'Volver a Aplicaciones de muestra' }))
    expect(screen.getByText('Previsualización visual')).toBeInTheDocument()
    expect(screen.queryByText('Catálogo real · Solo lectura')).not.toBeInTheDocument()
  })

  it('is disabled by default and absent from production even with the flag', () => {
    const fetchRequest = vi.spyOn(globalThis, 'fetch')
    vi.stubEnv('VITE_CATALOG_ENABLED', 'false')
    const { unmount } = render(<App />)
    fireEvent.click(within(screen.getByRole('navigation')).getByRole('button', { name: 'Aplicaciones' }))
    expect(screen.queryByRole('button', { name: /Abrir catálogo global conectado/ })).not.toBeInTheDocument()
    unmount()
    vi.stubEnv('DEV', false); vi.stubEnv('VITE_CATALOG_ENABLED', 'true')
    render(<App />)
    fireEvent.click(within(screen.getByRole('navigation')).getByRole('button', { name: 'Aplicaciones' }))
    expect(screen.queryByRole('button', { name: /Abrir catálogo global conectado/ })).not.toBeInTheDocument()
    expect(fetchRequest).not.toHaveBeenCalled()
  })

  it('shows loading, API applications and modules separately from fixtures without credentials or persistence', async () => {
    vi.stubEnv('DEV', true); vi.stubEnv('VITE_CATALOG_ENABLED', 'true')
    const request = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(response([application])).mockResolvedValueOnce(response([module]))
    const storage = vi.spyOn(Storage.prototype, 'setItem')
    const cookie = vi.spyOn(document, 'cookie', 'set')
    render(<App />)
    fireEvent.click(within(screen.getByRole('navigation')).getByRole('button', { name: 'Aplicaciones' }))
    fireEvent.click(screen.getByRole('button', { name: /Abrir catálogo global conectado/ }))
    expect(screen.getByText('Consultando PostgreSQL Docker…')).toBeInTheDocument()
    expect(screen.getByText('Esta pantalla consulta PostgreSQL Docker local · Solo lectura')).toBeVisible()
    fireEvent.click(await screen.findByRole('button', { name: 'API Application' }))
    expect(await screen.findByRole('heading', { name: 'API Module' })).toBeVisible()
    expect(screen.getAllByText(/PUBLISHED/).length).toBeGreaterThan(0)
    expect(screen.queryByText('LS Operaciones')).not.toBeInTheDocument()
    expect(request).toHaveBeenNthCalledWith(1, '/api/global-catalog/applications?limit=100&offset=0', expect.objectContaining({ credentials: 'omit', cache: 'no-store' }))
    expect(request).toHaveBeenNthCalledWith(2, `/api/global-catalog/applications/${application.id}/modules?limit=100&offset=0`, expect.anything())
    expect(storage).not.toHaveBeenCalled(); expect(cookie).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Volver a Aplicaciones de muestra' }))
    expect(screen.getByRole('heading', { level: 1, name: 'Aplicaciones' })).toHaveFocus()
  })

  it('shows controlled errors without fixture fallback and retries to an empty result', async () => {
    const request = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({ ok: false, status: 503 } as Response).mockResolvedValueOnce(response([]))
    render(<ConnectedCatalog onBack={() => {}} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos consultar el catálogo')
    expect(screen.queryByText('LS Core')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar consulta' }))
    expect(await screen.findByText('Sin resultados en esta página del catálogo.')).toBeVisible()
    expect(request).toHaveBeenCalledTimes(2)
  })

  it('handles module failures and ignores results after leaving the view', async () => {
    const request = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(response([application])).mockRejectedValueOnce(new Error('Private driver text')).mockResolvedValueOnce(response([]))
    const { unmount } = render(<ConnectedCatalog onBack={() => {}} />)
    fireEvent.click(await screen.findByRole('button', { name: 'API Application' }))
    expect(await screen.findByRole('alert')).not.toHaveTextContent('Private driver text')
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar consulta' }))
    await waitFor(() => expect(screen.getByText('Sin resultados en esta página del catálogo.')).toBeVisible())
    const signal = request.mock.calls[0][1]?.signal
    unmount()
    expect(signal?.aborted).toBe(true)
  })
})
