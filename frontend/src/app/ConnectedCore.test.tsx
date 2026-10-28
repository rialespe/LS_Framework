import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { ConnectedCore } from './ConnectedCore'

afterEach(() => { cleanup(); sessionStorage.clear(); vi.unstubAllGlobals(); vi.restoreAllMocks() })
it('uses real API contracts, confirms pending data only after confirmation and reconciles an uncertain duplicate safely', async () => {
  let authenticated = false
  let posts = 0
  let release: (() => void) | undefined
  let uncertain = false
  let lastId = ''
  const adminId = '40000000-0000-0000-0000-000000000001'
  const tenantId = '40000000-0000-0000-0000-000000000002'
  const json = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body })
  const confirmed = () => ({ outcome: 'CONFIRMED', correlation: lastId, tenant: { id: tenantId, name: 'Empresa desde PostgreSQL', status: 'PENDING', administratorId: adminId, createdAt: '2026-10-27T12:00:00Z', requestId: lastId } })
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    expect(init.credentials).toBe('same-origin')
    const action = url.replace('/api/auth/core/', '')
    if (action === 'csrf') { return json({ csrfToken: 'csrf-from-backend' }) }
    if (init.method === 'POST') { expect((init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('csrf-from-backend') }
    if (action === 'login') { authenticated = true; return json(null, 204) }
    if (!authenticated) { return json({}, 401) }
    if (action === 'session') { return json({ authenticated: true }) }
    if (action === 'authority') { return json({ kind: 'OWNER', canRegister: true }) }
    if (action === 'initial-administrator') { return json({ id: adminId, displayName: 'Administrador existente', status: 'ACTIVE' }) }
    if (action === 'logout') { authenticated = false; return json(null, 204) }
    if (action === 'registrations') {
      const body = JSON.parse(String(init.body)); expect(body.administratorId).toBe(adminId); expect(body.password).toBe('confirmed-password'); expect(body.tenantId).toBeUndefined()
      lastId = body.requestId; posts++
      if (!uncertain) { await new Promise<void>(resolve => { release = resolve }); return json(confirmed()) }
      return json({ outcome: 'UNKNOWN', correlation: lastId, tenant: null }, 202)
    }
    if (action.startsWith('registrations/')) { return json(confirmed()) }
    throw new Error('Unexpected contract')
  }))
  render(<ConnectedCore />)
  await screen.findByRole('heading', { name: 'Acceso independiente a LS Core' })
  fireEvent.change(screen.getByLabelText('Usuario'), { target: { value: 'owner' } }); fireEvent.change(screen.getByLabelText('Contraseña'), { target: { value: 'login-password' } }); fireEvent.click(screen.getByRole('button', { name: 'Ingresar' }))
  await screen.findByRole('button', { name: 'Buscar identidad' })
  const fill = async () => {
    fireEvent.change(screen.getByLabelText('Usuario del administrador'), { target: { value: 'existing' } }); fireEvent.click(screen.getByRole('button', { name: 'Buscar identidad' }))
    await screen.findByText(/Administrador existente/)
    fireEvent.change(screen.getByLabelText('Nombre de la empresa'), { target: { value: 'Nombre solicitado' } }); fireEvent.change(screen.getByLabelText('Confirmar tu contraseña'), { target: { value: 'confirmed-password' } })
  }
  await fill()
  fireEvent.click(screen.getByRole('button', { name: 'Crear empresa pendiente' })); fireEvent.click(screen.getByRole('button', { name: 'Crear empresa pendiente' }))
  await waitFor(() => expect(posts).toBe(1)); expect(screen.queryByRole('region', { name: 'Empresa registrada' })).not.toBeInTheDocument(); expect(screen.getByRole('button', { name: 'Crear empresa pendiente' })).toBeDisabled()
  release!()
  await screen.findByRole('region', { name: 'Empresa registrada' }); expect(screen.getByText('Empresa desde PostgreSQL')).toBeInTheDocument(); expect(screen.getByText('PENDIENTE')).toBeInTheDocument(); expect(screen.getByText(tenantId)).toBeInTheDocument()
  expect(sessionStorage.getItem('ls.core.pending-registration')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Nueva empresa' })); await fill(); uncertain = true
  fireEvent.click(screen.getByRole('button', { name: 'Crear empresa pendiente' })); await screen.findByRole('heading', { name: 'Creación sin confirmar' })
  expect(posts).toBe(2); expect(screen.queryByRole('region', { name: 'Empresa registrada' })).not.toBeInTheDocument(); expect(sessionStorage.getItem('ls.core.pending-registration')).toBe(lastId)
  fireEvent.click(screen.getByRole('button', { name: 'Consultar resultado' })); await screen.findByRole('region', { name: 'Empresa registrada' }); expect(posts).toBe(2)
  authenticated = false; fireEvent.click(screen.getByRole('button', { name: 'Revalidar acceso' })); await screen.findByRole('heading', { name: 'Acceso independiente a LS Core' }); expect(screen.queryByText(tenantId)).not.toBeInTheDocument()
})
