import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn } from 'storybook/test'
import { useState } from 'react'
import {
  QueueTicketTable,
  type QueueTicketSort,
  type QueueTicketTableProps,
} from './QueueTicketTable'

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
          'Agent Queue의 고밀도 ticket list에 사용한다. 서버 pagination에서는 받은 item 순서를 보존한다. 정렬은 sort/onSortChange controlled props로 route가 서버 요청과 cursor 초기화를 함께 소유한다. visibleColumns는 저장된 순서대로 렌더링한다. row open은 link semantics와 keyboard 이동을 제공하며 list/prefetch 자체는 semantic TICKET_VIEWED를 만들지 않는다.',
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
  play: async ({ canvas }) => {
    const rows = canvas.getAllByRole('row')
    await expect(rows[1]).toHaveTextContent('#1042')
    await expect(rows[2]).toHaveTextContent('#1041')
    await expect(canvas.queryByRole('button', { name: /티켓 ID/ })).toBeNull()
  },
}

export const ServerControlledSortAndColumns: Story = {
  args: {
    items,
    label: '서버 정렬 티켓',
    visibleColumns: ['ticketNumber', 'subject', 'status'],
  },
  render: (args) => <ControlledQueue {...args} />,
  play: async ({ canvas, userEvent }) => {
    await expect(canvas.getAllByRole('columnheader')).toHaveLength(4)
    await userEvent.click(
      canvas.getByRole('button', { name: '티켓 ID 내림차순' }),
    )
    await expect(canvas.getAllByRole('row')[1]).toHaveTextContent('#1041')
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

function ControlledQueue({
  items: initialItems,
  ...args
}: QueueTicketTableProps) {
  const [renderedItems, setRenderedItems] = useState(initialItems)
  const [sort, setSort] = useState<QueueTicketSort>({
    key: 'ticketNumber',
    direction: 'descending',
  })
  return (
    <QueueTicketTable
      {...args}
      items={renderedItems}
      onSortChange={(nextSort) => {
        setSort(nextSort)
        setRenderedItems((current) => [...current].reverse())
      }}
      sort={sort}
    />
  )
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
