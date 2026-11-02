import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { IdentityLinks } from './IdentityLinks'

afterEach(() => { cleanup(); sessionStorage.clear(); localStorage.clear(); vi.unstubAllGlobals() })
const date = '2026-11-10T12:00:00Z'
const json = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body })

it('requires fresh holder consent, preserves UNKNOWN and reconciles without repeating a mutation', async () => {
  let state = 'PENDING'; let reference = ''; let posts = 0
  let release: (() => void) | undefined
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    expect(init.credentials).toBe('same-origin'); expect(init.cache).toBe('no-store')
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'fresh' }) }
    if (init.method === 'POST') {
      posts++
      expect(url).toContain('/received/link-a/accept')
      const value = JSON.parse(String(init.body)); reference = value.requestId
      expect(value.password).toBe('Fresh-holder-47!'); expect(value.actorId).toBeUndefined(); expect(value.tenantId).toBeUndefined()
      expect((init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('fresh')
      await new Promise<void>(resolve => { release = resolve })
      return json({ outcome: 'UNKNOWN', correlation: reference, receipt: null }, 202)
    }
    if (url.includes('/operations/')) {
      state = 'ACCEPTED'
      return json({ outcome: 'CONFIRMED', correlation: reference, receipt: { requestId: reference, tenantId: 'tenant-a', linkId: 'link-a', status: state, action: 'ACCEPT', createdAt: date } })
    }
    return json({ requests: [{ id: 'link-a', tenantId: 'tenant-a', company: 'Empresa A', status: state, expiresAt: date, createdAt: date, decidedAt: null }], hasMore: false })
  }))
  render(<IdentityLinks />)
  fireEvent.click(await screen.findByRole('button', { name: 'Aceptar Empresa A' }))
  expect(posts).toBe(0)
  fireEvent.change(screen.getByLabelText('Confirmar tu contraseña F04'), { target: { value: 'Fresh-holder-47!' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar aceptación' }))
  await waitFor(() => expect(posts).toBe(1))
  expect(screen.queryByText(/Decisión confirmada/)).toBeNull()
  expect(JSON.stringify(sessionStorage)).not.toContain('Fresh-holder-47!')
  release!()
  await screen.findByText(/Resultado UNKNOWN/)
  expect(screen.getByRole('button', { name: 'Aceptar Empresa A' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: 'Consultar resultado de vinculación' }))
  await screen.findByText(/Decisión confirmada por PostgreSQL: ACCEPTED/)
  expect(posts).toBe(1); expect(sessionStorage.length).toBe(0); expect(localStorage.length).toBe(0)
})

it('shows neutral administrative receipts without inferring an existing identity or access', async () => {
  let reference = ''; let submitted = false
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'fresh' }) }
    const receipt = () => ({ requestId: reference, tenantId: 'tenant-a', linkId: null, status: 'SUBMITTED', action: 'REQUEST', createdAt: date })
    if (init.method === 'POST') {
      const value = JSON.parse(String(init.body)); reference = value.requestId; submitted = true
      expect(value.username).toBe('persona'); expect(value.password).toBe('Admin-47!')
      return json({ outcome: 'CONFIRMED', correlation: reference, receipt: receipt() })
    }
    return json({ requests: submitted ? [receipt()] : [], hasMore: false })
  }))
  render(<IdentityLinks tenantId="tenant-a" />)
  fireEvent.change(await screen.findByLabelText('Username del titular'), { target: { value: 'persona' } })
  fireEvent.change(screen.getByLabelText('Confirmar tu contraseña F04'), { target: { value: 'Admin-47!' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar solicitud' }))
  await screen.findByText(/Solicitud procesada/)
  expect(screen.queryByRole('button', { name: /Aceptar/ })).toBeNull()
  expect(sessionStorage.length).toBe(0)
})
