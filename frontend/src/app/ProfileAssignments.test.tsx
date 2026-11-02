import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { ProfileAssignments, OwnContextPermissions } from './ProfileAssignments'
import type { AssignmentWorkspace } from './coreApi'

afterEach(() => { cleanup(); sessionStorage.clear(); vi.unstubAllGlobals() })
const json = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body })
const workspace: AssignmentWorkspace = { canManage: true, member: { id: 'member', name: 'Real', status: 'ACTIVE' }, branches: [{ id: 'branch', code: '01', name: 'Matriz', status: 'ACTIVE', branchMembershipId: 'bm', accessStatus: 'ACTIVE', updatedAt: '2026-11-02T12:00:00Z' }], profiles: ['reader', 'editor'].map(id => ({ id, code: id, name: id, status: 'ACTIVE', assignable: true, permissions: [] })), assignments: [], history: [] }

it('keeps assignments unchanged until PostgreSQL confirms and reconciles UNKNOWN without another POST', async () => {
  let reference = ''; let posts = 0; let release: (() => void) | undefined
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'fresh' }) }
    if (url.endsWith('/applications')) { return json([{ id: 'app', name: 'Core', status: 'ACTIVE' }]) }
    if (url.includes('/operations/')) { return json({ correlation: reference, outcome: 'CONFIRMED', receipt: { requestId: reference, result: { revokedContexts: 1 } } }) }
    if (init.method === 'POST') { posts++; const body = JSON.parse(String(init.body)); reference = body.requestId; expect(body.profileIds).toEqual(['reader', 'editor']); expect(body.password).toBe('Fresh-50!'); expect(body.actorId).toBeUndefined(); await new Promise<void>(resolve => { release = resolve }); return json({ outcome: 'UNKNOWN', correlation: reference, receipt: null }, 202) }
    return json(workspace)
  }))
  render(<ProfileAssignments tenantId="tenant" membershipId="member" />)
  fireEvent.click(screen.getByText('Consultar aplicaciones para asignación'))
  fireEvent.change(await screen.findByLabelText('Aplicación'), { target: { value: 'app' } })
  fireEvent.change(await screen.findByLabelText('Sucursal'), { target: { value: 'branch' } })
  fireEvent.click(screen.getByLabelText(/reader/)); fireEvent.click(screen.getByLabelText(/editor/))
  fireEvent.click(screen.getByText('Asignar perfiles seleccionados'))
  expect(posts).toBe(0)
  fireEvent.click(screen.getByLabelText('Confirmo el cambio'))
  fireEvent.change(screen.getByLabelText('Contraseña F04 nueva'), { target: { value: 'Fresh-50!' } })
  fireEvent.click(screen.getByText('Confirmar cambio'))
  await waitFor(() => expect(posts).toBe(1))
  expect(screen.getByText('Sin perfiles asignados.')).toBeInTheDocument()
  expect(JSON.stringify(sessionStorage)).not.toContain('Fresh-50!')
  release!(); await screen.findByText(/Resultado UNKNOWN/)
  expect(screen.getByText('Asignar perfiles seleccionados')).toBeDisabled()
  fireEvent.click(screen.getByText('Reconciliar asignación'))
  await screen.findByText(/Cambio confirmado/)
  expect(posts).toBe(1); expect(sessionStorage.length).toBe(0)
})

it('does not create a branch link by reading an empty workspace', async () => {
  const fetch = vi.fn(async (url: string) => url.endsWith('/applications') ? json([{ id: 'app', name: 'Core' }]) : json({ ...workspace, branches: [], profiles: [] }))
  vi.stubGlobal('fetch', fetch)
  render(<ProfileAssignments tenantId="tenant" membershipId="member" />)
  expect(fetch).not.toHaveBeenCalled()
  fireEvent.click(screen.getByText('Consultar aplicaciones para asignación'))
  fireEvent.change(await screen.findByLabelText('Aplicación'), { target: { value: 'app' } })
  await screen.findByText('Sin sucursales registradas.')
  expect(fetch).toHaveBeenCalledTimes(2)
})

it('treats a revoked own context response as absent without parsing an empty 204 body', async () => {
  const parseEmpty = vi.fn(() => { throw new Error('Empty response must not be parsed') })
  vi.stubGlobal('fetch', vi.fn(async (url: string) => url.endsWith('/permissions') ? { ok: true, status: 204, json: parseEmpty } : json([])))
  render(<OwnContextPermissions />)
  fireEvent.click(screen.getByText('Consultar mis contextos'))
  await waitFor(() => expect(screen.getByText('Consultar mis contextos')).not.toBeDisabled())
  expect(parseEmpty).not.toHaveBeenCalled()
  expect(screen.queryByText(/Contexto no disponible/)).toBeNull()
})
