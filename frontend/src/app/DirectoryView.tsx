import { Icon } from '../components/Icon'
import { categoryLabels, demo } from './demo'
import type { DemoItem, Directory } from './demo'

export function Status({ value }: { value: DemoItem['status'] }) {
  return <span className={`status ${value === 'Pendiente' ? 'pending' : ''}`}><span />{value}</span>
}

export function EntityName({ item, index, onSelect, selected = false }: { item: DemoItem; index: number; onSelect: () => void; selected?: boolean }) {
  return <button className="entity-link" aria-label={`Seleccionar ${item.name}`} aria-pressed={selected} onClick={onSelect}>
    <span className={`entity-avatar tone-${index % 4}`} aria-hidden="true">{item.initials}</span>
    <span>{item.name}<small>{item.caption}</small></span>
  </button>
}

type Props = { section: Directory; query: string; onQuery: (query: string) => void; selected: DemoItem | null; onSelect: (item: DemoItem | null) => void }

export function DirectoryView({ section, query, onQuery, selected, onSelect }: Props) {
  const filtered = demo[section].filter(item => `${item.name} ${item.caption} ${item.category} ${item.id}`
    .toLocaleLowerCase('es').includes(query.trim().toLocaleLowerCase('es')))
  return <>
    <div className="directory-toolbar">
      <div className="directory-count"><strong>{demo[section].length}</strong> {section.toLocaleLowerCase('es')} de muestra</div>
      <label className="search-box"><Icon name="search" size={18} /><span className="sr-only">Buscar en {section.toLocaleLowerCase('es')}</span>
        <input type="search" placeholder={`Buscar en ${section.toLocaleLowerCase('es')}…`} value={query} onChange={event => onQuery(event.target.value)} />
      </label>
    </div>
    <div className={`directory-layout ${selected ? 'has-selection' : ''}`}>
      <section className="panel directory-panel" aria-label={`${section}: datos de demostración`}>
        <div className="panel-heading"><div><h2>{section === 'Aplicaciones' ? 'Catálogo de aplicaciones' : `Directorio de ${section.toLocaleLowerCase('es')}`}</h2>
          <p>Datos ficticios · Selecciona un registro para explorar su detalle.</p></div><span className="subtle-tag">Solo vista previa</span></div>
        {filtered.length === 0 ? <div className="empty-state" role="status">
          <span className="empty-icon"><Icon name="search" size={28} /></span><h3>No encontramos coincidencias</h3>
          <p>Prueba con otro nombre o limpia la búsqueda para volver a los datos de muestra.</p>
          <button className="button" onClick={() => onQuery('')}>Limpiar búsqueda</button>
        </div> : section === 'Aplicaciones' ? <div className="app-cards">{filtered.map((item, i) =>
          <button key={item.id} className={`application-card ${selected?.id === item.id ? 'selected' : ''}`} aria-pressed={selected?.id === item.id} onClick={() => onSelect(item)}>
            <span className={`application-logo tone-${i}`}>{item.initials}</span><Status value={item.status} />
            <h3>{item.name}</h3><p>{item.caption}</p><span className="application-bottom">Ver ficha de muestra<Icon name="arrow" size={16} /></span>
          </button>)}</div> : <div className="table-scroll"><table>
          <caption className="sr-only">{section} de demostración</caption>
          <thead><tr><th>Nombre</th><th>{categoryLabels[section]}</th><th>Estado</th><th><span className="sr-only">Detalle</span></th></tr></thead>
          <tbody>{filtered.map((item, i) => <tr className={selected?.id === item.id ? 'selected-row' : ''} key={item.id}>
            <td><EntityName item={item} index={i} selected={selected?.id === item.id} onSelect={() => onSelect(item)} /></td>
            <td>{item.category}</td><td><Status value={item.status} /></td>
            <td><button className="icon-button" aria-label={`Ver detalle de ${item.name}`} onClick={() => onSelect(item)}><Icon name="arrow" size={17} /></button></td>
          </tr>)}</tbody>
        </table></div>}
        <div className="panel-foot">{filtered.length} de {demo[section].length} registros de demostración<span>Sin conexión · Sin edición</span></div>
      </section>
      {selected && <aside className="panel detail-panel" aria-label="Detalle de demostración">
        <div className="detail-top"><span className="eyebrow">FICHA DE MUESTRA</span><button className="icon-button" aria-label="Cerrar detalle" onClick={() => onSelect(null)}><Icon name="close" size={18} /></button></div>
        <span className="detail-avatar">{selected.initials}</span><h2>{selected.name}</h2><p>{selected.caption}</p><Status value={selected.status} />
        <dl>{[['Referencia', selected.id], [categoryLabels[section], selected.category], ...selected.details].map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
        <div className="detail-note">Esta ficha contiene datos ficticios. No representa una cuenta, asignación ni acceso real.</div>
      </aside>}
    </div>
  </>
}
