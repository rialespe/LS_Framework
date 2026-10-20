import { useState } from 'react'
import { Icon } from '../components/Icon'
import { demo } from './demo'

export function ContextPreview() {
  const [companyId, setCompanyId] = useState('')
  const [branchId, setBranchId] = useState('')
  const company = demo.Empresas.find(item => item.id === companyId)
  // The existing category is a visual fixture association, never a security boundary.
  const branches = company ? demo.Sucursales.filter(item => item.category === company.name) : []
  const branch = branches.find(item => item.id === branchId)

  return <section aria-label="Selector de contexto de demostración">
    <div className="preview-notice"><Icon name="branch" /><div><strong>Selección visual sin contexto de seguridad ni permisos efectivos</strong><p>Explora las ubicaciones de muestra. Esta selección no cambia la información ni la navegación del resto de la aplicación.</p></div></div>
    <div className="context-layout">
      <div className="context-steps">
        <section className="preview-panel" aria-labelledby="company-title">
          <div className="preview-panel-heading"><span className="step-number">01</span><div><h2 id="company-title">Elige una empresa</h2><p>Tres organizaciones ficticias para explorar.</p></div></div>
          <div className="context-options" role="group" aria-label="Empresas de demostración">
            {demo.Empresas.map(item => <button key={item.id} className="context-option" aria-pressed={companyId === item.id} onClick={() => { setCompanyId(item.id); setBranchId('') }}>
              <span className="context-avatar" aria-hidden="true">{item.initials}</span><span><strong>{item.name}</strong><small>{item.caption}</small></span><span className="context-indicator" aria-hidden="true">{companyId === item.id ? '●' : '○'}</span>
            </button>)}
          </div>
        </section>
        <section className="preview-panel" aria-labelledby="branch-title">
          <div className="preview-panel-heading"><span className="step-number">02</span><div><h2 id="branch-title">Elige una sucursal</h2><p>Solo ubicaciones de la empresa seleccionada.</p></div></div>
          <div className="context-options" role="group" aria-label="Sucursales de demostración">
            {!company ? <p className="preview-empty">Selecciona una empresa para ver sus sucursales.</p> : branches.length === 0 ? <p className="preview-empty">Esta empresa no tiene sucursales disponibles en la demostración.</p> : branches.map(item => <button key={item.id} className="context-option" aria-pressed={branchId === item.id} onClick={() => setBranchId(item.id)}>
              <span className="context-avatar" aria-hidden="true"><Icon name="branch" /></span><span><strong>{item.name}</strong><small>{item.caption}</small></span><span className="context-indicator" aria-hidden="true">{branchId === item.id ? '●' : '○'}</span>
            </button>)}
          </div>
        </section>
      </div>
      <aside className="context-summary" aria-labelledby="context-summary-title">
        <span className="access-badge">SOLO VISUAL</span><Icon name="company" size={38} />
        <h2 id="context-summary-title">Tu selección de muestra</h2><p>Una vista previa de la organización y su ubicación.</p>
        <div role="status" aria-live="polite" aria-atomic="true"><dl><dt>Empresa</dt><dd>{company?.name ?? 'Sin empresa seleccionada'}</dd><dt>Sucursal</dt><dd>{branch?.name ?? 'Sin sucursal seleccionada'}</dd></dl></div>
        <p className="context-summary-note">No establece un contexto de seguridad ni concede acceso. Al salir de esta pantalla, la selección se descarta.</p>
      </aside>
    </div>
  </section>
}
