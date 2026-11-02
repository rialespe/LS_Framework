import { useCallback, useEffect, useRef, useState } from 'react'
import { coreApi, CoreFailure } from './coreApi'
import type { IdentityLink, IdentityLinkReceipt, IdentityLinkResult } from './coreApi'

const storageKey = 'ls.core.pending-identity-link'
const pendingEvent = 'ls-core-identity-link-pending'

/** This panel requires its own API check; enterprise authority is never inferred from the inbox. */
export function IdentityLinks({ tenantId }: { tenantId?: string }) {
  const [links, setLinks] = useState<IdentityLink[]>([])
  const [sent, setSent] = useState<IdentityLinkReceipt[]>([])
  const [ready, setReady] = useState(false)
  const [page, setPage] = useState(0)
  const [more, setMore] = useState(false)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [choice, setChoice] = useState<{ id: string; action: 'accept' | 'reject'; company: string } | null>(null)
  const [pending, setPending] = useState(() => sessionStorage.getItem(storageKey))
  const working = useRef(false)
  const mounted = useRef(false)
  const reload = useCallback(async () => {
    if (tenantId) {
      const value = await coreApi.identityLinksSent(tenantId, page)
      if (mounted.current) { setSent(value.requests); setMore(value.hasMore); setReady(true) }
    } else {
      const value = await coreApi.identityLinksReceived(page)
      if (mounted.current) { setLinks(value.requests); setMore(value.hasMore); setReady(true) }
    }
  }, [tenantId, page])
  useEffect(() => {
    mounted.current = true
    const sync = () => setPending(sessionStorage.getItem(storageKey))
    window.addEventListener(pendingEvent, sync)
    void reload().catch(() => { if (mounted.current) { setReady(false); setMessage('No se pudieron consultar las solicitudes. Verifica tu sesión LS Core y autorización.') } })
    return () => { mounted.current = false; window.removeEventListener(pendingEvent, sync) }
  }, [reload])
  const run = async (work: () => Promise<void>) => {
    if (working.current) { return }
    working.current = true; setBusy(true)
    try { await work() }
    catch { if (mounted.current) { setMessage('Operación sin confirmar. Conserva la referencia y consulta el resultado. No repitas la mutación.') } }
    finally { working.current = false; if (mounted.current) { setBusy(false) } }
  }
  const apply = async (result: IdentityLinkResult, reference: string) => {
    if (!mounted.current) { return }
    setChoice(null)
    if (result.correlation !== reference || result.outcome === 'UNKNOWN') {
      setMessage('Resultado UNKNOWN. Conserva la referencia y consulta su recibo antes de otra acción.'); return
    }
    if (result.outcome === 'CONFIRMED' && !result.receipt) { setMessage('Resultado sin recibo confirmado. Conserva la referencia.'); return }
    sessionStorage.removeItem(storageKey); window.dispatchEvent(new Event(pendingEvent))
    setMessage(result.outcome === 'CONFIRMED'
      ? result.receipt!.status === 'SUBMITTED' ? 'Solicitud procesada. Esta respuesta no confirma si existe el username ni concede acceso. Solo el titular puede consentir la vinculación.' : `Decisión confirmada por PostgreSQL: ${result.receipt!.status}. No se asignan sucursales ni permisos automáticamente.`
      : 'Operación rechazada o no disponible. Verifica F04, empresa y autoridad vigente. No se confirmó ningún cambio.')
    await reload()
  }
  const submit = (form: HTMLFormElement) => {
    const values = new FormData(form)
    const username = String(values.get('username') ?? '')
    const password = String(values.get('password') ?? '')
    form.reset()
    if (working.current || sessionStorage.getItem(storageKey)) { setMessage('Consulta primero el resultado pendiente.'); return }
    if (!tenantId && !choice) { return }
    const reference = crypto.randomUUID()
    sessionStorage.setItem(storageKey, reference); window.dispatchEvent(new Event(pendingEvent))
    const decision = choice
    setChoice(null)
    void run(async () => {
      const result = tenantId ? await coreApi.identityLinkRequest(tenantId, reference, username, password)
        : await coreApi.identityLinkDecision(decision!.id, decision!.action, reference, password)
      await apply(result, reference)
    })
  }
  return <section className="preview-panel" aria-label={tenantId ? 'Vinculación de usuario existente' : 'Mis solicitudes de vinculación'}>
    <h2>{tenantId ? 'Solicitar vinculación de usuario existente' : 'Mis solicitudes de vinculación'}</h2>
    <p>{tenantId ? 'Introduce el username. La solicitud no crea membresía ni revela si la identidad existe. No se envía correo electrónico.' : 'Cada vinculación requiere tu decisión autenticada. Puedes consultar solicitudes aunque no pertenezcas a ninguna empresa.'}</p>
    {message && <p role="status">{message}</p>}
    {pending && <div className="tenant-notice"><p>Referencia pendiente: <code>{pending}</code></p><button className="button" disabled={busy} onClick={() => { void run(async () => {
      try { await apply(await coreApi.identityLinkOperation(pending), pending) }
      catch (error) { if (error instanceof CoreFailure && error.status === 404) { setMessage('No hay recibo confirmado para esta sesión. Conserva la referencia; no repitas la operación.') } else { throw error } }
    }) }}>Consultar resultado de vinculación</button></div>}
    <button className="button" disabled={busy} onClick={() => { void run(reload) }}>Actualizar solicitudes</button>
    {ready && <>
      {tenantId ? <><h3>Solicitudes enviadas</h3><p>Los recibos enviados conservan el estado neutro SUBMITTED. No revelan destinatarios ni sus decisiones.</p><ol>{sent.map(value => <li key={value.requestId}>{value.status} · {new Date(value.createdAt).toLocaleString('es-CL')} · <code>{value.requestId}</code></li>)}</ol></>
        : <ul>{links.map(link => <li key={link.id}><strong>{link.company}</strong> · {link.status}<p>Vence: {new Date(link.expiresAt).toLocaleString('es-CL')}</p>{link.status === 'PENDING' && <div className="tenant-actions"><button className="button" disabled={busy || !!pending} onClick={() => setChoice({ id: link.id, action: 'accept', company: link.company })}>Aceptar {link.company}</button><button className="button" disabled={busy || !!pending} onClick={() => setChoice({ id: link.id, action: 'reject', company: link.company })}>Rechazar {link.company}</button></div>}</li>)}</ul>}
      {!sent.length && !links.length && <p>No hay solicitudes en esta página.</p>}
      <div className="tenant-actions"><button className="button" disabled={busy || page === 0} onClick={() => setPage(page - 1)}>Página anterior</button><span>Página {page + 1}</span><button className="button" disabled={busy || !more} onClick={() => setPage(page + 1)}>Página siguiente</button></div>
      {!pending && (tenantId || choice) && <form className="tenant-confirmation" onSubmit={event => { event.preventDefault(); submit(event.currentTarget) }}>
        <h3>{tenantId ? 'Confirmar solicitud administrativa' : `${choice!.action === 'accept' ? 'Aceptar vinculación con' : 'Rechazar vinculación con'} ${choice!.company}`}</h3>
        {tenantId && <label>Username del titular<input name="username" autoComplete="off" required maxLength={200} disabled={busy} /></label>}
        {!tenantId && <p>{choice!.action === 'accept' ? 'Confirmas tu consentimiento para crear una membresía en esta empresa. Esta acción no concede permisos operativos.' : 'La solicitud quedará rechazada sin crear membresía.'}</p>}
        <label>Confirmar tu contraseña F04<input name="password" type="password" autoComplete="current-password" required maxLength={1024} disabled={busy} /></label>
        <button className="button primary" disabled={busy}>Confirmar {tenantId ? 'solicitud' : choice!.action === 'accept' ? 'aceptación' : 'rechazo'}</button>
        {choice && <button className="button" type="button" disabled={busy} onClick={() => setChoice(null)}>Cancelar</button>}
      </form>}
    </>}
  </section>
}
