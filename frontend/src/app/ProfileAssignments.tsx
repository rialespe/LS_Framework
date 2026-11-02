import { useRef, useState } from 'react'
import { coreApi } from './coreApi'
import type { AssignmentAction, AssignmentInput, AssignmentResult, AssignmentWorkspace, EffectivePermission, OwnContext, RbacApplication } from './coreApi'

const pendingKey = 'ls.core.pending-profile-assignment'

export function ProfileAssignments({ tenantId, membershipId }: { tenantId: string; membershipId: string }) {
  const [apps, setApps] = useState<RbacApplication[]>([])
  const [app, setApp] = useState('')
  const [data, setData] = useState<AssignmentWorkspace | null>(null)
  const [branchId, setBranchId] = useState('')
  const [selected, setSelected] = useState<string[]>([])
  const [preview, setPreview] = useState<EffectivePermission[] | null>(null)
  const [command, setCommand] = useState<{ action: AssignmentAction; input: AssignmentInput } | null>(null)
  const [pending, setPending] = useState(() => sessionStorage.getItem(pendingKey))
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const working = useRef(false)
  const branch = data?.branches.find(b => b.id === branchId)
  const run = async (work: () => Promise<void>) => {
    if (working.current) { return }
    working.current = true; setBusy(true); setMessage('')
    try { await work() } catch { setMessage('No se pudo confirmar la operación. Conserva la referencia si existe y consulta el recibo antes de repetir.') }
    finally { working.current = false; setBusy(false) }
  }
  const choose = (action: AssignmentAction, assignmentId: string | null = null, expectedRevision: number | null = null) => {
    if (!branch) { return }
    setCommand({ action, input: { membershipId, branchId, branchMembershipId: action === 'join-branch' ? null : branch.branchMembershipId,
      assignmentId, expectedUpdatedAt: action === 'join-branch' ? null : branch.updatedAt, expectedRevision, profileIds: action === 'assign' ? selected : [] } })
  }
  const result = (value: AssignmentResult, reference: string) => {
    if (value.correlation !== reference || value.outcome === 'UNKNOWN') { setMessage('Resultado UNKNOWN. Reconciliar con la misma referencia; no repetir.'); return }
    if (value.outcome === 'CONFIRMED' && value.receipt?.requestId !== reference) { setMessage('Recibo incompleto. Conserva la referencia y reconcilia.'); return }
    sessionStorage.removeItem(pendingKey); setPending(null)
    setMessage(value.outcome === 'CONFIRMED' ? `Cambio confirmado en PostgreSQL. Contextos revocados: ${value.receipt?.result.revokedContexts ?? 0}. Selecciona un nuevo contexto para operar.` : `Cambio rechazado: ${value.outcome}`)
  }
  return <section className="preview-panel" aria-label="Sucursales y perfiles">
    <h3>Sucursales y perfiles</h3>
    <button className="button" disabled={busy} onClick={() => { void run(async () => { setApps(await coreApi.rbacApplications(tenantId)) }) }}>Consultar aplicaciones para asignación</button>
    {apps.length > 0 && <label>Aplicación<select value={app} disabled={busy || Boolean(command)} onChange={e => { const value = e.target.value; setApp(value); setData(null); setBranchId(''); setPreview(null); if (value) { void run(async () => { setData(await coreApi.assignmentWorkspace(tenantId, value, membershipId)) }) } }}><option value="">Seleccionar</option>{apps.map(a => <option key={a.id} value={a.id}>{a.name} · {a.status}</option>)}</select></label>}
    {data && <>
      <p>Estado empresarial: {data.member.status}. Incorporar a sucursal no concede perfiles.</p>
      <label>Sucursal<select value={branchId} disabled={busy || Boolean(command)} onChange={e => { setBranchId(e.target.value); setSelected([]); setPreview(null) }}><option value="">Seleccionar sucursal</option>{data.branches.map(b => <option key={b.id} value={b.id}>{b.code} · {b.name} · Sucursal {b.status} · Acceso {b.accessStatus ?? 'SIN VÍNCULO'}</option>)}</select></label>
      {data.branches.length === 0 && <p>Sin sucursales registradas.</p>}
      {branch && <>
        {branch.branchMembershipId ? <>
          <h4>Asignaciones persistidas</h4>
          <ul>{data.assignments.filter(a => a.branch_membership_id === branch.branchMembershipId).map(a => <li key={a.id}>{data.profiles.find(p => p.id === a.profile_id)?.name ?? a.profile_id} · {a.status} · {a.created_at} {a.revoked_at && `· Revocado ${a.revoked_at}`} {a.status === 'ACTIVE' && !a.revoked_at && data.canManage && <button disabled={busy || Boolean(pending) || Boolean(command)} onClick={() => choose('revoke', a.id, a.revision)}>Revocar perfil</button>}</li>)}</ul>
          {data.assignments.every(a => a.branch_membership_id !== branch.branchMembershipId) && <p>Sin perfiles asignados.</p>}
          <h4>Perfiles definidos y permisos declarados</h4>
          {data.profiles.map(p => <label key={p.id}><input type="checkbox" checked={selected.includes(p.id)} disabled={!data.canManage || !p.assignable || p.status !== 'ACTIVE' || busy || Boolean(pending) || Boolean(command) || data.assignments.some(a => a.profile_id === p.id && a.branch_membership_id === branch.branchMembershipId && a.status === 'ACTIVE' && !a.revoked_at)} onChange={e => setSelected(e.target.checked ? [...selected, p.id] : selected.filter(id => id !== p.id))} />{p.name} · {p.status} · {p.assignable ? 'DELEGABLE' : 'NO DELEGABLE'}<small> {p.permissions.map(p => `${p.code} (${p.scope}, ${p.operationKind ?? 'SIN CLASIFICACIÓN'}, ${p.contextMode ?? 'SIN REGLA'})`).join(', ') || 'Sin permisos declarados'}</small></label>)}
          <button className="button" disabled={!data.canManage || !selected.length || busy || Boolean(pending) || Boolean(command) || branch.status !== 'ACTIVE' || branch.accessStatus !== 'ACTIVE' || data.member.status !== 'ACTIVE'} onClick={() => choose('assign')}>Asignar perfiles seleccionados</button>
          <button className="button" disabled={busy} onClick={() => { void run(async () => { setPreview(await coreApi.assignmentPreview(tenantId, app, membershipId, branch.branchMembershipId!)) }) }}>Consultar proyección contextual</button>
          {preview && <div><p>Evaluación administrativa de esta sucursal. Las operaciones revalidan su sesión y permisos en PostgreSQL.</p><ul>{preview.map(p => <li key={p.id}>{p.code} · {p.resource_scope} · {p.operation_kind}</li>)}</ul>{!preview.length && <p>Sin permisos efectivos en esta sucursal.</p>}</div>}
        </> : <button className="button" disabled={!data.canManage || busy || Boolean(pending) || Boolean(command) || branch.status !== 'ACTIVE' || data.member.status !== 'ACTIVE'} onClick={() => choose('join-branch')}>Vincular usuario a sucursal</button>}
        {branch.accessStatus === 'SUSPENDED' && <p>Reactiva el acceso individual desde la administración de accesos empresariales antes de asignar perfiles.</p>}
      </>}
      <h4>Historial administrativo</h4><ul>{data.history.map(h => <li key={h.requestId}>{h.action} · {h.createdAt} · {h.requestId}</li>)}</ul>
    </>}
    {command && <form onSubmit={e => { e.preventDefault(); const form = e.currentTarget; const password = String(new FormData(form).get('password') ?? ''); const confirmed = new FormData(form).get('confirmed'); if (!confirmed) { return }; const chosen = command; setCommand(null); form.reset(); void run(async () => {
      const id = crypto.randomUUID(); sessionStorage.setItem(pendingKey, id); setPending(id); setPreview(null)
      const value = await coreApi.assignmentMutation(tenantId, app, chosen.action, chosen.input, id, password); result(value, id)
      setSelected([]); setData(await coreApi.assignmentWorkspace(tenantId, app, membershipId))
    }) }}><p>Confirmar {command.action}. La asignación y revocación invalidan los contextos de esta sucursal y aplicación.</p><label><input type="checkbox" name="confirmed" required />Confirmo el cambio</label><label>Contraseña F04 nueva<input name="password" type="password" autoComplete="off" required maxLength={1024} /></label><button className="button" disabled={busy || Boolean(pending)}>Confirmar cambio</button><button type="button" onClick={() => setCommand(null)}>Cancelar</button></form>}
    {pending && <p>Referencia pendiente: <code>{pending}</code> <button disabled={busy} onClick={() => { void run(async () => { result(await coreApi.assignmentReceipt(pending), pending); if (app) { setData(await coreApi.assignmentWorkspace(tenantId, app, membershipId)) } }) }}>Reconciliar asignación</button></p>}
    {message && <p role="status">{message}</p>}
  </section>
}

