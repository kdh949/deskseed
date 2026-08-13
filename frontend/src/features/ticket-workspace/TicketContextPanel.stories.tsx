import { useState } from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import type { AgentTicketDetail } from '../../api/types'
import { TicketContextPanel } from './TicketContextPanel'

const meta = {
  title: '06 Domain & Workspace/TicketContextPanel',
  component: TicketContextPanel,
  parameters: {
    docs: {
      description: {
        component:
          'Customer, related internal work, recent activity를 conversation 옆에서 확인하는 context panel이다. staff projection만 사용하며 deferred app/audit UI를 임의로 추가하지 않는다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof TicketContextPanel>

export default meta
type Story = StoryObj<typeof meta>

const baseArgs = {
  activeTab: 'customer' as const,
  onTabChange: () => undefined,
}

export const Customer: Story = {
  args: baseArgs,
  render: () => <ContextExample initialTab="customer" />,
}

export const RelatedWork: Story = {
  args: baseArgs,
  render: () => <ContextExample initialTab="related" />,
}

export const Activity: Story = {
  args: baseArgs,
  render: () => <ContextExample initialTab="activity" />,
}

function ContextExample({
  initialTab,
}: {
  initialTab: 'customer' | 'related' | 'activity'
}) {
  const [activeTab, setActiveTab] = useState(initialTab)
  return (
    <TicketContextPanel
      activeTab={activeTab}
      detail={detailFixture}
      onTabChange={setActiveTab}
    />
  )
}

const detailFixture: AgentTicketDetail = {
  assignmentOptions: { groups: [] },
  capabilities: ['AGENT_WORKSPACE'],
  comments: [],
  context: {
    children: [
      {
        assignee: { displayName: 'Jae Lee', id: 'staff-jae' },
        group: { id: 'group-billing', name: 'Billing' },
        isChild: true,
        openChildCount: 0,
        priority: 'NORMAL',
        requester: {
          displayName: '김지연',
          id: 'customer-kim',
          type: 'CUSTOMER',
        },
        sla: null,
        status: 'OPEN',
        subject: 'PG 승인 로그 확인',
        ticketNumber: 1043,
        updatedAt: '2026-08-11T09:40:00Z',
        version: 1,
      },
    ],
    customer: {
      displayName: '김지연',
      email: 'jiyeon.kim@example.com',
      id: 'customer-kim',
    },
    externalReferences: [],
    parent: null,
  },
  history: [
    {
      actor: { displayName: 'Mina Park', id: 'staff-mina', type: 'STAFF' },
      eventType: 'PRIORITY_CHANGED',
      id: 'history-1',
      occurredAt: '2026-08-11T09:29:00Z',
    },
  ],
  ticket: {
    assignee: { displayName: 'Mina Park', id: 'staff-mina' },
    group: { id: 'group-billing', name: 'Billing' },
    isChild: false,
    openChildCount: 1,
    priority: 'HIGH',
    requester: { displayName: '김지연', id: 'customer-kim', type: 'CUSTOMER' },
    sla: null,
    status: 'OPEN',
    subject: '결제 버튼을 누르면 오류가 납니다',
    ticketNumber: 1042,
    updatedAt: '2026-08-11T09:33:00Z',
    version: 4,
  },
  warnings: [],
}
