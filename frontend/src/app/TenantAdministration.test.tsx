import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { TenantAdministration } from './TenantAdministration'

afterEach(() => { cleanup(); sessionStorage.clear(); vi.unstubAllGlobals(); vi.restoreAllMocks() })
it('lists persisted companies, requires confirmation, updates only after commit and reconciles UNKNOWN', async () => {
  let status = 'PENDING'
  let posts = 0
  let reference = ''
  let uncertain = false
  let release: (() => void) | undefined
  const tenant = () => ({ id: '41000000-0000-0000-0000-000000000001', name: 'Empresa persistida', status, administratorId: 'admin', administratorName: 'Administrador preparado', administratorStatus: 'ACTIVE', createdAt: '2026-10-28T12:00:00Z' })
  const change = () => ({ requestId: reference, action: 'ACTIVATE', previousStatus: 'PENDING', resultingStatus: status, createdAt: '2026-10-28T12:00:00Z' })
  const json = (body: unknown, code = 200) => ({ ok: code < 400, status: code, json: async () => body })
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    expect(init.credentials).toBe('same-origin')
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'new-csrf' }) }
    if (url.endsWith('/tenant-authority')) { return json({ kind: 'OWNER', canRead: true, canActivate: true, canSuspend: true, canReactivate: true }) }
    if (url.includes('tenants?page=')) { return json({ tenants: [tenant()], hasMore: false }) }
    if (init.method === 'POST') {
      expect((init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('new-csrf')
      const body = JSON.parse(String(init.body))
      expect(body.password).toBe('fresh-confirmation')
      expect(body.actorId).toBeUndefined()
      reference = body.requestId; posts++
      if (uncertain) { return json({ outcome: 'UNKNOWN', correlation: reference, change: null }, 202) }
      await new Promise<void>(resolve => { release = resolve })
      status = url.endsWith('/suspend') ? 'SUSPENDED' : 'ACTIVE'
      return json({ outcome: 'CONFIRMED', correlation: reference, change: change() })
    }
    if (url.includes('/tenant-operations/')) {
      status = 'SUSPENDED'
      return json({ outcome: 'CONFIRMED', correlation: reference, change: change() })
    }
    return json({ tenant: tenant(), history: status === 'PENDING' ? [] : [change()] })
  }))
  render(<TenantAdministration />)
  await screen.findByText('Empresa persistida')
  fireEvent.click(screen.getByRole('button', { name: 'Ver ficha' }))
  await screen.findByRole('button', { name: 'Activar' })
  fireEvent.click(screen.getByRole('button', { name: 'Activar' }))
  expect(posts).toBe(0)
  fireEvent.change(screen.getByLabelText('Confirmar contraseña para activar'), { target: { value: 'fresh-confirmation' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar activar' }))
  await vi.waitFor(() => expect(posts).toBe(1))
  expect(within(screen.getByRole('region', { name: 'Ficha de empresa' })).getByText('Pendiente')).toBeTruthy()
  release!()
  await screen.findByRole('button', { name: 'Suspender' })
  expect(within(screen.getByRole('region', { name: 'Ficha de empresa' })).getByText('Activa')).toBeTruthy()
  uncertain = true
  fireEvent.click(screen.getByRole('button', { name: 'Suspender' }))
  fireEvent.change(screen.getByLabelText('Confirmar contraseña para suspender'), { target: { value: 'fresh-confirmation' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar suspender' }))
  await screen.findByText(/Resultado UNKNOWN/)
  expect(screen.queryByRole('button', { name: 'Reactivar' })).toBeNull()
  expect(screen.getByText(reference)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Consultar operación' }))
  await screen.findByText('Suspendida')
  fireEvent.click(screen.getByRole('button', { name: 'Ver ficha' }))
  await screen.findByRole('button', { name: 'Reactivar' })
  expect(posts).toBe(2)
})
