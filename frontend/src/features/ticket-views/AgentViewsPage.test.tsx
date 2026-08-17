import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AgentViewsPage } from './AgentViewsPage'

const viewContract = {
  active: true,
  description: '상담 운영에 사용하는 보기입니다.',
  definitionVersion: 1,
  orderVersion: 1,
  conditions: {
    version: 1,
    all: [{ field: 'STATUS', operator: 'LESS_THAN_SOLVED', values: [] }],
    any: [],
  },
  columns: ['TICKET_NUMBER', 'SUBJECT', 'STATUS'],
  sort: 'updatedAt:desc,ticketNumber:desc',
  ticketCountState: 'EXACT',
  ticketCountAsOf: '2026-08-18T03:04:05Z',
  readScope: 'ALL_TICKETS',
  createdAt: '2026-08-10T00:00:00Z',
  updatedAt: '2026-08-10T00:00:00Z',
} as const

const views = [
  {
    ...viewContract,
    id: '00000000-0000-4000-8000-000000000001',
    key: 'my-open',
    name: '내 open',
    scope: 'SYSTEM',
    ownerStaffId: null,
    categoryPath: ['Views'],
    ticketCount: null,
  },
  {
    ...viewContract,
    id: '00000000-0000-4000-8000-000000000002',
    key: 'pending',
    name: 'Pending',
    scope: 'SHARED',
    ownerStaffId: null,
    categoryPath: ['Views'],
    ticketCount: null,
  },
  {
    ...viewContract,
    id: '00000000-0000-4000-8000-000000000003',
    key: 'follow-up',
    name: '내가 팔로우 중인 티켓',
    scope: 'PERSONAL',
    ownerStaffId: '00000000-0000-4000-8000-000000000099',
    categoryPath: ['Views'],
    ticketCount: 2,
  },
  {
    ...viewContract,
    id: '00000000-0000-4000-8000-000000000004',
    key: 'drafts',
    name: '임시 보관함',
    scope: 'PERSONAL',
    ownerStaffId: '00000000-0000-4000-8000-000000000099',
    categoryPath: ['Views'],
    ticketCount: 1,
  },
]

function ticket(ticketNumber: number, subject: string) {
  return {
    ticketNumber,
    subject,
    status: ticketNumber === 1042 ? 'PENDING' : 'OPEN',
    priority: ticketNumber === 1042 ? 'URGENT' : 'NORMAL',
    requester: {
      id: `customer-${ticketNumber}`,
      type: 'CUSTOMER',
      displayName: ticketNumber === 1042 ? '김민수' : '이수진',
    },
    group: { id: '00000000-0000-0000-0000-000000000001', name: '결제 지원' },
    assignee:
      ticketNumber === 1042
        ? { id: '00000000-0000-0000-0000-000000000002', displayName: '상담사' }
        : null,
    updatedAt: '2026-08-10T10:02:00Z',
    version: 0,
    isChild: false,
    openChildCount: 0,
    sla: null,
  }
}

function ticketPage(
  items = [ticket(1042, '결제 승인 오류'), ticket(1041, '환불 문의')],
) {
  return {
    items,
    nextCursor: 'next-page',
    totalApproximate: null,
    sort: 'updatedAt:desc,ticketNumber:desc',
  }
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

function mockReadApi(
  page = ticketPage(),
  error?: { body: unknown; status: number },
) {
  let serverViews = [...views]
  return vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (url.endsWith('/api/v1/agent/assignment-options'))
      return Promise.resolve(jsonResponse({ groups: [] }))
    if (url.endsWith('/api/v1/agent/csrf'))
      return Promise.resolve(
        jsonResponse({ token: 'csrf', headerName: 'X-CSRF-TOKEN' }),
      )
    if (url.endsWith('/api/v1/agent/views/preview'))
      return Promise.resolve(
        jsonResponse({
          items: [],
          ticketCount: 2,
          ticketCountAsOf: '2026-08-18T03:04:05Z',
          sort: viewContract.sort,
        }),
      )
    if (url.endsWith('/api/v1/agent/views/reorder')) {
      const body = JSON.parse(String(init?.body))
      serverViews = body.viewKeys
        .map((key: string) => serverViews.find((view) => view.key === key))
        .filter(Boolean)
        .concat(serverViews.filter((view) => view.scope !== body.scope))
      return Promise.resolve(
        jsonResponse({
          scope: body.scope,
          orderVersion: body.expectedOrderVersion + 1,
          viewKeys: body.viewKeys,
        }),
      )
    }
    if (url.endsWith('/api/v1/agent/views') && method === 'POST') {
      const body = JSON.parse(String(init?.body))
      const created = {
        ...viewContract,
        ...body,
        id: '00000000-0000-4000-8000-000000000010',
        key: 'created-view',
        ownerStaffId: '00000000-0000-4000-8000-000000000099',
        active: true,
        categoryPath: ['Views'],
        ticketCount: 0,
      }
      serverViews = [...serverViews, created]
      return Promise.resolve(jsonResponse(created, 201))
    }
    if (url.endsWith('/api/v1/agent/views') && method === 'GET')
      return Promise.resolve(jsonResponse(serverViews))
    if (url.includes('/api/v1/agent/views/') && method === 'PATCH') {
      const key = url.split('/').at(-1)
      const body = JSON.parse(String(init?.body))
      const current = serverViews.find((view) => view.key === key)!
      const updated = {
        ...current,
        ...body,
        definitionVersion: current.definitionVersion + 1,
      }
      serverViews = serverViews.map((view) =>
        view.key === key ? updated : view,
      )
      return Promise.resolve(jsonResponse(updated))
    }
    if (
      url.includes('/api/v1/agent/views/') &&
      url.split('?')[0]?.endsWith('/tickets')
    ) {
      return Promise.resolve(
        error ? jsonResponse(error.body, error.status) : jsonResponse(page),
      )
    }
    return Promise.resolve(
      jsonResponse({ title: 'Not found', status: 404 }, 404),
    )
  })
}

