import { useRef, useState } from 'react'
import { Brand } from '../components/Brand'
import { Icon } from '../components/Icon'
import { Dashboard } from './Dashboard'
import { AccessPreview } from './AccessPreview'
import { DirectoryView } from './DirectoryView'
import { descriptions, icons, sections } from './demo'
import type { DemoItem, Section } from './demo'

export function App() {
  const [section, setSection] = useState<Section | 'Acceso (demo)'>('Dashboard')
  const [menuOpen, setMenuOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<DemoItem | null>(null)
  const heading = useRef<HTMLHeadingElement>(null)
  const navigate = (next: Section | 'Acceso (demo)') => {
    setSection(next)
    setMenuOpen(false)
    setQuery('')
    setSelected(null)
    heading.current?.focus()
  }

  return <div id="inicio">
    <a className="skip-link" href="#main">Saltar al contenido</a>
    <header className="topbar">
      <Brand onHome={() => navigate('Dashboard')} />
      <button className="menu-toggle icon-button" aria-label={menuOpen ? 'Cerrar menú' : 'Abrir menú'} aria-expanded={menuOpen} aria-controls="primary-nav" onClick={() => setMenuOpen(!menuOpen)}><Icon name={menuOpen ? 'close' : 'menu'} /></button>
      <nav id="primary-nav" className={menuOpen ? 'navigation open' : 'navigation'} aria-label="Navegación principal">
        {sections.map(item => <button key={item} aria-current={section === item ? 'page' : undefined} onClick={() => navigate(item)}><Icon name={icons[item]} size={17} />{item}</button>)}
        <button aria-current={section === 'Acceso (demo)' ? 'page' : undefined} onClick={() => navigate('Acceso (demo)')}><Icon name="key" size={17} />Acceso (demo)</button>
      </nav>
      <div className="workspace"><span className="workspace-dot" /><span>Espacio de muestra<small>Vista administrativa</small></span><span className="workspace-avatar" aria-hidden="true"><Icon name="grid" size={16} /></span></div>
    </header>
    <div className="demo-banner" id="demo-warning"><span className="demo-label">DEMO</span><strong>Modo demostración · Sin autenticación ni conexión al backend · No ingresar datos reales</strong></div>
    <main className="main" id="main">
      <div className="page-heading"><div><p className="eyebrow">WORKSPACE / {section === 'Dashboard' ? 'VISTA GENERAL' : section.toLocaleUpperCase('es')}</p>
        <h1 ref={heading} tabIndex={-1}>{section === 'Dashboard' ? 'Todo conectado. Todo en un lugar.' : section}</h1>
        <p>{section === 'Dashboard' ? 'Explora una nueva forma de visualizar tu organización con LS Core.' : section === 'Acceso (demo)' ? 'Cuatro momentos del acceso, una experiencia compartida. Solo diseño visual.' : descriptions[section]}</p>
      </div><span className="preview-tag"><span />Previsualización visual</span></div>
      {section === 'Acceso (demo)' ? <AccessPreview /> : section === 'Dashboard' ? <Dashboard onNavigate={navigate} onCompany={item => { navigate('Empresas'); setSelected(item) }} /> :
        <DirectoryView section={section} query={query} onQuery={value => { setQuery(value); setSelected(null) }} selected={selected} onSelect={setSelected} />}
      <footer><span><strong>LS Core</strong> Multi-Tenant v4.0 <span className="footer-divider">/</span> LS Soluciones SpA</span><span>Previsualización visual · F12 y LFD-56 en Backlog</span></footer>
    </main>
  </div>
}
