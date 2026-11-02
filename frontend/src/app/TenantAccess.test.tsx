import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { TenantAccess } from './TenantAccess'
import type { TenantAccessSnapshot } from './coreApi'

afterEach(() => { cleanup(); sessionStorage.clear(); vi.unstubAllGlobals() })
const date = '2026-11-02T12:00:00Z'
const snapshot: TenantAccessSnapshot = { membershipId: 'member-a', status: 'ACTIVE', updatedAt: date, tenantStatus: 'ACTIVE', f04Ready: true, administrativeDesignation: false, globalOwner: false, canManage: true, revokedContexts: 0, branches: [{ id: 'assignment-a', branchId: 'branch-a', name: 'SUCURSAL 02', branchStatus: 'ACTIVE', accessStatus: 'ACTIVE', updatedAt: date, revokedContexts: 0 }], history: [] }
const json = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body })

it('waits for PostgreSQL, requires a new password and reconciles UNKNOWN without repeating the mutation', async () => {
  let state = snapshot; let reference = ''; let posts = 0; let release: (() => void) | undefined
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    expect(init.credentials).toBe('same-origin'); expect(init.cache).toBe('no-store')
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'fresh' }) }
    if (init.method === 'POST') {
      posts++
      const body = JSON.parse(String(init.body)); reference = body.requestId
      expect(body.password).toBe('Fresh-48!'); expect(body.expectedUpdatedAt).toBe(date); expect(body.actorId).toBeUndefined()
      expect(url).toContain('/branch-accesses/assignment-a/suspend')
      await new Promise<void>(resolve => { release = resolve })
      return json({ outcome: 'UNKNOWN', correlation: reference, receipt: null }, 202)
    }
    if (url.includes('/access-operations/')) {
      state = { ...snapshot, branches: [{ ...snapshot.branches[0], accessStatus: 'SUSPENDED', revokedContexts: 1 }] }
      return json({ outcome: 'CONFIRMED', correlation: reference, receipt: { requestId: reference } })
    }
    return json(state)
  }))
  render(<TenantAccess tenantId="tenant-a" membershipId="member-a" />)
  fireEvent.click(await screen.findByRole('button', { name: 'Suspender acceso a SUCURSAL 02' }))
  expect(posts).toBe(0)
  fireEvent.change(screen.getByLabelText('Contraseña F04 nueva para confirmar'), { target: { value: 'Fresh-48!' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar cambio de acceso' }))
  await waitFor(() => expect(posts).toBe(1))
  expect(screen.queryByText('SUSPENDED')).toBeNull()
  expect(JSON.stringify(sessionStorage)).not.toContain('Fresh-48!')
  release!()
  await screen.findByText(/Resultado UNKNOWN/)
  expect(screen.getByRole('button', { name: 'Suspender membresía' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: 'Reconciliar cambio de acceso' }))
  await screen.findByText('SUSPENDED')
  expect(posts).toBe(1); expect(sessionStorage.length).toBe(0)
})

it('preserves the real empty assignment state and never activates PENDING memberships', async () => {
  const fetch = vi.fn(async () => json({ ...snapshot, status: 'PENDING', branches: [] }))
  vi.stubGlobal('fetch', fetch)
  render(<TenantAccess tenantId="tenant-a" membershipId="member-a" />)
  await screen.findByText('Sin asignaciones de sucursal. Consultar o reactivar no crea accesos.')
  expect(screen.queryByRole('button', { name: 'Reactivar membresía' })).toBeNull()
  expect(screen.queryByRole('button', { name: 'Suspender membresía' })).toBeNull()
  expect(fetch).toHaveBeenCalledTimes(1)
})

it('distinguishes CLOSED branches from suspended individual access and protects designations', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json({ ...snapshot, administrativeDesignation: true,
    branches: [{ ...snapshot.branches[0], accessStatus: 'SUSPENDED', branchStatus: 'CLOSED' }] })))
  render(<TenantAccess tenantId="tenant-a" membershipId="member-a" />)
  await screen.findByText('CLOSED')
  expect(screen.getByRole('button', { name: 'Reactivar acceso a SUCURSAL 02' })).toBeDisabled()
  expect(screen.queryByRole('button', { name: 'Suspender membresía' })).toBeNull()
  expect(screen.getByText(/Retira primero la designación/)).toBeInTheDocument()
})
