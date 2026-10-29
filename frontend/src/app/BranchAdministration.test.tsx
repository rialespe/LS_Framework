import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { BranchAdministration } from './BranchAdministration'
import { coreApi } from './coreApi'
import type { ManagedBranch } from './coreApi'

afterEach(() => { cleanup(); sessionStorage.clear(); vi.unstubAllGlobals(); vi.restoreAllMocks() })
const tenant = '43000000-0000-0000-0000-000000000001'
const date = '2026-10-29T02:00:00.123456Z'
const json = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body })
const branch = (): ManagedBranch => ({ id: '43000000-0000-0000-0000-000000000002', tenantId: tenant, code: 'CASA MATRIZ', name: 'Matriz persistida', status: 'ACTIVE', createdAt: date, updatedAt: date })
async function select() {
  await screen.findByRole('option', { name: 'Empresa persistida · ACTIVE' })
  fireEvent.change(screen.getByLabelText('Empresa autorizada para sucursales'), { target: { value: tenant } })
}
async function confirm() {
  fireEvent.change(screen.getByLabelText('Confirmar contraseña para sucursal'), { target: { value: 'Fresh-branch-43!' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar cambio de sucursal' }))
}
it('uses persisted data for create edit suspend reactivate and terminal close, with fresh CSRF and password', async () => {
  let values: ManagedBranch[] = []
  let reference = ''
  let posts = 0
  let release: (() => void) | undefined
  let protectCode = false
  const changes: unknown[] = []
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    expect(init.credentials).toBe('same-origin'); expect(init.cache).toBe('no-store')
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'fresh-csrf' }) }
    if (url.endsWith('/branch-administration/tenants')) { return json([{ id: tenant, name: 'Empresa persistida', status: 'ACTIVE' }]) }
    if (init.method === 'POST') {
      const body = JSON.parse(String(init.body)); reference = body.requestId; posts++
      expect((init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('fresh-csrf')
      expect(body.password).toBe('Fresh-branch-43!'); expect(body.actorId).toBeUndefined(); expect(body.tenantId).toBeUndefined()
      const previous = values[0]
      if (url.endsWith('/branches')) {
        expect(body.expectedUpdatedAt).toBeNull()
        await new Promise<void>(resolve => { release = resolve })
        values = [{ ...branch(), code: body.code, name: body.name }]
      } else {
        expect(body.expectedUpdatedAt).toBe(previous.updatedAt)
        values = [{ ...previous, updatedAt: `2026-10-29T02:00:0${posts}.123456Z` }]
        if (url.endsWith('/edit')) { values[0].code = body.code; values[0].name = body.name; protectCode = true }
        if (url.endsWith('/suspend')) { values[0].status = 'SUSPENDED' }
        if (url.endsWith('/reactivate')) { values[0].status = 'ACTIVE' }
        if (url.endsWith('/close')) { values[0].status = 'CLOSED' }
      }
      const change = { requestId: reference, tenantId: tenant, branch: values[0], action: 'CHANGE', previousStatus: previous?.status ?? null, previousCode: previous?.code ?? null, createdAt: values[0].updatedAt }
      changes.push(change)
      return json({ outcome: 'CONFIRMED', correlation: reference, change }, posts === 1 ? 201 : 200)
    }
    if (url.includes('/branches?page=')) { return json({ branches: values, canManage: true, hasMore: false }) }
    return json({ branch: values[0], codeEditable: !protectCode, canManage: true, history: changes })
  }))
  render(<BranchAdministration />); await select()
  await screen.findByText('No hay sucursales registradas.')
  fireEvent.click(screen.getByRole('button', { name: 'Nueva sucursal' }))
  fireEvent.change(screen.getByLabelText('Código de sucursal'), { target: { value: 'CASA MATRIZ' } })
  fireEvent.change(screen.getByLabelText('Nombre de sucursal'), { target: { value: 'Matriz persistida' } })
  expect(posts).toBe(0); await confirm()
  await vi.waitFor(() => expect(posts).toBe(1))
  expect(screen.queryByRole('button', { name: 'Ficha CASA MATRIZ' })).toBeNull()
  expect(screen.queryByLabelText('Confirmar contraseña para sucursal')).toBeNull()
  release!()
  await screen.findByRole('button', { name: 'Editar datos generales' })
  fireEvent.click(screen.getByRole('button', { name: 'Editar datos generales' }))
  fireEvent.change(screen.getByLabelText('Código de sucursal'), { target: { value: 'MATRIZ' } })
  fireEvent.change(screen.getByLabelText('Nombre de sucursal'), { target: { value: 'Nombre actualizado' } }); await confirm()
  await screen.findByRole('button', { name: 'Ficha MATRIZ' })
  fireEvent.click(screen.getByRole('button', { name: 'Editar datos generales' }))
  expect((screen.getByLabelText('Código de sucursal') as HTMLInputElement).readOnly).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
  fireEvent.click(screen.getByRole('button', { name: 'Suspender sucursal' })); await confirm()
  await screen.findByRole('button', { name: 'Reactivar sucursal' })
  fireEvent.click(screen.getByRole('button', { name: 'Reactivar sucursal' })); await confirm()
  await screen.findByRole('button', { name: 'Suspender sucursal' })
  fireEvent.click(screen.getByRole('button', { name: 'Cerrar sucursal' }))
  expect(screen.getByRole('alert').textContent).toContain('terminal'); await confirm()
  await screen.findByText(/Cierre terminal:/)
  expect(within(screen.getByRole('region', { name: 'Ficha de sucursal' })).getByText('Cerrada')).toBeTruthy()
  expect(screen.queryByRole('button', { name: 'Reactivar sucursal' })).toBeNull()
  expect(screen.queryByRole('button', { name: 'Editar datos generales' })).toBeNull()
  expect(posts).toBe(5); expect(sessionStorage.getItem('ls.core.pending-branch')).toBeNull()
})

