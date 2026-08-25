import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect } from 'storybook/test'
import { StoryRoute } from '../../../.storybook/StoryRoute'
import { mswHandlers } from '../../../.storybook/msw-handlers'
import { StaffSessionProvider } from '../staff-auth/StaffSessionContext'
import { AgentTicketWorkspacePage } from './AgentTicketWorkspacePage'

const detail = {
  ticket: {
    ticketNumber: 1042,
    subject: '결제 승인 오류',
    status: 'OPEN',
    priority: 'URGENT',
    requester: { id: 'customer-1', type: 'CUSTOMER', displayName: '김민수' },
    group: { id: 'group-payments', name: '결제 지원' },
    assignee: { id: 'agent-2', displayName: '다른 그룹 상담사' },
    updatedAt: '2026-08-10T10:02:00Z',
    version: 3,
    isChild: false,
    openChildCount: 0,
    sla: null,
  },
  comments: [
    {
      id: 'comment-public',
      visibility: 'PUBLIC',
      actor: { id: 'customer-1', type: 'CUSTOMER', displayName: '김민수' },
      body: '결제가 계속 실패합니다.',
      createdAt: '2026-08-10T09:00:00Z',
      source: 'WEB',
      attachments: [],
    },
    {
      id: 'comment-internal',
      visibility: 'INTERNAL',
      actor: { id: 'agent-2', type: 'STAFF', displayName: '다른 그룹 상담사' },
      body: 'PG사 확인이 필요합니다.',
      createdAt: '2026-08-10T09:30:00Z',
      source: 'STAFF_WEB',
      attachments: [],
    },
  ],
  capabilities: ['READ'],
  assignmentOptions: {
    groups: [
      {
        id: 'group-payments',
        name: '결제 지원',
        members: [{ id: 'agent-2', displayName: '다른 그룹 상담사' }],
      },
    ],
  },
  context: {
    customer: {
      id: 'customer-1',
      displayName: '김민수',
      email: 'minsu@example.test',
    },
    parent: null,
    children: [],
    externalReferenceCount: 0,
  },
  history: [
    {
      id: 'history-1',
      eventType: 'TICKET_CREATED',
      actor: { id: 'customer-1', type: 'CUSTOMER', displayName: '김민수' },
      occurredAt: '2026-08-10T09:00:00Z',
    },
  ],
  warnings: [],
}

const meta = {
  title: '07 Screens/Agent Ticket Workspace Page',
  component: AgentTicketWorkspacePage,
  parameters: {
    layout: 'fullscreen',
    msw: {
      handlers: [
        http.get('/api/v1/agent/me', () =>
          HttpResponse.json({
            id: 'agent-1',
            email: 'agent@example.test',
            displayName: '상담사',
            role: 'AGENT',
            capabilities: ['AGENT_WORKSPACE'],
          }),
        ),
        http.get('/api/v1/agent/tickets/1042', () => HttpResponse.json(detail)),
        ...mswHandlers,
      ],
    },
  },
  render: () => (
    <StaffSessionProvider>
      <StoryRoute path="/agent/tickets/:ticketNumber" to="/agent/tickets/1042">
        <AgentTicketWorkspacePage />
      </StoryRoute>
    </StaffSessionProvider>
  ),
  tags: ['autodocs'],
} satisfies Meta<typeof AgentTicketWorkspacePage>

export default meta
type Story = StoryObj<typeof meta>

export const ReadOnlyTicket: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: '결제 승인 오류' }),
    ).toBeVisible()
    await expect(canvas.getByText('INTERNAL · 직원 전용')).toBeVisible()
    await expect(
      canvas.getByText('현재 권한으로는 티켓을 수정할 수 없습니다.'),
    ).toBeVisible()
  },
}
