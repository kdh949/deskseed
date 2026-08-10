import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AgentTicketWorkspacePage } from './AgentTicketWorkspacePage'

vi.mock('../staff-auth/StaffSessionContext', () => ({
  useStaffSession: () => ({
    status: 'authenticated',
    staff: { id: 'agent-id', displayName: '상담사', role: 'AGENT' },
  }),
}))

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
  context: {
    customer: {
      id: 'customer-1',
      displayName: '김민수',
      email: 'minsu@example.com',
    },
    parent: null,
    children: [],
    externalReferences: [],
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

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/agent/tickets/1042']}>
        <Routes>
          <Route
            path="/agent/tickets/:ticketNumber"
            element={<AgentTicketWorkspacePage />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('AgentTicketWorkspacePage', () => {
  it('renders the read-only three-panel staff projection with public and internal conversation', async () => {
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response(JSON.stringify(detail), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    renderPage()

    expect(
      await screen.findByRole('heading', { name: '결제 승인 오류' }),
    ).toBeVisible()
    expect(screen.getByRole('region', { name: '티켓 속성' })).toBeVisible()
    expect(screen.getByRole('region', { name: '대화' })).toBeVisible()
    expect(screen.getByRole('region', { name: '티켓 컨텍스트' })).toBeVisible()
    expect(screen.getByText('결제가 계속 실패합니다.')).toBeVisible()
    expect(screen.getByText('PG사 확인이 필요합니다.')).toBeVisible()
    expect(screen.getByText('내부 메모')).toBeVisible()
    expect(screen.getByText('읽기 전용')).toBeVisible()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()

    const request = fetchMock.mock.calls[0]!
    const requestOptions = request[1] as { headers: Record<string, string> }
    expect(request[0]).toBe('/api/v1/agent/tickets/1042')
    expect(requestOptions.headers['X-Deskseed-Read-Intent']).toBe('NAVIGATION')
    expect(requestOptions.headers['X-Interaction-Id']).toBeTruthy()
  })

  it('reuses the navigation interaction for refetch and supports keyboard panel controls', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response(JSON.stringify(detail), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    await screen.findByRole('heading', { name: '결제 승인 오류' })
    await user.click(screen.getByRole('button', { name: '티켓 새로고침' }))

    expect(fetchMock).toHaveBeenCalledTimes(2)
    const firstOptions = fetchMock.mock.calls[0]![1] as {
      headers: Record<string, string>
    }
    const secondOptions = fetchMock.mock.calls[1]![1] as {
      headers: Record<string, string>
    }
    expect(secondOptions.headers['X-Interaction-Id']).toBe(
      firstOptions.headers['X-Interaction-Id'],
    )

    const propertySeparator = screen.getByRole('separator', {
      name: '속성 패널 너비 조절',
    })
    propertySeparator.focus()
    await user.keyboard('{ArrowRight}')
    expect(
      localStorage.getItem('deskseed:agent:agent-id:workspace-panels:v1'),
    ).toContain('propertyWidth')

    await user.click(screen.getByRole('button', { name: '컨텍스트 패널 접기' }))
    expect(
      screen.queryByRole('region', { name: '티켓 컨텍스트' }),
    ).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '컨텍스트 패널 펼치기' }),
    ).toBeVisible()
  })

  it('shows a safe denied state with a request id', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            title: 'Forbidden',
            status: 403,
            requestId: 'request-safe-403',
          }),
          {
            status: 403,
            headers: { 'Content-Type': 'application/problem+json' },
          },
        ),
      ),
    )

    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '티켓을 열 수 없습니다.',
    )
    expect(screen.getByRole('alert')).toHaveTextContent('request-safe-403')
  })
})
