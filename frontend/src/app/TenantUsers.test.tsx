import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { TemporaryPasswordExchange, TenantUserReceipt, TenantUsers } from './TenantUsers'
import type { TenantUser, TenantUserResult } from './coreApi'

afterEach(() => { cleanup(); sessionStorage.clear(); localStorage.clear(); vi.unstubAllGlobals(); vi.restoreAllMocks() })
const date = '2026-10-30T12:00:00Z'
const member: TenantUser = { id: 'member-a', userId: 'identity-a', username: 'nuevo', displayName: 'Persona nueva', email: null, emailVerified: false, identityStatus: 'PENDING', membershipStatus: 'PENDING', createdAt: date, createdBy: 'actor', mustChangePassword: true, passwordExpiresAt: date, f04Ready: false, managedIncorporation: true }
const json = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body })
function fill() {
  fireEvent.click(screen.getByRole('button', { name: 'Nuevo usuario' }))
  fireEvent.change(screen.getByLabelText('Nombre completo'), { target: { value: 'Persona nueva' } })
  fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'nuevo' } })
  fireEvent.change(screen.getByLabelText('Confirmar contraseña administrativa'), { target: { value: 'Fresh-admin-46!' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar alta de usuario' }))
}
const confirmed = (reference: string, secret: string | null): TenantUserResult => ({ outcome: 'CONFIRMED', correlation: reference, temporaryPassword: secret, change: { requestId: reference, tenantId: 'company-a', action: 'CREATE', userId: member.userId, membershipId: member.id, resultingStatus: 'PENDING', temporaryExpiresAt: date, createdAt: date, actorId: 'actor' } })

it('delivers only after confirmation, clears the view, keeps secrets out of storage/history and activates after F04', async () => {
  let reference = ''; let posts = 0; let release: (() => void) | undefined; let ready = false; let active = false
  const secret = 'one-time-secret-46'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    expect(init.credentials).toBe('same-origin'); expect(init.cache).toBe('no-store')
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'fresh-csrf' }) }
    if (init.method === 'POST') {
      const body = JSON.parse(String(init.body)); expect(body.password).toBe('Fresh-admin-46!'); expect(body.actorId).toBeUndefined()
      expect((init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('fresh-csrf'); reference = body.requestId; posts++
      if (!url.endsWith('/activate')) { expect(body.email).toBeNull(); await new Promise<void>(resolve => { release = resolve }) } else { active = true }
      return json(confirmed(reference, active ? null : secret), active ? 200 : 201)
    }
    const user = { ...member, f04Ready: ready, identityStatus: ready ? 'ACTIVE' : 'PENDING', membershipStatus: active ? 'ACTIVE' : 'PENDING' }
    if (url.endsWith('/' + member.id)) { return json({ user, canManage: true, history: [confirmed(reference, null).change] }) }
    return json({ users: posts ? [user] : [], hasMore: false, canManage: true })
  }))
  render(<TenantUsers tenantId="company-a" />)
  await screen.findByRole('button', { name: 'Nuevo usuario' }); fill()
  await vi.waitFor(() => expect(posts).toBe(1))
  expect(screen.queryByText(secret)).toBeNull()
  expect(screen.getByRole('button', { name: 'Nuevo usuario' }).hasAttribute('disabled')).toBe(true)
  release!()
  await screen.findByText(secret)
  await screen.findByRole('button', { name: 'Activar membresía tras F04' })
  expect(screen.getByRole('button', { name: 'Activar membresía tras F04' }).hasAttribute('disabled')).toBe(true)
  expect(JSON.stringify(sessionStorage)).not.toContain(secret); expect(localStorage.length).toBe(0)
  expect(screen.getByLabelText('Detalle de membresía').textContent).not.toContain(secret)
  fireEvent.click(screen.getByRole('button', { name: 'Cerrar entrega única' }))
  expect(screen.queryByText(secret)).toBeNull()
  ready = true
  fireEvent.click(screen.getByRole('button', { name: 'Ver nuevo' }))
  await screen.findByText('Credencial definitiva vigente')
  fireEvent.click(screen.getByRole('button', { name: 'Activar membresía tras F04' }))
  fireEvent.change(screen.getByLabelText('Confirmar contraseña administrativa'), { target: { value: 'Fresh-admin-46!' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar activación' }))
  await vi.waitFor(() => expect(posts).toBe(2))
  await screen.findByText('ACTIVE / ACTIVE')
  expect(screen.queryByText(secret)).toBeNull()
})

it('blocks an uncertain command, reconciles without a secret, and rejects existing identity creation neutrally', async () => {
  let reference = ''; let posts = 0; let conflict = false
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'csrf' }) }
    if (url.includes('/operations/')) { return json(confirmed(reference, null)) }
    if (init.method === 'POST') { posts++; reference = JSON.parse(String(init.body)).requestId; return json({ outcome: conflict ? 'CONFLICT' : 'UNKNOWN', correlation: reference, change: null, temporaryPassword: null }, conflict ? 409 : 202) }
    if (url.endsWith('/' + member.id)) { return json({ user: member, canManage: true, history: [confirmed(reference, null).change] }) }
    return json({ users: [member], canManage: true, hasMore: false })
  }))
  render(<TenantUsers tenantId="company-a" />)
  await screen.findByRole('button', { name: 'Nuevo usuario' }); fill()
  await screen.findByText(/Resultado UNKNOWN/)
  expect(sessionStorage.getItem('ls.core.pending-user')).toBe(reference)
  expect(screen.getByRole('button', { name: 'Nuevo usuario' }).hasAttribute('disabled')).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Consultar resultado de incorporación' }))
  await screen.findByLabelText('Detalle de membresía')
  expect(screen.queryByLabelText('Entrega única de contraseña temporal')).toBeNull()
  expect(sessionStorage.getItem('ls.core.pending-user')).toBeNull()
  conflict = true; fill()
  await screen.findByText(/No se vinculó una identidad existente/)
  expect(posts).toBe(2)
})

