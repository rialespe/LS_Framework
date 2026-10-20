import { useRef, useState } from 'react'
import { Icon } from '../components/Icon'

const views = ['Inicio de sesión', 'Primer ingreso', 'Recuperación', 'Sesión expirada'] as const
type AccessView = typeof views[number]

function CredentialField({ id, label, password = false }: { id: string; label: string; password?: boolean }) {
  return <div className="access-field">
    <label htmlFor={id}>{label}<span>Solo visual</span></label>
    <input id={id} type={password ? 'password' : 'text'} disabled readOnly value="" autoComplete="off"
      placeholder="Deshabilitado en demostración" aria-describedby="access-help" />
  </div>
}

export function AccessPreview() {
  const [view, setView] = useState<AccessView>('Inicio de sesión')
  const title = useRef<HTMLHeadingElement>(null)
  const changeView = (next: AccessView) => {
    setView(next)
    title.current?.focus()
  }

  return <section className="access-preview" aria-label="Previsualización de acceso">
    <div className="access-switcher" role="group" aria-label="Vistas de acceso de demostración">
      <span>EXPLORAR VISTAS</span>
      {views.map((item, index) => <button type="button" key={item} aria-pressed={view === item}
        onClick={() => changeView(item)}><span aria-hidden="true">0{index + 1}</span>{item}</button>)}
    </div>
    <div className="access-layout">
      <div className="access-story">
        <span className="access-edition">LS CORE <span>/</span> ACCESO</span>
        <div className="access-symbol" aria-hidden="true"><Icon name="key" size={38} /></div>
        <p className="hero-kicker">CONECTA CON TU ESPACIO</p>
        <h2>Tu trabajo empieza<br />con una buena <span>experiencia.</span></h2>
        <p>Una propuesta de acceso clara y cercana, con la identidad de LS Soluciones.</p>
        <div className="access-story-note"><span aria-hidden="true">↗</span><div><strong>Un recorrido, cuatro momentos</strong>
          <p>Explora el acceso, el primer ingreso, la recuperación y el aviso de sesión expirada.</p></div></div>
        <span className="access-story-foot">Ingeniería y Consultoría <span>·</span> LS Soluciones</span>
      </div>
      <div className="access-panel">
        <div className="access-panel-top"><span className="access-badge">VISTA DE DEMOSTRACIÓN</span><span>0{views.indexOf(view) + 1} / 04</span></div>
        <h2 ref={title} tabIndex={-1}>{view}</h2>
        {view === 'Inicio de sesión' && <>
          <p className="access-intro">Así se vería el acceso a LS Core. Puedes explorar las pantallas sin ingresar información.</p>
          <CredentialField id="demo-username" label="Usuario o correo" />
          <CredentialField id="demo-password" label="Contraseña" password />
          <button type="button" className="access-link" onClick={() => changeView('Recuperación')}>¿Olvidaste tu contraseña?</button>
          <button type="button" className="button access-submit" disabled>Iniciar sesión · No disponible<Icon name="arrow" size={18} /></button>
          <div className="access-next"><span>¿Quieres conocer el primer ingreso?</span>
            <button type="button" className="access-link" onClick={() => changeView('Primer ingreso')}>Ver cambio de contraseña temporal<Icon name="arrow" size={16} /></button></div>
        </>}
        {view === 'Primer ingreso' && <>
          <p className="access-intro">Propuesta visual de cambio de contraseña temporal. No se valida ni modifica ninguna contraseña.</p>
          <CredentialField id="demo-temporary" label="Contraseña temporal" password />
          <CredentialField id="demo-new" label="Nueva contraseña" password />
          <CredentialField id="demo-confirm" label="Confirmar nueva contraseña" password />
          <button type="button" className="button access-submit" disabled>Cambiar contraseña · No disponible</button>
          <p className="access-caption">Los requisitos de contraseña se definirán con el flujo real.</p>
        </>}
        {view === 'Recuperación' && <>
          <p className="access-intro">Conoce las opciones previstas para recuperar el acceso. Esta pantalla es informativa: no genera solicitudes ni envía correos.</p>
          <div className="access-recovery"><Icon name="users" size={22} /><div><h3>Recuperación asistida</h3>
            <p>El flujo futuro podrá orientar el contacto con el administrador. No hay un canal de atención conectado en esta demo.</p></div></div>
          <div className="access-recovery"><Icon name="key" size={22} /><div><h3>Recuperación por correo</h3>
            <p>Propuesta pendiente de definición e integración. No se comprueba si una cuenta existe.</p></div></div>
          <CredentialField id="demo-recovery" label="Usuario o correo para recuperación" />
          <button type="button" className="button access-submit" disabled>Solicitar recuperación · No disponible</button>
        </>}
        {view === 'Sesión expirada' && <>
          <div className="access-expired-icon" aria-hidden="true"><Icon name="clock" size={32} /></div>
          <p className="access-intro">En una sesión real, este aviso indicaría que es necesario volver al acceso para continuar.</p>
          <div className="access-info"><strong>Escenario ficticio</strong><p>No existe una sesión activa o expirada. Este botón solo muestra la pantalla de inicio de sesión.</p></div>
          <button type="button" className="button access-return" onClick={() => changeView('Inicio de sesión')}>Volver al acceso<Icon name="arrow" size={18} /></button>
        </>}
        <p className="access-help" id="access-help"><Icon name="key" size={16} />Campos bloqueados. No ingreses datos reales.</p>
        {view !== 'Inicio de sesión' && view !== 'Sesión expirada' && <button type="button" className="access-link access-back"
          onClick={() => changeView('Inicio de sesión')}>← Volver al acceso</button>}
      </div>
    </div>
  </section>
}
