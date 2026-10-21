import { useEffect, useState } from 'react'
import { Icon } from '../components/Icon'

type CatalogItem = { id: string; code: string; name: string; description: string | null; status: string; applicationId?: string }

function useCatalog(url: string | null) {
  const [retry, setRetry] = useState(0)
  const key = `${url}:${retry}`
  const [result, setResult] = useState<{ key: string; items?: CatalogItem[]; error?: boolean }>()
  useEffect(() => {
    if (!url) { return }
    const controller = new AbortController()
    fetch(url, { signal: controller.signal, credentials: 'omit', cache: 'no-store' })
      .then(async response => {
        if (!response.ok) { throw new Error('Catalog unavailable') }
        const items: unknown = await response.json()
        if (!Array.isArray(items) || !items.every(item => item && ['id', 'code', 'name', 'status'].every(field => typeof item[field] === 'string') && (item.description === null || typeof item.description === 'string'))) { throw new Error('Invalid response') }
        if (!controller.signal.aborted) { setResult({ key, items }) }
      })
      .catch(() => { if (!controller.signal.aborted) { setResult({ key, error: true }) } })
    return () => controller.abort()
  }, [url, key])
  return { items: result?.key === key ? result.items : undefined, error: result?.key === key && result.error, retry: () => setRetry(value => value + 1) }
}

function CatalogResults({ data, onSelect }: { data: ReturnType<typeof useCatalog>; onSelect?: (item: CatalogItem) => void }) {
  if (data.error) { return <div className="empty-state" role="alert"><h3>No pudimos consultar el catálogo</h3><p>Comprueba la API local y su conexión a Docker. No se muestran datos de muestra como reemplazo.</p><button className="button" onClick={data.retry}>Reintentar consulta</button></div> }
  if (!data.items) { return <p className="preview-empty" role="status">Consultando PostgreSQL Docker…</p> }
  if (data.items.length === 0) { return <p className="preview-empty" role="status">Sin resultados en esta página del catálogo.</p> }
  return <ul className="connected-catalog-list">{data.items.map(item => <li key={item.id}><span className="quick-icon"><Icon name={onSelect ? 'apps' : 'branch'} /></span><div><h3>{onSelect ? <button className="access-link" onClick={() => onSelect(item)}>{item.name}</button> : item.name}</h3><p>{item.description}</p><small>{item.code} · {item.status}</small></div></li>)}</ul>
}

export function ConnectedCatalog({ onBack }: { onBack: () => void }) {
  const [application, setApplication] = useState<CatalogItem | null>(null)
  const [offset, setOffset] = useState(0)
  const applications = useCatalog(`/api/global-catalog/applications?limit=100&offset=${offset}`)
  const modules = useCatalog(application ? `/api/global-catalog/applications/${encodeURIComponent(application.id)}/modules?limit=100&offset=0` : null)
  return <section aria-label="Catálogo global conectado">
    <button className="access-link" onClick={onBack}>Volver a Aplicaciones de muestra</button>
    <div className="preview-notice"><Icon name="apps" /><div><strong>Esta pantalla consulta PostgreSQL Docker local · Solo lectura</strong><p>Datos reales del catálogo de desarrollo, separados de los registros ficticios. No concede permisos empresariales ni representa autenticación.</p></div></div>
    <div className="context-layout"><section className="panel" aria-label="Aplicaciones desde PostgreSQL"><div className="panel-heading"><div><h2>Aplicaciones del catálogo</h2><p>Selecciona una aplicación para consultar sus módulos.</p></div><span className="subtle-tag">BD local</span></div>
      <CatalogResults data={applications} onSelect={item => setApplication(item)} />
      <div className="connected-pages"><button className="button" disabled={offset === 0} onClick={() => { setOffset(value => value - 100); setApplication(null) }}>Anterior</button><span>Página {offset / 100 + 1}</span><button className="button" disabled={!applications.items || applications.items.length < 100 || offset === 10000} onClick={() => { setOffset(value => value + 100); setApplication(null) }}>Siguiente</button></div>
    </section><section className="panel" aria-label="Módulos desde PostgreSQL"><div className="panel-heading"><div><h2>{application ? `Módulos de ${application.name}` : 'Módulos'}</h2><p>{application ? `${application.code} · ${application.status}` : 'Sin aplicación seleccionada'}</p></div></div>
      {application ? <CatalogResults data={modules} /> : <p className="preview-empty">Selecciona una aplicación del catálogo real.</p>}
      {modules.items?.length === 100 && <p className="preview-empty">Se muestran los primeros 100 módulos. Esta vista no ofrece un total.</p>}
    </section></div>
  </section>
}
