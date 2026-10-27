import { useCallback, useEffect, useRef, useState } from 'react'
import { Brand } from '../components/Brand'
import { Icon } from '../components/Icon'
import { ConnectedSessions } from './ConnectedSessions'
import { ApiFailure, fiscalApi } from './fiscalApi'
import type { Branch, Context, Module, Permission, Tenant, SessionEntry } from './fiscalApi'

type State = { phase: 'checking' | 'access' | 'workspace' | 'unavailable' | 'logout'; busy: boolean; message: string;
  tenants: Tenant[]; branches: Branch[]; tenantId: string; branchId: string; context: Context | null; permissions: Permission[]; modules: Module[]; sessions: SessionEntry[]; sessionView: 'none' | 'mine' | 'tenant'; managementTenant: string; tenantAdmin: boolean }
const empty: State = { phase: 'access', busy: false, message: '', tenants: [], branches: [], tenantId: '', branchId: '', context: null, permissions: [], modules: [], sessions: [], sessionView: 'none', managementTenant: '', tenantAdmin: false }

async function selector(message = ''): Promise<State> {
  await fiscalApi.session()
  return { ...empty, phase: 'workspace', tenants: await fiscalApi.tenants(), message }
}
async function confirmed(context: Context, tenants: Tenant[], branches: Branch[]): Promise<State> {
  if (!tenants.some(item => item.id === context.tenantId) || !branches.some(item => item.id === context.branchId)) { throw new ApiFailure(403) }
  // Activity writes serialize requests for this cookie; avoid parallel connections for the same session.
  const permissions = await fiscalApi.permissions()
  const modules = await fiscalApi.navigation()
  const tenantAdmin = await fiscalApi.tenantAuthority(context.tenantId)
  // Separate metadata endpoints cannot supply an atomic snapshot; discard if another tab changed selection.
  const latest = await fiscalApi.current()
  if (latest?.id !== context.id) { throw new ApiFailure(403) }
  return { ...empty, phase: 'workspace', tenants, branches, tenantId: context.tenantId, branchId: context.branchId,
    context: latest, permissions, modules, tenantAdmin, message: 'Contexto confirmado por el servidor.' }
}
async function restore(): Promise<State> {
  const available = await selector()
  const context = await fiscalApi.current()
  if (!context) { return available }
  return confirmed(context, available.tenants, await fiscalApi.branches(context.tenantId))
}

