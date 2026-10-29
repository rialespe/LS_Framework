import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { BranchModuleConfiguration } from './BranchModuleConfiguration'
import type { ConfigurationModule, ConfigurationValues, ModuleConfiguration } from './coreApi'

afterEach(() => { cleanup(); sessionStorage.clear(); vi.unstubAllGlobals(); vi.restoreAllMocks() })
const scope = { tenantId: 'tenant-a', branchId: 'branch-a', applicationId: 'fixture-app', moduleId: 'fixture-module', version: 1 }
const definition = (): ConfigurationModule => ({ scope, applicationName: 'FIXTURE NO PRODUCTIVO', moduleName: 'Módulo de prueba', contractStatus: 'PUBLISHED', applicationPublished: true, modulePublished: true, applicationEnabled: true, moduleEnabled: true, available: true, contextActive: true, canRead: true, canManage: true, readiness: 'ABSENT', revision: null, fields: [
  { key: 'label', label: 'Etiqueta de prueba', type: 'TEXT', required: true, maxLength: 40, minimum: null, maximum: null },
  { key: 'capacity', label: 'Capacidad de prueba', type: 'INTEGER', required: true, maxLength: null, minimum: 1, maximum: 10 },
] })
const json = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body })
const renderSection = () => render(<BranchModuleConfiguration tenantId={scope.tenantId} branchId={scope.branchId} branchStatus="ACTIVE" />)
async function confirm(button: string) {
  fireEvent.change(screen.getByLabelText('Confirmar contraseña de configuración'), { target: { value: 'Fresh-config-44!' } })
  fireEvent.click(screen.getByRole('button', { name: button }))
}
it('shows the real empty contract state without inventing Fiscal or POS forms', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json([])))
  renderSection()
  await screen.findByText('No hay módulos especializados configurables.')
  expect(screen.queryByRole('button', { name: 'Guardar borrador' })).toBeNull()
  expect(screen.queryByLabelText(/Confirmar contraseña/)).toBeNull()
})
it('saves incomplete drafts, displays missing requirements, validates stored data and resets READY on edit', async () => {
  let configuration: ModuleConfiguration | null = null
  let values: ConfigurationValues = {}
  let posts = 0
  let reference = ''
  const module = definition()
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'fresh-csrf' }) }
    if (init.method === 'POST') {
      const body = JSON.parse(String(init.body)); reference = body.requestId; posts++
      expect((init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('fresh-csrf')
      expect(body.password).toBe('Fresh-config-44!'); expect(body.actorId).toBeUndefined()
      expect(body.expectedRevision).toBe(configuration?.revision ?? null)
      if (url.endsWith('/validate') && (!values.label || !values.capacity)) { return json({ outcome: 'INCOMPLETE_REQUIREMENTS', correlation: reference, missingFields: ['label', 'capacity'] }, 422) }
      if (!url.endsWith('/validate')) { values = body.values }
      configuration = { scope, values, revision: (configuration?.revision ?? 0) + 1, readiness: url.endsWith('/validate') ? 'READY' : 'DRAFT', updatedAt: '2026-10-29T19:00:00Z' }
      module.readiness = configuration.readiness
      return json({ outcome: 'CONFIRMED', correlation: reference, change: { configuration }, missingFields: [] })
    }
    if (url.endsWith('/module-configurations')) { return json([module]) }
    return json({ module, configuration, missingFields: values.label && values.capacity ? [] : ['label', 'capacity'], history: [] })
  }))
  renderSection()
  fireEvent.click(await screen.findByRole('button', { name: 'Consultar configuración Módulo de prueba' }))
  await screen.findByText('Sin configuración persistida')
  await confirm('Guardar borrador')
  await screen.findByText('Borrador · revisión 1')
  await confirm('Validar preparación')
  await screen.findByText(/READY exige todos los campos requeridos/)
  fireEvent.change(screen.getByLabelText('Etiqueta de prueba (requerido para READY)'), { target: { value: 'Prueba local' } })
  fireEvent.change(screen.getByLabelText('Capacidad de prueba (requerido para READY)'), { target: { value: '2' } })
  await confirm('Guardar borrador')
  await screen.findByText('Borrador · revisión 2')
  expect(values.capacity).toBe(2)
  await confirm('Validar preparación')
  await screen.findByText('Preparada para este contrato · revisión 3')
  await confirm('Guardar borrador')
  await screen.findByText('Borrador · revisión 4')
  expect(posts).toBe(5)
  expect(sessionStorage.getItem('ls.core.pending-configuration')).toBeNull()
})
it('keeps UNKNOWN opaque and blocks writes until receipt reconciliation, then handles a stale revision', async () => {
  let reference = ''
  let outcome = 'UNKNOWN'
  let posts = 0
  const module = definition()
  const configuration = { scope, values: {}, revision: 1, readiness: 'DRAFT', updatedAt: '2026-10-29T19:00:00Z' }
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/csrf')) { return json({ csrfToken: 'csrf' }) }
    if (url.includes('/configuration-operations/')) { return json({ outcome: 'CONFIRMED', correlation: reference, change: { configuration }, missingFields: [] }) }
    if (init.method === 'POST') { reference = JSON.parse(String(init.body)).requestId; posts++; return json({ outcome, correlation: reference, missingFields: [] }, outcome === 'UNKNOWN' ? 202 : 409) }
    if (url.endsWith('/module-configurations')) { return json([module]) }
    return json({ module, configuration, missingFields: ['label'], history: [] })
  }))
  renderSection()
  fireEvent.click(await screen.findByRole('button', { name: 'Consultar configuración Módulo de prueba' }))
  await screen.findByText('Borrador · revisión 1')
  await confirm('Guardar borrador')
  await screen.findByText(/Resultado UNKNOWN/)
  expect(screen.getByText(reference)).toBeTruthy()
  expect(screen.queryByRole('button', { name: 'Guardar borrador' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Consultar resultado de configuración' }))
  await screen.findByText(/Configuración confirmada por PostgreSQL/)
  expect(sessionStorage.getItem('ls.core.pending-configuration')).toBeNull()
  outcome = 'CONFLICT'; await confirm('Guardar borrador')
  await screen.findByText(/La versión o el estado cambió/)
  expect(posts).toBe(2)
})
it('keeps disabled entitlements and module-specific authority separate from READY', async () => {
  const module = { ...definition(), readiness: 'READY', moduleEnabled: false, available: false, canManage: false }
  vi.stubGlobal('fetch', vi.fn(async (url: string) => url.endsWith('/module-configurations') ? json([module]) : json({ module, configuration: { scope, values: { label: 'Persistida', capacity: 2 }, readiness: 'READY', revision: 1, updatedAt: '2026-10-29T19:00:00Z' }, missingFields: [], history: [] })))
  renderSection()
  fireEvent.click(await screen.findByRole('button', { name: 'Consultar configuración Módulo de prueba' }))
  await screen.findByText(/Consulta histórica o sin facultad/)
  expect(screen.queryByRole('button', { name: 'Guardar borrador' })).toBeNull()
})
