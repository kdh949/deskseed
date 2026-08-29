import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn } from 'storybook/test'
import type { AgentTicketBatchCommand } from '../../api/types'
import { BulkTicketActionPanel } from './BulkTicketActionPanel'

const ticket = {
  ticketNumber: 1042,
  subject: '결제 승인 오류',
  status: 'OPEN' as const,
  priority: 'HIGH' as const,
  requester: { id: null, type: 'CUSTOMER' as const, displayName: '김민수' },
  group: { id: '11111111-1111-4111-8111-111111111111', name: '결제 지원' },
  assignee: null,
  createdAt: '2026-08-17T02:30:00Z',
  updatedAt: '2026-08-17T03:00:00Z',
  version: 7,
  isChild: false,
  openChildCount: 0,
  sla: null,
}

const options = {
  groups: [
    {
      id: '11111111-1111-4111-8111-111111111111',
      name: '결제 지원',
      members: [
        { id: '22222222-2222-4222-8222-222222222222', displayName: '김상담' },
      ],
    },
  ],
}

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false } },
})

const meta = {
  title: '06 Domain & Workspace/BulkTicketActionPanel',
  component: BulkTicketActionPanel,
  decorators: [
    (Story) => (
      <QueryClientProvider client={queryClient}>
        <Story />
      </QueryClientProvider>
    ),
  ],
  tags: ['autodocs'],
} satisfies Meta<typeof BulkTicketActionPanel>

export default meta
type Story = StoryObj<typeof meta>

export const PartialSuccess: Story = {
  args: {
    execute: fn(async (command: AgentTicketBatchCommand) => ({
      correlationId: 'bulk-correlation',
      results: command.items.map((item, index) => ({
        ticketNumber: item.ticketNumber,
        clientCommandId: item.clientCommandId,
        outcome: index === 0 ? ('CONFLICT' as const) : ('SUCCEEDED' as const),
        replayed: false,
        resultVersion: index === 0 ? null : item.expectedVersion + 1,
        auditId: index === 0 ? null : '33333333-3333-4333-8333-333333333333',
        code: index === 0 ? ('VERSION_PRECONDITION_FAILED' as const) : null,
      })),
    })),
    options,
    tickets: [ticket, { ...ticket, ticketNumber: 1043, version: 2 }],
  },
  play: async ({ canvas, userEvent }) => {
    await userEvent.click(canvas.getByRole('button', { name: '일괄 작업' }))
    await userEvent.click(canvas.getByRole('button', { name: '실행 전 확인' }))
    await expect(canvas.getByText('2개 티켓에 적용할까요?')).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '확인하고 실행' }))
    await expect(canvas.getByText('충돌')).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '실패한 1개 다시 시도' }),
    ).toBeVisible()
  },
}
