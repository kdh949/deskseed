import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { HttpResponse, http } from 'msw'
import type { AgentTicketDetail } from '../../api/types'
import { TicketContextPanel, type ContextTab } from './TicketContextPanel'

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

export const ExternalReferences: Story = {
  args: baseArgs,
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/tickets/1042/external-references', () =>
          HttpResponse.json({
            ticketVersion: 4,
            canManage: true,
            availableSystems: [externalSystem],
            items: [
              {
                  id: '11111111-1111-4111-8111-111111111111',
                system: externalSystem,
                objectType: 'ORDER',
                externalId: 'ORD-2026-0042',
                displayLabel: '결제 주문 42',
                linkState: 'AVAILABLE',
                safeDeepLink: 'https://orders.example.test/orders/42',
                metadata: { state: 'PAID' },
                metadataObservedAt: '2026-08-17T03:10:00Z',
                createdBy: {
                    actorId: '22222222-2222-4222-8222-222222222222',
                  displayName: 'Mina Park',
                },
                createdAt: '2026-08-17T03:11:00Z',
              },
            ],
          }),
        ),
      ],
    },
  },
  render: () => (
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <ContextExample initialTab="external" />
    </QueryClientProvider>
  ),
  play: async ({ canvas }) => {
    await expect(await canvas.findByText('결제 주문 42')).toBeVisible()
    await expect(canvas.getByText(/ACTIVE · AVAILABLE/)).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '새 탭에서 열기' }),
    ).toBeEnabled()
    await expect(
      canvas.getByRole('heading', { name: '외부 참조 추가' }),
    ).toBeVisible()
  },
}

function ContextExample({ initialTab }: { initialTab: ContextTab }) {
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
    externalReferenceCount: 2,
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

const externalSystem = {
  id: '33333333-3333-4333-8333-333333333333',
  systemKey: 'orders',
  displayName: 'Order Console',
  status: 'ACTIVE' as const,
  allowedHostnames: ['orders.example.test'],
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-10T00:00:00Z',
  version: 2,
}
