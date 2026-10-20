import { Icon } from '../components/Icon'

const devices = [
  { id: 'DEMO-DISP-01', device: 'Computador de escritorio', browser: 'Chrome · Windows', activity: '20 oct. 2026 · 10:30 (ficticio)', state: 'Actividad ilustrativa', example: true },
  { id: 'DEMO-DISP-02', device: 'Teléfono móvil', browser: 'Safari · iOS', activity: '19 oct. 2026 · 18:15 (ficticio)', state: 'En pausa · ilustrativo', example: false },
  { id: 'DEMO-DISP-03', device: 'Tableta', browser: 'Firefox · Android', activity: '18 oct. 2026 · 09:00 (ficticio)', state: 'Sin actividad · ilustrativo', example: false },
]

export function SessionsPreview() {
  return <section aria-label="Sesiones y dispositivos de demostración">
    <div className="preview-notice"><Icon name="clock" /><div><strong>Dispositivos ficticios. Sin sesiones autenticadas.</strong><p>Los estados y las fechas son ilustrativos. Todas las acciones están deshabilitadas; no se cierran ni revocan sesiones.</p></div></div>
    <div className="preview-panel">
      <div className="sessions-heading"><div><p className="eyebrow">DISPOSITIVOS DE MUESTRA</p><h2>Una vista de tus dispositivos</h2><p>Así podría presentarse la administración de sesiones.</p></div><span className="subtle-tag">3 registros ficticios</span></div>
      <div className="sessions-list">
        {devices.map(item => <article className="session-device" key={item.id} aria-labelledby={item.id}>
          <span className="device-icon"><Icon name="device" size={28} /></span>
          <div className="session-description"><h3 id={item.id}>{item.device}</h3><p>{item.browser}</p><small>{item.id}</small>{item.example && <span className="session-example">Sesión de ejemplo · no autenticada</span>}</div>
          <div className="session-activity"><span className="subtle-tag">{item.state}</span><p><Icon name="clock" size={14} />{item.activity}</p></div>
          <button className="preview-disabled" disabled>Revocar sesión de dispositivo<span className="sr-only">: {item.device}</span></button>
        </article>)}
      </div>
      <div className="session-actions" role="group" aria-label="Acciones de sesiones no disponibles"><p>Controles de muestra · no disponibles en esta demo</p><div><button className="preview-disabled" disabled>Cerrar sesión</button><button className="preview-disabled" disabled>Cerrar otras sesiones</button><button className="preview-disabled" disabled>Cambiar usuario</button></div></div>
    </div>
  </section>
}