export function AssignmentReceipt() {
  const [reference] = useState(() => sessionStorage.getItem(pendingKey))
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  if (!reference) { return null }
  return <section className="preview-panel"><h3>Recibo pendiente de asignación</h3><code>{reference}</code><p>La sesión del actor original puede reconciliar aunque haya perdido la autoridad administrativa.</p><button disabled={busy || confirmed} onClick={() => { setBusy(true); void coreApi.assignmentReceipt(reference).then(value => {
    if (value.outcome === 'CONFIRMED' && value.correlation === reference && value.receipt?.requestId === reference) { sessionStorage.removeItem(pendingKey); setConfirmed(true); setMessage('Operación confirmada por su recibo persistido. Vuelve a consultar el detalle autorizado.') }
    else { setMessage('Sin confirmación. Conserva el UUID; no repitas la mutación.') }
  }).catch(() => setMessage('Recibo no disponible. Conserva el UUID y verifica la sesión del actor original.')).finally(() => setBusy(false)) }}>Consultar recibo de asignación</button>{message && <p role="status">{message}</p>}</section>
}

export function OwnContextPermissions() {
  const [tenants, setTenants] = useState<{ id: string; name: string }[]>([])
  const [tenant, setTenant] = useState('')
  const [branches, setBranches] = useState<{ id: string; name: string; code: string }[]>([])
  const [context, setContext] = useState<OwnContext | null>(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const working = useRef(false)
  const run = async (action: () => Promise<void>) => { if (working.current) { return }; working.current = true; setBusy(true); setMessage(''); try { await action() } catch { setContext(null); setMessage('Contexto no disponible. Vuelve a consultar y seleccionar un contexto válido.') } finally { working.current = false; setBusy(false) } }
  return <section className="preview-panel" aria-label="Mis permisos contextuales"><h2>Mis permisos del contexto</h2><button className="button" disabled={busy} onClick={() => { void run(async () => { setTenants(await coreApi.contextTenants()); setContext(await coreApi.contextPermissions()) }) }}>Consultar mis contextos</button>
    {tenants.length > 0 && <label>Empresa<select value={tenant} disabled={busy} onChange={e => { const id = e.target.value; setTenant(id); setBranches([]); setContext(null); if (id) { void run(async () => { setBranches(await coreApi.contextBranches(id)) }) } }}><option value="">Seleccionar empresa</option>{tenants.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}</select></label>}
    {branches.map(b => <button className="button" key={b.id} disabled={busy} onClick={() => { void run(async () => { setContext(await coreApi.contextSelect(tenant, b.id)) }) }}>Seleccionar {b.code} · {b.name}</button>)}
    {context && <><p>Contexto {context.id} · Sucursal {context.branchId}</p><ul>{context.permissions.map(p => <li key={p.id}>{p.code} · {p.resourceScope} · {p.operationKind}</li>)}</ul>{!context.permissions.length && <p>Sin permisos efectivos.</p>}</>}{message && <p role="status">{message}</p>}
  </section>
}
