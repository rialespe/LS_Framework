import { useEffect, useRef, useState } from 'react'
import { coreApi, CoreFailure } from './coreApi'
import type { TenantUserDetail, TenantUserPage, TenantUserResult } from './coreApi'

const storageKey = 'ls.core.pending-user'
const otherPending = ['registration', 'lifecycle', 'administrator', 'branch', 'configuration']
type Delivery = { password: string; expires: string | null }

export function TenantUsers({ tenantId }: { tenantId: string }) {
  const [data, setData] = useState<TenantUserPage | null>(null)
  const [detail, setDetail] = useState<TenantUserDetail | null>(null)
  const [page, setPage] = useState(0)
  const [form, setForm] = useState<'create' | 'activate' | 'reissue' | null>(null)
  const [delivery, setDelivery] = useState<Delivery | null>(null)
  const [pending, setPending] = useState(() => sessionStorage.getItem(storageKey))
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const working = useRef(false)
  const mounted = useRef(true)
  const deliveryAllowed = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])
  useEffect(() => {
    let cancelled = false
    void coreApi.tenantUsers(tenantId, page).then(value => { if (!cancelled) { setData(value) } }).catch(() => { if (!cancelled) { setData(null); setMessage('No se pudo verificar la autoridad para esta empresa.') } })
    return () => { cancelled = true }
  }, [tenantId, page])
  // Closing, switching workspace, hiding the page or leaving it destroys the only delivery view.
  useEffect(() => {
    const hide = () => { if (document.visibilityState === 'hidden') { deliveryAllowed.current = false; setDelivery(null) } }
    const leave = () => { deliveryAllowed.current = false; setDelivery(null) }
    document.addEventListener('visibilitychange', hide)
    window.addEventListener('pagehide', leave)
    return () => { document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', leave) }
  }, [])
  const run = async (work: () => Promise<void>) => {
    if (working.current) { return }
    working.current = true; setBusy(true)
    try { await work() } catch (error) {
      if (mounted.current) { setDelivery(null); setData(null); setDetail(null); setMessage(error instanceof CoreFailure && error.status === 401 ? 'La sesión expiró. Inicia sesión nuevamente.' : 'Consulta no disponible. Revalida la autoridad.') }
    } finally { working.current = false; if (mounted.current) { setBusy(false) } }
  }
  const reload = async () => { const value = await coreApi.tenantUsers(tenantId, page); if (mounted.current) { setData(value) } }
  const apply = async (result: TenantUserResult, reference: string, delivered: boolean) => {
    if (!mounted.current) { result.temporaryPassword = null; return }
    setForm(null)
    if (result.correlation !== reference || result.outcome === 'UNKNOWN') { result.temporaryPassword = null; setMessage('Resultado UNKNOWN. Consulta la referencia antes de otra operación.'); return }
    if (result.outcome === 'CONFIRMED' && result.change) {
      sessionStorage.removeItem(storageKey); setPending(null)
      setMessage('Operación confirmada por PostgreSQL. La membresía requiere canje F04 y activación administrativa; no concede permisos automáticos.')
      if (delivered && deliveryAllowed.current && document.visibilityState !== 'hidden' && result.temporaryPassword) { setDelivery({ password: result.temporaryPassword, expires: result.change.temporaryExpiresAt }) }
      result.temporaryPassword = null
      await reload()
      const value = await coreApi.tenantUser(tenantId, result.change.membershipId)
      if (mounted.current) { setDetail(value) }
    } else {
      result.temporaryPassword = null; sessionStorage.removeItem(storageKey); setPending(null)
      setMessage(result.outcome === 'CONFLICT' ? 'Alta u operación no disponible. No se vinculó una identidad existente.' : result.outcome === 'INCOMPLETE_REQUIREMENTS' ? 'Completa F04 y verifica el estado de la empresa y membresía.' : 'Operación rechazada o servicio no disponible. Revalida tus datos y autoridad.')
      await reload()
    }
  }
  const submit = (element: HTMLFormElement) => {
    const values = new FormData(element)
    const password = String(values.get('password'))
    const input = { username: String(values.get('username') ?? ''), displayName: String(values.get('displayName') ?? ''), email: String(values.get('email') ?? '').trim() || null }
    element.reset()
    if (working.current || pending || !form) { return }
    if (otherPending.some(key => sessionStorage.getItem('ls.core.pending-' + key))) { setMessage('Hay otra operación pendiente de reconciliación. Consulta su referencia.'); return }
    const action = form
    const member = detail?.user.id ?? null
    const reference = crypto.randomUUID()
    sessionStorage.setItem(storageKey, reference); setPending(reference); deliveryAllowed.current = true; setDelivery(null)
    void run(async () => {
      try { await apply(await coreApi.tenantUserMutation(tenantId, action, member, reference, input, password), reference, true) }
      catch { if (mounted.current) { setForm(null); setMessage('Entrega o COMMIT sin confirmar. Conserva la referencia y consulta el resultado. No repitas el alta.') } }
    })
  }
  return <section aria-label="Usuarios de empresa">
    <div className="tenant-toolbar"><div><p className="eyebrow">EMPRESAS / USUARIOS</p><h3>Usuarios</h3><p>Identidad global y membresía exclusiva de esta empresa.</p></div><button className="button" disabled={busy} onClick={() => { setDelivery(null); void run(reload) }}>Actualizar usuarios</button></div>
    {message && <p role="status" className="pilot-message">{message}</p>}
    {pending && <div className="tenant-notice"><h4>Operación sin reconciliar</h4><p>Referencia: <code>{pending}</code></p><button className="button" disabled={busy} onClick={() => { void run(async () => { try { await apply(await coreApi.tenantUserOperation(pending), pending, false) } catch (error) { if (error instanceof CoreFailure && error.status === 404) { setMessage('Todavía no hay un recibo confirmado. Conserva la referencia; no repitas el comando.') } else { throw error } } }) }}>Consultar resultado de incorporación</button></div>}
    {delivery && <div className="tenant-confirmation" role="region" aria-label="Entrega única de contraseña temporal"><h4>Contraseña temporal · entrega única</h4><p>Compártela por un medio seguro con su titular. Debe cambiarla antes del primer acceso ordinario.</p><p>Válida hasta {delivery.expires ? new Date(delivery.expires).toLocaleString('es-CL') : '72 horas como máximo'}.</p><output className="temporary-password">{delivery.password}</output><div className="tenant-actions"><button className="button" onClick={() => { void navigator.clipboard.writeText(delivery.password).then(() => setMessage('Contraseña copiada. Elimina la copia del portapapeles después de entregarla.')).catch(() => setMessage('No se pudo copiar. Puedes transcribirla durante esta visualización.')) }}>Copiar contraseña temporal</button><button className="button primary" onClick={() => setDelivery(null)}>Cerrar entrega única</button></div><p>Al cerrar o abandonar esta vista no podrás recuperarla. El historial nunca contiene la contraseña.</p></div>}
    {data && <><div className="tenant-table-wrap"><table className="tenant-table"><thead><tr><th>Usuario</th><th>Identidad</th><th>Membresía</th><th>Incorporación</th><th>Detalle</th></tr></thead><tbody>{data.users.map(user => <tr key={user.id}><td><strong>{user.displayName}</strong><small>{user.username}</small></td><td>{user.identityStatus}</td><td>{user.membershipStatus}</td><td>{new Date(user.createdAt).toLocaleString('es-CL')}</td><td><button className="button" disabled={busy} onClick={() => { setDelivery(null); setForm(null); void run(async () => { const value = await coreApi.tenantUser(tenantId, user.id); if (mounted.current) { setDetail(value) } }) }}>Ver {user.username}</button></td></tr>)}</tbody></table>{data.users.length === 0 && <p>No hay usuarios en esta página.</p>}</div><div className="tenant-actions"><button className="button" disabled={busy || page === 0} onClick={() => { setDelivery(null); setPage(page - 1) }}>Anterior</button><span>Página {page + 1}</span><button className="button" disabled={busy || !data.hasMore} onClick={() => { setDelivery(null); setPage(page + 1) }}>Siguiente</button>{data.canManage && <button className="button primary" disabled={busy || !!pending || !!delivery} onClick={() => { setForm('create'); setDetail(null) }}>Nuevo usuario</button>}</div></>}
    {detail && <article className="preview-panel tenant-detail" aria-label="Detalle de membresía"><h4>{detail.user.displayName}</h4><dl><dt>Username</dt><dd>{detail.user.username}</dd><dt>Identidad / membresía</dt><dd>{detail.user.identityStatus} / {detail.user.membershipStatus}</dd><dt>Correo</dt><dd>{detail.user.email ?? 'No informado'} · {detail.user.emailVerified ? 'Verificado' : 'Sin verificar'}</dd><dt>Estado F04</dt><dd>{detail.user.f04Ready ? 'Credencial definitiva vigente' : detail.user.mustChangePassword ? 'Cambio obligatorio pendiente' : 'Requisitos incompletos'}</dd><dt>Creado por</dt><dd>{detail.user.createdBy ?? 'Sin registro en el modelo anterior'}</dd></dl><p>La membresía no asigna sucursales, roles, perfiles ni permisos.</p>
      {detail.canManage && detail.user.managedIncorporation && detail.user.membershipStatus === 'PENDING' && <div className="tenant-actions"><button className="button" disabled={busy || !!pending || !detail.user.f04Ready || !!delivery} onClick={() => setForm('activate')}>Activar membresía tras F04</button>{detail.user.identityStatus === 'PENDING' && <button className="button" disabled={busy || !!pending || !!delivery} onClick={() => setForm('reissue')}>Reemitir temporal por entrega fallida</button>}</div>}
      <h4>Historial administrativo</h4><ol className="tenant-history">{detail.history.map(change => <li key={change.requestId}><strong>{change.action} · {change.resultingStatus}</strong><small>{new Date(change.createdAt).toLocaleString('es-CL')} · Actor {change.actorId}</small><small>Referencia: {change.requestId}</small></li>)}</ol></article>}
    {form && !pending && <form className="tenant-confirmation" onSubmit={e => { e.preventDefault(); submit(e.currentTarget) }}><h4>{form === 'create' ? 'Nuevo usuario' : form === 'activate' ? 'Activar membresía' : 'Reemitir contraseña temporal'}</h4>
      {form === 'create' && <><label htmlFor="member-name">Nombre completo</label><input id="member-name" name="displayName" required maxLength={200} disabled={busy} /><label htmlFor="member-user">Username</label><input id="member-user" name="username" required maxLength={200} autoComplete="off" disabled={busy} /><label htmlFor="member-email">Correo opcional</label><input id="member-email" name="email" type="email" maxLength={320} disabled={busy} /><p>Solo identidades nuevas. No se vinculan cuentas existentes ni se envían invitaciones.</p></>}
      {form === 'reissue' && <p>Emite una contraseña nueva e invalida la anterior. No recupera la contraseña perdida. Solo es posible antes del canje F04.</p>}
      <label htmlFor="member-password">Confirmar contraseña administrativa</label><input id="member-password" name="password" type="password" autoComplete="current-password" required maxLength={1024} disabled={busy} /><div className="tenant-actions"><button className="button primary" disabled={busy}>Confirmar {form === 'create' ? 'alta de usuario' : form === 'activate' ? 'activación' : 'reemisión'}</button><button type="button" className="button" disabled={busy} onClick={() => setForm(null)}>Cancelar</button></div></form>}
  </section>
}

