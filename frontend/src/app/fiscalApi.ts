export type Tenant = { id: string; name: string }
export type Branch = Tenant & { code: string }
export type Permission = Branch & { resourceScope: string; moduleId: string | null }
export type Module = Branch & { permissions: Permission[] }
export type Context = { id: string; tenantId: string; branchId: string; applicationId: string; permissions: Permission[]; modules: Module[] }

export class ApiFailure extends Error {
  constructor(readonly status: number) { super('Solicitud no disponible') }
}

const base = '/api/auth/fiscal/'
async function request(path: string, init?: RequestInit): Promise<unknown> {
  const response = await fetch(base + path, { ...init, credentials: 'same-origin', cache: 'no-store', redirect: 'error' })
  if (!response.ok) { throw new ApiFailure(response.status) }
  return response.status === 204 ? null : response.json()
}
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) { throw new ApiFailure(502) }
  return value as Record<string, unknown>
}
function text(value: unknown): string {
  if (typeof value !== 'string' || !value) { throw new ApiFailure(502) }
  return value
}
function uuid(value: unknown): string {
  const result = text(value)
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(result)) { throw new ApiFailure(502) }
  return result
}
function list<T>(value: unknown, parse: (item: unknown) => T): T[] {
  if (!Array.isArray(value)) { throw new ApiFailure(502) }
  return value.map(parse)
}
function tenant(value: unknown): Tenant {
  const item = object(value)
  return { id: uuid(item.id), name: text(item.name) }
}
function branch(value: unknown): Branch {
  return { ...tenant(value), code: text(object(value).code) }
}
function permission(value: unknown): Permission {
  const item = object(value)
  const scope = text(item.resourceScope)
  if (scope !== 'TENANT' && scope !== 'BRANCH') { throw new ApiFailure(502) }
  return { ...branch(value), resourceScope: scope, moduleId: item.moduleId === null ? null : uuid(item.moduleId) }
}
function module(value: unknown): Module {
  return { ...branch(value), permissions: list(object(value).permissions, permission) }
}
function context(value: unknown): Context {
  const item = object(value)
  return { id: uuid(item.id), tenantId: uuid(item.tenantId), branchId: uuid(item.branchId), applicationId: uuid(item.applicationId),
    permissions: list(item.permissions, permission), modules: list(item.modules, module) }
}
async function csrf(): Promise<string> {
  const token = object(await request('csrf'))
  if (token.headerName !== 'X-CSRF-TOKEN') { throw new ApiFailure(502) }
  return text(token.csrfToken)
}
async function post(path: string, body?: unknown) {
  const token = await csrf()
  return request(path, { method: 'POST', headers: { 'X-CSRF-TOKEN': token, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined })
}
export const fiscalApi = {
  async session() { if (object(await request('session')).authenticated !== true) { throw new ApiFailure(401) } },
  async login(username: string, password: string) { await post('login', { username, password }); await csrf() },
  async logout() { await post('logout') },
  async tenants() { return list(await request('tenants'), tenant) },
  async branches(id: string) { return list(await request(`tenants/${encodeURIComponent(id)}/branches`), branch) },
  async current() { const result = await request('context'); return result === null ? null : context(result) },
  async select(tenantId: string, branchId: string) { return context(await post('context', { tenantId, branchId })) },
  async permissions() { return list(await request('permissions'), permission) },
  async navigation() { return list(await request('navigation'), module) },
}
