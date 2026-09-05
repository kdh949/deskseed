import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect, userEvent } from 'storybook/test'
import { StoryRoute } from '../../../.storybook/StoryRoute'
import { AgentViewsPage } from './AgentViewsPage'

const views = [
  {
    id: '00000000-0000-4000-8000-000000000001',
    key: 'my-open',
    name: '내 open',
    scope: 'SYSTEM',
    ownerStaffId: null,
    active: true,
    description: '상담 운영에 사용하는 보기입니다.',
    categoryPath: ['Views'],
    definitionVersion: 1,
    orderVersion: 1,
    conditions: {
      version: 1,
      all: [{ field: 'STATUS', operator: 'LESS_THAN_SOLVED', values: [] }],
      any: [],
    },
    columns: ['TICKET_NUMBER', 'SUBJECT', 'STATUS'],
    sort: 'updatedAt:desc,ticketNumber:desc',
    ticketCount: null,
    ticketCountState: 'OMITTED_VISIBLE_LIMIT',
    ticketCountAsOf: null,
    readScope: 'ALL_TICKETS',
    createdAt: '2026-08-10T00:00:00Z',
    updatedAt: '2026-08-10T00:00:00Z',
  },
]

const tickets = {
  items: [
    {
      ticketNumber: 1042,
      subject: '결제 승인 오류',
      status: 'OPEN',
      priority: 'NORMAL',
      requester: {
        id: 'customer-1042',
        type: 'CUSTOMER',
        displayName: '김민수',
      },
      group: { id: 'group-payments', name: '결제 지원' },
      assignee: { id: 'agent-1042', displayName: '상담사' },
      createdAt: '2026-08-10T09:00:00Z',
      updatedAt: '2026-08-10T10:02:00Z',
      version: 3,
      isChild: false,
      openChildCount: 0,
      sla: null,
    },
  ],
  nextCursor: null,
  totalApproximate: null,
  sort: 'updatedAt:desc,ticketNumber:desc',
}

const meta = {
  title: '07 Screens/Agent Views Page',
  component: AgentViewsPage,
  parameters: {
    layout: 'fullscreen',
    msw: {
      handlers: [
        http.get('/api/v1/agent/views', () => HttpResponse.json(views)),
        http.get('/api/v1/agent/assignment-options', () =>
          HttpResponse.json({ groups: [] }),
        ),
        http.get('/api/v1/agent/views/:viewKey/tickets', () =>
          HttpResponse.json(tickets),
        ),
      ],
    },
  },
  render: () => (
    <StoryRoute path="/agent/views/:viewKey" to="/agent/views/my-open">
      <AgentViewsPage />
    </StoryRoute>
  ),
  tags: ['autodocs'],
} satisfies Meta<typeof AgentViewsPage>

export default meta
type Story = StoryObj<typeof meta>

export const Queue: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: '내 티켓' }),
    ).toBeVisible()
    await expect(canvas.queryByText('TICKET QUEUE')).not.toBeInTheDocument()
    await expect(canvas.queryByText('WORKSPACE')).not.toBeInTheDocument()
    await userEvent.click(await canvas.findByLabelText('티켓 #1042 선택'))
    await expect(canvas.getByText('1개 선택됨')).toBeVisible()
  },
}