export function TenantUsersWorkspace() {
  const [companies, setCompanies] = useState<{ id: string; name: string; status: string }[]>([])
  const [selected, setSelected] = useState('')
  const [message, setMessage] = useState('')
  useEffect(() => { let cancelled = false; void coreApi.tenantUserTenants().then(values => { if (!cancelled) { setCompanies(values) } }).catch(() => { if (!cancelled) { setMessage('No se pudo comprobar la autoridad sobre empresas.') } }); return () => { cancelled = true } }, [])
  return <section className="preview-panel tenant-users-workspace"><h2>Empresas → Usuarios</h2>{message && <p role="status">{message}</p>}<label htmlFor="user-company">Empresa autorizada</label><select id="user-company" value={selected} onChange={e => setSelected(e.target.value)}><option value="">Seleccionar empresa</option>{companies.map(company => <option key={company.id} value={company.id}>{company.name} · {company.status}</option>)}</select>{selected && <TenantUsers key={selected} tenantId={selected} />}</section>
}

export function TemporaryPasswordExchange() {
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const working = useRef(false)
  return <form className="access-panel tenant-exchange" onSubmit={e => {
    e.preventDefault(); const values = new FormData(e.currentTarget); e.currentTarget.reset()
    if (working.current) { return }; working.current = true; setBusy(true)
    void coreApi.exchangeTemporary(String(values.get('username')), String(values.get('temporary')), String(values.get('definitive'))).then(() => setMessage('Canje F04 confirmado. Identidad activa. El administrador debe activar tu membresía; no tienes permisos automáticos.')).catch(() => setMessage('Canje no disponible o sin confirmación. No lo repitas automáticamente; verifica el acceso o solicita asistencia.')).finally(() => { working.current = false; setBusy(false) })
  }}><h2>Cambiar contraseña temporal</h2><p>Contraseña definitiva: mínimo 8 caracteres con letras, números y símbolos. Vigencia de 90 días.</p><label htmlFor="exchange-user">Username del titular</label><input id="exchange-user" name="username" autoComplete="username" required maxLength={200} disabled={busy} /><label htmlFor="exchange-temporary">Contraseña temporal recibida</label><input id="exchange-temporary" name="temporary" type="password" autoComplete="off" required maxLength={1024} disabled={busy} /><label htmlFor="exchange-definitive">Nueva contraseña definitiva</label><input id="exchange-definitive" name="definitive" type="password" autoComplete="new-password" required minLength={8} maxLength={1024} disabled={busy} /><button className="button" disabled={busy}>Completar canje F04</button>{message && <p role="status">{message}</p>}</form>
}

