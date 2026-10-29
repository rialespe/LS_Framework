import { useEffect, useRef, useState } from 'react'
import { BranchModuleConfiguration } from './BranchModuleConfiguration'
import { coreApi, CoreFailure } from './coreApi'
import type { BranchAction, BranchDetail, BranchPage, BranchResult } from './coreApi'

const states: Record<string, string> = { ACTIVE: 'Activa', SUSPENDED: 'Suspendida', CLOSED: 'Cerrada' }
const labels: Record<BranchAction, string> = { create: 'Crear sucursal', edit: 'Guardar cambios', suspend: 'Suspender sucursal', reactivate: 'Reactivar sucursal', close: 'Cerrar sucursal' }
const notices: Record<string, string> = {
  CONFLICT: 'Conflicto: revalida la ficha. El código puede estar ocupado o protegido por referencias, la versión cambió o la sucursal está cerrada.',
  INCOMPLETE_REQUIREMENTS: 'Revisa los datos: código de 1 a 40 letras o números ASCII, espacios, punto, guion o guion bajo; nombre de 1 a 200 caracteres.',
  DENIED: 'Autoridad o contraseña no válida. Revalida tu acceso a la empresa.',
  NOT_FOUND: 'La empresa o sucursal no está disponible. Revalida la información.',
  UNAVAILABLE: 'Servicio no disponible. El servidor no confirmó la operación. Revalida la información antes de intentar otro cambio.',
}
const pendingKey = 'ls.core.pending-branch'
const otherPending = () => ['ls.core.pending-configuration', 'ls.core.pending-administrator', 'ls.core.pending-lifecycle', 'ls.core.pending-registration'].some(key => sessionStorage.getItem(key))

