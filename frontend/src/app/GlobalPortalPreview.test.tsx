import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { demo } from './demo'

function openPortal() {
  fireEvent.click(screen.getByRole('button', { name: 'Explorar portal global' }))
}
function filter(name: string) {
  fireEvent.click(within(screen.getByRole('group', { name: 'Filtrar empresas por estado' })).getByRole('button', { name }))
}
function search(value: string) { fireEvent.change(screen.getByRole('searchbox'), { target: { value } }) }

describe('Global portal visual preview', () => {
  it('opens from Dashboard, preserves the navigation and reports fixture counts and unavailable data', () => {
    render(<App />)
    const menu = within(screen.getByRole('navigation')).getAllByRole('button').map(button => button.textContent)
    openPortal()
    expect(screen.getByRole('heading', { level: 1, name: 'Portal global (demo)' })).toHaveFocus()
    expect(within(screen.getByRole('navigation')).getAllByRole('button').map(button => button.textContent)).toEqual(menu)
    expect(screen.getByText('Modo demostración · Sin autenticación ni conexión al backend · No ingresar datos reales')).toBeVisible()
    expect(screen.getByText('Portal global ficticio · Sin funciones administrativas')).toBeVisible()
    const metrics = screen.getByRole('region', { name: 'Indicadores de empresas ficticias' })
    for (const [label, value] of [['Total de empresas', '3'], ['Activo', '2'], ['Pendiente', '1'], ['Suspendido', '0']]) {
      expect(within(metrics).getByText(label).parentElement?.querySelector('strong')).toHaveTextContent(value)
    }
    expect(within(metrics).getByText('Total de empresas').parentElement?.querySelector('strong')).toHaveTextContent(String(demo.Empresas.length))
    const information = screen.getByRole('region', { name: 'Información administrativa no disponible' })
    expect(within(information).getAllByText('Sin datos disponibles')).toHaveLength(3)
    expect(information).toHaveTextContent('No hay un administrador inicial asignado ni verificado')
    expect(information).toHaveTextContent('no contiene eventos de auditoría reales')
    fireEvent.click(screen.getByRole('button', { name: 'Volver al Dashboard' }))
    expect(screen.getByRole('heading', { level: 1 })).toHaveFocus()
    expect(screen.getByRole('button', { name: 'Explorar portal global' })).toBeVisible()
  })

  it('searches only names, combines state filters and handles zero suspended companies', () => {
    render(<App />)
    openPortal()
    search('  AURORA  ')
    expect(screen.getByRole('button', { name: 'Seleccionar Aurora Tecnología' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Seleccionar Norte Logística' })).not.toBeInTheDocument()
    filter('Pendiente')
    expect(screen.getByRole('status')).toHaveTextContent('No encontramos coincidencias')
    fireEvent.click(screen.getByRole('button', { name: 'Limpiar búsqueda' }))
    expect(screen.getByRole('button', { name: 'Seleccionar Estudio Horizonte' })).toBeInTheDocument()
    filter('Activo')
    expect(screen.queryByRole('button', { name: 'Seleccionar Estudio Horizonte' })).not.toBeInTheDocument()
    expect(screen.getByRole('table')).toHaveTextContent('Norte Logística')
    filter('Suspendido')
    expect(screen.getByRole('status')).toHaveTextContent('cambia el filtro de estado')
    expect(screen.getByText('0 de 3 registros de demostración')).toBeInTheDocument()
    filter('Todos')
    search('DEMO-EMP-01')
    expect(screen.getByRole('status')).toHaveTextContent('No encontramos coincidencias')
    search('')
    expect(screen.getAllByRole('button', { name: /^Seleccionar / })).toHaveLength(3)
  })

  it('shows existing company details and clears selection when the result set changes', () => {
    render(<App />)
    openPortal()
    for (const item of demo.Empresas) {
      fireEvent.click(screen.getByRole('button', { name: `Seleccionar ${item.name}` }))
      const detail = screen.getByRole('complementary', { name: 'Detalle de demostración' })
      for (const value of [item.name, item.id, item.status, ...item.details.flat()]) { expect(detail).toHaveTextContent(value) }
      fireEvent.click(screen.getByRole('button', { name: 'Cerrar detalle' }))
    }
    fireEvent.click(screen.getByRole('button', { name: 'Seleccionar Aurora Tecnología' }))
    search('Norte')
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Seleccionar Norte Logística' }))
    filter('Pendiente')
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
  })

  it('keeps exploration in memory without requests, cookies or storage access', () => {
    const request = vi.spyOn(globalThis, 'fetch').mockImplementation(() => { throw new Error('Unexpected request') })
    const xhr = vi.spyOn(XMLHttpRequest.prototype, 'open')
    const storageRead = vi.spyOn(Storage.prototype, 'getItem')
    const storageWrite = vi.spyOn(Storage.prototype, 'setItem')
    const cookie = vi.spyOn(document, 'cookie', 'set')
    try {
      render(<App />)
      openPortal()
      search('Aurora')
      fireEvent.click(screen.getByRole('button', { name: 'Seleccionar Aurora Tecnología' }))
      filter('Suspendido')
      fireEvent.click(screen.getByRole('button', { name: 'Volver al Dashboard' }))
      openPortal()
      expect(screen.getByRole('searchbox')).toHaveValue('')
      expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
      expect(screen.getAllByRole('button', { name: /^Seleccionar / })).toHaveLength(3)
      for (const spy of [request, xhr, storageRead, storageWrite, cookie]) { expect(spy).not.toHaveBeenCalled() }
    } finally { vi.restoreAllMocks() }
  })
})
