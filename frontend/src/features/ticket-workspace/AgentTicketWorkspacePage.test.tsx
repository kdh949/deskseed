import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router'
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

function RouteNavigationControls() {
  const navigate = useNavigate()
  return (
    <>
      <button type="button" onClick={() => navigate('/agent/tickets/1042')}>
        티켓 1042 열기
      </button>
      <button type="button" onClick={() => navigate('/agent/tickets/1043')}>
        티켓 1043 열기
      </button>
    </>
  )
}

function renderPage(path = '/agent/tickets/1042') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <RouteNavigationControls />
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
      await screen.findByRole('heading', { name: /#1042.*결제 승인 오류/ }),
    ).toBeVisible()
    expect(
      screen.getByRole('complementary', { name: '티켓 속성' }),
    ).toBeVisible()
    expect(
      screen.getByRole('region', { name: '티켓 대화 및 답변' }),
    ).toBeVisible()
    expect(screen.getByRole('tabpanel', { name: 'Customer' })).toBeVisible()
    expect(screen.getByText('결제가 계속 실패합니다.')).toBeVisible()
    expect(screen.getByText('PG사 확인이 필요합니다.')).toBeVisible()
    expect(screen.getByText('INTERNAL')).toBeVisible()
    expect(
      screen.getByRole('button', { name: '내부 메모 추가' }),
    ).toBeDisabled()
    expect(
      screen.getByRole('textbox', { name: '내부 메모 내용' }),
    ).toBeVisible()

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
    await screen.findByRole('heading', { name: /#1042.*결제 승인 오류/ })
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
    expect(secondOptions.headers['X-Deskseed-Read-Intent']).toBe('BACKGROUND')

    await user.click(screen.getByRole('button', { name: '티켓 1043 열기' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    await user.click(screen.getByRole('button', { name: '티켓 1042 열기' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4))

    const thirdOptions = fetchMock.mock.calls[2]![1] as {
      headers: Record<string, string>
    }
    const fourthOptions = fetchMock.mock.calls[3]![1] as {
      headers: Record<string, string>
    }
    expect(thirdOptions.headers['X-Interaction-Id']).not.toBe(
      firstOptions.headers['X-Interaction-Id'],
    )
    expect(fourthOptions.headers['X-Interaction-Id']).not.toBe(
      firstOptions.headers['X-Interaction-Id'],
    )
    expect(fourthOptions.headers['X-Interaction-Id']).not.toBe(
      thirdOptions.headers['X-Interaction-Id'],
    )

    await user.click(screen.getByRole('button', { name: '티켓 속성 접기' }))
    expect(
      screen.getByRole('button', { name: '티켓 속성 펼치기' }),
    ).toBeVisible()
    await user.click(screen.getByRole('button', { name: '고객 맥락 열기' }))
    expect(screen.getByRole('button', { name: '고객 맥락 닫기' })).toBeVisible()
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

  it.each(['not-a-number', '0', '-1', '9007199254740992'])(
    'shows a validation state without fetching for invalid ticket route %s',
    async (ticketNumber) => {
      const fetchMock = vi.fn()
      vi.stubGlobal('fetch', fetchMock)

      renderPage(`/agent/tickets/${ticketNumber}`)

      expect(
        await screen.findByRole('heading', {
          name: '티켓 번호를 확인할 수 없습니다.',
        }),
      ).toBeVisible()
      expect(fetchMock).not.toHaveBeenCalled()
    },
  )

  it('uses an RFC 4122 UUID when randomUUID is unavailable', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(detail), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', {
      getRandomValues: (bytes: Uint8Array) => bytes.fill(0xab),
    })

    renderPage()
    await screen.findByRole('heading', { name: /#1042.*결제 승인 오류/ })

    const request = fetchMock.mock.calls[0]!
    const options = request[1] as { headers: Record<string, string> }
    expect(options.headers['X-Interaction-Id']).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    )
  })
})
