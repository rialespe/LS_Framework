import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { App } from './App'

describe('App smoke', () => {
  it('renders the LS Core identity and Multi-Tenant version', () => {
    render(<App />)

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('LS Core')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Multi-Tenant v4.0')
    expect(screen.getByRole('link', { name: 'LS Core, inicio' })).toHaveAttribute('href', '#inicio')
  })

  it('provides the main accessible page structure', () => {
    render(<App />)

    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(screen.getByRole('main')).toBeInTheDocument()
    expect(screen.getAllByRole('region')).toHaveLength(2)
    expect(screen.getByRole('contentinfo')).toBeInTheDocument()
  })

  it('renders without requesting a backend connection', () => {
    const fetchRequest = vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
      throw new Error('Unexpected backend request')
    })
    const xhrRequest = vi.spyOn(XMLHttpRequest.prototype, 'open')
    try {
      render(<App />)

      expect(screen.getByRole('main')).toBeInTheDocument()
      expect(fetchRequest).not.toHaveBeenCalled()
      expect(xhrRequest).not.toHaveBeenCalled()
    } finally {
      vi.restoreAllMocks()
    }
  })
})
