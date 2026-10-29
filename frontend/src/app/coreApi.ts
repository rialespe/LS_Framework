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
  if (!response.ok && !(resultBody && [403, 404, 409, 422, 503].includes(response.status))) { throw new CoreFailure(response.status) }
  if (response.status === 204) { return undefined as T }
  const result = await response.json()
  if (resultBody && !response.ok && (!result || typeof result.outcome !== 'string')) { throw new CoreFailure(response.status) }
  return result as T
}
export const coreApi = {
  async login(username: string, password: string) { await request('login', { username, password }); await request('csrf') },
  async authority(): Promise<{ kind: string; canRegister: boolean; canAdmin?: boolean; tenantOnly?: boolean; branchesOnly?: boolean }> {
    await request('session')
    try { return await request<{ kind: string; canRegister: boolean }>('authority') }
    catch (error) {
      if (!(error instanceof CoreFailure && [403, 404].includes(error.status))) { throw error }
      try { const authority = await request<TenantAuthority>('tenant-authority'); return { kind: authority.kind, canRegister: false, canAdmin: authority.canRead } }
      catch (access) { if (!(access instanceof CoreFailure && [403, 404].includes(access.status))) { throw access }; try { await request('administrator-tenants'); return { kind: 'Administración empresarial autorizada', canRegister: false, canAdmin: true, tenantOnly: true } } catch (scope) { if (!(scope instanceof CoreFailure && [403, 404].includes(scope.status))) { throw scope }; await request('branch-administration/tenants'); return { kind: 'Administración de sucursales autorizada', canRegister: false, canAdmin: true, branchesOnly: true } } }
    }
  },
  configurationModules: (tenant: string, branch: string) => request<ConfigurationModule[]>('branch-administration/tenants/' + encodeURIComponent(tenant) + '/branches/' + encodeURIComponent(branch) + '/module-configurations'),
  configurationDetail: (scope: ConfigurationScope) => request<ConfigurationDetail>(configurationPath(scope)),
  configurationMutation: (scope: ConfigurationScope, action: ConfigurationAction, requestId: string, expectedRevision: number | null, values: ConfigurationValues, password: string) => request<ConfigurationResult>(configurationPath(scope) + '/' + action, { requestId, expectedRevision, values, password }, true),
  configurationOperation: (id: string) => request<ConfigurationResult>('branch-administration/configuration-operations/' + encodeURIComponent(id)),
  branchTenants: () => request<{ id: string; name: string; status: string }[]>('branch-administration/tenants'),
  branches: (tenant: string, page = 0) => request<BranchPage>('branch-administration/tenants/' + encodeURIComponent(tenant) + '/branches?page=' + page),
  branch: (tenant: string, id: string) => request<BranchDetail>('branch-administration/tenants/' + encodeURIComponent(tenant) + '/branches/' + encodeURIComponent(id)),
  branchMutation: (tenant: string, action: BranchAction, id: string | null, requestId: string, code: string | null, name: string | null, expectedUpdatedAt: string | null, password: string) => request<BranchResult>('branch-administration/tenants/' + encodeURIComponent(tenant) + '/branches' + (action === 'create' ? '' : '/' + encodeURIComponent(id!) + '/' + action), { requestId, code, name, expectedUpdatedAt, password }, true),
  branchOperation: (id: string) => request<BranchResult>('branch-administration/operations/' + encodeURIComponent(id)),
  administratorTenants: () => request<{ id: string; name: string; status: string }[]>('administrator-tenants'),
  administrators: (tenant: string) => request<AdministratorListing>('tenants/' + encodeURIComponent(tenant) + '/administrators'),
  administratorCandidate: (tenant: string, username: string) => request<AdministratorCandidate>('tenants/' + encodeURIComponent(tenant) + '/administrators/candidate', { username }),
  administratorMutation: (tenant: string, action: DesignationAction, target: string | null, requestId: string, username: string | null, password: string) => request<AdministratorResult>('tenants/' + encodeURIComponent(tenant) + '/administrators/' + (target ? encodeURIComponent(target) + '/' : '') + action, { requestId, username, password }, true),
  administratorOperation: (id: string) => request<AdministratorResult>('administrator-operations/' + encodeURIComponent(id)),
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

export type DesignationAction = 'designate' | 'activate' | 'revoke' | 'replace'
export type TenantDesignation = { id: string; userId: string; username: string; displayName: string; identityStatus: string; membershipStatus: string; status: string; revokedAt: string | null; grantedAt: string; valid: boolean }
export type AdministratorCandidate = { username: string; displayName: string; identityStatus: string; ready: boolean }
export type AdministratorChange = { requestId: string; tenantId: string; action: string; targetId: string | null; designationId: string; resultingStatus: string; createdAt: string }
export type AdministratorListing = { administrators: TenantDesignation[]; canManage: boolean; validCount: number; history: AdministratorChange[] }
export type AdministratorResult = { outcome: 'CONFIRMED' | 'UNKNOWN' | 'CONFLICT' | 'DENIED' | 'NOT_FOUND' | 'INCOMPLETE_REQUIREMENTS' | 'UNAVAILABLE'; correlation: string; reason: string | null; change: AdministratorChange | null }

export type BranchAction = 'create' | 'edit' | 'suspend' | 'reactivate' | 'close'
export type ManagedBranch = { id: string; tenantId: string; code: string; name: string; status: string; createdAt: string; updatedAt: string }
export type BranchChange = { requestId: string; tenantId: string; action: string; branch: ManagedBranch; previousCode: string | null; previousName: string | null; previousStatus: string | null; createdAt: string }
export type BranchPage = { branches: ManagedBranch[]; hasMore: boolean; canManage: boolean }
export type BranchDetail = { branch: ManagedBranch; canManage: boolean; codeEditable: boolean; history: BranchChange[] }
export type BranchResult = { outcome: 'CONFIRMED' | 'UNKNOWN' | 'CONFLICT' | 'DENIED' | 'NOT_FOUND' | 'INCOMPLETE_REQUIREMENTS' | 'UNAVAILABLE'; correlation: string; reason: string | null; change: BranchChange | null }

export type ConfigurationScope = { tenantId: string; branchId: string; applicationId: string; moduleId: string; version: number }
export type ConfigurationValues = Record<string, string | number | boolean>
export type ConfigurationField = { key: string; label: string; type: 'TEXT' | 'INTEGER' | 'BOOLEAN'; required: boolean; maxLength: number | null; minimum: number | null; maximum: number | null }
export type ConfigurationModule = { scope: ConfigurationScope; applicationName: string; moduleName: string; contractStatus: string; applicationPublished: boolean; modulePublished: boolean; applicationEnabled: boolean; moduleEnabled: boolean; available: boolean; contextActive: boolean; canRead: boolean; canManage: boolean; readiness: string; revision: number | null; fields: ConfigurationField[] }
export type ModuleConfiguration = { scope: ConfigurationScope; values: ConfigurationValues; readiness: string; revision: number; updatedAt: string }
export type ConfigurationChange = { requestId: string; action: string; previousReadiness: string | null; configuration: ModuleConfiguration }
export type ConfigurationDetail = { module: ConfigurationModule; configuration: ModuleConfiguration | null; missingFields: string[]; history: ConfigurationChange[] }
export type ConfigurationAction = 'prepare' | 'update' | 'validate'
export type ConfigurationResult = { outcome: 'CONFIRMED' | 'UNKNOWN' | 'CONFLICT' | 'DENIED' | 'NOT_FOUND' | 'MODULE_UNAVAILABLE' | 'INCOMPLETE_REQUIREMENTS' | 'UNAVAILABLE'; correlation: string; reason: string | null; change: ConfigurationChange | null; missingFields: string[] }
function configurationPath(scope: ConfigurationScope) { return 'branch-administration/tenants/' + encodeURIComponent(scope.tenantId) + '/branches/' + encodeURIComponent(scope.branchId) + '/module-configurations/' + encodeURIComponent(scope.applicationId) + '/' + encodeURIComponent(scope.moduleId) + '/' + scope.version }
