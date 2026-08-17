import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn } from 'storybook/test'
import { QueueTicketTable } from './QueueTicketTable'

const items = [
  {
    assignee: '김상담',
    group: '고객지원',
    priority: 'HIGH' as const,
    requester: '홍길동',
    sla: {
      metric: 'FIRST_REPLY' as const,
      state: 'AT_RISK' as const,
      dueAt: '2026-08-17T04:30:00Z',
      targetMinutes: 60,
      policyVersion: 3,
      scheduleVersion: 7,
    },
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
    sla: null,
    status: 'PENDING' as const,
    subject: '계정 이메일 변경 요청',
    ticketNumber: 1041,
    updatedAt: '2024-04-01T10:20:00Z',
    updatedLabel: '1시간 전',
  },
]

const meta = {
  title: '04 Patterns/QueueTicketTable',
  component: QueueTicketTable,
  parameters: {
    docs: {
      description: {
        component:
          'Agent Queue의 고밀도 ticket list에 사용한다. native table header와 정렬 상태를 유지하고, row open은 link semantics와 keyboard 이동을 제공하며 list/prefetch 자체는 semantic TICKET_VIEWED를 만들지 않는다.',
      },
    },
  },
  tags: ['autodocs'],
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
  play: async ({ args, canvas, userEvent }) => {
    await userEvent.click(
      canvas.getByRole('checkbox', { name: '티켓 #1041 선택' }),
    )
    await expect(args.onSelectionChange).toHaveBeenCalledWith(1041, {
      orderedTicketNumbers: [1042, 1041],
      range: false,
    })
  },
}

export const LongContent: Story = {
  args: {
    items: items.map((item, index) =>
      index === 0
        ? {
            ...item,
            requester: 'Alexander Kim-Jiyeon Very-Long-Customer-Name',
            subject:
              '결제 승인 오류 이후 주문 생성과 영수증 발행이 함께 지연되는 현상에 대한 매우 긴 문의 제목',
          }
        : item,
    ),
    label: '긴 콘텐츠 티켓',
  },
}

export const KeyboardNavigation: Story = {
  args: {
    items,
    label: '내 담당 티켓',
    onOpenTicket: fn(),
    onSelectionChange: fn(),
  },
  play: async ({ canvas, userEvent }) => {
    const firstLink = canvas.getByRole('link', {
      name: /티켓 #1042/,
    })
    const secondLink = canvas.getByRole('link', {
      name: /티켓 #1041/,
    })
    firstLink.focus()
    await userEvent.keyboard('{ArrowDown}')
    await expect(secondLink).toHaveFocus()
    await userEvent.keyboard(' ')
  },
}