/** A session may still read its own receipt after losing enterprise authority. */
export function TenantUserReceipt() {
  const [reference] = useState(() => sessionStorage.getItem(storageKey))
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [confirmed, setConfirmed] = useState(false)
  const working = useRef(false)
  if (!reference) { return null }
  return <section className="preview-panel" aria-label="Reconciliación de incorporación"><h2>Recibo de incorporación</h2><p>Referencia: <code>{reference}</code></p><p>Inicia sesión con el actor original para consultar su recibo. La consulta no entrega contraseñas ni restituye autoridad empresarial.</p><button className="button" disabled={busy || confirmed} onClick={() => {
    if (working.current) { return }; working.current = true; setBusy(true)
    void coreApi.tenantUserOperation(reference).then(result => {
      result.temporaryPassword = null
      if (result.outcome === 'CONFIRMED' && result.correlation === reference && result.change) {
        sessionStorage.removeItem(storageKey); setConfirmed(true)
        setMessage('Incorporación confirmada. Conserva el recibo y solicita a un administrador vigente que revise la entrega y el estado de membresía.')
      } else { setMessage('Resultado sin confirmar. Conserva la referencia; no repitas el comando.') }
    }).catch(() => setMessage('Recibo no disponible. Verifica la sesión del actor original y conserva la referencia.')).finally(() => { working.current = false; setBusy(false) })
  }}>Consultar recibo del actor original</button>{message && <p role="status">{message}</p>}</section>
}
