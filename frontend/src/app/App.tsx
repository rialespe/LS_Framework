import { Brand } from '../components/Brand'
import { appConfig } from './config'

export function App() {
  return (
    <div className="shell" id="inicio">
      <header className="header">
        <Brand />
        <span className="version">Framework · v4.0</span>
      </header>
      <main>
        <section className="intro" aria-labelledby="title">
          <p className="eyebrow">LS CORE / BASE TÉCNICA</p>
          <h1 id="title">LS Core<br /><span>Multi-Tenant v4.0</span></h1>
          <p className="description">Una base común para las aplicaciones de LS Soluciones SpA.</p>
          <div className="notice">
            <span className="notice-dot" aria-hidden="true" />
            Estructura inicial · LFD-22
          </div>
        </section>
        <section className="foundation" aria-labelledby="foundation-title">
          <div>
            <p className="eyebrow">PUNTO DE PARTIDA</p>
            <h2 id="foundation-title">Preparado para construir.</h2>
            <p>El proyecto cuenta con una base modular de backend y frontend. Las funciones de administración se incorporarán en próximas etapas.</p>
          </div>
          <div className="technical">
            <h3>Configuración de desarrollo</h3>
            <dl><dt>URL base de API</dt><dd><code>{appConfig.apiBaseUrl}</code></dd></dl>
            <p>Esta pantalla funciona de forma independiente y no comprueba la conexión al backend.</p>
          </div>
        </section>
      </main>
      <footer>LS Soluciones SpA <span>LS Core · Monolito modular</span></footer>
    </div>
  )
}
