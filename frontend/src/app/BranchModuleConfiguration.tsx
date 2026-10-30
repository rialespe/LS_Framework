import { useEffect, useRef, useState } from 'react'
import { coreApi, CoreFailure } from './coreApi'
import type { ConfigurationAction, ConfigurationDetail, ConfigurationModule, ConfigurationResult, ConfigurationValues } from './coreApi'

const pendingKey = 'ls.core.pending-configuration'
const notices: Record<string, string> = {
  DENIED: 'No tienes autoridad vigente para configurar este módulo o la contraseña no es válida.',
  CONFLICT: 'La versión o el estado cambió. Revalida la configuración antes de continuar.',
  MODULE_UNAVAILABLE: 'El contrato, aplicación o módulo no está publicado o habilitado. La configuración no concede habilitaciones.',
  NOT_FOUND: 'La configuración o su recurso no está disponible.',
  INCOMPLETE_REQUIREMENTS: 'Revisa los campos y guarda un borrador válido. READY exige todos los campos requeridos.',
  UNAVAILABLE: 'Servicio no disponible. Revalida la información antes de otro intento manual.',
}
const states: Record<string, string> = { ABSENT: 'Sin configuración', DRAFT: 'Borrador', READY: 'Preparada para este contrato', RESTRICTED: 'Requiere facultad específica' }
export function BranchModuleConfiguration({ tenantId, branchId, branchStatus }: { tenantId: string; branchId: string; branchStatus: string }) {
  const [modules, setModules] = useState<ConfigurationModule[]>([])
  const [detail, setDetail] = useState<ConfigurationDetail | null>(null)
  const [busy, setBusy] = useState(true)
  const [message, setMessage] = useState('')
  const [pending, setPending] = useState<string | null>(() => sessionStorage.getItem(pendingKey))
  const working = useRef(false)
  useEffect(() => {
    let cancelled = false
    void coreApi.configurationModules(tenantId, branchId).then(values => { if (!cancelled) { setModules(values) } }).catch(() => { if (!cancelled) { setMessage('No se pudo verificar la configuración autorizada de esta sucursal.') } }).finally(() => { if (!cancelled) { setBusy(false) } })
    return () => { cancelled = true }
  }, [tenantId, branchId])
  const fail = () => { setModules([]); setDetail(null); setMessage('No se pudo verificar la autoridad o disponibilidad de la configuración.') }
  const run = async (work: () => Promise<void>) => {
    if (working.current || busy) { return }
    working.current = true; setBusy(true)
    try { await work() } catch { fail() } finally { working.current = false; setBusy(false) }
  }
  const refresh = async () => {
    setModules(await coreApi.configurationModules(tenantId, branchId))
    if (detail) { setDetail(await coreApi.configurationDetail(detail.module.scope)) }
  }
  const apply = async (result: ConfigurationResult, reference: string) => {
    if (result.correlation !== reference) { setMessage('Resultado sin confirmar. Conserva la referencia.'); return }
    if (result.outcome === 'CONFIRMED' && result.change) {
      sessionStorage.removeItem(pendingKey); setPending(null)
      setMessage('Configuración confirmada por PostgreSQL. READY no activa aplicaciones, módulos ni accesos.')
      try { setModules(await coreApi.configurationModules(tenantId, branchId)); if (result.change.configuration.scope.branchId === branchId) { setDetail(await coreApi.configurationDetail(result.change.configuration.scope)) } }
      catch { setDetail(null); setMessage('Operación confirmada; no se pudo releer la configuración. Revalida tu acceso.') }
    } else if (notices[result.outcome]) {
      sessionStorage.removeItem(pendingKey); setPending(null)
      setMessage(notices[result.outcome] + (result.missingFields.length ? ' Campos: ' + result.missingFields.join(', ') : ''))
      if (result.outcome === 'DENIED' || result.outcome === 'NOT_FOUND') { setDetail(null); setModules([]) }
    } else { setMessage('Resultado UNKNOWN o sin confirmar. Conserva la referencia y consulta el resultado antes de otra mutación.') }
  }
  const submit = (form: HTMLFormElement, validate: boolean) => {
    const inputs = new FormData(form)
    const password = String(inputs.get('password') ?? ''); (form.elements.namedItem('password') as HTMLInputElement).value = ''
    if (!detail || !password || working.current || busy || pending) { return }
    if (['ls.core.pending-user', 'ls.core.pending-registration', 'ls.core.pending-lifecycle', 'ls.core.pending-administrator', 'ls.core.pending-branch'].some(key => sessionStorage.getItem(key))) { setMessage('Hay otra operación crítica sin reconciliar. Consulta su referencia antes de continuar.'); return }
    const values: ConfigurationValues = {}
    if (!validate) {
      for (const field of detail.module.fields) {
        const raw = String(inputs.get(field.key) ?? '')
        if (raw === '') { continue }
        values[field.key] = field.type === 'INTEGER' ? Number(raw) : field.type === 'BOOLEAN' ? raw === 'true' : raw
      }
    }
    const action: ConfigurationAction = validate ? 'validate' : detail.configuration ? 'update' : 'prepare'
    if (!window.confirm(validate ? '¿Validar el último borrador guardado para este contrato?' : '¿Guardar estos valores como borrador de configuración?')) { return }
    const reference = crypto.randomUUID(); setPending(reference); sessionStorage.setItem(pendingKey, reference)
    void run(async () => {
      try { await apply(await coreApi.configurationMutation(detail.module.scope, action, reference, detail.configuration?.revision ?? null, values, password), reference) }
      catch (error) {
        if (error instanceof CoreFailure && [400, 401, 403, 404].includes(error.status)) { sessionStorage.removeItem(pendingKey); setPending(null); fail() }
        else { setMessage('No se confirmó el resultado. Consulta la referencia; no repitas la mutación.') }
      }
    })
  }
  const canWrite = detail?.module.canManage && detail.module.available && detail.module.contextActive && branchStatus === 'ACTIVE'
  return <section className="branch-module-configuration" aria-label="Configuración por módulos"><div className="tenant-toolbar"><div><h4>Configuración por módulos</h4><p>Los datos generales no requieren información especializada. Cada contrato es independiente y versionado.</p></div><button className="button" disabled={busy} onClick={() => { void run(refresh) }}>Revalidar módulos</button></div>
    {message && <p role="status" className="pilot-message">{message}</p>}{busy && <p role="status">Consultando contratos autorizados…</p>}
    {pending && <div className="tenant-notice"><p>Configuración pendiente. Referencia: <code>{pending}</code></p><button className="button" disabled={busy} onClick={() => { void run(async () => { try { await apply(await coreApi.configurationOperation(pending), pending) } catch (error) { if (error instanceof CoreFailure && error.status === 404) { setMessage('No hay confirmación disponible. Conserva la referencia y no repitas la operación.') } else { throw error } } }) }}>Consultar resultado de configuración</button></div>}
    {!busy && !modules.length && !message && <div className="tenant-notice"><strong>No hay módulos especializados configurables.</strong><p>La sucursal conserva sus datos generales. LS Fiscal y LS POS requieren catálogos y contratos aprobados antes de mostrar campos especializados.</p></div>}
    {modules.map(module => <article className="module-configuration-card" key={module.scope.applicationId + module.scope.moduleId + module.scope.version}><h5>{module.applicationName} / {module.moduleName}</h5><p>Contrato v{module.scope.version} · {module.contractStatus}</p><p>{states[module.readiness] ?? module.readiness}</p><p>Aplicación publicada: {module.applicationPublished ? 'Sí' : 'No'} · Módulo publicado: {module.modulePublished ? 'Sí' : 'No'} · Aplicación habilitada: {module.applicationEnabled ? 'Sí' : 'No'} · Módulo habilitado: {module.moduleEnabled ? 'Sí' : 'No'}</p>{module.canRead && <button className="button" disabled={busy} onClick={() => { void run(async () => { setDetail(await coreApi.configurationDetail(module.scope)) }) }}>Consultar configuración {module.moduleName}</button>}</article>)}
    {detail && <section aria-label="Contrato de configuración"><h5>{detail.module.moduleName} · versión {detail.module.scope.version}</h5><p>{detail.configuration ? states[detail.configuration.readiness] + ' · revisión ' + detail.configuration.revision : 'Sin configuración persistida'}</p><p>Preparada no equivale a habilitada. Validar usa exclusivamente el último borrador guardado.</p>{detail.missingFields.length > 0 && <p>Requeridos para READY: {detail.missingFields.join(', ')}</p>}
      {!canWrite && <p className="tenant-notice">Consulta histórica o sin facultad de modificación. Revisa estado, contrato y habilitaciones.</p>}
      {!pending && <form className="branch-form" key={detail.module.scope.moduleId + detail.module.scope.version + (detail.configuration?.revision ?? 0)} onSubmit={e => { e.preventDefault(); const button = (e.nativeEvent as SubmitEvent).submitter as HTMLButtonElement | null; submit(e.currentTarget, button?.value === 'validate') }}>
        {detail.module.fields.map(field => <label key={field.key} className="configuration-field">{field.label}{field.required ? ' (requerido para READY)' : ' (opcional)'}{field.type === 'BOOLEAN' ? <select name={field.key} defaultValue={detail.configuration?.values[field.key] === undefined ? '' : String(detail.configuration.values[field.key])} disabled={busy || !canWrite}><option value="">Sin valor</option><option value="true">Sí</option><option value="false">No</option></select> : <input name={field.key} type={field.type === 'INTEGER' ? 'number' : 'text'} min={field.minimum ?? undefined} max={field.maximum ?? undefined} step={field.type === 'INTEGER' ? 1 : undefined} maxLength={field.maxLength ?? undefined} defaultValue={detail.configuration?.values[field.key] as string | number | undefined ?? ''} disabled={busy || !canWrite} />}</label>)}
        {canWrite && <><label>Confirmar contraseña de configuración<input name="password" type="password" autoComplete="current-password" maxLength={1024} required disabled={busy} /></label><div className="tenant-actions"><button className="button primary" value="save" disabled={busy}>Guardar borrador</button>{detail.configuration && <button className="button" value="validate" disabled={busy}>Validar preparación</button>}</div></>}
      </form>}
      <h5>Historial de configuración</h5><ol className="tenant-history">{detail.history.map(change => <li key={change.requestId}><strong>{change.action} · {change.previousReadiness ?? 'ABSENT'} → {change.configuration.readiness}</strong><small>Revisión {change.configuration.revision} · {new Date(change.configuration.updatedAt).toLocaleString('es-CL')}</small><small>Referencia: {change.requestId}</small></li>)}</ol>
    </section>}
  </section>
}
