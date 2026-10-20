import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { demo } from './demo'

const warning = 'Modo demostración · Sin autenticación ni conexión al backend · No ingresar datos reales'
function navigate(name: string) {
  fireEvent.click(within(screen.getByRole('navigation')).getByRole('button', { name }))
}
function choose(name: string) { fireEvent.click(screen.getByRole('button', { name: new RegExp(name) })) }

describe('Context and sessions visual previews', () => {
  it('navigates both previews with focus, warning and the existing menu preserved', () => {
    render(<App />)
    for (const name of ['Contexto (demo)', 'Sesiones (demo)', 'Acceso (demo)', 'Dashboard', 'Empresas', 'Sucursales', 'Usuarios', 'Aplicaciones']) {
      fireEvent.click(screen.getByRole('button', { name: 'Abrir menú' }))
      navigate(name)
      expect(screen.getByRole('heading', { level: 1 })).toHaveFocus()
      expect(within(screen.getByRole('navigation')).getByRole('button', { name })).toHaveAttribute('aria-current', 'page')
      expect(screen.getByRole('button', { name: 'Abrir menú' })).toHaveAttribute('aria-expanded', 'false')
      expect(screen.getByText(warning)).toBeVisible()
    }
  })

  it('shows only associated branches, resets the branch on company changes and discards local choices on exit', () => {
    render(<App />)
    navigate('Contexto (demo)')
    expect(screen.getByText('Selecciona una empresa para ver sus sucursales.')).toBeVisible()
    const summary = screen.getByRole('status')
    expect(summary).toHaveTextContent('Sin empresa seleccionada')
    const branches = screen.getByRole('group', { name: 'Sucursales de demostración' })
    for (const [company, expected] of [
      ['Aurora Tecnología', ['Casa matriz Santiago', 'Oficina Providencia']],
      ['Norte Logística', ['Centro Antofagasta']],
      ['Estudio Horizonte', ['Estudio Valparaíso']],
    ] as const) {
      choose(company)
      expect(summary).toHaveTextContent('Sin sucursal seleccionada')
      expect(within(branches).getAllByRole('button')).toHaveLength(expected.length)
      for (const branch of expected) {
        const button = within(branches).getByRole('button', { name: new RegExp(branch) })
        fireEvent.click(button)
        expect(button).toHaveAttribute('aria-pressed', 'true')
        expect(summary).toHaveTextContent(branch)
      }
    }
    expect(screen.getByText('Selección visual sin contexto de seguridad ni permisos efectivos')).toBeVisible()
    navigate('Empresas')
    expect(screen.getByRole('button', { name: 'Ver detalle de Aurora Tecnología' })).toBeInTheDocument()
    navigate('Contexto (demo)')
    expect(screen.getByRole('status')).toHaveTextContent('Sin empresa seleccionada')
  })

  it('handles a company with no branches without inventing a location', () => {
    const fixtures = [...demo.Sucursales]
    demo.Sucursales.splice(0)
    try {
      render(<App />)
      navigate('Contexto (demo)')
      choose('Aurora Tecnología')
      expect(screen.getByText('Esta empresa no tiene sucursales disponibles en la demostración.')).toBeVisible()
      expect(screen.getByRole('status')).toHaveTextContent('Sin sucursal seleccionada')
    } finally { demo.Sucursales.push(...fixtures) }
  })

  it('keeps every session action disabled and makes no requests or persistence writes', () => {
    const fetchRequest = vi.spyOn(globalThis, 'fetch').mockImplementation(() => { throw new Error('Unexpected request') })
    const xhrRequest = vi.spyOn(XMLHttpRequest.prototype, 'open')
    const storageWrite = vi.spyOn(Storage.prototype, 'setItem')
    const cookieWrite = vi.spyOn(document, 'cookie', 'set')
    try {
      render(<App />)
      navigate('Contexto (demo)')
      choose('Aurora Tecnología')
      choose('Oficina Providencia')
      navigate('Sesiones (demo)')
      const preview = screen.getByRole('region', { name: 'Sesiones y dispositivos de demostración' })
      expect(within(preview).getAllByRole('article')).toHaveLength(3)
      expect(within(preview).getByText('Sesión de ejemplo · no autenticada')).toBeVisible()
      const actions = within(preview).getAllByRole('button')
      expect(actions).toHaveLength(6)
      for (const action of actions) { expect(action).toBeDisabled(); fireEvent.click(action) }
      expect(screen.getByText(warning)).toBeVisible()
      expect(fetchRequest).not.toHaveBeenCalled()
      expect(xhrRequest).not.toHaveBeenCalled()
      expect(storageWrite).not.toHaveBeenCalled()
      expect(cookieWrite).not.toHaveBeenCalled()
    } finally { vi.restoreAllMocks() }
  })
})