export function BranchAdministration() {
  const [companies, setCompanies] = useState<{ id: string; name: string; status: string }[]>([])
  const [tenant, setTenant] = useState('')
  const [page, setPage] = useState(0)
  const [data, setData] = useState<BranchPage | null>(null)
  const [showModules, setShowModules] = useState(false)
  const [detail, setDetail] = useState<BranchDetail | null>(null)
  const [action, setAction] = useState<BranchAction | null>(null)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(true)
  const [pending, setPending] = useState<string | null>(() => sessionStorage.getItem(pendingKey))
  const working = useRef(false)
  useEffect(() => {
    let cancelled = false
    void coreApi.branchTenants().then(values => { if (!cancelled) { setCompanies(values) } }).catch(() => { if (!cancelled) { setMessage('No se pudo comprobar la autoridad para administrar sucursales.') } }).finally(() => { if (!cancelled) { setBusy(false) } })
    return () => { cancelled = true }
  }, [])
  const failure = (error: unknown) => {
    setCompanies([]); setTenant(''); setData(null); setDetail(null); setAction(null)
    setMessage(error instanceof CoreFailure && error.status === 401 ? 'La sesión LS Core venció. Inicia sesión nuevamente.' : 'No se pudo comprobar la autoridad o disponibilidad. Revalida el acceso.')
  }
  const run = async (work: () => Promise<void>) => {
    if (working.current || busy) { return }
    working.current = true; setBusy(true)
    try { await work() } catch (error) { failure(error) }
    finally { working.current = false; setBusy(false) }
  }
  const selectCompany = (id: string) => {
    if (working.current || busy) { return }
    setTenant(id); setData(null); setDetail(null); setAction(null); setPage(0); setMessage('')
    if (id) { void run(async () => { setData(await coreApi.branches(id, 0)) }) }
  }
  const refresh = async () => {
    const values = await coreApi.branchTenants(); setCompanies(values)
    if (!tenant) { return }
    if (!values.some(company => company.id === tenant)) { setTenant(''); setData(null); setDetail(null); setAction(null); setMessage('La autoridad sobre esta empresa ya no está vigente.'); return }
    setData(await coreApi.branches(tenant, page))
    if (detail) { setDetail(await coreApi.branch(tenant, detail.branch.id)) }
  }
  const apply = async (result: BranchResult, reference: string) => {
    if (result.correlation !== reference) { setMessage('Resultado sin confirmar. Conserva la referencia.'); return }
    if (result.outcome === 'CONFIRMED' && result.change) {
      sessionStorage.removeItem(pendingKey); setPending(null); setAction(null)
      setMessage('Operación confirmada por PostgreSQL. Los contextos revocados no se recuperan automáticamente.')
      try {
        setCompanies(await coreApi.branchTenants())
        if (result.change.tenantId === tenant) { setData(await coreApi.branches(tenant, page)); setDetail(await coreApi.branch(tenant, result.change.branch.id)) }
        else { setDetail(null); if (tenant) { setData(await coreApi.branches(tenant, page)) } }
      } catch { setData(null); setDetail(null); setMessage('Operación confirmada por PostgreSQL. No se pudo releer la ficha; revalida tu acceso.') }
    } else if (notices[result.outcome]) {
      sessionStorage.removeItem(pendingKey); setPending(null); setAction(null); setMessage(notices[result.outcome])
      if (result.outcome === 'DENIED' || result.outcome === 'NOT_FOUND') { setCompanies([]); setTenant(''); setData(null); setDetail(null) }
    } else { setAction(null); setMessage('Resultado UNKNOWN o sin confirmar. Conserva la referencia y consulta el resultado antes de otra mutación.') }
  }
  const submit = (form: HTMLFormElement) => {
    const values = new FormData(form)
    const password = String(values.get('password') ?? '')
    const code = action === 'edit' && !detail?.codeEditable ? detail?.branch.code ?? null : String(values.get('code') ?? '')
    const name = String(values.get('name') ?? '')
    const field = form.elements.namedItem('password') as HTMLInputElement; field.value = ''
    if (!action || !tenant || working.current || busy || pending || !password) { return }
    if (otherPending()) { setMessage('Hay otra operación crítica sin reconciliar. Consulta su referencia antes de continuar.'); return }
    if (!window.confirm(labels[action] + (action === 'close' ? '. El cierre es terminal. ¿Confirmas?' : '. ¿Confirmas la operación?'))) { return }
    const reference = crypto.randomUUID(); sessionStorage.setItem(pendingKey, reference); setPending(reference)
    void run(async () => {
      try { await apply(await coreApi.branchMutation(tenant, action, action === 'create' ? null : detail!.branch.id, reference, code, name, action === 'create' ? null : detail!.branch.updatedAt, password), reference) }
      catch (error) {
        setAction(null)
        if (error instanceof CoreFailure && [400, 401, 403, 404].includes(error.status)) {
          sessionStorage.removeItem(pendingKey); setPending(null); failure(error)
        } else { setMessage('No se confirmó el resultado. Conserva la referencia y consulta la operación antes de continuar.') }
      }
    })
  }
  const b = detail?.branch
  return <section className="tenant-admin" aria-label="Administración de sucursales">
    <div className="tenant-toolbar"><div><p className="eyebrow">EMPRESAS / SUCURSALES</p><h2>Sucursales</h2><p>Datos generales y estados persistidos. Cada cambio requiere tu contraseña.</p></div><button className="button" disabled={busy} onClick={() => { void run(refresh) }}>Revalidar sucursales</button></div>
    <div className="branch-selector"><label htmlFor="branch-company">Empresa autorizada para sucursales</label><select id="branch-company" value={tenant} disabled={busy} onChange={e => selectCompany(e.target.value)}><option value="">Seleccionar empresa</option>{companies.map(company => <option key={company.id} value={company.id}>{company.name} · {company.status}</option>)}</select></div>
    {message && <p className="pilot-message" role="status">{message}</p>}{busy && <p role="status">Consultando el servidor…</p>}
    {pending && <div className="tenant-notice"><h3>Operación pendiente de reconciliación</h3><p>Referencia: <code>{pending}</code></p><button className="button" disabled={busy} onClick={() => { void run(async () => { try { await apply(await coreApi.branchOperation(pending), pending) } catch (error) { if (error instanceof CoreFailure && error.status === 404) { setMessage('Todavía no hay confirmación. Conserva la referencia; no repitas la acción.') } else { throw error } } }) }}>Consultar resultado de sucursal</button></div>}
    {data && <div className="tenant-grid"><section className="preview-panel tenant-list"><div className="tenant-toolbar"><h3>Sucursales de la empresa</h3>{data.canManage && <button className="button primary" disabled={busy || !!pending} onClick={() => { setAction('create'); setDetail(null); setMessage('') }}>Nueva sucursal</button>}</div>
      {companies.find(company => company.id === tenant)?.status === 'PENDING' && <p className="tenant-notice">Empresa PENDING: las sucursales son preparación administrativa y no conceden acceso operativo.</p>}
      <div className="tenant-table-wrap"><table className="tenant-table"><thead><tr><th>Código</th><th>Nombre</th><th>Estado</th><th>Creación</th><th>Ficha</th></tr></thead><tbody>{data.branches.map(row => <tr key={row.id}><td><strong>{row.code}</strong></td><td>{row.name}</td><td><span className={'tenant-state state-' + row.status.toLowerCase()}>{states[row.status] ?? row.status}</span></td><td>{new Date(row.createdAt).toLocaleString('es-CL')}</td><td><button className="button" disabled={busy} onClick={() => { setAction(null); void run(async () => { setDetail(await coreApi.branch(tenant, row.id)) }) }}>Ficha {row.code}</button></td></tr>)}</tbody></table></div>
      {!data.branches.length && <p>No hay sucursales registradas.</p>}<div className="tenant-pagination"><button className="button" disabled={busy || page === 0} onClick={() => { void run(async () => { setData(await coreApi.branches(tenant, page - 1)); setPage(page - 1); setDetail(null); setAction(null) }) }}>Anterior</button><span>Página {page + 1}</span><button className="button" disabled={busy || !data.hasMore} onClick={() => { void run(async () => { setData(await coreApi.branches(tenant, page + 1)); setPage(page + 1); setDetail(null); setAction(null) }) }}>Siguiente</button></div>
    </section>
    {(b || action === 'create') && <section className="preview-panel tenant-detail" aria-label="Ficha de sucursal"><h3>{b ? b.name : 'Nueva sucursal'}</h3>{b && <><span className={'tenant-state state-' + b.status.toLowerCase()}>{states[b.status]}</span><dl><dt>Código</dt><dd>{b.code}</dd><dt>Identificador</dt><dd>{b.id}</dd><dt>Empresa propietaria</dt><dd>{b.tenantId}</dd><dt>Creación</dt><dd>{new Date(b.createdAt).toLocaleString('es-CL')}</dd><dt>Actualización</dt><dd>{new Date(b.updatedAt).toLocaleString('es-CL')}</dd></dl>{b.status === 'CLOSED' && <p className="tenant-notice">Cierre terminal: se conserva la sucursal y su código; no admite nuevos cambios.</p>}
      {detail?.canManage && b.status !== 'CLOSED' && <div className="tenant-actions"><button className="button" disabled={busy || !!pending} onClick={() => setAction('edit')}>Editar datos generales</button>{b.status === 'ACTIVE' && <button className="button" disabled={busy || !!pending} onClick={() => setAction('suspend')}>Suspender sucursal</button>}{b.status === 'SUSPENDED' && <button className="button" disabled={busy || !!pending} onClick={() => setAction('reactivate')}>Reactivar sucursal</button>}<button className="button danger" disabled={busy || !!pending} onClick={() => setAction('close')}>Cerrar sucursal</button></div>}</>}
      {action && !pending && <form key={action + (b?.updatedAt ?? '')} className="tenant-confirmation branch-form" onSubmit={e => { e.preventDefault(); submit(e.currentTarget) }}><h4>{labels[action]}</h4>{(action === 'create' || action === 'edit') && <><label htmlFor="branch-code">Código de sucursal</label><input id="branch-code" name="code" required maxLength={action === 'edit' && !detail?.codeEditable ? undefined : 40} defaultValue={action === 'edit' ? b?.code : ''} readOnly={action === 'edit' && !detail?.codeEditable} disabled={busy} /><p>1–40 letras o números ASCII, espacios, punto, guion o guion bajo. Un código con referencias operativas se conserva.</p><label htmlFor="branch-name">Nombre de sucursal</label><input id="branch-name" name="name" required maxLength={200} defaultValue={action === 'edit' ? b?.name : ''} disabled={busy} /></>}
        {action === 'close' && <p role="alert">El cierre es terminal. Se revocarán los contextos de esta sucursal y se conservará el historial.</p>}{action === 'suspend' && <p>Se revocarán solo los contextos de esta sucursal. Las otras sucursales y empresas se conservan.</p>}
        <label htmlFor="branch-password">Confirmar contraseña para sucursal</label><input id="branch-password" name="password" type="password" autoComplete="current-password" required maxLength={1024} disabled={busy} /><div className="tenant-actions"><button className="button primary" disabled={busy}>Confirmar cambio de sucursal</button><button className="button" type="button" disabled={busy} onClick={() => setAction(null)}>Cancelar</button></div>
      </form>}
      {detail && <><button className="button" disabled={busy} onClick={() => setShowModules(!showModules)}>Configuración por módulos</button>{showModules && <BranchModuleConfiguration key={b!.id} tenantId={tenant} branchId={b!.id} branchStatus={b!.status} />}<h4>Historial de sucursal</h4><ol className="tenant-history">{detail.history.map(change => <li key={change.requestId}><strong>{change.action} · {change.previousStatus ? states[change.previousStatus] + ' → ' : ''}{states[change.branch.status]}</strong><small>{change.previousCode ?? 'Nueva'} → {change.branch.code} · {change.branch.name}</small><small>{new Date(change.createdAt).toLocaleString('es-CL')}</small><small>Referencia: {change.requestId}</small></li>)}</ol></>}
    </section>}
    </div>}
  </section>
}
