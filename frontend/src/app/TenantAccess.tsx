import { useCallback, useEffect, useRef, useState } from 'react'
import { coreApi } from './coreApi'
import type { TenantAccessSnapshot, TenantAccessResult } from './coreApi'

const storageKey = 'ls.core.pending-access'
const eventName = 'ls-core-access-pending'
function pendingChanged() { window.dispatchEvent(new Event(eventName)) }
function usePending() {
  const [pending, setPending] = useState(() => sessionStorage.getItem(storageKey))
  useEffect(() => { const sync = () => setPending(sessionStorage.getItem(storageKey)); window.addEventListener(eventName, sync); return () => window.removeEventListener(eventName, sync) }, [])
  return pending
}

export function TenantAccess({ tenantId, membershipId, onChanged }: { tenantId: string; membershipId: string; onChanged?: () => void }) {
  const [data, setData] = useState<TenantAccessSnapshot | null>(null)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [choice, setChoice] = useState<{ branch: string | null; action: 'suspend' | 'reactivate'; expected: string; name: string } | null>(null)
  const pending = usePending()
  const working = useRef(false)
  const mounted = useRef(false)
  const generation = useRef(0)
  const reload = useCallback(async () => {
    const current = ++generation.current
    const value = await coreApi.tenantAccess(tenantId, membershipId)
    if (value.membershipId !== membershipId) { throw new Error('Resource unavailable') }
    if (current === generation.current) { setData(value) }
  }, [tenantId, membershipId])
  useEffect(() => { const lifetime = generation; mounted.current = true; void reload().catch(() => { if (mounted.current) { setMessage('No se pudieron verificar los accesos. Revalida sesión y autoridad.') } }); return () => { mounted.current = false; lifetime.current++ } }, [reload])
  const apply = async (result: TenantAccessResult, reference: string) => {
    if (!mounted.current) { return }
    setChoice(null)
    if (result.correlation !== reference || result.outcome === 'UNKNOWN' || result.outcome === 'CONFIRMED' && !result.receipt) {
      setMessage('Resultado UNKNOWN o sin recibo. Conserva la referencia y consulta el resultado antes de repetir.'); return
    }
    sessionStorage.removeItem(storageKey); pendingChanged()
    setMessage(result.outcome === 'CONFIRMED' ? 'Cambio confirmado por PostgreSQL. La reactivación requiere seleccionar un nuevo contexto; no recupera los anteriores.'
      : result.outcome === 'CONFLICT' ? 'Conflicto: actualiza el detalle. Retira previamente cualquier designación administrativa mediante V24.'
        : 'Cambio rechazado o no disponible. Verifica F04, autorización y estados de empresa, membresía y sucursal.')
    await reload(); if (mounted.current) { onChanged?.() }
  }
  const run = async (work: () => Promise<void>) => {
    if (working.current) { return }
    working.current = true; setBusy(true)
    try { await work() } catch { if (mounted.current) { setMessage('Resultado sin confirmar. Conserva la referencia y consulta el recibo. No repitas automáticamente.') } }
    finally { working.current = false; if (mounted.current) { setBusy(false) } }
  }
  const submit = (form: HTMLFormElement) => {
    const password = String(new FormData(form).get('password') ?? ''); form.reset()
    if (!choice || working.current || sessionStorage.getItem(storageKey)) { return }
    const selected = choice
    const reference = crypto.randomUUID()
    sessionStorage.setItem(storageKey, reference); pendingChanged(); setChoice(null)
    void run(async () => { await apply(await coreApi.tenantAccessMutation(tenantId, membershipId, selected.branch, selected.action, reference, selected.expected, password), reference) })
  }
  return <section aria-label="Accesos individuales"><h4>Acceso empresarial y por sucursal</h4>
    {message && <p role="status">{message}</p>}
    {pending && <div className="tenant-notice"><p>Operación pendiente: <code>{pending}</code></p><button className="button" disabled={busy} onClick={() => { void run(async () => { await apply(await coreApi.tenantAccessOperation(pending), pending) }) }}>Reconciliar cambio de acceso</button></div>}
    <button className="button" disabled={busy} onClick={() => { void run(reload) }}>Actualizar accesos</button>
    {data && <><dl><dt>Empresa</dt><dd>{data.tenantStatus}</dd><dt>Membresía empresarial</dt><dd>{data.status}</dd><dt>F04 del titular</dt><dd>{data.f04Ready ? 'Vigente' : 'Requisitos incompletos'}</dd><dt>Contextos históricos revocados</dt><dd>{data.revokedContexts}</dd></dl>
      {data.globalOwner && <p>El OWNER global está protegido.</p>}
      {data.administrativeDesignation && <p>Retira primero la designación administrativa mediante Administradores. V24 protege al último administrador válido.</p>}
      {data.canManage && !data.globalOwner && !data.administrativeDesignation && ['ACTIVE', 'SUSPENDED'].includes(data.status) && <button className="button" disabled={busy || !!pending || data.status === 'SUSPENDED' && (data.tenantStatus !== 'ACTIVE' || !data.f04Ready)} onClick={() => setChoice({ branch: null, action: data.status === 'ACTIVE' ? 'suspend' : 'reactivate', expected: data.updatedAt, name: 'membresía empresarial' })}>{data.status === 'ACTIVE' ? 'Suspender membresía' : 'Reactivar membresía'}</button>}
      <h4>Asignaciones de sucursal</h4>{data.branches.length === 0 ? <p>Sin asignaciones de sucursal. Consultar o reactivar no crea accesos.</p> : <div className="tenant-table-wrap"><table className="tenant-table"><thead><tr><th>Sucursal</th><th>Estado global</th><th>Acceso individual</th><th>Contextos revocados</th><th>Acción</th></tr></thead><tbody>{data.branches.map(branch => <tr key={branch.id}><td>{branch.name}</td><td>{branch.branchStatus}</td><td>{branch.accessStatus}</td><td>{branch.revokedContexts}</td><td>{data.canManage && !data.globalOwner && ['ACTIVE', 'SUSPENDED'].includes(branch.accessStatus) && <button className="button" disabled={busy || !!pending || branch.accessStatus === 'SUSPENDED' && (data.status !== 'ACTIVE' || data.tenantStatus !== 'ACTIVE' || branch.branchStatus !== 'ACTIVE' || !data.f04Ready)} onClick={() => setChoice({ branch: branch.id, action: branch.accessStatus === 'ACTIVE' ? 'suspend' : 'reactivate', expected: branch.updatedAt, name: branch.name })}>{branch.accessStatus === 'ACTIVE' ? 'Suspender acceso a ' : 'Reactivar acceso a '}{branch.name}</button>}</td></tr>)}</tbody></table></div>}
      <p>La reactivación conserva permisos y evidencia histórica. Debes seleccionar un contexto nuevo para operar.</p>
      <h4>Historial de accesos · últimas 50 operaciones</h4><ol className="tenant-history">{data.history.map(receipt => <li key={receipt.requestId}><strong>{receipt.action} · {receipt.previousStatus} → {receipt.resultingStatus}</strong><small>{new Date(receipt.createdAt).toLocaleString('es-CL')} · Actor {receipt.actorId}</small><small>Contextos revocados: {receipt.revokedContexts} · Referencia: {receipt.requestId}</small></li>)}</ol>
      {choice && !pending && <form key={choice.branch + choice.action} className="tenant-confirmation" onSubmit={event => { event.preventDefault(); submit(event.currentTarget) }}><h4>Confirmar {choice.action === 'suspend' ? 'suspensión' : 'reactivación'} de {choice.name}</h4><p>{choice.action === 'suspend' ? 'Se revocarán únicamente los contextos afectados por este acceso.' : 'Los contextos revocados permanecerán revocados. No se crearán permisos nuevos.'}</p><label>Contraseña F04 nueva para confirmar<input name="password" type="password" autoComplete="current-password" required maxLength={1024} disabled={busy} /></label><div className="tenant-actions"><button className="button primary" disabled={busy}>Confirmar cambio de acceso</button><button className="button" type="button" disabled={busy} onClick={() => setChoice(null)}>Cancelar</button></div></form>}
    </>}
  </section>
}

export function TenantAccessReceipt() {
  const pending = usePending()
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const working = useRef(false)
  if (!pending) { return null }
  return <section className="preview-panel"><h3>Recibo pendiente de cambio de acceso</h3><p>Inicia sesión con el actor original y consulta la referencia <code>{pending}</code>.</p><button className="button" disabled={busy} onClick={() => {
    if (working.current) { return }; working.current = true; setBusy(true)
    void coreApi.tenantAccessOperation(pending).then(result => {
      if (result.correlation === pending && result.outcome === 'CONFIRMED' && result.receipt) { sessionStorage.removeItem(storageKey); pendingChanged(); setMessage('Cambio de acceso confirmado. Actualiza el detalle para comprobar el estado vigente.') }
      else { setMessage('Sin confirmación. Conserva la referencia; no repitas la operación.') }
    }).catch(() => setMessage('Recibo no disponible para esta sesión. Conserva la referencia; un 404 no demuestra rollback.')).finally(() => { working.current = false; setBusy(false) })
  }}>Consultar recibo de acceso</button>{message && <p role="status">{message}</p>}</section>
}
