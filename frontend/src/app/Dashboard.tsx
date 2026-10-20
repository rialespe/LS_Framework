import { Icon } from '../components/Icon'
import { demo, icons } from './demo'
import type { DemoItem, Directory } from './demo'
import { EntityName, Status } from './DirectoryView'

const activity = [
  { icon: 'company', name: 'Aurora Tecnología', text: 'Empresa incorporada al espacio de muestra', time: '10:45' },
  { icon: 'users', name: 'Elena Torres', text: 'Perfil ficticio añadido al equipo de Operaciones', time: '09:30' },
  { icon: 'apps', name: 'LS Core', text: 'Aplicación de muestra disponible en el catálogo', time: 'Ayer' },
] as const

export function Dashboard({ onNavigate, onCompany, onPortal }: { onNavigate: (section: Directory) => void; onCompany: (item: DemoItem) => void; onPortal: () => void }) {
  return <>
    <section className="hero" aria-label="Bienvenida a LS Core">
      <div className="hero-copy"><span className="hero-kicker">LS CORE · MULTI-TENANT v4.0</span>
        <h2>La base de lo que<br />viene para tu empresa<span>.</span></h2>
        <p>Empresas, equipos y aplicaciones.<br />Una experiencia de administración más simple.</p>
        <button className="button light" onClick={() => onNavigate('Empresas')}>Explorar empresas<Icon name="arrow" size={17} /></button>
      </div>
      <div className="hero-art" aria-hidden="true">
        <div className="orbit orbit-one" /><div className="orbit orbit-two" /><div className="art-line line-one" /><div className="art-line line-two" />
        <div className="art-core">LS<span>CORE</span></div>
        <div className="art-node node-one"><Icon name="company" />Empresas</div><div className="art-node node-two"><Icon name="apps" />Aplicaciones</div><div className="art-node node-three"><Icon name="users" />Equipos</div>
        <span className="art-caption">UN ECOSISTEMA CONECTADO</span>
      </div>
    </section>
    <section className="portal-entry" aria-label="Acceso al portal global de muestra"><span className="quick-icon"><Icon name="company" size={26} /></span><div><h2>Portal global (demo)</h2><p>Empresas, estados y fichas ficticias · Sin funciones administrativas</p></div><button className="button" onClick={onPortal}>Explorar portal global<Icon name="arrow" size={17} /></button></section>
    <section className="metrics" aria-label="Resumen de datos ficticios">
      {(['Empresas', 'Sucursales', 'Usuarios', 'Aplicaciones'] as Directory[]).map((item, index) =>
        <button className="metric" key={item} onClick={() => onNavigate(item)}>
          <span className={`metric-icon tone-${index}`}><Icon name={icons[item]} /></span><span className="metric-label">{item}</span>
          <strong>{String(demo[item].length).padStart(2, '0')}</strong><span className="metric-bottom">Registros de muestra<Icon name="arrow" size={15} /></span>
        </button>)}
    </section>
    <div className="dashboard-grid">
      <section className="panel" aria-labelledby="companies-title">
        <div className="panel-heading"><div><h2 id="companies-title">Empresas en tu ecosistema</h2><p>Una mirada a los espacios de trabajo ficticios.</p></div>
          <button className="text-button" onClick={() => onNavigate('Empresas')}>Ver todas<Icon name="arrow" size={16} /></button></div>
        <div className="table-scroll"><table><caption className="sr-only">Empresas de demostración</caption>
          <thead><tr><th>Empresa</th><th>Sector</th><th>Estado</th></tr></thead>
          <tbody>{demo.Empresas.map((item, i) => <tr key={item.id}>
            <td><EntityName item={item} index={i} onSelect={() => onCompany(item)} /></td><td>{item.category}</td><td><Status value={item.status} /></td>
          </tr>)}</tbody></table></div>
        <div className="panel-foot"><span>3 empresas ficticias · Vista de demostración</span></div>
      </section>
      <section className="panel quick-panel" aria-labelledby="quick-title">
        <div className="panel-heading"><div><h2 id="quick-title">Accesos rápidos</h2><p>Tu siguiente paso, a un clic.</p></div></div>
        {(['Sucursales', 'Usuarios', 'Aplicaciones'] as Directory[]).map(item => <button className="quick-link" key={item} onClick={() => onNavigate(item)}>
          <span className="quick-icon"><Icon name={icons[item]} /></span><span>Explorar {item.toLocaleLowerCase('es')}<small>{item === 'Sucursales' ? 'Ubicaciones y espacios de trabajo' : item === 'Usuarios' ? 'Personas y equipos de muestra' : 'Herramientas de tu ecosistema'}</small></span><Icon name="arrow" size={17} />
        </button>)}
      </section>
      <section className="panel" aria-labelledby="activity-title">
        <div className="panel-heading"><div><h2 id="activity-title">Actividad de muestra</h2><p>Eventos ficticios para explorar la interfaz.</p></div><span className="subtle-tag">Demostración</span></div>
        <ol className="activity-list">{activity.map(item => <li key={item.name}><span className="activity-icon"><Icon name={item.icon} size={17} /></span><div><strong>{item.name}</strong><p>{item.text}</p></div><span className="activity-time">{item.time}</span></li>)}</ol>
      </section>
      <section className="explore-card"><span className="eyebrow">DISEÑADO PARA CONECTAR</span><Icon name="apps" size={32} /><h2>Un núcleo.<br />Más posibilidades.</h2><p>Descubre cómo se vería el catálogo de aplicaciones de tu organización.</p><button className="text-button" onClick={() => onNavigate('Aplicaciones')}>Explorar aplicaciones<Icon name="arrow" size={17} /></button></section>
    </div>
  </>
}
