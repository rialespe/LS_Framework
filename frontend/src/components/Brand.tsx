export function Brand({ onHome }: { onHome?: () => void }) {
  return (
    <a className="brand" href="#inicio" aria-label="LS Core, inicio" onClick={onHome}>
      <span className="brand-mark" aria-hidden="true">LS</span>
      <span>LS Soluciones<small className="brand-company">LS CORE <span>ADMIN WORKSPACE</span></small></span>
    </a>
  )
}
