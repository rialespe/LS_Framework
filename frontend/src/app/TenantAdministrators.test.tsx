import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { TenantAdministrators } from './TenantAdministrators'
import type { TenantDesignation } from './coreApi'

afterEach(() => { cleanup(); sessionStorage.clear(); vi.unstubAllGlobals(); vi.restoreAllMocks() })
const date = '2026-10-28T12:00:00Z'
const designation = (username: string): TenantDesignation => ({ id: username, userId: username, username, displayName: username + ' persistido', identityStatus: 'ACTIVE', membershipStatus: 'ACTIVE', status: 'ACTIVE', revokedAt: null, grantedAt: date, valid: true })
const json = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body })
async function confirm() {
  fireEvent.change(screen.getByLabelText('Confirmar nuevamente tu contraseña'), { target: { value: 'Fresh-confirmation-42!' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar operación administrativa' }))
}
async function candidate(username: string) {
  fireEvent.change(screen.getByLabelText('Username exacto del candidato'), { target: { value: username } })
  fireEvent.click(screen.getByRole('button', { name: 'Consultar candidato' }))
  await screen.findByText(new RegExp(username + '.*F04 vigente'))
}
it('lists server data, designates once, revokes, protects the last administrator and replaces atomically', async () => {
  let values = [designation('inicial')]
  let posts = 0
  let reference = ''
  let release: (() => void) | undefined
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    expect(init.credentials).toBe('same-origin')
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'fresh-token' }) }
    if (url.endsWith('/candidate')) {
      const body = JSON.parse(String(init.body))
      return json({ username: body.username, displayName: body.username, identityStatus: 'ACTIVE', ready: true })
    }
    if (init.method === 'POST') {
      expect((init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('fresh-token')
      const body = JSON.parse(String(init.body)); reference = body.requestId; posts++
      expect(body.password).toBe('Fresh-confirmation-42!'); expect(body.actorId).toBeUndefined()
      if (url.endsWith('/designate')) { await new Promise<void>(resolve => { release = resolve }); values.push(designation(body.username)) }
      if (url.endsWith('/revoke')) {
        if (values.filter(row => row.valid).length === 1) { return json({ outcome: 'CONFLICT', correlation: reference }, 409) }
        values = values.map(row => url.includes('/' + row.id + '/') ? { ...row, status: 'REVOKED', valid: false, revokedAt: date } : row)
      }
      if (url.endsWith('/replace')) { values = values.map(row => url.includes('/' + row.id + '/') ? { ...row, status: 'REVOKED', valid: false, revokedAt: date } : row); values.push(designation(body.username)) }
      return json({ outcome: 'CONFIRMED', correlation: reference, change: { requestId: reference } })
    }
    return json({ administrators: values, validCount: values.filter(row => row.valid).length, canManage: true, history: [] })
  }))
  render(<TenantAdministrators tenantId="company-a" />)
  await screen.findByText('inicial persistido')
  fireEvent.click(screen.getByRole('button', { name: 'Agregar administrador' }))
  await candidate('segundo')
  await confirm()
  await vi.waitFor(() => expect(posts).toBe(1))
  expect(screen.queryByText('segundo persistido')).toBeNull()
  expect(screen.getByRole('button', { name: 'Agregar administrador' }).hasAttribute('disabled')).toBe(true)
  release!()
  await screen.findByText('segundo persistido')
  expect(screen.queryByLabelText('Confirmar nuevamente tu contraseña')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Revocar inicial' })); await confirm()
  await vi.waitFor(() => expect(screen.queryByRole('button', { name: 'Revocar inicial' })).toBeNull())
  fireEvent.click(screen.getByRole('button', { name: 'Revocar segundo' }))
  expect(screen.getByRole('alert').textContent).toContain('último administrador')
  await confirm(); await screen.findByText(/Conflicto: revalida/)
  expect(screen.getByRole('button', { name: 'Revocar segundo' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Reemplazar segundo' }))
  await candidate('reemplazo'); await confirm()
  await screen.findByText('reemplazo persistido')
  expect(screen.queryByRole('button', { name: 'Revocar segundo' })).toBeNull()
  expect(posts).toBe(4)
})

it('blocks mutations during UNKNOWN, reconciles the reference and revalidates a concurrent conflict', async () => {
  let reference = ''
  let posts = 0
  let outcome = 'UNKNOWN'
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'fresh' }) }
    if (url.includes('/administrator-operations/')) { return json({ outcome: 'CONFIRMED', correlation: reference, change: { requestId: reference } }) }
    if (init.method === 'POST') { reference = JSON.parse(String(init.body)).requestId; posts++; return json({ outcome, correlation: reference }, outcome === 'UNKNOWN' ? 202 : 409) }
    return json({ administrators: [designation('primero'), designation('segundo')], validCount: 2, canManage: true, history: [] })
  }))
  render(<TenantAdministrators tenantId="company-a" />)
  await screen.findByText('primero persistido')
  fireEvent.click(screen.getByRole('button', { name: 'Revocar primero' })); await confirm()
  await screen.findByText(/Resultado UNKNOWN/)
  expect(screen.getByText(reference)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Agregar administrador' }))
  expect(posts).toBe(1)
  expect(screen.queryByLabelText('Username exacto del candidato')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Consultar resultado administrativo' }))
  await screen.findByText(/Operación confirmada por PostgreSQL/)
  expect(sessionStorage.getItem('ls.core.pending-administrator')).toBeNull()
  outcome = 'CONFLICT'
  fireEvent.click(screen.getByRole('button', { name: 'Revocar primero' })); await confirm()
  await screen.findByText(/Conflicto: revalida/)
  expect(posts).toBe(2)
})

it('removes administrative data after a current authority denial', async () => {
  let denied = false
  vi.stubGlobal('fetch', vi.fn(async () => denied ? json({ error: 'Operation unavailable' }, 403) : json({ administrators: [designation('autorizado')], validCount: 1, canManage: true, history: [] })))
  render(<TenantAdministrators tenantId="company-a" />)
  await screen.findByText('autorizado persistido')
  denied = true
  fireEvent.click(screen.getByRole('button', { name: 'Revalidar información' }))
  await screen.findByText(/No se pudo verificar la autoridad empresarial/)
  expect(screen.queryByText('autorizado persistido')).toBeNull()
  expect(screen.queryByRole('button', { name: 'Agregar administrador' })).toBeNull()
})