it('blocks duplicate sends and incompatible mutations during UNKNOWN and reconciles before displaying the final state', async () => {
  const value = branch()
  let reference = ''
  let posts = 0
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'csrf' }) }
    if (url.endsWith('/branch-administration/tenants')) { return json([{ id: tenant, name: 'Empresa persistida', status: 'ACTIVE' }]) }
    if (url.includes('/operations/')) { value.status = 'SUSPENDED'; return json({ outcome: 'CONFIRMED', correlation: reference, change: { tenantId: tenant, branch: value } }) }
    if (init.method === 'POST') { reference = JSON.parse(String(init.body)).requestId; posts++; return json({ outcome: 'UNKNOWN', correlation: reference, change: null }, 202) }
    if (url.includes('/branches?page=')) { return json({ branches: [value], hasMore: false, canManage: true }) }
    return json({ branch: value, codeEditable: false, canManage: true, history: [] })
  }))
  render(<BranchAdministration />); await select()
  fireEvent.click(await screen.findByRole('button', { name: 'Ficha CASA MATRIZ' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Suspender sucursal' })); await confirm()
  await screen.findByText(/Resultado UNKNOWN/)
  expect(screen.getByText(reference)).toBeTruthy(); expect(posts).toBe(1)
  expect(screen.queryByRole('button', { name: 'Reactivar sucursal' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Nueva sucursal' })); expect(posts).toBe(1)
  fireEvent.click(screen.getByRole('button', { name: 'Consultar resultado de sucursal' }))
  await screen.findByRole('button', { name: 'Reactivar sucursal' })
  expect(sessionStorage.getItem('ls.core.pending-branch')).toBeNull()
})

it('shows conflict and requires server revalidation, then removes data when authority is denied', async () => {
  let denied = false
  let outcome = 'CONFLICT'
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'csrf' }) }
    if (denied) { return json({ error: 'Operation unavailable' }, 403) }
    if (url.endsWith('/branch-administration/tenants')) { return json([{ id: tenant, name: 'Empresa persistida', status: 'ACTIVE' }]) }
    if (init.method === 'POST') { return json({ outcome, correlation: JSON.parse(String(init.body)).requestId, change: null }, outcome === 'CONFLICT' ? 409 : 503) }
    if (url.includes('/branches?page=')) { return json({ branches: [branch()], hasMore: false, canManage: true }) }
    return json({ branch: branch(), codeEditable: true, canManage: true, history: [] })
  }))
  render(<BranchAdministration />); await select()
  fireEvent.click(await screen.findByRole('button', { name: 'Ficha CASA MATRIZ' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Suspender sucursal' })); await confirm()
  await screen.findByText(/Conflicto: revalida/)
  expect(sessionStorage.getItem('ls.core.pending-branch')).toBeNull()
  outcome = 'UNAVAILABLE'
  fireEvent.click(screen.getByRole('button', { name: 'Suspender sucursal' })); await confirm()
  await screen.findByText(/Servicio no disponible/)
  expect(sessionStorage.getItem('ls.core.pending-branch')).toBeNull()
  denied = true; fireEvent.click(screen.getByRole('button', { name: 'Revalidar sucursales' }))
  await screen.findByText(/No se pudo comprobar la autoridad o disponibilidad/)
  expect(screen.queryByRole('button', { name: 'Nueva sucursal' })).toBeNull()
  expect(screen.queryByRole('region', { name: 'Ficha de sucursal' })).toBeNull()
})

it('admits a branch-only delegated Core user through the independent branch authority endpoint', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url.endsWith('/session')) { return json({}) }
    if (url.endsWith('/branch-administration/tenants')) { return json([]) }
    return json({ error: 'Operation unavailable' }, 403)
  }))
  expect(await coreApi.authority()).toMatchObject({ canRegister: false, branchesOnly: true })
})
