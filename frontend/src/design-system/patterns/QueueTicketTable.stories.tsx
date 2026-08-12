import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn } from 'storybook/test'
import { QueueTicketTable } from './QueueTicketTable'

const items = [
  {
    assignee: '김상담',
    group: '고객지원',
    priority: 'HIGH' as const,
    requester: '홍길동',
    status: 'OPEN' as const,
    subject: '결제 영수증을 다시 받을 수 있나요?',
    ticketNumber: 1042,
    updatedAt: '2024-04-01T11:45:00Z',
    updatedLabel: '15분 전',
  },
  {
    assignee: '박지원',
    group: '고객지원',
    isChild: true,
    priority: 'NORMAL' as const,
    requester: '이하늘',
    status: 'PENDING' as const,
    subject: '계정 이메일 변경 요청',
    ticketNumber: 1041,
    updatedAt: '2024-04-01T10:20:00Z',
    updatedLabel: '1시간 전',
  },
]

const meta = {
  component: QueueTicketTable,
  tags: ['ai-generated'],
} satisfies Meta<typeof QueueTicketTable>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  args: {
    items,
    label: '내 담당 티켓',
  },
  play: async ({ canvas, userEvent }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '티켓 ID 내림차순' }),
    )
    await expect(
      canvas.getByRole('columnheader', { name: /티켓 id/i }),
    ).toHaveAttribute('aria-sort', 'ascending')
  },
}

export const Selectable: Story = {
  args: {
    items,
    label: '내 담당 티켓',
    onSelectAll: fn(),
    onSelectionChange: fn(),
    selectedTicketNumbers: new Set([1042]),
  },
}
