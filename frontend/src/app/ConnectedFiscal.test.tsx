import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { App } from './App'

const id = (n: number) => `13000000-0000-0000-0000-${String(n).padStart(12, '0')}`
const tenants = [{ id: id(1), name: 'Empresa API A' }, { id: id(2), name: 'Empresa API B' }]
const branches = [{ id: id(11), code: 'A', name: 'Sucursal API A' }, { id: id(12), code: 'B', name: 'Sucursal API B' }]
const first = { id: id(31), code: 'branch.read', name: 'Consultar ubicación A', resourceScope: 'BRANCH', moduleId: id(41) }
const second = { id: id(32), code: 'reports.read', name: 'Consultar informes B', resourceScope: 'TENANT', moduleId: id(42) }
const makeContext = (tenantId: string, branchId: string) => {
  const permissions = [branchId === id(11) ? first : second]
  return { id: id(branchId === id(11) ? 51 : 52), applicationId: id(99), tenantId, branchId, permissions,
    modules: [{ id: permissions[0].moduleId, code: branchId === id(11) ? 'locations' : 'reports',
      name: branchId === id(11) ? 'Ubicaciones autorizadas' : 'Informes autorizados', permissions }] }
}

it('connects login, rotated CSRF, selection, live permissions/navigation, context loss, logout and expired-session recovery', async () => {
  let authenticated = false
  let selected: ReturnType<typeof makeContext> | null = null
  let csrf = 0
  let revokeContext = false
  let unblockSelection: (() => void) | undefined
  let sessions = [70, 71, 72].map((number, index) => ({ id: id(number), current: index === 0,
    createdAt: '2026-10-27T10:00:00Z', lastSeenAt: '2026-10-27T10:01:00Z', expiresAt: '2026-10-27T18:00:00Z',
    idleExpiresAt: '2026-10-27T10:31:00Z', status: 'ACTIVE', tenantRevoked: false }))
  const posts: string[] = []
  const storage = vi.spyOn(Storage.prototype, 'setItem')
  const json = (body: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
  const fetchRequest = vi.fn(async (url: string, init: RequestInit) => {
    expect(url.startsWith('/api/auth/fiscal/')).toBe(true)
    expect(init.credentials).toBe('same-origin')
    expect(init.cache).toBe('no-store')
    const action = url.slice('/api/auth/fiscal/'.length)
    if (action === 'csrf') { return json({ csrfToken: `csrf-${++csrf}`, headerName: 'X-CSRF-TOKEN' }) }
    if (init.method === 'POST') {
      expect((init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe(`csrf-${csrf}`)
      posts.push(action)
      if (action === 'login') { authenticated = true; return json(null, 204) }
    }
    if (!authenticated) { return json({ error: 'Authentication unavailable' }, 401) }
    if (action === 'session') { return json({ authenticated: true }) }
    if (action.endsWith('/session-administration')) { return json({ authorized: false }) }
    if (action === 'sessions') { return json(sessions) }
    if (action === 'sessions/revoke-others') { sessions = sessions.map(item => item.current ? item : { ...item, status: 'REVOKED' }); return json({ revoked: 1 }) }
    if (action.startsWith('sessions/') && action.endsWith('/revoke')) { sessions = sessions.map(item => action.includes(item.id) ? { ...item, status: 'REVOKED' } : item); return json({ revoked: 1 }) }
    if (action === 'tenants') { expect(csrf).toBeGreaterThanOrEqual(2); return json(tenants) }
    if (action.startsWith('tenants/')) { return json(branches) }
    if (action === 'logout') { authenticated = false; selected = null; return json(null, 204) }
    if (action === 'context' && init.method === 'POST') {
      const body = JSON.parse(String(init.body))
      if (body.branchId === id(12) && !unblockSelection) { await new Promise<void>(resolve => { unblockSelection = resolve }) }
      selected = makeContext(body.tenantId, body.branchId)
      return json(selected)
    }
    if (action === 'context' && revokeContext) { revokeContext = false; selected = null; return json({ error: 'Context unavailable' }, 403) }
    if (action === 'context') { return selected ? json(selected) : json(null, 204) }
    if (action === 'permissions') { return json(selected?.permissions ?? [], selected ? 200 : 403) }
    if (action === 'navigation') { return json(selected?.modules ?? [], selected ? 200 : 403) }
    throw new Error('Unexpected endpoint')
  })
  vi.stubEnv('VITE_CONNECTED_PILOT', 'true')
  vi.stubGlobal('fetch', fetchRequest)
  const signIn = async () => {
    fireEvent.change(await screen.findByLabelText('Usuario'), { target: { value: 'contract-user' } })
    fireEvent.change(screen.getByLabelText('Contraseña'), { target: { value: 'mock-only' } })
    fireEvent.click(screen.getByRole('button', { name: 'Ingresar' }))
    await screen.findByRole('button', { name: tenants[0].name })
  }
  try {
    render(<App />)
    await signIn()
    expect(posts).toEqual(['login'])
    expect(screen.queryByText('Registros de muestra')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: tenants[0].name }))
    fireEvent.click(await screen.findByRole('button', { name: /Sucursal API A/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar contexto' }))
    const permissions = await screen.findByRole('region', { name: 'Permisos efectivos' })
    expect(within(permissions).getByText(first.name)).toBeVisible()
    expect(within(screen.getByRole('navigation')).getByRole('button', { name: /Ubicaciones autorizadas/ })).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Administrar sesiones de empresa' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Mis sesiones' }))
    await screen.findByRole('heading', { name: 'Sesión actual' })
    expect(screen.queryByRole('region', { name: 'Permisos efectivos' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cerrar sesión actual' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: /Cerrar otra sesión: 00000071/ }))
    expect(posts.at(-1)).toBe('context') // Confirmation has not made a request.
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar revocación' }))
    await waitFor(() => expect(screen.getByRole('button', { name: /Cerrar otra sesión: 00000071/ })).toBeDisabled())
    expect(posts.at(-1)).toBe(`sessions/${id(71)}/revoke`)
    fireEvent.click(screen.getByRole('button', { name: 'Cerrar todas las demás sesiones' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar revocación' }))
    await waitFor(() => expect(screen.getByRole('button', { name: /Cerrar otra sesión: 00000072/ })).toBeDisabled())
    expect(screen.getByRole('button', { name: 'Cerrar sesión actual' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Volver al contexto' }))
    await screen.findByRole('region', { name: 'Permisos efectivos' })
    fireEvent.click(screen.getByRole('button', { name: /Sucursal API B/ }))
    expect(screen.queryByText(first.name)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Ubicaciones autorizadas/ })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar contexto' }))
    await waitFor(() => expect(unblockSelection).toBeDefined())
    expect(screen.queryByText(first.name)).not.toBeInTheDocument()
    unblockSelection?.()
    await waitFor(() => expect(within(screen.getByRole('region', { name: 'Permisos efectivos' })).getByText(second.name)).toBeVisible())
    expect(screen.queryByText(first.name)).not.toBeInTheDocument()
    revokeContext = true
    fireEvent.click(screen.getByRole('button', { name: 'Actualizar sesión' }))
    await screen.findByText(/El contexto dejó de estar disponible/)
    expect(screen.queryByRole('region', { name: 'Permisos efectivos' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: tenants[1].name }))
    fireEvent.click(await screen.findByRole('button', { name: /Sucursal API A/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar contexto' }))
    await screen.findByRole('region', { name: 'Permisos efectivos' })
    fireEvent.click(screen.getByRole('button', { name: 'Cerrar sesión' }))
    expect(screen.queryByRole('region', { name: 'Permisos efectivos' })).not.toBeInTheDocument()
    expect(screen.queryByText(tenants[0].name)).not.toBeInTheDocument()
    await screen.findByLabelText('Usuario')
    expect(posts.at(-1)).toBe('logout')
    expect(screen.getByLabelText('Contraseña')).toHaveValue('')
    await signIn()
    fireEvent.click(screen.getByRole('button', { name: 'Mis sesiones' }))
    await screen.findByRole('heading', { name: 'Sesión actual' })
    authenticated = false
    fireEvent.click(screen.getByRole('button', { name: 'Actualizar sesiones' }))
    await screen.findByText('La sesión no está vigente. Inicia sesión para continuar.')
    expect(screen.queryByRole('region', { name: 'Selector de contexto' })).not.toBeInTheDocument()
    expect(screen.queryByText(tenants[1].name)).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Sesión actual' })).not.toBeInTheDocument()
    expect(storage).not.toHaveBeenCalled()
    expect(document.cookie).toBe('')
  } finally { vi.unstubAllEnvs(); vi.unstubAllGlobals(); vi.restoreAllMocks() }
})
