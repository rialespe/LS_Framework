import { useCallback, useEffect, useRef, useState } from 'react'
import { TenantAdministrators } from './TenantAdministrators'
import { coreApi, CoreFailure } from './coreApi'
import type { TenantAction, TenantAuthority, TenantDetail, TenantPage, LifecycleResult } from './coreApi'

const labels = { activate: 'Activar', suspend: 'Suspender', reactivate: 'Reactivar' }
const states: Record<string, string> = { PENDING: 'Pendiente', ACTIVE: 'Activa', SUSPENDED: 'Suspendida' }
const notices: Record<string, string> = {
  DENIED: 'Acción no autorizada. Comprueba tu autoridad y confirma nuevamente tu contraseña.',
  INVALID_TRANSITION: 'La transición ya no es válida. Consulta la ficha actualizada.',
  INCOMPLETE_REQUIREMENTS: 'Prepara al administrador inicial en F04: identidad activa, contraseña definitiva vigente, membresía y designación consistentes.',
  UNAVAILABLE: 'Servicio no disponible. Consulta el resultado antes de enviar otra operación.',
}
export function TenantAdministration() {
  const [authority, setAuthority] = useState<TenantAuthority | null>(null)
  const [data, setData] = useState<TenantPage>({ tenants: [], hasMore: false })
  const [detail, setDetail] = useState<TenantDetail | null>(null)
  const [page, setPage] = useState(0)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [showAdministrators, setShowAdministrators] = useState(false)
  const [action, setAction] = useState<TenantAction | null>(null)
  const [pending, setPending] = useState<string | null>(() => sessionStorage.getItem('ls.core.pending-lifecycle'))
  const working = useRef(false)
  const fail = useCallback((error: unknown) => {
    setAuthority(null); setData({ tenants: [], hasMore: false }); setDetail(null); setAction(null)
    setMessage(error instanceof CoreFailure && error.status === 401 ? 'La sesión LS Core venció. Inicia sesión nuevamente.' : 'No se pudo verificar la autoridad global. Revalida el acceso.')
  }, [])
  const load = useCallback(async (next: number) => {
    if (working.current) { return }
    working.current = true; setBusy(true)
    try { const access = await coreApi.tenantAuthority(); const list = await coreApi.tenants(next); setAuthority(access); setData(list); setPage(next); setDetail(null); setAction(null) }
    catch (error) { fail(error) }
    finally { working.current = false; setBusy(false) }
  }, [fail])
  useEffect(() => { queueMicrotask(() => { void load(0) }) }, [load])
  const select = async (id: string) => {
    if (working.current) { return }
    working.current = true; setBusy(true); setAction(null); setShowAdministrators(false)
    try { setAuthority(await coreApi.tenantAuthority()); setDetail(await coreApi.tenant(id)) }
    catch (error) { fail(error) }
    finally { working.current = false; setBusy(false) }
  }
  const applyResult = async (result: LifecycleResult, tenantId?: string) => {
    if (result.outcome === 'CONFIRMED' && result.change && result.correlation === pendingReference.current) {
      sessionStorage.removeItem('ls.core.pending-lifecycle'); setPending(null); pendingReference.current = null
      setMessage('Operación confirmada por el servidor. Los contextos revocados requieren una nueva selección autorizada.')
      setAuthority(await coreApi.tenantAuthority()); setData(await coreApi.tenants(page))
      if (tenantId) { setDetail(await coreApi.tenant(tenantId)) } else { setDetail(null) }
    } else if (['DENIED', 'INVALID_TRANSITION', 'INCOMPLETE_REQUIREMENTS'].includes(result.outcome)) {
      sessionStorage.removeItem('ls.core.pending-lifecycle'); setPending(null); pendingReference.current = null
      setMessage(notices[result.outcome]); setAction(null)
      if (result.outcome === 'DENIED') { setAuthority(null); setDetail(null); setData({ tenants: [], hasMore: false }) }
    } else {
      setMessage(result.outcome === 'UNKNOWN' ? 'Resultado UNKNOWN. Conserva la referencia de reconciliación; el estado final no está confirmado.' : notices.UNAVAILABLE)
      setDetail(null); setAction(null)
    }
  }
  const pendingReference = useRef(pending)
  const submit = async (form: HTMLFormElement) => {
    if (sessionStorage.getItem('ls.core.pending-configuration') || sessionStorage.getItem('ls.core.pending-branch') || sessionStorage.getItem('ls.core.pending-administrator') || sessionStorage.getItem('ls.core.pending-registration')) { setMessage('Hay otra operación crítica sin reconciliar. Consulta su referencia antes de continuar.'); form.reset(); return }
    if (working.current || !detail || !action || pending) { return }
    const password = String(new FormData(form).get('password')); form.reset()
    if (!password || !window.confirm(labels[action] + ' esta empresa. ¿Confirmas la operación?')) { return }
    const id = crypto.randomUUID(); pendingReference.current = id; setPending(id); sessionStorage.setItem('ls.core.pending-lifecycle', id)
    working.current = true; setBusy(true); setMessage('')
    try { await applyResult(await coreApi.transition(detail.tenant.id, action, id, password), detail.tenant.id); setAction(null) }
    catch (error) {
      setDetail(null); setAction(null); setMessage('No se confirmó el resultado. Conserva la referencia y consulta la operación antes de continuar.')
      if (error instanceof CoreFailure && error.status === 401) { fail(error) }
    } finally { working.current = false; setBusy(false) }
  }
  const reconcile = async () => {
    if (!pending || working.current) { return }
    working.current = true; setBusy(true)
    try { await applyResult(await coreApi.operation(pending)) }
    catch (error) {
      if (error instanceof CoreFailure && error.status === 404) { setMessage('No hay confirmación disponible. Conserva la referencia para reconciliación; no repitas la acción.') }
      else { fail(error) }
    } finally { working.current = false; setBusy(false) }
  }
  const t = detail?.tenant
  return <section className="tenant-admin" aria-label="Administración de empresas">
    <div className="tenant-toolbar"><div><p className="eyebrow">CONTROL GLOBAL</p><h2>Administración de empresas</h2><p>Consulta estados y confirma cada cambio con tu contraseña.</p></div><button className="button" disabled={busy} onClick={() => { void load(page) }}>Actualizar empresas</button></div>
    {message && <p className="pilot-message" role="status">{message}</p>}
    {busy && <p role="status">Consultando el servidor…</p>}
    {pending && <div className="tenant-notice"><h3>Operación pendiente de reconciliación</h3><p>Referencia: <code>{pending}</code></p><button className="button" disabled={busy} onClick={() => { void reconcile() }}>Consultar operación</button></div>}
    {authority?.canRead && <div className="tenant-grid"><section className="preview-panel tenant-list"><h3>Empresas registradas</h3>
      <div className="tenant-table-wrap"><table className="tenant-table"><thead><tr><th>Empresa / identificador</th><th>Estado</th><th>Administrador inicial</th><th>Creación</th><th>Ficha</th></tr></thead><tbody>
        {data.tenants.map(tenant => <tr key={tenant.id}><td><strong>{tenant.name}</strong><small>{tenant.id}</small></td><td><span className={'tenant-state state-' + tenant.status.toLowerCase()}>{states[tenant.status] ?? tenant.status}</span></td><td>{tenant.administratorName ?? 'Sin designación inicial'}<small>{tenant.administratorId}</small></td><td>{new Date(tenant.createdAt).toLocaleDateString('es-CL')}</td><td><button className="button" disabled={busy} onClick={() => { void select(tenant.id) }}>Ver ficha</button></td></tr>)}
      </tbody></table></div>{!data.tenants.length && <p>No hay empresas registradas.</p>}
      <div className="tenant-pagination"><button className="button" disabled={busy || page === 0} onClick={() => { void load(page - 1) }}>Anterior</button><span>Página {page + 1}</span><button className="button" disabled={busy || !data.hasMore} onClick={() => { void load(page + 1) }}>Siguiente</button></div>
    </section>
    {t && <section className="preview-panel tenant-detail" aria-label="Ficha de empresa"><p className="eyebrow">FICHA DE EMPRESA</p><h3>{t.name}</h3><span className={'tenant-state state-' + t.status.toLowerCase()}>{states[t.status] ?? t.status}</span>
      <dl><dt>Identificador</dt><dd>{t.id}</dd><dt>Fecha de creación</dt><dd>{new Date(t.createdAt).toLocaleString('es-CL')}</dd><dt>Administrador inicial</dt><dd>{t.administratorName ?? 'Sin designación'}<small>{t.administratorId}</small></dd><dt>Estado de identidad</dt><dd>{t.administratorStatus ?? 'Sin información'}</dd></dl>
      {!pending && <div className="tenant-actions">{t.status === 'PENDING' && authority.canActivate && <button className="button primary" disabled={busy} onClick={() => setAction('activate')}>Activar</button>}{t.status === 'ACTIVE' && authority.canSuspend && <button className="button danger" disabled={busy} onClick={() => setAction('suspend')}>Suspender</button>}{t.status === 'SUSPENDED' && authority.canReactivate && <button className="button primary" disabled={busy} onClick={() => setAction('reactivate')}>Reactivar</button>}</div>}
      {action && !pending && <form className="tenant-confirmation" onSubmit={e => { e.preventDefault(); void submit(e.currentTarget) }}><h4>{labels[action]} empresa</h4><p>{action === 'suspend' ? 'Se revocarán los contextos empresariales de esta empresa. Se conservarán los datos y las otras empresas.' : 'Se comprobará la preparación administrativa vigente. No se conceden módulos ni aplicaciones.'}</p><label htmlFor="lifecycle-password">Confirmar contraseña para {labels[action].toLowerCase()}</label><input id="lifecycle-password" name="password" type="password" autoComplete="current-password" maxLength={1024} required disabled={busy} /><div className="tenant-actions"><button className="button primary" disabled={busy}>Confirmar {labels[action].toLowerCase()}</button><button className="button" type="button" disabled={busy} onClick={() => setAction(null)}>Cancelar</button></div></form>}
      <button className="button" disabled={busy || !!pending} onClick={() => setShowAdministrators(!showAdministrators)}>Administradores</button>{showAdministrators && <TenantAdministrators key={t.id} tenantId={t.id} />}
      <h4>Historial de cambios</h4>{detail.history.length ? <ol className="tenant-history">{detail.history.map(change => <li key={change.requestId}><strong>{states[change.previousStatus] } → {states[change.resultingStatus]}</strong><small>{new Date(change.createdAt).toLocaleString('es-CL')}</small><small>Referencia: {change.requestId}</small></li>)}</ol> : <p>Sin cambios de estado registrados.</p>}
    </section>}
    </div>}
  </section>
}
