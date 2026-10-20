import { useState } from 'react'
import { Icon } from '../components/Icon'
import { DirectoryView } from './DirectoryView'
import { demo } from './demo'
import type { DemoItem } from './demo'

const states = ['Activo', 'Pendiente', 'Suspendido'] as const
type CompanyState = typeof states[number]

export function GlobalPortalPreview({ onBack }: { onBack: () => void }) {
  const [query, setQuery] = useState('')
  const [state, setState] = useState<CompanyState | undefined>()
  const [selected, setSelected] = useState<DemoItem | null>(null)

  return <section aria-label="Portal global de demostración">
    <button className="access-link portal-back" onClick={onBack}><Icon name="arrow" size={16} />Volver al Dashboard</button>
    <div className="portal-intro"><div><p className="hero-kicker">LS SOLUCIONES · VISTA GLOBAL</p><h2>Una mirada a cada empresa.</h2><p>Explora el directorio ficticio y sus fichas de muestra, en un solo lugar.</p></div><span className="portal-symbol" aria-hidden="true"><Icon name="company" size={42} /></span></div>
    <div className="preview-notice"><Icon name="grid" /><div><strong>Portal global ficticio · Sin funciones administrativas</strong><p>Esta vista no concede privilegios ni permite crear, editar, activar o suspender empresas.</p></div></div>
    <section className="metrics portal-metrics" aria-label="Indicadores de empresas ficticias">
      {[{ label: 'Total de empresas', count: demo.Empresas.length }, ...states.map(value => ({ label: value, count: demo.Empresas.filter(item => item.status === value).length }))].map((metric, index) => <div className="metric" key={metric.label}>
        <span className={`metric-icon tone-${index}`}><Icon name={index === 0 ? 'company' : 'grid'} /></span><span className="metric-label">{metric.label}</span><strong>{metric.count}</strong><span className="metric-bottom">Datos de demostración</span>
      </div>)}
    </section>
    <div className="portal-filters" role="group" aria-label="Filtrar empresas por estado"><span>Estado</span>
      {(['Todos', ...states] as const).map(value => <button key={value} aria-pressed={value === (state ?? 'Todos')} onClick={() => { setState(value === 'Todos' ? undefined : value); setSelected(null) }}>{value}</button>)}
    </div>
    <DirectoryView section="Empresas" query={query} onQuery={value => { setQuery(value); setSelected(null) }} selected={selected} onSelect={setSelected} statusFilter={state} searchByName />
    <section className="portal-information" aria-label="Información administrativa no disponible">
      {([
        { icon: 'apps', title: 'Aplicaciones', text: 'Asignaciones por empresa no disponibles. El catálogo de muestra no representa módulos asignados.' },
        { icon: 'users', title: 'Administrador inicial', text: 'Datos no disponibles. No hay un administrador inicial asignado ni verificado en esta demostración.' },
        { icon: 'clock', title: 'Auditoría', text: 'Datos no disponibles. Esta vista no contiene eventos de auditoría reales ni registra operaciones.' },
      ] as const).map(item => <article className="panel portal-info" key={item.title}><span className="quick-icon"><Icon name={item.icon} /></span><h2>{item.title}</h2><span className="subtle-tag">Sin datos disponibles</span><p>{item.text}</p></article>)}
    </section>
  </section>
}