/** Development-only same-origin client. No session token, identity assertion or business SQL authority. */
export function ConnectedFiscal() {
  const [state, setState] = useState<State>({ ...empty, phase: 'checking', busy: true })
  const [menuOpen, setMenuOpen] = useState(false)
  const generation = useRef(0)
  const working = useRef(false)
  const closing = useRef(false)
  const execute = useCallback(async (work: () => Promise<State>, kind: 'session' | 'login' | 'context' | 'logout') => {
    const current = ++generation.current
    working.current = true
    const commit = (next: State) => { if (current === generation.current) { setState(next) } }
    try { commit(await work()) } catch (error) {
      if (current !== generation.current) { return }
      const status = error instanceof ApiFailure ? error.status : 0
      if (status === 401) {
        commit({ ...empty, message: kind === 'login' ? 'No pudimos iniciar sesión. Revisa tus datos o consulta al administrador.'
          : kind === 'logout' ? 'La sesión ya está cerrada.' : 'La sesión no está vigente. Inicia sesión para continuar.' })
      } else if (kind === 'logout') {
        commit({ ...empty, phase: 'logout', message: 'No pudimos confirmar el cierre en el servidor. Reintenta cerrar la sesión.' })
      } else if (kind === 'login') {
        commit({ ...empty, message: 'No pudimos iniciar sesión. Comprueba la conexión segura y vuelve a intentarlo.' })
      } else if (status === 403) {
        // Nothing from the old context remains visible while the session is revalidated.
        commit({ ...empty, phase: 'checking', busy: true })
        try { commit(await selector('El contexto dejó de estar disponible. Selecciona nuevamente una empresa y sucursal.')) } catch (failure) {
          commit({ ...empty, phase: failure instanceof ApiFailure && failure.status === 401 ? 'access' : 'unavailable',
            message: 'No pudimos revalidar la sesión. Vuelve a comprobar el acceso.' })
        }
      } else {
        commit({ ...empty, phase: 'unavailable', message: 'No pudimos consultar el servidor. Los datos anteriores se han descartado.' })
      }
    } finally { if (current === generation.current) { working.current = false } }
  }, [])
  const refresh = useCallback(() => { setState({ ...empty, phase: 'checking', busy: true }); return execute(restore, 'session') }, [execute])
  useEffect(() => {
    void execute(restore, 'session')
    const lifecycle = generation
    const revalidate = () => { if (!working.current && !closing.current) { void refresh() } }
    const visible = () => { if (document.visibilityState === 'visible') { revalidate() } }
    window.addEventListener('focus', revalidate)
    window.addEventListener('pageshow', revalidate)
    document.addEventListener('visibilitychange', visible)
    return () => { ++lifecycle.current; window.removeEventListener('focus', revalidate); window.removeEventListener('pageshow', revalidate); document.removeEventListener('visibilitychange', visible) }
  }, [execute, refresh])
  const login = (form: HTMLFormElement) => {
    closing.current = false
    const fields = new FormData(form)
    const username = String(fields.get('username') ?? '')
    const password = String(fields.get('password') ?? '')
    form.reset()
    if (!username.trim() || !password) { setState({ ...empty, message: 'Completa el usuario y la contraseña.' }); return }
    setState({ ...empty, busy: true })
    void execute(async () => { await fiscalApi.login(username, password); return restore() }, 'login')
  }
  const chooseTenant = (tenantId: string) => {
    const initial = { ...empty, phase: 'workspace' as const, tenants: state.tenants, tenantId }
    setState({ ...initial, busy: true })
    void execute(async () => ({ ...initial, branches: await fiscalApi.branches(tenantId) }), 'context')
  }
  const chooseBranch = (branchId: string) => {
    ++generation.current
    setState({ ...empty, phase: 'workspace', tenants: state.tenants, branches: state.branches, tenantId: state.tenantId, branchId })
  }
  const select = () => {
    const initial = { ...state, context: null, permissions: [], modules: [], message: '' }
    setState({ ...initial, busy: true })
    void execute(async () => confirmed(await fiscalApi.select(state.tenantId, state.branchId), state.tenants, state.branches), 'context')
  }
  const logout = () => {
    closing.current = true
    setMenuOpen(false)
    setState({ ...empty, phase: 'logout', busy: true })
    void execute(async () => { await fiscalApi.logout(); return { ...empty, message: 'Sesión cerrada.' } }, 'logout')
  }
  const showSessions = (tenantId = '') => {
    const initial: State = { ...empty, phase: 'workspace', sessionView: tenantId ? 'tenant' : 'mine', managementTenant: tenantId }
    setState({ ...initial, busy: true })
    void execute(async () => ({ ...initial, sessions: await fiscalApi.sessions(tenantId || undefined) }), 'context')
  }
  const revoke = (id: string | null) => {
    const initial: State = { ...empty, phase: 'workspace', sessionView: state.sessionView, managementTenant: state.managementTenant }
    setState({ ...initial, busy: true })
    void execute(async () => {
      if (state.managementTenant && id) { await fiscalApi.revokeTenantSession(state.managementTenant, id) }
      else if (id) { await fiscalApi.revokeSession(id) }
      else { await fiscalApi.revokeOthers() }
      return { ...initial, sessions: await fiscalApi.sessions(state.managementTenant || undefined), message: 'Revocación confirmada por el servidor.' }
    }, 'context')
  }
  const tenant = state.context ? state.tenants.find(item => item.id === state.context?.tenantId) : undefined
  const branch = state.context ? state.branches.find(item => item.id === state.context?.branchId) : undefined

  return <div id="inicio">
    <a className="skip-link" href="#main">Saltar al contenido</a>
    <header className="topbar">
      <Brand onHome={() => { if (!state.busy) { void refresh() } }} />
      <button className="menu-toggle icon-button" aria-label={menuOpen ? 'Cerrar menú' : 'Abrir menú'} aria-expanded={menuOpen} aria-controls="fiscal-nav" onClick={() => setMenuOpen(!menuOpen)}><Icon name={menuOpen ? 'close' : 'menu'} /></button>
      <nav id="fiscal-nav" className={menuOpen ? 'navigation open' : 'navigation'} aria-label="Módulos autorizados">
        {state.modules.map(item => <button key={item.id} disabled title="Pantalla aún no disponible"><Icon name="apps" size={17} />{item.name}<span className="sr-only"> · No disponible</span></button>)}
      </nav>
      <div className="workspace"><span className="workspace-dot" /><span>LS Fiscal<small>Piloto conectado local</small></span></div>
    </header>
    <div className="demo-banner"><span className="demo-label">CONECTADO</span><strong>LS Fiscal · Entorno local · Datos del backend · Sin operaciones empresariales</strong></div>
    <main className="main" id="main">
      <div className="page-heading"><div><p className="eyebrow">LS FISCAL / ACCESO Y CONTEXTO</p><h1>Tu espacio de trabajo.</h1><p>Accede y elige dónde trabajar con LS Soluciones.</p></div>
        {state.phase === 'workspace' && <div className="pilot-actions"><button className="button" disabled={state.busy} onClick={() => showSessions()}>Mis sesiones</button>{state.tenantAdmin && state.context && <button className="button" disabled={state.busy} onClick={() => showSessions(state.context!.tenantId)}>Administrar sesiones de empresa</button>}<button className="button" disabled={state.busy} onClick={() => { void refresh() }}>Actualizar sesión</button><button className="button" disabled={state.busy} onClick={logout}>Cerrar sesión</button></div>}
      </div>
      {state.message && <p className="pilot-message" role="status">{state.message}</p>}
      {state.busy && <p role="status">Consultando el servidor…</p>}
      {state.phase === 'access' && <section className="access-layout" aria-label="Acceso a LS Fiscal">
        <div className="access-story"><span className="access-edition">LS FISCAL / ACCESO</span><div className="access-symbol" aria-hidden="true"><Icon name="key" size={38} /></div><p className="hero-kicker">CONECTA CON TU ESPACIO</p><h2>Tu trabajo empieza<br />con una buena <span>experiencia.</span></h2><p>Inicia sesión para consultar tus empresas y sucursales disponibles.</p></div>
        <form className="access-panel" onSubmit={event => { event.preventDefault(); login(event.currentTarget) }}>
          <span className="access-badge">LS FISCAL · SESIÓN INDEPENDIENTE</span><h2>Iniciar sesión</h2>
          <div className="access-field"><label htmlFor="fiscal-username">Usuario</label><input id="fiscal-username" name="username" autoComplete="username" required maxLength={254} disabled={state.busy} /></div>
          <div className="access-field"><label htmlFor="fiscal-password">Contraseña</label><input id="fiscal-password" name="password" type="password" autoComplete="current-password" required maxLength={1024} disabled={state.busy} /></div>
          <button className="button access-submit" type="submit" disabled={state.busy}>Ingresar<Icon name="arrow" size={18} /></button>
          <p className="access-help">Utiliza la cuenta autorizada del entorno local. Las cookies de sesión son administradas por el navegador.</p>
        </form>
      </section>}
      {state.phase === 'workspace' && state.sessionView !== 'none' && <ConnectedSessions key={state.sessionView + state.managementTenant} entries={state.sessions} busy={state.busy} tenant={state.sessionView === 'tenant'} onRefresh={() => showSessions(state.managementTenant)} onRevoke={id => revoke(id)} onRevokeOthers={() => revoke(null)} onLogout={logout} onBack={() => { void refresh() }} />}
      {state.phase === 'workspace' && state.sessionView === 'none' && <>
        <section className="context-layout" aria-label="Selector de contexto">
          <div className="context-steps">
            <section className="preview-panel" aria-labelledby="pilot-company"><div className="preview-panel-heading"><span className="step-number">01</span><div><h2 id="pilot-company">Elige una empresa</h2><p>Empresas disponibles para esta sesión.</p></div></div>
              {!state.tenants.length && !state.busy && <p className="preview-empty">No tienes empresas disponibles. Consulta al administrador.</p>}
              <div className="context-options">{state.tenants.map(item => <button className="context-option" key={item.id} disabled={state.busy} aria-pressed={state.tenantId === item.id} onClick={() => chooseTenant(item.id)}><span className="context-avatar"><Icon name="company" /></span><strong>{item.name}</strong></button>)}</div>
            </section>
            <section className="preview-panel" aria-labelledby="pilot-branch"><div className="preview-panel-heading"><span className="step-number">02</span><div><h2 id="pilot-branch">Elige una sucursal</h2><p>Confirma tu selección para consultar sus permisos.</p></div></div>
              {!state.tenantId ? <p className="preview-empty">Selecciona una empresa.</p> : !state.branches.length && !state.busy ? <p className="preview-empty">No hay sucursales disponibles.</p> : null}
              <div className="context-options">{state.branches.map(item => <button className="context-option" key={item.id} disabled={state.busy} aria-pressed={state.branchId === item.id} onClick={() => chooseBranch(item.id)}><Icon name="branch" /><span><strong>{item.name}</strong><small>{item.code}</small></span></button>)}</div>
              <button className="button pilot-confirm" disabled={state.busy || !state.tenantId || !state.branchId} onClick={select}>Confirmar contexto</button>
            </section>
          </div>
          <aside className="context-summary"><span className="access-badge">CONTEXTO ACTIVO</span><h2>{tenant?.name ?? 'Sin contexto confirmado'}</h2><p>{branch?.name ?? 'Selecciona empresa y sucursal para continuar.'}</p><p className="context-summary-note">La autorización y su vigencia se comprueban en el servidor.</p></aside>
        </section>
        {state.context && <div className="dashboard-grid pilot-results">
          <section className="panel" aria-labelledby="pilot-permissions"><div className="panel-heading"><h2 id="pilot-permissions">Permisos efectivos</h2></div>
            {state.permissions.length ? <ul className="pilot-list">{state.permissions.map(item => <li key={item.id}><strong>{item.name}</strong><small>{item.code} · {item.resourceScope === 'BRANCH' ? 'Sucursal seleccionada' : 'Empresa seleccionada'}</small></li>)}</ul> : <p className="preview-empty">No tienes permisos efectivos en esta sucursal.</p>}
          </section>
          <section className="panel" aria-labelledby="pilot-modules"><div className="panel-heading"><h2 id="pilot-modules">Navegación disponible</h2></div>
            {state.modules.length ? <ul className="pilot-list">{state.modules.map(item => <li key={item.id}><strong>{item.name}</strong><small>{item.code} · Pantalla no disponible todavía</small><p>{item.permissions.map(permission => permission.name).join(' · ')}</p></li>)}</ul> : <p className="preview-empty">No hay módulos disponibles para este contexto.</p>}
          </section>
        </div>}
      </>}
      {state.phase === 'unavailable' && <div className="preview-panel"><button className="button" onClick={() => { void refresh() }}>Revalidar sesión</button><button className="button" onClick={logout}>Cerrar sesión</button></div>}
      {state.phase === 'logout' && !state.busy && <button className="button" onClick={logout}>Reintentar cierre de sesión</button>}
      <footer><span><strong>LS Fiscal</strong> · LS Soluciones SpA</span><span>Piloto local de F12 · Capacidades según contexto del servidor</span></footer>
    </main>
  </div>
}
