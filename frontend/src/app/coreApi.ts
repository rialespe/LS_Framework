export class CoreFailure extends Error { constructor(public status: number) { super('Solicitud no disponible') } }
export type Administrator = { id: string; displayName: string; status: string }
export type RegisteredTenant = { id: string; name: string; status: string; administratorId: string; createdAt: string; requestId: string }
export type RegistrationResult = { outcome: 'CONFIRMED' | 'UNKNOWN' | 'DENIED' | 'UNAVAILABLE'; correlation: string; tenant: RegisteredTenant | null }
async function request<T>(action: string, body?: unknown, resultBody = false): Promise<T> {
  const headers: Record<string, string> = {}
  if (body !== undefined) {
    const token = await request<{ csrfToken: string }>('csrf')
    headers['X-CSRF-TOKEN'] = token.csrfToken
    headers['Content-Type'] = 'application/json'
  }
  const response = await fetch('/api/auth/core/' + action, { credentials: 'same-origin', cache: 'no-store',
    method: body === undefined ? 'GET' : 'POST', headers, body: body === undefined ? undefined : JSON.stringify(body) })
  if (!response.ok && !(resultBody && [403, 409, 422, 503].includes(response.status))) { throw new CoreFailure(response.status) }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}
export const coreApi = {
  async login(username: string, password: string) { await request('login', { username, password }); await request('csrf') },
  async authority(): Promise<{ kind: string; canRegister: boolean; canAdmin?: boolean }> {
    await request('session')
    try { return await request<{ kind: string; canRegister: boolean }>('authority') }
    catch (error) {
      if (!(error instanceof CoreFailure && error.status === 403)) { throw error }
      const authority = await request<TenantAuthority>('tenant-authority')
      return { kind: authority.kind, canRegister: false, canAdmin: authority.canRead }
    }
  },
  tenantAuthority: () => request<TenantAuthority>('tenant-authority'),
  tenants: (page = 0) => request<TenantPage>('tenants?page=' + page),
  tenant: (id: string) => request<TenantDetail>('tenants/' + encodeURIComponent(id)),
  transition: (id: string, action: TenantAction, requestId: string, password: string) => request<LifecycleResult>('tenants/' + encodeURIComponent(id) + '/' + action, { requestId, password }, true),
  operation: (id: string) => request<LifecycleResult>('tenant-operations/' + encodeURIComponent(id)),
  administrator: (username: string) => request<Administrator>('initial-administrator', { username }),
  register: (requestId: string, name: string, administratorId: string, password: string) => request<RegistrationResult>('registrations', { requestId, name, administratorId, password }),
  find: (id: string) => request<RegistrationResult>('registrations/' + encodeURIComponent(id)),
  logout: () => request('logout', {}),
}

export type TenantAction = 'activate' | 'suspend' | 'reactivate'
export type TenantAuthority = { kind: string; canRead: boolean; canActivate: boolean; canSuspend: boolean; canReactivate: boolean }
export type ManagedTenant = { id: string; name: string; status: string; administratorId: string | null; administratorName: string | null; administratorStatus: string | null; createdAt: string }
export type TenantPage = { tenants: ManagedTenant[]; hasMore: boolean }
export type TenantChange = { requestId: string; action: string; previousStatus: string; resultingStatus: string; createdAt: string }
export type TenantDetail = { tenant: ManagedTenant; history: TenantChange[] }
export type LifecycleResult = { outcome: 'CONFIRMED' | 'UNKNOWN' | 'DENIED' | 'INVALID_TRANSITION' | 'INCOMPLETE_REQUIREMENTS' | 'UNAVAILABLE'; correlation: string; reason: string | null; change: TenantChange | null }
