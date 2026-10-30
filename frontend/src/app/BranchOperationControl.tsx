type Props = { branchStatus: string; tenantStatus: string }

/** Describes persisted states; this component never authorizes an operation or creates a context. */
export function BranchOperationControl({ branchStatus, tenantStatus }: Props) {
  return <aside className="tenant-notice" aria-label="Control de operaciones de sucursal">
    <h4>Control de operaciones</h4>
    {tenantStatus !== 'ACTIVE' ? <p>La empresa no está ACTIVE: no admite nuevos contextos ni operaciones empresariales.</p>
      : branchStatus === 'ACTIVE' ? <p>Sucursal activa: cada operación requiere su propia autorización, contexto y habilitación. El estado ACTIVE no concede acceso.</p>
        : branchStatus === 'SUSPENDED' ? <p>Sucursal suspendida: bloquea nuevas operaciones y selección de contexto operativo. Se conserva el historial autorizado.</p>
          : <p>Sucursal cerrada: bloqueo permanente de nuevas operaciones. El cierre es terminal y se conserva el historial autorizado.</p>}
    <p>Los contextos revocados no se recuperan al reactivar. Se requiere una nueva selección autorizada.</p>
    <p>El historial administrativo de esta ficha no concede permisos de negocio ni acceso multisucursal. Las sesiones y API Keys no tienen admisión general de negocio.</p>
  </aside>
}
