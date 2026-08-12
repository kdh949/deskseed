import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type * as ApiClient from '../api/client'
import type { ExternalSystem } from '../api/types'
import { ExternalSystemsPage } from './ExternalSystemsPage'

const apiMocks = vi.hoisted(() => ({
  listExternalSystems: vi.fn(),
  createExternalSystem: vi.fn(),
  updateExternalSystem: vi.fn(),
}))

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof ApiClient>('../api/client')
  return { ...actual, ...apiMocks }
})

const system: ExternalSystem = {
  id: '11111111-1111-4111-8111-111111111111',
  systemKey: 'shop-order',
  displayName: '주문 운영',
  status: 'ACTIVE',
  allowedHostnames: ['admin.shop.example'],
  createdAt: '2026-08-12T00:00:00Z',
  updatedAt: '2026-08-12T00:00:00Z',
  version: 0,
}

describe('ExternalSystemsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.listExternalSystems.mockResolvedValue([system])
    apiMocks.createExternalSystem.mockResolvedValue(system)
    apiMocks.updateExternalSystem.mockResolvedValue({
      ...system,
      status: 'DISABLED',
      version: 1,
    })
  })

  it('registers exact hosts and updates the versioned status policy', async () => {
    const user = userEvent.setup()
    render(<ExternalSystemsPage />)

    expect(await screen.findByText('주문 운영')).toBeVisible()
    await user.type(screen.getByLabelText(/System key/), 'shop-payment')
    await user.type(screen.getByLabelText('표시 이름'), '결제 운영')
    await user.type(
      screen.getByLabelText(/허용 HTTPS hostname ·/),
      'pay.shop.example\npay-admin.shop.example',
    )
    await user.click(screen.getByRole('button', { name: '외부 시스템 등록' }))
    await waitFor(() =>
      expect(apiMocks.createExternalSystem).toHaveBeenCalledWith({
        systemKey: 'shop-payment',
        displayName: '결제 운영',
        allowedHostnames: ['pay.shop.example', 'pay-admin.shop.example'],
      }),
    )

    await user.click(screen.getByRole('button', { name: '정책 편집' }))
    const dialog = screen.getByRole('dialog')
    expect(dialog).toBeVisible()
    expect(within(dialog).getByLabelText('표시 이름')).toHaveFocus()
    await user.selectOptions(within(dialog).getByLabelText('상태'), 'DISABLED')
    await user.click(screen.getByRole('button', { name: '정책 저장' }))
    await waitFor(() =>
      expect(apiMocks.updateExternalSystem).toHaveBeenCalledWith(
        system.id,
        expect.objectContaining({ status: 'DISABLED', expectedVersion: 0 }),
      ),
    )
  })
})
