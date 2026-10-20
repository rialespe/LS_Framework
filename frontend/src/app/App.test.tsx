import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { sections } from './demo'

function navigate(name: string) {
  fireEvent.click(within(screen.getByRole('navigation')).getByRole('button', { name }))
}

const accessWarning = 'Modo demostración · Sin autenticación ni conexión al backend · No ingresar datos reales'

describe('Access visual preview', () => {
  it('navigates all four views with the warning visible and credentials disabled', () => {
    render(<App />)
    navigate('Acceso (demo)')
    expect(screen.getByRole('heading', { level: 1, name: 'Acceso (demo)' })).toHaveFocus()
    const preview = screen.getByRole('region', { name: 'Previsualización de acceso' })
    const switcher = screen.getByRole('group', { name: 'Vistas de acceso de demostración' })
    const fields: Record<string, string[]> = {
      'Inicio de sesión': ['Usuario o correo', 'Contraseña'],
      'Primer ingreso': ['Contraseña temporal', 'Nueva contraseña', 'Confirmar nueva contraseña'],
      'Recuperación': ['Usuario o correo para recuperación'],
      'Sesión expirada': [],
    }
    for (const [view, labels] of Object.entries(fields)) {
      const control = within(switcher).getByRole('button', { name: view })
      fireEvent.click(control)
      expect(control).toHaveAttribute('aria-pressed', 'true')
      expect(within(preview).getByRole('heading', { level: 2, name: view })).toHaveFocus()
      expect(screen.getByText(accessWarning)).toBeVisible()
      expect(preview.querySelectorAll('input')).toHaveLength(labels.length)
      for (const label of labels) {
        const field = within(preview).getByLabelText(new RegExp(`^${label}`))
        expect(field).toBeDisabled()
        expect(field).toHaveValue('')
        expect(field).toHaveAttribute('readonly')
        expect(field).toHaveAttribute('autocomplete', 'off')
        expect(field).not.toHaveAttribute('name')
        if (label.includes('contraseña') || label === 'Contraseña') { expect(field).toHaveAttribute('type', 'password') }
      }
      for (const action of within(preview).queryAllByRole('button', { name: /No disponible/ })) {
        expect(action).toBeDisabled()
      }
      expect(preview.querySelector('form')).toBeNull()
    }
    navigate('Dashboard')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Todo conectado')
    expect(screen.getByText(accessWarning)).toBeVisible()
  })

  it('follows recovery, first entry and expired-session demo links without success messages', () => {
    render(<App />)
    navigate('Acceso (demo)')
    fireEvent.click(screen.getByRole('button', { name: '¿Olvidaste tu contraseña?' }))
    expect(screen.getByRole('heading', { level: 2, name: 'Recuperación' })).toHaveFocus()
    expect(screen.getByText(/no genera solicitudes ni envía correos/)).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /Volver al acceso/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Ver cambio de contraseña temporal' }))
    expect(screen.getByRole('heading', { level: 2, name: 'Primer ingreso' })).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: /Volver al acceso/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Sesión expirada' }))
    expect(screen.getByText(/No existe una sesión activa o expirada/)).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Volver al acceso' }))
    expect(screen.getByRole('heading', { level: 2, name: 'Inicio de sesión' })).toHaveFocus()
    expect(screen.queryByText(/correo enviado|contraseña cambiada|autenticación exitosa/i)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Abrir menú' }))
    navigate('Acceso (demo)')
    expect(screen.getByRole('button', { name: 'Abrir menú' })).toHaveAttribute('aria-expanded', 'false')
  })

  it('makes no requests or storage writes while exploring access', () => {
    const fetchRequest = vi.spyOn(globalThis, 'fetch').mockImplementation(() => { throw new Error('Unexpected request') })
    const xhrRequest = vi.spyOn(XMLHttpRequest.prototype, 'open')
    const storageWrite = vi.spyOn(Storage.prototype, 'setItem')
    try {
      render(<App />)
      navigate('Acceso (demo)')
      const switcher = screen.getByRole('group', { name: 'Vistas de acceso de demostración' })
      for (const button of within(switcher).getAllByRole('button')) { fireEvent.click(button) }
      fireEvent.click(screen.getByRole('button', { name: 'Volver al acceso' }))
      expect(fetchRequest).not.toHaveBeenCalled()
      expect(xhrRequest).not.toHaveBeenCalled()
      expect(storageWrite).not.toHaveBeenCalled()
      expect(document.cookie).toBe('')
    } finally { vi.restoreAllMocks() }
  })
})

describe('Administrative visual preview', () => {
  it('renders identity, demonstration notice and accessible structure', () => {
    render(<App />)
    expect(screen.getByRole('link', { name: 'LS Core, inicio' })).toHaveAttribute('href', '#inicio')
    const logo = screen.getByRole('img', { name: 'LS Soluciones — Ingeniería y Consultoría' })
    expect(logo).toHaveAttribute('src', '/branding/logoLS_Vertical.png')
    expect(logo).toHaveAttribute('width', '2172')
    expect(logo).toHaveAttribute('height', '724')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Todo conectado')
    expect(screen.getByText(accessWarning)).toBeVisible()
    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(screen.getByRole('main')).toBeInTheDocument()
    expect(screen.getByRole('contentinfo')).toHaveTextContent('F12 y LFD-56 en Backlog')
    expect(screen.getByRole('region', { name: 'Resumen de datos ficticios' })).toBeInTheDocument()
  })

  it('navigates all directories, keeps the demo notice and returns home', () => {
    render(<App />)
    for (const name of sections.slice(1)) {
      navigate(name)
      expect(screen.getByRole('heading', { level: 1, name })).toHaveFocus()
      expect(within(screen.getByRole('navigation')).getByRole('button', { name })).toHaveAttribute('aria-current', 'page')
      expect(screen.getByText(accessWarning)).toBeVisible()
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
