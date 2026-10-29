import { useCallback, useEffect, useRef, useState } from 'react'
import { coreApi, CoreFailure } from './coreApi'
import type { AdministratorCandidate, AdministratorListing, AdministratorResult, DesignationAction, TenantDesignation } from './coreApi'

const labels: Record<DesignationAction, string> = { designate: 'Agregar administrador', activate: 'Activar designación', revoke: 'Revocar designación', replace: 'Reemplazar administrador' }
const messages: Record<string, string> = {
  CONFLICT: 'Conflicto: revalida la lista. El último administrador válido está protegido y una designación vigente no puede duplicarse.',
  DENIED: 'Autoridad o contraseña no válida. Revalida tu acceso a esta empresa.',
  INCOMPLETE_REQUIREMENTS: 'Completa F04 y revisa la membresía. Un reemplazo exige un candidato válido y la empresa debe estar activada.',
  NOT_FOUND: 'El recurso no está disponible. Revalida la información.',
}
const storageKey = 'ls.core.pending-administrator'
export function TenantAdministrators({ tenantId }: { tenantId: string }) {
  const [data, setData] = useState<AdministratorListing | null>(null)
  const [candidate, setCandidate] = useState<AdministratorCandidate | null>(null)
  const [action, setAction] = useState<DesignationAction | null>(null)
  const [target, setTarget] = useState<TenantDesignation | null>(null)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [pending, setPending] = useState<string | null>(() => sessionStorage.getItem(storageKey))
  const working = useRef(false)
  const current = useRef(tenantId)
  const load = useCallback(async () => {
    try { setData(await coreApi.administrators(tenantId)) }
    catch (error) { setData(null); throw error }
  }, [tenantId])
  const failure = (error: unknown) => {
    setData(null); setAction(null); setCandidate(null); setTarget(null)
    setMessage(error instanceof CoreFailure && error.status === 401 ? 'La sesión LS Core venció.' : 'No se pudo verificar la autoridad empresarial. Revalida el acceso.')
  }
  useEffect(() => {
    let cancelled = false
    current.current = tenantId
    queueMicrotask(() => {
      if (cancelled) { return }
      setData(null); setCandidate(null); setAction(null); setTarget(null); setBusy(true)
      void coreApi.administrators(tenantId).then(value => { if (!cancelled) { setData(value) } }).catch(() => { if (!cancelled) { setMessage('No se pudo verificar la autoridad empresarial.') } }).finally(() => { if (!cancelled) { setBusy(false) } })
    })
    return () => { cancelled = true }
  }, [tenantId])
  const run = async (work: () => Promise<void>) => {
    if (working.current || busy) { return }
    working.current = true; setBusy(true)
    try { await work() } catch (error) { failure(error) }
    finally { working.current = false; setBusy(false) }
  }
  const choose = (value: DesignationAction, designation: TenantDesignation | null = null) => { setAction(value); setTarget(designation); setCandidate(null); setMessage('') }
  const lookup = (form: HTMLFormElement) => {
    const username = String(new FormData(form).get('username'))
    setCandidate(null)
    void run(async () => {
      try { setCandidate(await coreApi.administratorCandidate(tenantId, username)) }
      catch (error) { if (error instanceof CoreFailure && error.status === 404) { setMessage('No hay candidato disponible con ese username exacto.') } else { throw error } }
    })
  }
  const apply = async (result: AdministratorResult, reference: string) => {
    if (result.correlation !== reference) { setMessage('Resultado sin confirmar. Conserva la referencia.'); return }
    if (result.outcome === 'CONFIRMED' && result.change) {
      sessionStorage.removeItem(storageKey); setPending(null); setAction(null); setCandidate(null); setTarget(null)
      setMessage('Operación confirmada por PostgreSQL. Los contextos revocados requieren nueva selección autorizada.')
      try { await load() } catch { setData(null); setMessage('Operación confirmada. Revalida la autoridad para consultar esta empresa.') }
    } else if (messages[result.outcome]) {
      sessionStorage.removeItem(storageKey); setPending(null); setAction(null); setCandidate(null)
      setMessage(messages[result.outcome])
      if (result.outcome === 'DENIED') { setData(null) } else { await load() }
    } else { setMessage('Resultado UNKNOWN o sin confirmar. Consulta la referencia antes de enviar otra mutación.'); setAction(null); setCandidate(null) }
  }
  const submit = (form: HTMLFormElement) => {
    const password = String(new FormData(form).get('password')); form.reset()
    if (sessionStorage.getItem('ls.core.pending-configuration') || sessionStorage.getItem('ls.core.pending-branch') || sessionStorage.getItem('ls.core.pending-lifecycle') || sessionStorage.getItem('ls.core.pending-registration')) { setMessage('Hay otra operación crítica sin reconciliar. Consulta su referencia antes de continuar.'); return }
    if (pending || working.current || busy || !action || !password || ((action === 'designate' || action === 'replace') && !candidate)) { return }
    if (!window.confirm(labels[action] + '. ¿Confirmas esta operación crítica?')) { return }
    const reference = crypto.randomUUID(); sessionStorage.setItem(storageKey, reference); setPending(reference)
    const selectedTenant = tenantId
    void run(async () => {
      try {
        const result = await coreApi.administratorMutation(tenantId, action, target?.id ?? null, reference, candidate?.username ?? null, password)
        if (current.current === selectedTenant) { await apply(result, reference) }
      } catch { setAction(null); setCandidate(null); setMessage('No se confirmó el resultado. Conserva la referencia y consulta la operación.') }
    })
  }
  return <section className="tenant-administrators" aria-label="Administradores de empresa">
    <div className="tenant-toolbar"><div><p className="eyebrow">EMPRESAS / ADMINISTRADORES</p><h3>Administradores</h3><p>La autoridad se comprueba para esta empresa en cada solicitud.</p></div><button className="button" disabled={busy} onClick={() => { void run(load) }}>Revalidar información</button></div>
    {message && <p className="pilot-message" role="status">{message}</p>}
    {busy && <p role="status">Consultando PostgreSQL…</p>}
    {pending && <div className="tenant-notice"><h4>Operación pendiente de reconciliación</h4><p>Referencia: <code>{pending}</code></p><button className="button" disabled={busy} onClick={() => { void run(async () => { try { await apply(await coreApi.administratorOperation(pending), pending) } catch (error) { if (error instanceof CoreFailure && error.status === 404) { setMessage('Todavía no hay confirmación. Conserva la referencia; no repitas la mutación.') } else { throw error } } }) }}>Consultar resultado administrativo</button></div>}
    {data && <><p>Administradores válidos y activos: <strong>{data.validCount}</strong></p>
      {data.validCount <= 1 && <p className="tenant-notice">El último administrador válido está protegido. Usa un reemplazo atómico para retirarlo.</p>}
      <div className="tenant-table-wrap"><table className="tenant-table"><thead><tr><th>Usuario</th><th>Identidad</th><th>Membresía</th><th>Designación</th><th>Fecha</th><th>Acciones</th></tr></thead><tbody>{data.administrators.map(row => <tr key={row.id}><td><strong>{row.displayName}</strong><small>{row.username}</small></td><td>{row.identityStatus}</td><td>{row.membershipStatus}</td><td>{row.status}<small>{row.revokedAt ? 'Revocada' : row.valid ? 'Vigente y válida' : 'Sin autoridad operativa'}</small></td><td>{new Date(row.grantedAt).toLocaleString('es-CL')}</td><td>{data.canManage && !row.revokedAt && <div className="tenant-actions"><button className="button danger" disabled={busy || !!pending} onClick={() => choose('revoke', row)}>Revocar {row.username}</button><button className="button" disabled={busy || !!pending} onClick={() => choose('replace', row)}>Reemplazar {row.username}</button>{row.status === 'PENDING' && <button className="button" disabled={busy || !!pending} onClick={() => choose('activate', row)}>Activar {row.username}</button>}</div>}</td></tr>)}</tbody></table></div>
      {data.canManage && <button className="button primary" disabled={busy || !!pending} onClick={() => choose('designate')}>Agregar administrador</button>}
      {action && !pending && <div className="tenant-confirmation"><h4>{labels[action]}</h4>{target && <p>Designación: {target.username}</p>}
        {(action === 'designate' || action === 'replace') && <form onSubmit={e => { e.preventDefault(); lookup(e.currentTarget) }}><label htmlFor="admin-candidate">Username exacto del candidato</label><input id="admin-candidate" name="username" maxLength={1024} required disabled={busy} onChange={() => setCandidate(null)} /><button className="button" disabled={busy}>Consultar candidato</button></form>}
        {candidate && <p>{candidate.displayName} · {candidate.username} · {candidate.identityStatus} · {candidate.ready ? 'F04 vigente' : 'Solo preparación sin autoridad'}</p>}
        {action === 'revoke' && target?.valid && data.validCount <= 1 && <p role="alert">La retirada del último administrador válido será rechazada. Puedes reemplazarlo.</p>}
        <form onSubmit={e => { e.preventDefault(); submit(e.currentTarget) }}><label htmlFor="admin-password">Confirmar nuevamente tu contraseña</label><input id="admin-password" name="password" type="password" autoComplete="current-password" maxLength={1024} required disabled={busy} /><div className="tenant-actions"><button className="button primary" disabled={busy || ((action === 'designate' || action === 'replace') && !candidate)}>Confirmar operación administrativa</button><button className="button" type="button" disabled={busy} onClick={() => { setAction(null); setCandidate(null) }}>Cancelar</button></div></form>
      </div>}
      <h4>Historial administrativo</h4><ol className="tenant-history">{data.history.map(change => <li key={change.requestId}><strong>{change.action} · {change.resultingStatus}</strong><small>{new Date(change.createdAt).toLocaleString('es-CL')}</small><small>Designación: {change.designationId}</small><small>Referencia: {change.requestId}</small></li>)}</ol>
    </>}
  </section>
}

export function TenantAdministratorsWorkspace() {
  const [companies, setCompanies] = useState<{ id: string; name: string; status: string }[]>([])
  const [selected, setSelected] = useState('')
  const [message, setMessage] = useState('')
  useEffect(() => { let cancelled = false; void coreApi.administratorTenants().then(values => { if (!cancelled) { setCompanies(values) } }).catch(() => { if (!cancelled) { setMessage('No se pudo comprobar la autoridad sobre empresas.') } }); return () => { cancelled = true } }, [])
  return <section className="preview-panel"><h2>Empresas → Administradores</h2>{message && <p role="status">{message}</p>}<label htmlFor="admin-company">Empresa autorizada</label><select id="admin-company" value={selected} onChange={e => setSelected(e.target.value)}><option value="">Seleccionar empresa</option>{companies.map(company => <option key={company.id} value={company.id}>{company.name} · {company.status}</option>)}</select>{selected && <TenantAdministrators key={selected} tenantId={selected} />}</section>
}
