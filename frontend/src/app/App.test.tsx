import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { sections } from './demo'

function navigate(name: string) {
  fireEvent.click(within(screen.getByRole('navigation')).getByRole('button', { name }))
}

describe('Administrative visual preview', () => {
  it('renders identity, demonstration notice and accessible structure', () => {
    render(<App />)
    expect(screen.getByRole('link', { name: 'LS Core, inicio' })).toHaveAttribute('href', '#inicio')
    const logo = screen.getByRole('img', { name: 'LS Soluciones — Ingeniería y Consultoría' })
    expect(logo).toHaveAttribute('src', '/branding/logoLS_Vertical.png')
    expect(logo).toHaveAttribute('width', '2172')
    expect(logo).toHaveAttribute('height', '724')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Todo conectado')
    expect(screen.getByText('Modo demostración — Sin conexión al backend')).toBeVisible()
    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(screen.getByRole('main')).toBeInTheDocument()
    expect(screen.getByRole('contentinfo')).toHaveTextContent('F12 en Backlog')
    expect(screen.getByRole('region', { name: 'Resumen de datos ficticios' })).toBeInTheDocument()
  })

  it('navigates all directories, keeps the demo notice and returns home', () => {
    render(<App />)
    for (const name of sections.slice(1)) {
      navigate(name)
      expect(screen.getByRole('heading', { level: 1, name })).toHaveFocus()
      expect(within(screen.getByRole('navigation')).getByRole('button', { name })).toHaveAttribute('aria-current', 'page')
      expect(screen.getByText('Modo demostración — Sin conexión al backend')).toBeVisible()
      expect(screen.getByRole('region', { name: `${name}: datos de demostración` })).toBeInTheDocument()
    }
    fireEvent.click(screen.getByRole('link', { name: 'LS Core, inicio' }))
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Todo conectado')
  })

  it('selects a demo record, clears detail on filtering and recovers from empty results', () => {
    render(<App />)
    fireEvent.click(screen.getByRole('button', { name: 'Explorar empresas' }))
    fireEvent.click(screen.getByRole('button', { name: 'Ver detalle de Aurora Tecnología' }))
    const detail = screen.getByRole('complementary', { name: 'Detalle de demostración' })
    expect(within(detail).getByText('DEMO-EMP-01')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Seleccionar Aurora Tecnología' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'sin coincidencias' } })
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('No encontramos coincidencias')
    fireEvent.click(screen.getByRole('button', { name: 'Limpiar búsqueda' }))
    expect(screen.getByRole('button', { name: 'Ver detalle de Aurora Tecnología' })).toBeInTheDocument()
  })

  it('opens and closes the mobile navigation and resets selection between sections', () => {
    render(<App />)
    fireEvent.click(screen.getByRole('button', { name: 'Abrir menú' }))
    expect(screen.getByRole('button', { name: 'Cerrar menú' })).toHaveAttribute('aria-expanded', 'true')
    navigate('Aplicaciones')
    expect(screen.getByRole('button', { name: 'Abrir menú' })).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(screen.getByRole('button', { name: /LS Core.*Ver ficha de muestra/ }))
    expect(screen.getByRole('complementary')).toHaveTextContent('DEMO-APP-01')
    fireEvent.click(screen.getByRole('button', { name: 'Cerrar detalle' }))
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /LS Core.*Ver ficha de muestra/ }))
    navigate('Usuarios')
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
    expect(screen.getByRole('searchbox')).toHaveValue('')
  })

  it('does not request a backend or persist data throughout navigation and filtering', () => {
    const fetchRequest = vi.spyOn(globalThis, 'fetch').mockImplementation(() => { throw new Error('Unexpected backend request') })
    const xhrRequest = vi.spyOn(XMLHttpRequest.prototype, 'open')
    const storageWrite = vi.spyOn(Storage.prototype, 'setItem')
    try {
      render(<App />)
      for (const name of sections.slice(1)) {
        navigate(name)
        fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'demo' } })
      }
      expect(fetchRequest).not.toHaveBeenCalled()
      expect(xhrRequest).not.toHaveBeenCalled()
      expect(storageWrite).not.toHaveBeenCalled()
      expect(document.cookie).toBe('')
    } finally { vi.restoreAllMocks() }
  })
})
