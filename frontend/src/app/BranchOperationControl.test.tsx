import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import { BranchOperationControl } from './BranchOperationControl'

it('reflects persisted states and preserves the distinction between history and operation authority', () => {
  const { rerender } = render(<BranchOperationControl branchStatus="ACTIVE" tenantStatus="ACTIVE" />)
  expect(screen.getByText(/El estado ACTIVE no concede acceso/)).toBeTruthy()
  rerender(<BranchOperationControl branchStatus="SUSPENDED" tenantStatus="ACTIVE" />)
  expect(screen.getByText(/bloquea nuevas operaciones y selección/)).toBeTruthy()
  expect(screen.getByText(/no se recuperan al reactivar/)).toBeTruthy()
  expect(screen.getByText(/no concede permisos de negocio ni acceso multisucursal/)).toBeTruthy()
  rerender(<BranchOperationControl branchStatus="CLOSED" tenantStatus="ACTIVE" />)
  expect(screen.getByText(/bloqueo permanente/)).toBeTruthy()
  expect(screen.queryByRole('button')).toBeNull()
  rerender(<BranchOperationControl branchStatus="ACTIVE" tenantStatus="SUSPENDED" />)
  expect(screen.getByText(/La empresa no está ACTIVE/)).toBeTruthy()
})
