export class CoreFailure extends Error { constructor(public status: number) { super('Solicitud no disponible') } }
export type Administrator = { id: string; displayName: string; status: string }
export type RegisteredTenant = { id: string; name: string; status: string; administratorId: string; createdAt: string; requestId: string }
export type RegistrationResult = { outcome: 'CONFIRMED' | 'UNKNOWN' | 'DENIED' | 'UNAVAILABLE'; correlation: string; tenant: RegisteredTenant | null }
async function request<T>(action: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  if (body !== undefined) {
    const token = await request<{ csrfToken: string }>('csrf')
    headers['X-CSRF-TOKEN'] = token.csrfToken
    headers['Content-Type'] = 'application/json'
  }
  const response = await fetch('/api/auth/core/' + action, { credentials: 'same-origin', cache: 'no-store',
    method: body === undefined ? 'GET' : 'POST', headers, body: body === undefined ? undefined : JSON.stringify(body) })
  if (!response.ok) { throw new CoreFailure(response.status) }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}
export const coreApi = {
  async login(username: string, password: string) { await request('login', { username, password }); await request('csrf') },
  async authority() { await request('session'); return request<{ kind: string; canRegister: boolean }>('authority') },
  administrator: (username: string) => request<Administrator>('initial-administrator', { username }),
  register: (requestId: string, name: string, administratorId: string, password: string) => request<RegistrationResult>('registrations', { requestId, name, administratorId, password }),
  find: (id: string) => request<RegistrationResult>('registrations/' + encodeURIComponent(id)),
  logout: () => request('logout', {}),
}
