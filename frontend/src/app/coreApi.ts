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
  if (!response.ok && !(resultBody && [400, 403, 404, 409, 422, 503].includes(response.status))) { throw new CoreFailure(response.status) }
  if (response.status === 204) { return undefined as T }
  const result = await response.json()
  if (resultBody && !response.ok && (!result || typeof result.outcome !== 'string')) { throw new CoreFailure(response.status) }
  return result as T
}
export const coreApi = {
  assignmentWorkspace: (tenant: string, app: string, member: string) => request<AssignmentWorkspace>(assignmentPath(tenant, app) + '/members/' + encodeURIComponent(member)),
  assignmentPreview: (tenant: string, app: string, member: string, branchMember: string) => request<EffectivePermission[]>(assignmentPath(tenant, app) + '/members/' + encodeURIComponent(member) + '/branches/' + encodeURIComponent(branchMember) + '/preview'),
  assignmentMutation: (tenant: string, app: string, action: AssignmentAction, input: AssignmentInput, requestId: string, password: string) => request<AssignmentResult>(assignmentPath(tenant, app) + '/commands/' + action, { ...input, requestId, password }, true),
  assignmentReceipt: (id: string) => request<AssignmentResult>('rbac/assignments/operations/' + encodeURIComponent(id)),
  contextTenants: () => request<{ id: string; name: string }[]>('rbac/context/tenants'),
  contextBranches: (tenant: string) => request<{ id: string; name: string; code: string }[]>('rbac/context/tenants/' + encodeURIComponent(tenant) + '/branches'),
  contextSelect: (tenantId: string, branchId: string) => request<OwnContext>('rbac/context', { tenantId, branchId }),
  contextPermissions: async () => (await request<OwnContext | undefined>('rbac/context/permissions')) ?? null,
  rbacTenants: () => request<{ id: string; name: string; status: string }[]>('rbac/tenants'),
  rbacApplications: (tenant: string) => request<RbacApplication[]>('rbac/tenants/' + encodeURIComponent(tenant) + '/applications'),
  rbacWorkspace: (tenant: string, application: string) => request<RbacWorkspace>(rbacPath(tenant, application)),
  rbacMutation: (tenant: string, application: string, action: RbacAction, requestId: string, input: RbacInput, password: string) => request<RbacResult>(rbacPath(tenant, application) + '/commands/' + action, { requestId, ...input, password }, true),
  rbacOperation: (id: string) => request<RbacResult>('rbac/operations/' + encodeURIComponent(id)),
  tenantAccess: (tenant: string, member: string) => request<TenantAccessSnapshot>('tenant-users/tenants/' + encodeURIComponent(tenant) + '/users/' + encodeURIComponent(member) + '/access'),
  tenantAccessOperation: (id: string) => request<TenantAccessResult>('tenant-users/access-operations/' + encodeURIComponent(id)),
  tenantAccessMutation: (tenant: string, member: string, branch: string | null, action: 'suspend' | 'reactivate', requestId: string, expectedUpdatedAt: string, password: string) => request<TenantAccessResult>('tenant-users/tenants/' + encodeURIComponent(tenant) + '/users/' + encodeURIComponent(member) + (branch ? '/branch-accesses/' + encodeURIComponent(branch) : '/access') + '/' + action, { requestId, expectedUpdatedAt, password }, true),
  identityLinksReceived: (page = 0) => request<{ requests: IdentityLink[]; hasMore: boolean }>('identity-links/received?page=' + page),
  identityLinksSent: (tenant: string, page = 0) => request<{ requests: IdentityLinkReceipt[]; hasMore: boolean }>('identity-links/tenants/' + encodeURIComponent(tenant) + '/sent?page=' + page),
  identityLinkRequest: (tenant: string, requestId: string, username: string, password: string) => request<IdentityLinkResult>('identity-links/tenants/' + encodeURIComponent(tenant) + '/requests', { requestId, username, password }, true),
  identityLinkDecision: (id: string, action: 'accept' | 'reject', requestId: string, password: string) => request<IdentityLinkResult>('identity-links/received/' + encodeURIComponent(id) + '/' + action, { requestId, password }, true),
  identityLinkOperation: (id: string) => request<IdentityLinkResult>('identity-links/operations/' + encodeURIComponent(id)),
  exchangeTemporary: (username: string, temporaryPassword: string, newPassword: string) => request('tenant-users/exchange', { username, temporaryPassword, newPassword }),
  tenantUserTenants: () => request<{ id: string; name: string; status: string }[]>('tenant-users/tenants'),
  tenantUsers: (tenant: string, page = 0) => request<TenantUserPage>('tenant-users/tenants/' + encodeURIComponent(tenant) + '/users?page=' + page),
  tenantUser: (tenant: string, id: string) => request<TenantUserDetail>('tenant-users/tenants/' + encodeURIComponent(tenant) + '/users/' + encodeURIComponent(id)),
  tenantUserOperation: (id: string) => request<TenantUserResult>('tenant-users/operations/' + encodeURIComponent(id)),
  tenantUserMutation: (tenant: string, action: 'create' | 'activate' | 'reissue', id: string | null, requestId: string, input: { username: string; displayName: string; email: string | null }, password: string) => request<TenantUserResult>('tenant-users/tenants/' + encodeURIComponent(tenant) + '/users' + (action === 'create' ? '' : '/' + encodeURIComponent(id!) + '/' + action), { requestId, ...(action === 'create' ? input : {}), password }, true),
  async login(username: string, password: string) { await request('login', { username, password }); await request('csrf') },
  async authority(): Promise<{ kind: string; canRegister: boolean; canAdmin?: boolean; tenantOnly?: boolean; branchesOnly?: boolean; usersOnly?: boolean; rbacOnly?: boolean; personal?: boolean }> {
    await request('session')
    try { return await request<{ kind: string; canRegister: boolean }>('authority') }
    catch (error) { if (!(error instanceof CoreFailure && [403, 404].includes(error.status))) { throw error } }
    try { const authority = await request<TenantAuthority>('tenant-authority'); return { kind: authority.kind, canRegister: false, canAdmin: authority.canRead } }
    catch (error) { if (!(error instanceof CoreFailure && [403, 404].includes(error.status))) { throw error } }
    for (const scope of [
      { path: 'administrator-tenants', kind: 'Administración empresarial autorizada', tenantOnly: true },
      { path: 'tenant-users/tenants', kind: 'Administración de usuarios autorizada', usersOnly: true },
      { path: 'branch-administration/tenants', kind: 'Administración de sucursales autorizada', branchesOnly: true },
      { path: 'rbac/tenants', kind: 'Administración RBAC autorizada', rbacOnly: true },
    ]) {
      try { await request(scope.path); return { ...scope, canRegister: false, canAdmin: true } }
      catch (error) { if (!(error instanceof CoreFailure && [403, 404].includes(error.status))) { throw error } }
    }
    await request('identity-links/received')
    return { kind: 'Titular autenticado', canRegister: false, personal: true }
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

export type TenantUser = { id: string; userId: string; username: string; displayName: string; email: string | null; emailVerified: boolean; identityStatus: string; membershipStatus: string; createdAt: string; createdBy: string | null; mustChangePassword: boolean; passwordExpiresAt: string | null; f04Ready: boolean; managedIncorporation: boolean }
export type TenantUserChange = { requestId: string; tenantId: string; action: string; userId: string; membershipId: string; resultingStatus: string; temporaryExpiresAt: string | null; createdAt: string; actorId: string }
export type TenantUserPage = { users: TenantUser[]; hasMore: boolean; canManage: boolean }
export type TenantUserDetail = { user: TenantUser; canManage: boolean; history: TenantUserChange[] }
export type TenantUserResult = { outcome: 'CONFIRMED' | 'UNKNOWN' | 'CONFLICT' | 'DENIED' | 'NOT_FOUND' | 'INVALID' | 'INCOMPLETE_REQUIREMENTS' | 'UNAVAILABLE'; correlation: string; change: TenantUserChange | null; temporaryPassword: string | null }

export type IdentityLink = { id: string; tenantId: string; company: string; status: string; expiresAt: string; createdAt: string; decidedAt: string | null }
export type IdentityLinkReceipt = { requestId: string; tenantId: string; action: string; linkId: string | null; status: string; createdAt: string }
export type IdentityLinkResult = { outcome: string; correlation: string; receipt: IdentityLinkReceipt | null }
export type TenantAccessChange = { requestId: string; tenantId: string; membershipId: string; branchMembershipId: string | null; branchId: string | null; action: string; previousStatus: string; resultingStatus: string; revokedContexts: number; updatedAt: string; createdAt: string; actorId: string; expectedUpdatedAt: string }
export type TenantAccessResult = { outcome: string; correlation: string; receipt: TenantAccessChange | null }
export type TenantAccessSnapshot = { membershipId: string; status: string; updatedAt: string; tenantStatus: string; f04Ready: boolean; administrativeDesignation: boolean; globalOwner: boolean; canManage: boolean; revokedContexts: number; branches: { id: string; branchId: string; name: string; branchStatus: string; accessStatus: string; updatedAt: string; revokedContexts: number }[]; history: TenantAccessChange[] }
const rbacPath = (tenant: string, application: string) => 'rbac/tenants/' + encodeURIComponent(tenant) + '/applications/' + encodeURIComponent(application)
export type RbacAction = 'role-create' | 'role-update' | 'role-disable' | 'role-template' | 'profile-create' | 'profile-update' | 'profile-disable' | 'profile-template'
export type RbacInput = { resourceId: string | null; templateId: string | null; expectedRevision: number | null; code: string | null; name: string | null; description: string | null; permissionIds: string[]; roleIds: string[] }
export type RbacApplication = { id: string; code: string; name: string; status: string }
export type RbacResource = { id: string; code: string; name: string; description: string | null; status: string; revision: number; sourceTemplateId: string | null; sourceVersion: number | null; permissionIds?: string[]; roleIds?: string[] }
export type RbacReceipt = { requestId: string; tenantId: string; applicationId: string; action: string; resourceId: string; previous: RbacResource | null; result: RbacResource; createdAt: string; actorId: string }
export type RbacResult = { outcome: string; correlation: string; receipt: RbacReceipt | null }
export type RbacPermission = { id: string; moduleId: string | null; code: string; name: string; scope: string; status: string; privilegeKind: string | null; delegable: boolean }
export type RbacTemplate = { id: string; code: string; name: string; version: number; items: string[]; delegable: boolean }
export type RbacWorkspace = { canManage: boolean; modules: { id: string; code: string; name: string; status: string }[]; permissions: RbacPermission[]; roleTemplates: RbacTemplate[]; profileTemplates: RbacTemplate[]; roles: RbacResource[]; profiles: RbacResource[]; history: RbacReceipt[] }

const assignmentPath = (tenant: string, app: string) => 'rbac/assignments/tenants/' + encodeURIComponent(tenant) + '/applications/' + encodeURIComponent(app)
export type AssignmentAction = 'join-branch' | 'assign' | 'revoke'
export type AssignmentInput = { membershipId: string; branchId: string; branchMembershipId: string | null; assignmentId: string | null; expectedUpdatedAt: string | null; expectedRevision: number | null; profileIds: string[] }
export type EffectivePermission = { id: string; code: string; name: string; resource_scope: string; operation_kind: string; module_id: string | null }
export type AssignmentReceipt = { requestId: string; tenantId: string; applicationId: string; membershipId: string; action: string; createdAt: string; result: { revokedContexts: number; branchMembershipId: string; action: string } }
export type AssignmentResult = { outcome: string; correlation: string; receipt: AssignmentReceipt | null }
export type AssignmentWorkspace = { canManage: boolean; member: { id: string; name: string; status: string }; branches: { id: string; code: string; name: string; status: string; branchMembershipId: string | null; accessStatus: string | null; updatedAt: string | null }[]; profiles: { id: string; code: string; name: string; status: string; assignable: boolean; permissions: { id: string; code: string; scope: string; operationKind: string | null; contextMode: string | null }[] }[]; assignments: { id: string; profile_id: string; branch_membership_id: string; status: string; revoked_at: string | null; created_at: string; revision: number }[]; history: AssignmentReceipt[] }
export type OwnContext = { id: string; tenantId: string; applicationId: string; branchId: string; permissions: { id: string; code: string; resourceScope: string; operationKind: string }[] }
