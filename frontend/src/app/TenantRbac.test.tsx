import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { RbacEditor } from './TenantRbac'
import type { RbacResource, RbacWorkspace } from './coreApi'

afterEach(() => { cleanup(); sessionStorage.clear(); vi.unstubAllGlobals() })
const date = '2026-11-02T12:00:00Z'
const app = { id: 'core', name: 'LS Core', code: 'ls-core', status: 'ACTIVE' }
const resource: RbacResource = { id: 'role-a', code: 'reader', name: 'Reader', description: null, status: 'ACTIVE', revision: 1, sourceTemplateId: null, sourceVersion: null, permissionIds: ['read'] }
const workspace: RbacWorkspace = { canManage: true, modules: [{ id: 'branches', code: 'branches', name: 'Sucursales', status: 'ACTIVE' }],
  permissions: [{ id: 'read', moduleId: 'branches', code: 'branches.read', name: 'Consultar sucursal', scope: 'BRANCH', status: 'PUBLISHED', privilegeKind: 'READ', delegable: true },
    { id: 'write', moduleId: 'branches', code: 'roles.manage', name: 'Administrar roles', scope: 'TENANT', status: 'PUBLISHED', privilegeKind: null, delegable: false }],
  roles: [], profiles: [], roleTemplates: [], profileTemplates: [], history: [] }
const json = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body })

it('distinguishes publication from delegation and reconciles UNKNOWN without optimistic roles or repeated mutations', async () => {
  let reference = ''; let posts = 0; let release: (() => void) | undefined; let current = workspace
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    expect(init.credentials).toBe('same-origin'); expect(init.cache).toBe('no-store')
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'fresh' }) }
    if (init.method === 'POST') {
      posts++; const body = JSON.parse(String(init.body)); reference = body.requestId
      expect(body.password).toBe('Fresh-rbac-49!'); expect(body.permissionIds).toEqual(['read']); expect(body.roleIds).toEqual([]); expect(body.actorId).toBeUndefined()
      expect(url).toContain('/commands/role-create'); expect((init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('fresh')
      await new Promise<void>(resolve => { release = resolve })
      return json({ outcome: 'UNKNOWN', correlation: reference, receipt: null }, 202)
    }
    if (url.includes('/operations/')) {
      current = { ...workspace, roles: [resource] }
      return json({ outcome: 'CONFIRMED', correlation: reference, receipt: { requestId: reference, tenantId: 'tenant', applicationId: 'core', action: 'ROLE_CREATE', resourceId: 'role-a', previous: null, result: resource, createdAt: date, actorId: 'actor' } })
    }
    return json(current)
  }))
  render(<RbacEditor tenant="tenant" application={app} />)
  await screen.findByText('Consultar sucursal')
  expect(screen.getByText('No delegable')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Roles' }))
  fireEvent.click(screen.getByRole('button', { name: 'Crear rol personalizado' }))
  fireEvent.change(screen.getByLabelText('Código'), { target: { value: 'reader' } })
  fireEvent.change(screen.getByLabelText('Nombre', { exact: true }), { target: { value: 'Reader' } })
  expect(screen.getByLabelText(/roles.manage/)).toBeDisabled()
  fireEvent.click(screen.getByLabelText(/branches.read/))
  expect(posts).toBe(0)
  fireEvent.change(screen.getByLabelText('Contraseña F04 nueva para RBAC'), { target: { value: 'Fresh-rbac-49!' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar operación RBAC' }))
  await waitFor(() => expect(posts).toBe(1))
  expect(screen.queryByRole('button', { name: 'Editar reader' })).toBeNull()
  expect(JSON.stringify(sessionStorage)).not.toContain('Fresh-rbac-49!')
  release!()
  await screen.findByText(/Resultado UNKNOWN/)
  expect(screen.getByRole('button', { name: 'Crear rol personalizado' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: 'Reconciliar operación RBAC' }))
  await screen.findByRole('button', { name: 'Editar reader' })
  expect(posts).toBe(1); expect(sessionStorage.length).toBe(0)
})

it('creates a profile with several tenant roles and shows each declared composition without assignments', async () => {
  const second = { ...resource, id: 'role-b', code: 'reviewer', name: 'Reviewer' }
  let current = { ...workspace, roles: [resource, second] }
  let posts = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'fresh' }) }
    if (init.method === 'POST') {
      posts++; const body = JSON.parse(String(init.body)); expect(body.roleIds).toEqual(['role-a', 'role-b']); expect(body.permissionIds).toEqual([])
      const profile = { ...resource, id: 'profile', name: 'Review team', code: 'team', permissionIds: undefined, roleIds: body.roleIds }
      current = { ...current, profiles: [profile] }
      return json({ outcome: 'CONFIRMED', correlation: body.requestId, receipt: { requestId: body.requestId, result: profile } })
    }
    return json(current)
  }))
  render(<RbacEditor tenant="tenant" application={app} />)
  await screen.findByText('Consultar sucursal')
  fireEvent.click(screen.getByRole('button', { name: 'Perfiles' }))
  fireEvent.click(screen.getByRole('button', { name: 'Crear perfil personalizado' }))
  fireEvent.change(screen.getByLabelText('Código'), { target: { value: 'team' } })
  fireEvent.change(screen.getByLabelText('Nombre', { exact: true }), { target: { value: 'Review team' } })
  fireEvent.click(screen.getByLabelText('Reader · ACTIVE')); fireEvent.click(screen.getByLabelText('Reviewer · ACTIVE'))
  fireEvent.change(screen.getByLabelText('Contraseña F04 nueva para RBAC'), { target: { value: 'Fresh-rbac-49!' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar operación RBAC' }))
  await screen.findByText('Composición declarada por rol; no representa una unión efectiva de permisos.')
  expect(posts).toBe(1)
  expect(screen.queryByRole('button', { name: /Asignar perfil/ })).toBeNull()
})

it('clears a known INVALID receipt response without treating it as an uncertain commit', async () => {
  let posts = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'fresh' }) }
    if (init.method === 'POST') {
      posts++; const value = JSON.parse(String(init.body))
      return json({ outcome: 'INVALID', correlation: value.requestId, receipt: null }, 400)
    }
    return json(workspace)
  }))
  render(<RbacEditor tenant="tenant" application={app} />)
  await screen.findByText('Consultar sucursal')
  fireEvent.click(screen.getByRole('button', { name: 'Roles' }))
  fireEvent.click(screen.getByRole('button', { name: 'Crear rol personalizado' }))
  fireEvent.change(screen.getByLabelText('Código'), { target: { value: '   ' } })
  fireEvent.change(screen.getByLabelText('Nombre', { exact: true }), { target: { value: 'Reader' } })
  fireEvent.change(screen.getByLabelText('Contraseña F04 nueva para RBAC'), { target: { value: 'Fresh-rbac-49!' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar operación RBAC' }))
  await screen.findByText(/Operación rechazada o no disponible/)
  expect(posts).toBe(1); expect(sessionStorage.length).toBe(0)
  expect(screen.getByRole('button', { name: 'Crear rol personalizado' })).not.toBeDisabled()
})
