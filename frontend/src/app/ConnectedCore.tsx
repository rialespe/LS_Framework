import { useCallback, useEffect, useRef, useState } from 'react'
import { TenantUsersWorkspace, TemporaryPasswordExchange, TenantUserReceipt } from './TenantUsers'
import { BranchAdministration } from './BranchAdministration'
import { TenantAdministratorsWorkspace } from './TenantAdministrators'
import { TenantAdministration } from './TenantAdministration'
import { Brand } from '../components/Brand'
import { coreApi, CoreFailure } from './coreApi'
import type { Administrator, RegisteredTenant, RegistrationResult } from './coreApi'

/** Real administrative form. Application state never grants platform authority. */
export function ConnectedCore() {
  const [phase, setPhase] = useState<'checking' | 'login' | 'workspace' | 'denied'>('checking')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [administrator, setAdministrator] = useState<Administrator | null>(null)
  const [tenant, setTenant] = useState<RegisteredTenant | null>(null)
  const [uncertain, setUncertain] = useState(() => Boolean(sessionStorage.getItem('ls.core.pending-registration')))
  const [kind, setKind] = useState('')
  const [section, setSection] = useState<'new' | 'admin' | 'administrators' | 'branches' | 'users'>('new')
  const [canRegister, setCanRegister] = useState(true)
  const [menu, setMenu] = useState(false)
  // Only a reconciliation reference, never a session token, password, tenant or identity.
  const [reference, setReference] = useState(() => sessionStorage.getItem('ls.core.pending-registration'))
  const requestId = useRef<string | null>(sessionStorage.getItem('ls.core.pending-registration'))
  const working = useRef(false)
  const generation = useRef(0)
  const clear = useCallback(() => { setAdministrator(null); setTenant(null); setKind('') }, [])
  const failure = useCallback((error: unknown) => {
    clear()
    if (error instanceof CoreFailure && error.status === 401) { setPhase('login'); setMessage('La sesión LS Core no está vigente. Inicia sesión.') }
    else { setPhase('denied'); setMessage('No pudimos verificar la autorización para registrar empresas.') }
  }, [clear])
  const refresh = useCallback(async () => {
    if (working.current) { return }
    working.current = true
    const current = ++generation.current
    clear(); setPhase('checking')
    try { const result = await coreApi.authority(); if (current === generation.current) { setKind(result.kind); setCanRegister(result.canRegister); if (!result.canRegister && result.canAdmin) { setSection(result.usersOnly ? 'users' : result.branchesOnly ? 'branches' : result.tenantOnly ? 'administrators' : 'admin') }; setPhase(result.canRegister || result.canAdmin ? 'workspace' : 'denied'); if (result.canRegister && requestId.current) { try { const saved = await coreApi.find(requestId.current); if (current === generation.current && saved.outcome === 'CONFIRMED' && saved.tenant) { setTenant(saved.tenant); setUncertain(false); sessionStorage.removeItem('ls.core.pending-registration') } } catch (error) { if (!(error instanceof CoreFailure && error.status === 404)) { throw error } } } } }
    catch (error) { if (current === generation.current) { failure(error) } }
    finally { working.current = false }
  }, [clear, failure])
  useEffect(() => {
    queueMicrotask(() => { void refresh() })
    const lifetime = generation
    const focus = () => { void refresh() }
    window.addEventListener('focus', focus)
    window.addEventListener('pageshow', focus)
    return () => { lifetime.current++; window.removeEventListener('focus', focus); window.removeEventListener('pageshow', focus) }
  }, [refresh])
  const run = async (work: () => Promise<void>) => {
    if (working.current) { return }
    working.current = true; setBusy(true); setMessage('')
    try { await work() } finally { working.current = false; setBusy(false) }
  }
  const login = (form: HTMLFormElement) => {
    const values = new FormData(form); const username = String(values.get('username')); const password = String(values.get('password')); form.reset()
    void run(async () => { try { await coreApi.login(username, password); const authority = await coreApi.authority(); setKind(authority.kind); setCanRegister(authority.canRegister); if (!authority.canRegister && authority.canAdmin) { setSection(authority.usersOnly ? 'users' : authority.branchesOnly ? 'branches' : authority.tenantOnly ? 'administrators' : 'admin') }; setPhase(authority.canRegister || authority.canAdmin ? 'workspace' : 'denied') }
      catch { clear(); setPhase('login'); setMessage('Acceso no disponible. Revisa tus datos y tu autorización.') } })
  }
  const lookup = (form: HTMLFormElement) => {
    const username = String(new FormData(form).get('administrator')); setAdministrator(null)
    void run(async () => { try { setAdministrator(await coreApi.administrator(username)) }
      catch (error) { if (error instanceof CoreFailure && error.status === 404) { setMessage('No hay una identidad disponible. Utiliza el registro administrativo F04.') } else { failure(error) } } })
  }
  const show = (result: RegistrationResult) => {
    if (result.outcome === 'CONFIRMED' && result.tenant && result.tenant.requestId === requestId.current) {
      setTenant(result.tenant); setUncertain(false); sessionStorage.removeItem('ls.core.pending-registration'); setMessage('Empresa confirmada por PostgreSQL. Permanece pendiente de activación.')
    } else { setTenant(null); setUncertain(true); setMessage('Resultado sin confirmar. Conserva la referencia y consulta el resultado antes de otra creación.') }
  }
  const create = (form: HTMLFormElement) => {
    if (sessionStorage.getItem('ls.core.pending-user') || sessionStorage.getItem('ls.core.pending-configuration') || sessionStorage.getItem('ls.core.pending-branch') || sessionStorage.getItem('ls.core.pending-administrator') || sessionStorage.getItem('ls.core.pending-lifecycle')) { setMessage('Hay otra operación crítica sin reconciliar. Consulta su referencia antes de continuar.'); const secretField = form.elements.namedItem('confirmation') as HTMLInputElement; secretField.value = ''; return }
    if (!administrator || uncertain || tenant || working.current) { return }
    const data = new FormData(form); const name = String(data.get('name')).trim(); const password = String(data.get('confirmation'))
    const secretField = form.elements.namedItem('confirmation') as HTMLInputElement; secretField.value = ''
    if (!name || !password || !window.confirm('¿Crear esta empresa PENDIENTE con el administrador seleccionado?')) { return }
    const id = requestId.current ?? crypto.randomUUID(); requestId.current = id; setReference(id); sessionStorage.setItem('ls.core.pending-registration', id)
    void run(async () => { try { show(await coreApi.register(id, name, administrator.id, password)) }
      catch (error) {
        setTenant(null)
        if (error instanceof CoreFailure && [400, 403].includes(error.status)) { requestId.current = null; sessionStorage.removeItem('ls.core.pending-registration'); failure(error); setMessage('Creación rechazada. Revalida la autorización y la contraseña.') }
        else { setUncertain(true); setMessage('No se pudo confirmar la creación. Consulta el resultado con la misma referencia.'); if (error instanceof CoreFailure && error.status === 401) { failure(error) } }
      } })
  }
  const reconcile = () => { if (requestId.current) { void run(async () => { try { show(await coreApi.find(requestId.current!)) } catch (error) {
    if (error instanceof CoreFailure && error.status === 404) { setMessage('Todavía no se encontró un resultado confirmado. Conserva la referencia; no envíes otra alta.') } else { failure(error) }
  } }) } }
  const logout = () => {
    clear(); setPhase('checking')
    void run(async () => { try { await coreApi.logout(); setPhase('login'); setMessage('Sesión cerrada.') } catch (error) { failure(error) } })
  }
  return <div><header className="topbar"><Brand onHome={() => { void refresh() }} /><button className="menu-toggle" aria-label="Menú" onClick={() => setMenu(!menu)}>Menú</button>
    <nav className={menu ? 'navigation open' : 'navigation'} aria-label="Administración LS Core">{phase === 'workspace' && <><button aria-current={section === 'admin' ? 'page' : undefined} onClick={() => setSection('admin')}>Empresas → Administración de empresas</button><button aria-current={section === 'administrators' ? 'page' : undefined} onClick={() => setSection('administrators')}>Empresas / Administradores</button><button aria-current={section === 'users' ? 'page' : undefined} onClick={() => setSection('users')}>Empresas → Usuarios</button><button aria-current={section === 'branches' ? 'page' : undefined} onClick={() => setSection('branches')}>Empresas / Sucursales</button>{canRegister && <button aria-current={section === 'new' ? 'page' : undefined} onClick={() => setSection('new')}>Empresas / Nueva empresa</button>}</>}</nav>
    <div className="workspace"><span>LS Core<small>Administración autenticada</small></span></div></header>
    <main className="main core-main"><div className="page-heading"><div><p className="eyebrow">LS SOLUCIONES / EMPRESAS</p><h1>{section === 'new' ? 'Nueva empresa' : 'Empresas'}</h1><p>LS Core → Administración segura de empresas.</p></div>
      {phase === 'workspace' && <div className="pilot-actions"><span>{kind}</span><button className="button" disabled={busy} onClick={() => { void refresh() }}>Revalidar acceso</button><button className="button" disabled={busy} onClick={logout}>Cerrar sesión</button></div>}</div>
      {message && <p role="status" className="pilot-message">{message}</p>}{(busy || phase === 'checking') && <p role="status">Consultando el servidor…</p>}
      {phase === 'login' && <form className="access-panel" onSubmit={e => { e.preventDefault(); login(e.currentTarget) }}><h2>Acceso independiente a LS Core</h2>
        <div className="access-field"><label htmlFor="core-user">Usuario</label><input id="core-user" name="username" autoComplete="username" required disabled={busy} /></div>
        <div className="access-field"><label htmlFor="core-password">Contraseña</label><input id="core-password" name="password" type="password" autoComplete="current-password" required maxLength={1024} disabled={busy} /></div><button className="button" disabled={busy}>Ingresar</button></form>}
      {phase === 'denied' && <section className="preview-panel"><p>Se requiere autoridad global o empresarial expresamente habilitada.</p><button className="button" disabled={busy} onClick={logout}>Cerrar sesión</button><button className="button" onClick={() => { void refresh() }}>Comprobar acceso</button></section>}
      {(phase === 'login' || phase === 'denied') && <TenantUserReceipt />}
      {phase === 'login' && <TemporaryPasswordExchange />}
      {phase === 'workspace' && section === 'users' && <TenantUsersWorkspace />}
      {phase === 'workspace' && section === 'branches' && <BranchAdministration />}
      {phase === 'workspace' && section === 'administrators' && <TenantAdministratorsWorkspace />}
      {phase === 'workspace' && section === 'admin' && <TenantAdministration />}
      {phase === 'workspace' && section === 'new' && canRegister && <div className="context-layout"><div className="context-steps">
        {!tenant && !uncertain && <><form className="preview-panel" onSubmit={e => { e.preventDefault(); lookup(e.currentTarget) }}><h2>Administrador inicial</h2><p>Identifica un usuario existente por su nombre exacto. Para una identidad nueva, utiliza F04 antes del alta.</p>
          <div className="access-field"><label htmlFor="core-admin">Usuario del administrador</label><input id="core-admin" name="administrator" required disabled={busy} maxLength={1024} onChange={() => setAdministrator(null)} /></div><button className="button" disabled={busy}>Buscar identidad</button>
          {administrator && <p>{administrator.displayName} · {administrator.status}</p>}</form>
          <form className="preview-panel" onSubmit={e => { e.preventDefault(); create(e.currentTarget) }}><h2>Datos de la empresa</h2><div className="access-field"><label htmlFor="core-name">Nombre de la empresa</label><input id="core-name" name="name" required maxLength={200} disabled={busy} /></div>
            <div className="access-field"><label htmlFor="core-confirm">Confirmar tu contraseña</label><input id="core-confirm" name="confirmation" type="password" required maxLength={1024} autoComplete="current-password" disabled={busy} /></div><p>Empresa, membresía y designación administrativa se crean PENDIENTES.</p><button className="button" disabled={busy || !administrator}>Crear empresa pendiente</button></form></>}
        {uncertain && <section className="preview-panel"><h2>Creación sin confirmar</h2><p>Referencia: {reference}</p><button className="button" disabled={busy} onClick={reconcile}>Consultar resultado</button></section>}
        {tenant && <section className="preview-panel" aria-label="Empresa registrada"><h2>{tenant.name}</h2><p>Estado: <strong>{tenant.status === 'PENDING' ? 'PENDIENTE' : tenant.status}</strong></p><dl><dt>UUID de empresa</dt><dd>{tenant.id}</dd><dt>Administrador inicial</dt><dd>{tenant.administratorId}</dd><dt>Fecha de creación</dt><dd>{tenant.createdAt}</dd><dt>Referencia</dt><dd>{tenant.requestId}</dd></dl><button className="button" onClick={() => { requestId.current = null; setTenant(null); setAdministrator(null); setMessage('') }}>Nueva empresa</button></section>}
      </div></div>}
    </main></div>
}
