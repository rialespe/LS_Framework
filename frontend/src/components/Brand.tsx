export function Brand({ onHome }: { onHome?: () => void }) {
  return (
    <a className="brand" href="#inicio" aria-label="LS Core, inicio" onClick={onHome}>
      <img className="brand-logo" src="/branding/logoLS_Vertical.png" alt="LS Soluciones — Ingeniería y Consultoría" width="2172" height="724" />
      <span className="brand-platform">LS Core<small>Administración</small></span>
    </a>
  )
}