it('does not revive a one-time delivery after unmount or a late response', async () => {
  let release: (() => void) | undefined; let reference = ''
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'csrf' }) }
    if (init.method === 'POST') { reference = JSON.parse(String(init.body)).requestId; await new Promise<void>(resolve => { release = resolve }); return json(confirmed(reference, 'late-secret')) }
    return json({ users: [], canManage: true, hasMore: false })
  }))
  const view = render(<TenantUsers tenantId="company-a" />)
  await screen.findByRole('button', { name: 'Nuevo usuario' }); fill()
  await vi.waitFor(() => expect(release).toBeTruthy()); view.unmount(); release!()
  render(<TenantUsers tenantId="company-a" />)
  await screen.findByRole('button', { name: 'Consultar resultado de incorporación' })
  expect(screen.queryByText('late-secret')).toBeNull()
  expect(sessionStorage.getItem('ls.core.pending-user')).toBe(reference)
})

it('exchanges the temporary credential with CSRF and clears the form', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'csrf' }) }
    expect(url).toBe('/api/auth/core/tenant-users/exchange')
    expect(JSON.parse(String(init.body))).toEqual({ username: 'nuevo', temporaryPassword: 'temporal', newPassword: 'Definitiva-46!' })
    return json(null, 204)
  }))
  render(<TemporaryPasswordExchange />)
  fireEvent.change(screen.getByLabelText('Username del titular'), { target: { value: 'nuevo' } })
  fireEvent.change(screen.getByLabelText('Contraseña temporal recibida'), { target: { value: 'temporal' } })
  fireEvent.change(screen.getByLabelText('Nueva contraseña definitiva'), { target: { value: 'Definitiva-46!' } })
  fireEvent.click(screen.getByRole('button', { name: 'Completar canje F04' }))
  await screen.findByText(/Canje F04 confirmado/)
  expect((screen.getByLabelText('Contraseña temporal recibida') as HTMLInputElement).value).toBe('')
})

it('discards a late secret when the page was hidden during the command', async () => {
  let release: (() => void) | undefined; let reference = ''
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'csrf' }) }
    if (init.method === 'POST') { reference = JSON.parse(String(init.body)).requestId; await new Promise<void>(resolve => { release = resolve }); return json(confirmed(reference, 'hidden-page-secret')) }
    if (url.endsWith('/' + member.id)) { return json({ user: member, canManage: true, history: [] }) }
    return json({ users: [], canManage: true, hasMore: false })
  }))
  render(<TenantUsers tenantId="company-a" />)
  await screen.findByRole('button', { name: 'Nuevo usuario' }); fill()
  await vi.waitFor(() => expect(release).toBeTruthy())
  visibility.mockReturnValue('hidden'); fireEvent(document, new Event('visibilitychange'))
  visibility.mockReturnValue('visible'); fireEvent(document, new Event('visibilitychange')); release!()
  await screen.findByLabelText('Detalle de membresía')
  expect(screen.queryByText('hidden-page-secret')).toBeNull()
  expect(screen.queryByLabelText('Entrega única de contraseña temporal')).toBeNull()
})

it('reconciles the original actor receipt without restoring management or displaying a secret', async () => {
  sessionStorage.setItem('ls.core.pending-user', 'request-with-revoked-authority')
  vi.stubGlobal('fetch', vi.fn(async () => json(confirmed('request-with-revoked-authority', null))))
  render(<TenantUserReceipt />)
  fireEvent.click(screen.getByRole('button', { name: 'Consultar recibo del actor original' }))
  await screen.findByText(/Incorporación confirmada/)
  expect(sessionStorage.getItem('ls.core.pending-user')).toBeNull()
  expect(screen.queryByRole('button', { name: 'Nuevo usuario' })).toBeNull()
  expect(screen.queryByLabelText('Entrega única de contraseña temporal')).toBeNull()
})
