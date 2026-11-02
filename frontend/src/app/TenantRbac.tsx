import { useCallback, useEffect, useRef, useState } from 'react'
import { coreApi } from './coreApi'
import type { RbacAction, RbacApplication, RbacInput, RbacResource, RbacResult, RbacWorkspace } from './coreApi'

const key = 'ls.core.pending-rbac'
const eventName = 'ls-core-rbac-pending'
const notify = () => window.dispatchEvent(new Event(eventName))
const emptyInput = (): RbacInput => ({ resourceId: null, templateId: null, expectedRevision: null, code: null, name: null, description: null, permissionIds: [], roleIds: [] })
function usePending() {
  const [pending, setPending] = useState(() => sessionStorage.getItem(key))
  useEffect(() => { const sync = () => setPending(sessionStorage.getItem(key)); window.addEventListener(eventName, sync); return () => window.removeEventListener(eventName, sync) }, [])
  return pending
}

export function TenantRbac() {
  const [companies, setCompanies] = useState<{ id: string; name: string; status: string }[]>([])
  const [tenant, setTenant] = useState('')
  const [apps, setApps] = useState<RbacApplication[]>([])
  const [app, setApp] = useState('')
  const [message, setMessage] = useState('')
  useEffect(() => { let cancelled = false; void coreApi.rbacTenants().then(value => { if (!cancelled) { setCompanies(value) } }).catch(() => { if (!cancelled) { setMessage('No se pudo comprobar la autoridad RBAC de esta sesión.') } }); return () => { cancelled = true } }, [])
  useEffect(() => {
    if (!tenant) { return }
    let cancelled = false
    void coreApi.rbacApplications(tenant).then(value => { if (!cancelled) { setApps(value) } }).catch(() => { if (!cancelled) { setMessage('Aplicaciones no disponibles para esta empresa.') } })
    return () => { cancelled = true }
  }, [tenant])
  return <section className="preview-panel"><h2>Empresas → Roles y perfiles</h2><p>Catálogo global publicado y composición empresarial dentro del límite de delegación de la plataforma. No asigna perfiles a personas o sucursales.</p>
    {message && <p role="status">{message}</p>}
    <label>Empresa RBAC autorizada<select value={tenant} onChange={event => { setTenant(event.target.value); setApp(''); setApps([]); setMessage('') }}><option value="">Seleccionar empresa</option>{companies.map(company => <option key={company.id} value={company.id}>{company.name}</option>)}</select></label>
    {tenant && <label>Aplicación empresarial<select value={app} onChange={event => setApp(event.target.value)}><option value="">Seleccionar aplicación</option>{apps.map(application => <option key={application.id} value={application.id}>{application.name} · {application.status}</option>)}</select></label>}
    {tenant && apps.length === 0 && <p>Sin aplicaciones disponibles. La plataforma debe habilitar la aplicación y autorizar su administración.</p>}
    {tenant && app && <RbacEditor key={tenant + app} tenant={tenant} application={apps.find(value => value.id === app)!} />}
  </section>
}

