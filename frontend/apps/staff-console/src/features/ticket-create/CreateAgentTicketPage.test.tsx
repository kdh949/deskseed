import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CreateAgentTicketPage } from './CreateAgentTicketPage'

const assignmentOptions = {
  groups: [
    {
      id: '11111111-1111-4111-8111-111111111111',
      name: '결제 지원',
      members: [
        {
          id: '22222222-2222-4222-8222-222222222222',
          displayName: '박서준',
        },
      ],
    },
  ],
}

const searchResult = {
  searchEventId: '33333333-3333-4333-8333-333333333333',
  searchInteractionId: '44444444-4444-4444-8444-444444444444',
  items: [
    {
      id: '55555555-5555-4555-8555-555555555555',
      name: '김민아',
      email: 'mina.kim@example.test',
      verified: false,
    },
  ],
  resultCount: 1,
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type':
        status >= 400 ? 'application/problem+json' : 'application/json',
    },
  })
}

function mockCreateTicketApi({
  createStatus = 201,
  createBody = {
    ticketNumber: 1050,
    version: 0,
    auditId: '66666666-6666-4666-8666-666666666666',
    warnings: [],
  },
}: {
  createBody?: unknown
  createStatus?: number
} = {}) {
  return vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (url.endsWith('/api/v1/agent/assignment-options')) {
      return Promise.resolve(jsonResponse(assignmentOptions))
    }
    if (url.endsWith('/api/v1/agent/csrf')) {
      return Promise.resolve(
        jsonResponse({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }),
      )
    }
    if (url.endsWith('/api/v1/agent/customers/search') && method === 'POST') {
      return Promise.resolve(jsonResponse(searchResult))
    }
    if (url.endsWith('/api/v1/agent/tickets') && method === 'POST') {
      return Promise.resolve(jsonResponse(createBody, createStatus))
    }
    return Promise.resolve(jsonResponse({ title: 'Not found' }, 404))
  })
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/agent/tickets/new']}>
        <Routes>
          <Route
            path="/agent/tickets/new"
            element={<CreateAgentTicketPage />}
          />
          <Route
            path="/agent/tickets/:ticketNumber"
            element={<p>티켓 열림</p>}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('CreateAgentTicketPage', () => {
  it('shows a loading state before assignment options resolve', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise(() => {})),
    )

    renderPage()

    expect(screen.getByText('새 티켓 양식을 준비하고 있습니다.')).toBeVisible()
  })

  it('lets the agent search and select an existing customer, then create the ticket', async () => {
    const user = userEvent.setup()
    const fetchMock = mockCreateTicketApi()
    vi.stubGlobal('fetch', fetchMock)

    renderPage()

    await screen.findByRole('heading', { name: '새 티켓 생성' })

    await user.type(screen.getByLabelText('이름 또는 이메일로 검색'), '김민아')
    const resultButton = await screen.findByRole('button', {
      name: /김민아/,
    })
    await user.click(resultButton)
    expect(screen.getByText('mina.kim@example.test')).toBeVisible()

    await user.type(screen.getByLabelText('제목'), '검색으로 찾은 고객 문의')
    await user.type(screen.getByLabelText('첫 코멘트 내용'), '내부 조사 시작')
    await user.selectOptions(screen.getByLabelText('그룹'), '결제 지원')
    await user.selectOptions(screen.getByLabelText('담당자'), '박서준')

    await user.click(screen.getByRole('button', { name: '티켓 생성' }))

    await screen.findByText('티켓 열림')

    const createCall = fetchMock.mock.calls.find(
      ([requestUrl, requestInit]) =>
        String(requestUrl).endsWith('/api/v1/agent/tickets') &&
        (requestInit as RequestInit | undefined)?.method === 'POST',
    )
    expect(createCall).toBeDefined()
    const body = JSON.parse(String(createCall?.[1]?.body))
    expect(body).toMatchObject({
      requester: { customerId: '55555555-5555-4555-8555-555555555555' },
      subject: '검색으로 찾은 고객 문의',
      firstComment: { visibility: 'INTERNAL', body: '내부 조사 시작' },
      priority: 'NORMAL',
      groupId: '11111111-1111-4111-8111-111111111111',
      assigneeId: '22222222-2222-4222-8222-222222222222',
    })
  })

  it('creates a new customer when no existing match is selected', async () => {
    const user = userEvent.setup()
    const fetchMock = mockCreateTicketApi()
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    await screen.findByRole('heading', { name: '새 티켓 생성' })

    await user.click(screen.getByRole('tab', { name: '새 고객 등록' }))
    await user.type(screen.getByLabelText('이름'), '박서준')
    await user.type(screen.getByLabelText('이메일'), 'seojun@example.test')
    await user.type(screen.getByLabelText('제목'), '신규 고객 문의')
    await user.type(
      screen.getByLabelText('첫 코멘트 내용'),
      '전화 문의 내용 기록',
    )

    await user.click(screen.getByRole('button', { name: '티켓 생성' }))

    await screen.findByText('티켓 열림')

    const createCall = fetchMock.mock.calls.find(
      ([requestUrl, requestInit]) =>
        String(requestUrl).endsWith('/api/v1/agent/tickets') &&
        (requestInit as RequestInit | undefined)?.method === 'POST',
    )
    const body = JSON.parse(String(createCall?.[1]?.body))
    expect(body.requester).toEqual({
      name: '박서준',
      email: 'seojun@example.test',
    })
  })

  it('shows a validation error and does not submit when no requester is selected', async () => {
    const user = userEvent.setup()
    const fetchMock = mockCreateTicketApi()
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    await screen.findByRole('heading', { name: '새 티켓 생성' })

    await user.type(screen.getByLabelText('제목'), '제목만 입력')
    await user.click(screen.getByRole('button', { name: '티켓 생성' }))

    expect(
      await screen.findByText(
        '요청자를 검색해서 선택하거나 새로 등록해 주세요.',
      ),
    ).toBeVisible()
    expect(
      fetchMock.mock.calls.some(
        ([requestUrl, requestInit]) =>
          String(requestUrl).endsWith('/api/v1/agent/tickets') &&
          (requestInit as RequestInit | undefined)?.method === 'POST',
      ),
    ).toBe(false)
  })

  it('shows a server error and preserves input when creation fails', async () => {
    const user = userEvent.setup()
    const fetchMock = mockCreateTicketApi({
      createStatus: 409,
      createBody: {
        type: '/problems/client-command-id-reused',
        title: 'Client command ID was already used',
        status: 409,
        requestId: 'req-create-409',
      },
    })
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    await screen.findByRole('heading', { name: '새 티켓 생성' })

    await user.click(screen.getByRole('tab', { name: '새 고객 등록' }))
    await user.type(screen.getByLabelText('이름'), '박서준')
    await user.type(screen.getByLabelText('이메일'), 'seojun@example.test')
    await user.type(screen.getByLabelText('제목'), '재시도 케이스')
    await user.type(screen.getByLabelText('첫 코멘트 내용'), '중복 커맨드 확인')

    await user.click(screen.getByRole('button', { name: '티켓 생성' }))

    await waitFor(() => {
      expect(
        screen.getByText(/Client command ID was already used/),
      ).toBeVisible()
    })
    expect(screen.getByLabelText('제목')).toHaveValue('재시도 케이스')
  })
})
