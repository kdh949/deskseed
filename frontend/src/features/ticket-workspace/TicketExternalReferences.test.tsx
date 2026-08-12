import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type * as ApiClient from '../../api/client'
import type { ExternalReferenceContext } from '../../api/types'
import { TicketExternalReferences } from './TicketExternalReferences'

const apiMocks = vi.hoisted(() => ({
  listTicketExternalReferences: vi.fn(),
  createTicketExternalReference: vi.fn(),
  deleteTicketExternalReference: vi.fn(),
}))

vi.mock('../../api/client', async () => {
  const actual = await vi.importActual<typeof ApiClient>('../../api/client')
  return { ...actual, ...apiMocks }
})

const system = {
  id: '11111111-1111-4111-8111-111111111111',
  systemKey: 'shop-order',
  displayName: '주문 운영',
  status: 'ACTIVE' as const,
  allowedHostnames: ['admin.shop.example'],
  createdAt: '2026-08-12T00:00:00Z',
  updatedAt: '2026-08-12T00:00:00Z',
  version: 0,
}

const context: ExternalReferenceContext = {
  ticketVersion: 7,
  canManage: true,
  availableSystems: [system],
  items: [
    {
      id: '22222222-2222-4222-8222-222222222222',
      system,
      objectType: 'ORDER',
      externalId: 'order-100',
      displayLabel: '주문 100',
      linkState: 'AVAILABLE',
      safeDeepLink: 'https://admin.shop.example/orders/100',
      metadata: { status: 'paid', amountDisplay: 12900 },
      metadataObservedAt: '2026-08-12T00:00:00Z',
      createdBy: {
        actorId: '33333333-3333-4333-8333-333333333333',
        displayName: '상담사',
      },
      createdAt: '2026-08-12T00:00:00Z',
    },
  ],
}

describe('TicketExternalReferences', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.listTicketExternalReferences.mockResolvedValue(context)
    apiMocks.createTicketExternalReference.mockResolvedValue({
      ticketVersion: 8,
      reference: context.items[0],
    })
    apiMocks.deleteTicketExternalReference.mockResolvedValue({
      ticketVersion: 8,
      removedReferenceId: context.items[0]!.id,
    })
  })

  it('opens only projected safe links and creates and removes with the current ticket version', async () => {
    const user = userEvent.setup()
    const completed = vi.fn().mockResolvedValue(undefined)
    renderComponent(completed)

    const link = await screen.findByRole('link', {
      name: '원본 새 창에서 열기',
    })
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(link).toHaveAttribute(
      'href',
      'https://admin.shop.example/orders/100',
    )

    await user.click(screen.getByText('외부 참조 연결'))
    await user.type(screen.getByLabelText('외부 ID'), 'order-200')
    await user.type(screen.getByLabelText('표시 이름'), '주문 200')
    await user.type(
      screen.getByLabelText('HTTPS 원본 링크'),
      'https://admin.shop.example/orders/200',
    )
    await user.type(screen.getByLabelText('상태'), 'paid')
    await user.click(screen.getByRole('button', { name: '참조 연결' }))
    await waitFor(() =>
      expect(apiMocks.createTicketExternalReference).toHaveBeenCalledWith(
        1001,
        expect.objectContaining({
          externalId: 'order-200',
          expectedVersion: 7,
          metadata: { status: 'paid' },
        }),
      ),
    )
    expect(completed).toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '연결 해제' }))
    await user.click(screen.getByRole('button', { name: '연결 해제 확인' }))
    await waitFor(() =>
      expect(apiMocks.deleteTicketExternalReference).toHaveBeenCalledWith(
        1001,
        context.items[0]!.id,
        7,
      ),
    )
  })
})

function renderComponent(onCommandCompleted: () => Promise<unknown>) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <TicketExternalReferences
        ticketNumber={1001}
        canUpdate
        active
        onCommandCompleted={onCommandCompleted}
      />
    </QueryClientProvider>,
  )
}
