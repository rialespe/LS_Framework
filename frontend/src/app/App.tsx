import { useRef, useState } from 'react'
import { Brand } from '../components/Brand'
import { Icon } from '../components/Icon'
import { Dashboard } from './Dashboard'
import { AccessPreview } from './AccessPreview'
import { ContextPreview } from './ContextPreview'
import { SessionsPreview } from './SessionsPreview'
import { GlobalPortalPreview } from './GlobalPortalPreview'
import { ConnectedCatalog } from './ConnectedCatalog'
import { DirectoryView } from './DirectoryView'
import { descriptions, icons, sections } from './demo'
import type { DemoItem, Section } from './demo'

const previews = {
  'Acceso (demo)': { icon: 'key', description: 'Cuatro momentos del acceso, una experiencia compartida. Solo diseño visual.' },
  'Contexto (demo)': { icon: 'branch', description: 'Explora empresas y sucursales de muestra, sin permisos efectivos.' },
  'Sesiones (demo)': { icon: 'device', description: 'Una mirada a la administración de dispositivos. Sin sesiones reales.' },
} as const
type Preview = keyof typeof previews
type NavigationSection = Section | Preview | 'Portal global (demo)' | 'Catálogo global (BD) · Solo lectura'

export function App() {
  const catalogEnabled = import.meta.env.DEV && import.meta.env.VITE_CATALOG_ENABLED === 'true'
  const [section, setSection] = useState<NavigationSection>('Dashboard')
  const [menuOpen, setMenuOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<DemoItem | null>(null)
  const heading = useRef<HTMLHeadingElement>(null)
  const navigate = (next: NavigationSection) => {
    setSection(next)
    setMenuOpen(false)
    setQuery('')
    setSelected(null)
    heading.current?.focus()
  }

  const description = section === 'Catálogo global (BD) · Solo lectura' ? 'Catálogo de desarrollo conectado a PostgreSQL Docker mediante la API local.' : section === 'Portal global (demo)' ? 'Directorio global de muestra. Sin operaciones administrativas.' : section in previews ? previews[section as Preview].description : section === 'Dashboard' ? 'Explora una nueva forma de visualizar tu organización con LS Core.' : descriptions[section as Exclude<Section, 'Dashboard'>]

  return <div id="inicio">
    <a className="skip-link" href="#main">Saltar al contenido</a>
    <header className="topbar">
      <Brand onHome={() => navigate('Dashboard')} />
      <button className="menu-toggle icon-button" aria-label={menuOpen ? 'Cerrar menú' : 'Abrir menú'} aria-expanded={menuOpen} aria-controls="primary-nav" onClick={() => setMenuOpen(!menuOpen)}><Icon name={menuOpen ? 'close' : 'menu'} /></button>
      <nav id="primary-nav" className={menuOpen ? 'navigation open' : 'navigation'} aria-label="Navegación principal">
        {sections.map(item => <button key={item} aria-current={section === item ? 'page' : undefined} onClick={() => navigate(item)}><Icon name={icons[item]} size={17} />{item}</button>)}
        {(Object.keys(previews) as Preview[]).map(item => <button key={item} aria-current={section === item ? 'page' : undefined} onClick={() => navigate(item)}><Icon name={previews[item].icon} size={17} />{item}</button>)}
      </nav>
      <div className="workspace"><span className="workspace-dot" /><span>Espacio de muestra<small>Vista administrativa</small></span><span className="workspace-avatar" aria-hidden="true"><Icon name="grid" size={16} /></span></div>
    </header>
    <div className="demo-banner" id="demo-warning"><span className="demo-label">DEMO</span><strong>{section === 'Catálogo global (BD) · Solo lectura' ? 'Desarrollo local · Sin autenticación · Conexión real al catálogo Docker · No ingresar datos reales' : 'Modo demostración · Sin autenticación ni conexión al backend · No ingresar datos reales'}</strong></div>
    <main className="main" id="main">
      <div className="page-heading"><div><p className="eyebrow">WORKSPACE / {section === 'Dashboard' ? 'VISTA GENERAL' : section.toLocaleUpperCase('es')}</p>
        <h1 ref={heading} tabIndex={-1}>{section === 'Dashboard' ? 'Todo conectado. Todo en un lugar.' : section}</h1>
        <p>{description}</p>
      </div><span className="preview-tag"><span />{section === 'Catálogo global (BD) · Solo lectura' ? 'Catálogo real · Solo lectura' : 'Previsualización visual'}</span></div>
      {section === 'Catálogo global (BD) · Solo lectura' && catalogEnabled ? <ConnectedCatalog onBack={() => navigate('Aplicaciones')} /> : section === 'Portal global (demo)' ? <GlobalPortalPreview onBack={() => navigate('Dashboard')} /> : section === 'Contexto (demo)' ? <ContextPreview /> : section === 'Sesiones (demo)' ? <SessionsPreview /> : section === 'Acceso (demo)' ? <AccessPreview /> : section === 'Dashboard' ? <Dashboard onNavigate={navigate} onPortal={() => navigate('Portal global (demo)')} onCompany={item => { navigate('Empresas'); setSelected(item) }} /> : section !== 'Catálogo global (BD) · Solo lectura' && <>
        {section === 'Aplicaciones' && catalogEnabled && <div className="preview-notice"><Icon name="apps" /><div><strong>Catálogo global (BD) · Solo lectura</strong><p>Consulta separada del catálogo real en Docker local.</p><button className="access-link" onClick={() => navigate('Catálogo global (BD) · Solo lectura')}>Abrir catálogo global conectado<Icon name="arrow" size={16} /></button></div></div>}
        <DirectoryView section={section} query={query} onQuery={value => { setQuery(value); setSelected(null) }} selected={selected} onSelect={setSelected} /></>}
      <footer><span><strong>LS Core</strong> Multi-Tenant v4.0 <span className="footer-divider">/</span> LS Soluciones SpA</span><span>Previsualización visual · F12, LFD-56 y LFD-57 en Backlog · F13 y LFD-58 en Backlog</span></footer>
    </main>
  </div>
}