function ticketRequestUrls(fetchMock: ReturnType<typeof vi.fn>) {
  return fetchMock.mock.calls
    .map(([url]) => String(url))
    .filter(
      (url) => url.includes('/api/v1/agent/views/') && url.includes('/tickets'),
    )
}

function renderPage(path = '/agent/views/pending') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/agent/views/:viewKey" element={<AgentViewsPage />} />
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

describe('AgentViewsPage', () => {
  it('uses supported URL filters, hides unsupported controls, and supports current-page selection', async () => {
    const user = userEvent.setup()
    const fetchMock = mockReadApi()
    vi.stubGlobal('fetch', fetchMock)

    renderPage('/agent/views/pending?status=OPEN')

    expect(
      await screen.findByRole('heading', { name: '고객 답변 대기' }),
    ).toBeVisible()
    expect(
      await screen.findByRole('table', { name: '고객 답변 대기 티켓' }),
    ).toBeVisible()
    expect(screen.getByRole('option', { name: '보류' })).toBeVisible()
    expect(screen.getByRole('option', { name: '종료' })).toBeVisible()
    expect(
      screen.getAllByRole('columnheader').map((header) => header.textContent),
    ).toEqual(['티켓 선택', '티켓 ID', '제목', '상태'])
    expect(
      screen.getByRole('link', { name: '티켓 #1042 결제 승인 오류' }),
    ).toBeVisible()
    expect(
      screen.queryByText('긴급', { selector: 'span' }),
    ).not.toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('상태 필터'), 'PENDING')
    await user.selectOptions(screen.getByLabelText('우선순위 필터'), 'URGENT')
    await waitFor(() => {
      expect(ticketRequestUrls(fetchMock).at(-1)).toContain('status=PENDING')
      expect(ticketRequestUrls(fetchMock).at(-1)).toContain('priority=URGENT')
    })

    expect(screen.queryByLabelText('채널 필터')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'View 저장' }),
    ).not.toBeInTheDocument()

    await user.click(screen.getByLabelText('티켓 #1042 선택'))
    expect(
      await screen.findByRole('region', { name: '선택된 티켓' }),
    ).toHaveTextContent('1개 선택됨')
    await user.click(screen.getByRole('button', { name: '선택 해제' }))
    expect(
      screen.queryByRole('region', { name: '선택된 티켓' }),
    ).not.toBeInTheDocument()

    await user.type(
      screen.getByRole('searchbox', { name: '현재 목록 검색' }),
      '없는값',
    )
    expect(
      await screen.findByRole('heading', {
        name: '일치하는 티켓이 없습니다.',
      }),
    ).toBeVisible()
    expect(ticketRequestUrls(fetchMock).some((url) => url.includes('q='))).toBe(
      false,
    )
  })

  it('uses roving row keyboard navigation, selects with Space, and opens only after Enter', async () => {
    const user = userEvent.setup()
    const fetchMock = mockReadApi()
    vi.stubGlobal('fetch', fetchMock)

    renderPage('/agent/views/my-open')
    const firstTicketLink = await screen.findByRole('link', {
      name: '티켓 #1042 결제 승인 오류',
    })
    const secondTicketLink = screen.getByRole('link', {
      name: '티켓 #1041 환불 문의',
    })

    firstTicketLink.focus()
    await user.keyboard('{ArrowDown}')
    expect(secondTicketLink).toHaveFocus()
    await user.keyboard(' ')
    expect(await screen.findByText('1개 선택됨')).toBeVisible()

    await user.keyboard('{ArrowUp}')
    expect(firstTicketLink).toHaveFocus()
    await user.keyboard(' ')
    expect(await screen.findByText('2개 선택됨')).toBeVisible()

    await user.keyboard('{Enter}')
    expect(await screen.findByText('티켓 열림')).toBeVisible()
    expect(
      fetchMock.mock.calls.some(([url]) =>
        String(url).includes('/api/v1/agent/tickets/'),
      ),
    ).toBe(false)
  })

  it('creates a server-backed personal view and opens its queue', async () => {
    const user = userEvent.setup()
    const fetchMock = mockReadApi()
    vi.stubGlobal('fetch', fetchMock)

    renderPage('/agent/views/my-open')
    await screen.findByRole('table', { name: '내 티켓 티켓' })
    await user.click(screen.getByRole('button', { name: '새 보기 만들기' }))
    const dialog = await screen.findByRole('dialog', {
      name: '새 보기 만들기',
    })
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '보기 이름을 입력하세요.',
    )

    await user.type(
      within(dialog).getByRole('textbox', { name: '보기 이름' }),
      '검토 전용 보기',
    )
    await user.type(
      within(dialog).getByRole('textbox', { name: '설명' }),
      '결제 문의 검토용',
    )
    await user.click(
      within(dialog).getByRole('button', { name: '보기 만들기' }),
    )

    expect(
      await screen.findByRole('heading', { name: '검토 전용 보기' }),
    ).toBeVisible()
    expect(screen.getByRole('link', { name: /검토 전용 보기/ })).toBeVisible()
    expect(
      fetchMock.mock.calls.some(
        ([url, init]) =>
          String(url).endsWith('/api/v1/agent/views') &&
          init?.method === 'POST',
      ),
    ).toBe(true)
    expect(ticketRequestUrls(fetchMock).at(-1)).toContain(
      '/agent/views/created-view/tickets',
    )
    const createCall = fetchMock.mock.calls.find(
      ([url, init]) =>
        String(url).endsWith('/api/v1/agent/views') && init?.method === 'POST',
    )
    expect(JSON.parse(String(createCall?.[1]?.body))).toMatchObject({
      description: '결제 문의 검토용',
    })
  })

  it('keeps one active view and exposes only applicable view actions', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', mockReadApi())

    renderPage('/agent/views/my-open')
    await screen.findByRole('table', { name: '내 티켓 티켓' })

    expect(screen.getByRole('link', { name: '내 티켓' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(screen.getByText('상담 운영에 사용하는 보기입니다.')).toBeVisible()
    expect(
      screen.getByRole('link', { name: '고객 답변 대기' }),
    ).not.toHaveAttribute('aria-current')

    await user.click(screen.getByRole('button', { name: '작업' }))
    const menu = await screen.findByRole('menu', { name: '보기 작업' })
    expect(
      within(menu).getByRole('menuitem', { name: '새 보기 만들기' }),
    ).toBeVisible()
    expect(
      within(menu).queryByRole('menuitem', { name: '보기 설정' }),
    ).not.toBeInTheDocument()
  })

  it('updates a personal definition with expectedVersion and reorders only on confirmation', async () => {
    const user = userEvent.setup()
    const fetchMock = mockReadApi()
    vi.stubGlobal('fetch', fetchMock)

    renderPage('/agent/views/follow-up')
    await screen.findByRole('table', { name: '내가 팔로우 중인 티켓 티켓' })
    expect(
      screen.queryByRole('button', { name: '내 티켓 편집' }),
    ).not.toBeInTheDocument()

    await user.click(
      screen.getByRole('button', { name: '내가 팔로우 중인 티켓 편집' }),
    )
    let dialog = await screen.findByRole('dialog', {
      name: '내가 팔로우 중인 티켓 편집',
    })
    await user.click(within(dialog).getByRole('button', { name: '아래로' }))
    await user.click(within(dialog).getByRole('button', { name: '취소' }))
    const linksAfterCancel = screen
      .getAllByRole('link')
      .map((link) => link.textContent)
    expect(
      linksAfterCancel.findIndex((label) =>
        label?.startsWith('내가 팔로우 중인 티켓'),
      ),
    ).toBeLessThan(
      linksAfterCancel.findIndex((label) => label?.startsWith('임시 보관함')),
    )

    await user.click(
      screen.getByRole('button', { name: '내가 팔로우 중인 티켓 편집' }),
    )
    dialog = await screen.findByRole('dialog', {
      name: '내가 팔로우 중인 티켓 편집',
    })
    await user.clear(within(dialog).getByRole('textbox', { name: '보기 이름' }))
    await user.type(
      within(dialog).getByRole('textbox', { name: '보기 이름' }),
      '검토 예정',
    )
    await user.click(within(dialog).getByRole('button', { name: '아래로' }))
    await user.click(within(dialog).getByRole('button', { name: '변경 저장' }))

    expect(
      await screen.findByRole('heading', { name: '검토 예정' }),
    ).toBeVisible()
    const personalLinks = screen
      .getAllByRole('link')
      .map((link) => link.textContent)
    expect(
      personalLinks.findIndex((label) => label?.startsWith('임시 보관함')),
    ).toBeLessThan(
      personalLinks.findIndex((label) => label?.startsWith('검토 예정')),
    )
    const updateCall = fetchMock.mock.calls.find(
      ([url, init]) =>
        String(url).endsWith('/api/v1/agent/views/follow-up') &&
        init?.method === 'PATCH',
    )
    expect(JSON.parse(String(updateCall?.[1]?.body))).toMatchObject({
      expectedVersion: 1,
      name: '검토 예정',
    })
  })

  it('updates only the description with the current definition version', async () => {
    const user = userEvent.setup()
    const fetchMock = mockReadApi()
    vi.stubGlobal('fetch', fetchMock)

    renderPage('/agent/views/follow-up')
    await screen.findByRole('table', { name: '내가 팔로우 중인 티켓 티켓' })
    await user.click(
      screen.getByRole('button', { name: '내가 팔로우 중인 티켓 편집' }),
    )
    const dialog = await screen.findByRole('dialog', {
      name: '내가 팔로우 중인 티켓 편집',
    })
    const description = within(dialog).getByRole('textbox', { name: '설명' })
    await user.clear(description)
    await user.type(description, '후속 응답만 확인합니다.')
    await user.click(within(dialog).getByRole('button', { name: '변경 저장' }))

    const updateCall = fetchMock.mock.calls.find(
      ([url, init]) =>
        String(url).endsWith('/api/v1/agent/views/follow-up') &&
        init?.method === 'PATCH',
    )
    expect(JSON.parse(String(updateCall?.[1]?.body))).toMatchObject({
      expectedVersion: 1,
      name: '내가 팔로우 중인 티켓',
      description: '후속 응답만 확인합니다.',
    })
  })

  it('preserves a conflicting description draft until explicit reload', async () => {
    const user = userEvent.setup()
    const baseFetch = mockReadApi()
    let conflicted = false
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      if (url.endsWith('/api/v1/agent/views/follow-up') && method === 'PATCH') {
        conflicted = true
        return Promise.resolve(
          jsonResponse({ title: 'Conflict', status: 409 }, 409),
        )
      }
      if (
        conflicted &&
        url.endsWith('/api/v1/agent/views') &&
        method === 'GET'
      ) {
        return Promise.resolve(
          jsonResponse(
            views.map((view) =>
              view.key === 'follow-up'
                ? {
                    ...view,
                    description: '서버의 최신 설명',
                    definitionVersion: 2,
                  }
                : view,
            ),
          ),
        )
      }
      return baseFetch(input, init)
    })
    vi.stubGlobal('fetch', fetchMock)

    renderPage('/agent/views/follow-up')
    await screen.findByRole('table', { name: '내가 팔로우 중인 티켓 티켓' })
    await user.click(
      screen.getByRole('button', { name: '내가 팔로우 중인 티켓 편집' }),
    )
    const description = screen.getByRole('textbox', { name: '설명' })
    await user.clear(description)
    await user.type(description, '충돌한 로컬 초안')
    await user.click(screen.getByRole('button', { name: '변경 저장' }))

    expect(await screen.findByText('보기 버전 충돌')).toBeVisible()
    expect(description).toHaveValue('충돌한 로컬 초안')
    await user.click(
      screen.getByRole('button', { name: '최신 버전 다시 불러오기' }),
    )
    await waitFor(() =>
      expect(screen.getByRole('textbox', { name: '설명' })).toHaveValue(
        '서버의 최신 설명',
      ),
    )
  })

  it('shows an exact count basis without inventing one for omitted counts', async () => {
    vi.stubGlobal('fetch', mockReadApi())

    renderPage('/agent/views/follow-up')

    expect(
      await screen.findByRole('link', {
        name: /내가 팔로우 중인 티켓.*티켓 2개.*기준/,
      }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '내 티켓' })).not.toHaveTextContent(
      '기준',
    )
  })

  it('keeps a committed definition and retries only reorder with the latest order version', async () => {
    const user = userEvent.setup()
    const baseFetch = mockReadApi()
    let reorderAttempts = 0
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      if (url.endsWith('/api/v1/agent/views/reorder')) {
        reorderAttempts += 1
        if (reorderAttempts === 1) {
          return Promise.resolve(
            jsonResponse({ title: 'conflict', status: 409 }, 409),
          )
        }
        const body = JSON.parse(String(init?.body))
        return Promise.resolve(
          jsonResponse({
            scope: 'PERSONAL',
            orderVersion: 3,
            viewKeys: body.viewKeys,
          }),
        )
      }
      if (
        reorderAttempts > 0 &&
        url.endsWith('/api/v1/agent/views') &&
        method === 'GET'
      ) {
        return Promise.resolve(
          jsonResponse(
            views.map((view) =>
              view.key === 'follow-up'
                ? {
                    ...view,
                    name: '부분 저장 보기',
                    definitionVersion: 2,
                    orderVersion: 2,
                  }
                : {
                    ...view,
                    orderVersion:
                      view.scope === 'PERSONAL' ? 2 : view.orderVersion,
                  },
            ),
          ),
        )
      }
      return baseFetch(input, init)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage('/agent/views/follow-up')
    await screen.findByRole('table', { name: '내가 팔로우 중인 티켓 티켓' })
    await user.click(
      screen.getByRole('button', { name: '내가 팔로우 중인 티켓 편집' }),
    )
    const dialog = await screen.findByRole('dialog', {
      name: '내가 팔로우 중인 티켓 편집',
    })
    await user.clear(within(dialog).getByRole('textbox', { name: '보기 이름' }))
    await user.type(
      within(dialog).getByRole('textbox', { name: '보기 이름' }),
      '부분 저장 보기',
    )
    await user.click(within(dialog).getByRole('button', { name: '아래로' }))
    await user.click(within(dialog).getByRole('button', { name: '변경 저장' }))

    expect(await screen.findByText(/보기 정의는 저장되었습니다/)).toBeVisible()
    expect(screen.getByRole('textbox', { name: '보기 이름' })).toHaveValue(
      '부분 저장 보기',
    )
    await user.click(screen.getByRole('button', { name: '변경 저장' }))
    await waitFor(() => expect(reorderAttempts).toBe(2))

    const patchCalls = fetchMock.mock.calls.filter(
      ([url, init]) =>
        String(url).endsWith('/api/v1/agent/views/follow-up') &&
        init?.method === 'PATCH',
    )
    expect(patchCalls).toHaveLength(1)
    const reorderBodies = fetchMock.mock.calls
      .filter(([url]) => String(url).endsWith('/api/v1/agent/views/reorder'))
      .map(([, init]) => JSON.parse(String(init?.body)))
    expect(reorderBodies[1]).toMatchObject({ expectedOrderVersion: 2 })
  })

  it('distinguishes empty, denied, and failed queues with safe recovery states', async () => {
    const emptyFetchMock = mockReadApi(ticketPage([]))
    vi.stubGlobal('fetch', emptyFetchMock)
    const first = renderPage()
    expect(
      await screen.findByRole('heading', {
        name: '처리할 티켓이 없습니다.',
      }),
    ).toBeVisible()
    first.unmount()

    const deniedFetchMock = mockReadApi(ticketPage(), {
      status: 403,
      body: {
        type: '/problems/agent-ticket-read',
        title: 'Forbidden',
        status: 403,
        requestId: 'safe-denied-request-id',
      },
    })
    vi.stubGlobal('fetch', deniedFetchMock)
    const second = renderPage('/agent/views/my-open')
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '이 티켓 목록에 접근할 수 없습니다.',
    )
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeVisible()
    second.unmount()

    const failedFetchMock = mockReadApi(ticketPage(), {
      status: 503,
      body: {
        type: '/problems/agent-ticket-read',
        title: 'Unavailable',
        status: 503,
        requestId: 'safe-request-id',
      },
    })
    vi.stubGlobal('fetch', failedFetchMock)
    renderPage('/agent/views/my-open')
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'safe-request-id',
    )
  })
})