export function RbacEditor({ tenant, application }: { tenant: string; application: RbacApplication }) {
  const [data, setData] = useState<RbacWorkspace | null>(null)
  const [tab, setTab] = useState<'catalog' | 'roles' | 'profiles'>('catalog')
  const [form, setForm] = useState<{ action: RbacAction; input: RbacInput } | null>(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const pending = usePending()
  const working = useRef(false)
  const mounted = useRef(false)
  const reload = useCallback(async () => { const value = await coreApi.rbacWorkspace(tenant, application.id); if (mounted.current) { setData(value) } }, [tenant, application.id])
  useEffect(() => { mounted.current = true; void reload().catch(() => { if (mounted.current) { setData(null); setMessage('No se pudo verificar el catálogo empresarial. Revalida sesión y delegación.') } }); return () => { mounted.current = false } }, [reload])
  const run = async (work: () => Promise<void>) => {
    if (working.current) { return }
    working.current = true; setBusy(true)
    try { await work() }
    catch { if (mounted.current) { setForm(null); setMessage('Entrega o COMMIT sin confirmar. Conserva la referencia y consulta el resultado; no repitas automáticamente.') } }
    finally { working.current = false; if (mounted.current) { setBusy(false) } }
  }
  const apply = async (result: RbacResult, reference: string) => {
    if (!mounted.current) { return }
    setForm(null)
    if (result.correlation !== reference || result.outcome === 'UNKNOWN' || result.outcome === 'CONFIRMED' && !result.receipt) { setMessage('Resultado UNKNOWN. Consulta el recibo antes de otra mutación.'); return }
    sessionStorage.removeItem(key); notify()
    setMessage(result.outcome === 'CONFIRMED' ? 'Operación RBAC confirmada por PostgreSQL. No se crearon asignaciones de perfiles a usuarios o sucursales.'
      : result.outcome === 'CONFLICT' ? 'Conflicto de código, versión o dependencias. Actualiza el catálogo; las composiciones con asignaciones activas requieren un flujo posterior.'
        : 'Operación rechazada o no disponible. Verifica sesión, F04, facultad RBAC y permisos delegables de módulos habilitados.')
    await reload()
  }
  const submit = (element: HTMLFormElement) => {
    const password = String(new FormData(element).get('password') ?? ''); element.reset()
    if (!form || working.current || sessionStorage.getItem(key)) { return }
    const selected = form
    const reference = crypto.randomUUID()
    sessionStorage.setItem(key, reference); notify(); setForm(null)
    void run(async () => { await apply(await coreApi.rbacMutation(tenant, application.id, selected.action, reference, selected.input, password), reference) })
  }
  const choose = (action: RbacAction, resource?: RbacResource) => setForm({ action, input: resource ? {
    resourceId: resource.id, templateId: null, expectedRevision: resource.revision,
    code: action.endsWith('disable') ? null : resource.code, name: action.endsWith('disable') ? null : resource.name,
    description: action.endsWith('disable') ? null : resource.description,
    permissionIds: action === 'role-update' ? resource.permissionIds ?? [] : [], roleIds: action === 'profile-update' ? resource.roleIds ?? [] : [],
  } : emptyInput() })
  const editInput = (value: Partial<RbacInput>) => setForm(current => current ? { ...current, input: { ...current.input, ...value } } : null)
  const permissionName = (id: string) => data?.permissions.find(permission => permission.id === id)?.code ?? 'Permiso fuera del catálogo disponible'
  const roleAllowed = (role: RbacResource) => role.status === 'ACTIVE' && (role.permissionIds ?? []).every(id => data?.permissions.some(permission => permission.id === id && permission.delegable))
  const invalidSelection = form && (form.input.permissionIds.some(id => !data?.permissions.some(permission => permission.id === id && permission.delegable)) || form.input.roleIds.some(id => !data?.roles.some(role => role.id === id && roleAllowed(role))))
  const canWrite = data?.canManage && ['ACTIVE', 'READ_ONLY'].includes(application.status)
  const isRole = form?.action.startsWith('role-')
  const metadata = form && (form.action.endsWith('create') || form.action.endsWith('update'))
  const resources = tab === 'roles' ? data?.roles : data?.profiles
  return <section aria-label="Catálogos RBAC empresariales"><div className="tenant-toolbar"><h3>{application.name} · {application.status}</h3><button className="button" disabled={busy} onClick={() => { void run(reload) }}>Actualizar RBAC</button></div>
    {message && <p role="status">{message}</p>}
    {pending && <div className="tenant-notice"><p>Referencia pendiente: <code>{pending}</code></p><button className="button" disabled={busy} onClick={() => { void run(async () => { await apply(await coreApi.rbacOperation(pending), pending) }) }}>Reconciliar operación RBAC</button></div>}
    <div className="tenant-actions" role="group" aria-label="Vistas RBAC">{(['catalog', 'roles', 'profiles'] as const).map(value => <button className="button" key={value} aria-pressed={tab === value} onClick={() => { setTab(value); setForm(null) }}>{value === 'catalog' ? 'Catálogo de permisos' : value === 'roles' ? 'Roles' : 'Perfiles'}</button>)}</div>
    {data && <>
      {tab === 'catalog' && <><h4>Permisos publicados y delegación efectiva para componer roles</h4><p>Publicado no equivale a delegable. Las facultades RBAC se administran por la plataforma y no proceden de estos roles.</p><div className="tenant-table-wrap"><table className="tenant-table"><thead><tr><th>Módulo</th><th>Permiso</th><th>Ámbito</th><th>Publicación</th><th>Disponible para el tenant</th></tr></thead><tbody>{data.permissions.map(permission => <tr key={permission.id}><td>{data.modules.find(module => module.id === permission.moduleId)?.name ?? (permission.moduleId ? 'Módulo no disponible' : 'Aplicación')}<small>{data.modules.find(module => module.id === permission.moduleId)?.status}</small></td><td><strong>{permission.name}</strong><small>{permission.code}</small></td><td>{permission.scope}</td><td>{permission.status}</td><td>{permission.delegable ? 'Delegable' : 'No delegable'}<small>{permission.privilegeKind ?? 'Sin concesión de plataforma'}</small></td></tr>)}</tbody></table></div></>}
      {tab !== 'catalog' && <><div className="tenant-actions"><h4>{tab === 'roles' ? 'Roles empresariales' : 'Perfiles empresariales'}</h4>{canWrite && <button className="button primary" disabled={busy || !!pending} onClick={() => choose(tab === 'roles' ? 'role-create' : 'profile-create')}>{tab === 'roles' ? 'Crear rol personalizado' : 'Crear perfil personalizado'}</button>}</div>
        {resources?.length === 0 && <p>Sin {tab === 'roles' ? 'roles' : 'perfiles'} empresariales. No se inicializan al activar una empresa.</p>}
        {resources?.map(resource => <article className="tenant-detail" key={resource.id}><h4>{resource.name}</h4><p>{resource.code} · {resource.status} · Revisión {resource.revision}</p><p>{resource.description}</p><p>{resource.sourceTemplateId ? `Origen: plantilla · versión ${resource.sourceVersion ?? 'histórica no registrada'} · ${(tab === 'roles' ? data.roleTemplates : data.profileTemplates).find(template => template.id === resource.sourceTemplateId)?.code ?? resource.sourceTemplateId}` : 'Origen: personalizado'}</p>
          {tab === 'roles' ? <ul>{resource.permissionIds?.map(id => <li key={id}>{permissionName(id)}</li>)}</ul> : <><p>Composición declarada por rol; no representa una unión efectiva de permisos.</p><ul>{resource.roleIds?.map(id => { const role = data.roles.find(value => value.id === id); return <li key={id}><strong>{role?.name ?? 'Rol no disponible'}</strong> · {role?.status}<small>{role?.permissionIds?.map(permissionName).join(', ') || 'Sin permisos declarados'}</small></li> })}</ul></>}
          {canWrite && resource.status === 'ACTIVE' && <div className="tenant-actions"><button className="button" disabled={busy || !!pending} onClick={() => choose(tab === 'roles' ? 'role-update' : 'profile-update', resource)}>Editar {resource.code}</button><button className="button" disabled={busy || !!pending} onClick={() => choose(tab === 'roles' ? 'role-disable' : 'profile-disable', resource)}>Deshabilitar {resource.code}</button></div>}
        </article>)}
        <h4>Plantillas globales aprobadas</h4><ul>{(tab === 'roles' ? data.roleTemplates : data.profileTemplates).map(template => <li key={template.id}><strong>{template.name}</strong> · {template.code} v{template.version} · {template.delegable ? 'Delegable' : 'Publicado, no delegable'}<small>{tab === 'roles' ? template.items.map(permissionName).join(', ') : template.items.map(id => data.roleTemplates.find(role => role.id === id)?.name ?? 'Plantilla de rol no disponible').join(', ')}</small>{canWrite && <button className="button" disabled={busy || !!pending || !template.delegable} onClick={() => setForm({ action: tab === 'roles' ? 'role-template' : 'profile-template', input: { ...emptyInput(), templateId: template.id } })}>Inicializar {template.code} v{template.version}</button>}</li>)}</ul>
      </>}
      {form && !pending && <form key={form.action + form.input.resourceId + form.input.templateId} className="tenant-confirmation" onSubmit={event => { event.preventDefault(); submit(event.currentTarget) }}><h4>Confirmar {form.action.endsWith('disable') ? 'deshabilitación' : form.action.endsWith('template') ? 'inicialización de plantilla' : isRole ? 'rol empresarial' : 'perfil empresarial'}</h4>
        {form.input.resourceId && <p>Recurso: {[...data.roles, ...data.profiles].find(resource => resource.id === form.input.resourceId)?.name} · Revisión esperada {form.input.expectedRevision}</p>}
        {form.input.templateId && <p>Plantilla: {[...data.roleTemplates, ...data.profileTemplates].find(template => template.id === form.input.templateId)?.name} · {form.input.templateId}</p>}
        {metadata && <><label>Código<input value={form.input.code ?? ''} required maxLength={80} disabled={busy} onChange={event => editInput({ code: event.target.value })} /></label><label>Nombre<input value={form.input.name ?? ''} required maxLength={200} disabled={busy} onChange={event => editInput({ name: event.target.value })} /></label><label>Descripción<textarea value={form.input.description ?? ''} maxLength={2000} disabled={busy} onChange={event => editInput({ description: event.target.value || null })} /></label>
          <fieldset disabled={busy}><legend>{isRole ? 'Permisos delegables del rol' : 'Roles empresariales del perfil'}</legend>{isRole ? data.permissions.map(permission => <label key={permission.id}><input type="checkbox" checked={form.input.permissionIds.includes(permission.id)} disabled={!permission.delegable && !form.input.permissionIds.includes(permission.id)} onChange={event => editInput({ permissionIds: event.target.checked ? [...form.input.permissionIds, permission.id] : form.input.permissionIds.filter(id => id !== permission.id) })} />{permission.code} · {permission.scope} · {permission.delegable ? 'Delegable' : 'No delegable'}</label>)
            : data.roles.map(role => <label key={role.id}><input type="checkbox" checked={form.input.roleIds.includes(role.id)} disabled={!roleAllowed(role) && !form.input.roleIds.includes(role.id)} onChange={event => editInput({ roleIds: event.target.checked ? [...form.input.roleIds, role.id] : form.input.roleIds.filter(id => id !== role.id) })} />{role.name} · {role.status}</label>)}
            {form.input.permissionIds.filter(id => !data.permissions.some(permission => permission.id === id)).map(id => <button className="button" type="button" key={id} onClick={() => editInput({ permissionIds: form.input.permissionIds.filter(value => value !== id) })}>Retirar permiso no disponible</button>)}
          </fieldset>{invalidSelection && <p>Retira los elementos que ya no sean delegables antes de confirmar.</p>}</>}
        <p>La confirmación es transaccional y no crea asignaciones a usuarios o sucursales. Deshabilitar conserva el historial y respeta dependencias.</p><label>Contraseña F04 nueva para RBAC<input name="password" type="password" autoComplete="current-password" required maxLength={1024} disabled={busy} /></label><div className="tenant-actions"><button className="button primary" disabled={busy || !!invalidSelection}>Confirmar operación RBAC</button><button type="button" className="button" disabled={busy} onClick={() => setForm(null)}>Cancelar</button></div>
      </form>}
      <h4>Historial administrativo · últimas 50 operaciones</h4><ol className="tenant-history">{data.history.map(receipt => <li key={receipt.requestId}><strong>{receipt.action} · {receipt.result.name}</strong><small>{receipt.previous?.status ?? 'Sin estado previo'} → {receipt.result.status} · Revisión {receipt.result.revision}</small><small>{new Date(receipt.createdAt).toLocaleString('es-CL')} · Actor {receipt.actorId} · Referencia: {receipt.requestId}</small></li>)}</ol>
    </>}
  </section>
}

export function RbacReceipt() {
  const pending = usePending()
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const working = useRef(false)
  if (!pending && !message) { return null }
  return <section className="preview-panel"><h3>Recibo RBAC del actor original</h3>{pending && <><p>Referencia pendiente: <code>{pending}</code>. Inicia sesión con el actor original.</p><button className="button" disabled={busy} onClick={() => {
    if (working.current) { return }; working.current = true; setBusy(true)
    void coreApi.rbacOperation(pending).then(result => {
      if (result.correlation === pending && result.outcome === 'CONFIRMED' && result.receipt) { sessionStorage.removeItem(key); notify(); setMessage('Recibo RBAC confirmado. Revalida la autoridad para consultar el estado empresarial vigente.') }
      else { setMessage('Resultado sin confirmar. Conserva la referencia; no repitas automáticamente.') }
    }).catch(() => setMessage('Recibo no disponible para esta sesión. Un 404 no demuestra rollback; conserva la referencia.')).finally(() => { working.current = false; setBusy(false) })
  }}>Consultar recibo RBAC</button></>}{message && <p role="status">{message}</p>}</section>
}
