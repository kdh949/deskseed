import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { HttpResponse, http } from 'msw'
import { AgentSearchPage } from './AgentSearchPage'

const ticket = {
  ticketNumber: 1042,
  subject: '중복 결제 확인',
  status: 'OPEN',
  priority: 'HIGH',
  requester: { id: null, type: 'CUSTOMER', displayName: '김민수' },
  group: { id: '11111111-1111-4111-8111-111111111111', name: '결제 지원' },
  assignee: null,
  updatedAt: '2026-08-17T03:00:00Z',
  version: 7,
  isChild: false,
  openChildCount: 0,
  sla: {
    metric: 'FIRST_REPLY',
    state: 'AT_RISK',
    dueAt: '2026-08-17T04:30:00Z',
    targetMinutes: 60,
    policyVersion: 3,
    scheduleVersion: 7,
  },
}

const meta = {
  title: '06 Domain & Workspace/AgentSearchPage',
  component: AgentSearchPage,
  decorators: [
    (Story) => (
      <QueryClientProvider
        client={
          new QueryClient({ defaultOptions: { queries: { retry: false } } })
        }
      >
        <Story />
      </QueryClientProvider>
    ),
  ],
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/assignment-options', () =>
          HttpResponse.json({ groups: [] }),
        ),
        http.get('/api/v1/agent/csrf', () =>
          HttpResponse.json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' }),
        ),
        http.post('/api/v1/agent/search', () =>
          HttpResponse.json({
            searchEventId: '33333333-3333-4333-8333-333333333333',
            searchInteractionId: '44444444-4444-4444-8444-444444444444',
            items: [ticket],
            resultCount: 1,
            sort: 'score:desc,ticketNumber:desc',
            nextCursor: null,
          }),
        ),
      ],
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof AgentSearchPage>

export default meta
type Story = StoryObj<typeof meta>

export const SearchResults: Story = {
  play: async ({ canvas, userEvent }) => {
    await userEvent.type(
      canvas.getByLabelText('서버 전체 티켓 검색어'),
      '중복 결제',
    )
    await userEvent.click(
      canvas.getByRole('button', { name: '서버 전체 검색' }),
    )
    await expect(canvas.getByText('정확한 전체 결과 1개')).toBeVisible()
    await expect(canvas.getByLabelText('최초 답변 SLA 위험')).toBeVisible()
  },
}
