import type { Meta, StoryObj } from '@storybook/react-vite'
import { delay, http, HttpResponse } from 'msw'
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
      all: [
        { field: 'STATUS', operator: 'EQUALS', values: ['OPEN'] },
        { field: 'ASSIGNEE', operator: 'IS_CURRENT_ACTOR', values: [] },
      ],
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
        http.get('/api/v1/agent/ticket-configuration/filter-catalog', () =>
          HttpResponse.json({ fields: [], tags: [], forms: [], statuses: [] }),
        ),
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
      await canvas.findByRole('heading', { name: '내 처리 중 티켓' }),
    ).toBeVisible()
    await userEvent.click(await canvas.findByLabelText('티켓 #1042 선택'))
    await expect(canvas.getByText('1개 선택됨')).toBeVisible()
  },
}

const viewStateHandlers = (response: () => Response | Promise<Response>) => [
  http.get('/api/v1/agent/views', response),
  http.get('/api/v1/agent/assignment-options', () =>
    HttpResponse.json({ groups: [] }),
  ),
  http.get('/api/v1/agent/views/:viewKey/tickets', () =>
    HttpResponse.json(tickets),
  ),
]

export const ViewListLoading: Story = {
  parameters: {
    msw: {
      handlers: viewStateHandlers(async () => {
        await delay('infinite')
        return HttpResponse.json(views)
      }),
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByLabelText('보기 목록 불러오는 중'),
    ).toBeVisible()
    await expect(await canvas.findByText('결제 승인 오류')).toBeVisible()
  },
}

export const ViewListError: Story = {
  parameters: {
    msw: {
      handlers: viewStateHandlers(() =>
        HttpResponse.json(
          { title: 'Unavailable', status: 503, requestId: 'view-list-example' },
          { status: 503 },
        ),
      ),
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('보기 목록을 불러오지 못했습니다'),
    ).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '보기 목록 다시 시도' }),
    ).toBeVisible()
    await expect(await canvas.findByText('결제 승인 오류')).toBeVisible()
    await expect(
      canvas.queryByText('일치하는 보기가 없습니다.'),
    ).not.toBeInTheDocument()
  },
}

export const ViewListDenied: Story = {
  parameters: {
    msw: {
      handlers: viewStateHandlers(() =>
        HttpResponse.json({ title: 'Forbidden', status: 403 }, { status: 403 }),
      ),
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('보기 목록에 접근할 권한이 없습니다'),
    ).toBeVisible()
  },
}

export const ViewListEmpty: Story = {
  parameters: {
    msw: { handlers: viewStateHandlers(() => HttpResponse.json([])) },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('표시할 저장 보기가 없습니다'),
    ).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '티켓 검색 열기' }),
    ).toBeVisible()
  },
}

export const MyOpenEmpty: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/views', () => HttpResponse.json(views)),
        http.get('/api/v1/agent/assignment-options', () =>
          HttpResponse.json({ groups: [] }),
        ),
        http.get('/api/v1/agent/views/:viewKey/tickets', () =>
          HttpResponse.json({ ...tickets, items: [] }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('내게 배정된 처리 중 티켓이 없습니다'),
    ).toBeVisible()
    await expect(
      canvas.getByText(
        '신규·고객 답변 대기·보류 티켓은 다른 보기나 티켓 검색에서 확인하세요.',
      ),
    ).toBeVisible()
  },
}
