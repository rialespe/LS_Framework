import { useState } from 'react'
import { Icon } from '../components/Icon'
import type { SessionEntry } from './fiscalApi'

const states = { ACTIVE: 'Activa', REVOKED: 'Revocada', ABSOLUTE_EXPIRED: 'Vencida', IDLE_EXPIRED: 'Inactiva' }
const date = (value: string) => new Date(value).toLocaleString('es-CL')

/** Same visual structure as SessionsPreview, with PostgreSQL metadata only. */
export function ConnectedSessions({ entries, busy, tenant, onRefresh, onRevoke, onRevokeOthers, onLogout, onBack }: {
  entries: SessionEntry[]; busy: boolean; tenant: boolean; onRefresh: () => void; onRevoke: (id: string) => void;
  onRevokeOthers: () => void; onLogout: () => void; onBack: () => void;
}) {
  const [pending, setPending] = useState<string | null>(null)
  return <section aria-label={tenant ? 'Sesiones de la empresa' : 'Mis sesiones'}>
    <button className="access-link" disabled={busy} onClick={onBack}>Volver al contexto</button>
    <div className="preview-panel">
      <div className="sessions-heading"><div><p className="eyebrow">SESIONES REALES · LS FISCAL</p><h2>{tenant ? 'Acceso de sesiones a la empresa' : 'Mis sesiones'}</h2>
        <p>{tenant ? 'La revocación afecta solo el acceso de esa sesión a esta empresa.' : 'Sesiones independientes de tu cuenta en esta aplicación.'}</p></div>
        <button className="button" disabled={busy} onClick={() => { setPending(null); onRefresh() }}>Actualizar sesiones</button>
      </div>
      <p>No se registran datos de dispositivo, navegador o ubicación. Se muestran hasta 100 sesiones, incluida la actual.</p>
      {pending && <div className="preview-notice" role="alert"><div><strong>Confirmar revocación</strong><p>{pending === 'others' ? 'Se cerrarán todas tus otras sesiones de LS Fiscal. Conservarás la actual.' : tenant ? 'Esta sesión no podrá volver a seleccionar esta empresa. Su acceso a otras empresas se conservará.' : 'La sesión elegida dejará de autenticar en su siguiente operación.'}</p>
        <div className="pilot-actions"><button className="button" disabled={busy} onClick={() => setPending(null)}>Cancelar</button><button className="button" disabled={busy} onClick={() => { const target = pending; setPending(null); if (target === 'others') { onRevokeOthers() } else { onRevoke(target) } }}>Confirmar revocación</button></div>
      </div></div>}
      {!entries.length && !busy && <p className="preview-empty">No hay sesiones vinculadas disponibles.</p>}
      <div className="sessions-list">{entries.map(item => <article className="session-device" key={item.id}>
        <span className="device-icon" aria-hidden="true"><Icon name="key" size={28} /></span>
        <div className="session-description"><h3>{item.current ? 'Sesión actual' : `Sesión ${item.id.slice(-8)}`}</h3><small>{item.id}</small>
          <p>Creación: <time dateTime={item.createdAt}>{date(item.createdAt)}</time></p><p>Última actividad: {item.lastSeenAt ? <time dateTime={item.lastSeenAt}>{date(item.lastSeenAt)}</time> : 'Sin actividad registrada; se utiliza la creación.'}</p>
          <p>Vencimiento absoluto: <time dateTime={item.expiresAt}>{date(item.expiresAt)}</time></p><p>Límite de inactividad: <time dateTime={item.idleExpiresAt}>{date(item.idleExpiresAt)}</time></p>
        </div>
        <div className="session-activity"><span className="subtle-tag">{item.tenantRevoked ? 'Acceso a empresa revocado' : states[item.status]}</span></div>
        {item.current && !tenant ? <button className="button" disabled={busy} onClick={onLogout}>Cerrar sesión actual</button>
          : <button className="button" disabled={busy || item.status === 'REVOKED' || item.tenantRevoked} onClick={() => setPending(item.id)}>{tenant ? 'Revocar acceso a empresa' : 'Cerrar otra sesión'}<span className="sr-only">: {item.id.slice(-8)}</span></button>}
      </article>)}</div>
      {!tenant && <div className="session-actions"><button className="button" disabled={busy || !entries.some(item => !item.current && item.status !== 'REVOKED')} onClick={() => setPending('others')}>Cerrar todas las demás sesiones</button></div>}
    </div>
  </section>
}
