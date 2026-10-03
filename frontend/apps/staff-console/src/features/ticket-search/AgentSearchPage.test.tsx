import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AgentSearchPage } from './AgentSearchPage'
import type { AgentTicketSearchInput } from '../../api/types'

const ticket = {
  ticketNumber: 1042,
  subject: '중복 결제 확인',
  status: 'OPEN',
  priority: 'HIGH',
  requester: { id: null, type: 'CUSTOMER', displayName: '김민수' },
  group: { id: '11111111-1111-4111-8111-111111111111', name: '결제 지원' },
  assignee: null,
  createdAt: '2026-08-17T02:00:00Z',
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

function json(body: unknown) {
  return new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
  })
}

function OpenedTicket() {
  const location = useLocation()
  return (
    <p>
      origin:{' '}
      {(location.state as { originSearchEventId: string }).originSearchEventId}
    </p>
  )
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/agent/search']}>
        <Routes>
          <Route path="/agent/search" element={<AgentSearchPage />} />
          <Route
            path="/agent/tickets/:ticketNumber"
            element={<OpenedTicket />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('AgentSearchPage', () => {
  it('keeps raw query out of the URL, uses POST cursors, and forwards origin search audit state', async () => {
    const user = userEvent.setup()
    const requests: Array<{ url: string; body?: unknown }> = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        requests.push({
          url,
          body: init?.body ? JSON.parse(String(init.body)) : undefined,
        })
        if (url.endsWith('/api/v1/agent/assignment-options'))
          return json({ groups: [] })
        if (url.endsWith('/api/v1/agent/csrf'))
          return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
        if (url.endsWith('/api/v1/agent/search')) {
          const body = JSON.parse(String(init?.body)) as {
            cursor?: string | null
          }
          return json({
            searchEventId: '33333333-3333-4333-8333-333333333333',
            searchInteractionId: '44444444-4444-4444-8444-444444444444',
            items: [ticket],
            resultCount: body.cursor
              ? { value: 2, relation: 'EXACT' }
              : { value: 2, relation: 'LOWER_BOUND' },
            sort: 'updatedAt:desc,ticketNumber:desc',
            nextCursor: body.cursor ? null : 'opaque-next',
          })
        }
        return json({})
      }),
    )

    renderPage()
    await user.type(screen.getByLabelText('티켓 검색어'), '중복 결제')
    await user.click(screen.getByRole('button', { name: '티켓 검색' }))

    expect(await screen.findByText('결과 2개 이상')).toBeVisible()
    const firstSearch = requests.find((request) =>
      request.url.endsWith('/api/v1/agent/search'),
    )
    expect(firstSearch?.url).not.toContain('중복')
    expect(firstSearch?.body).toMatchObject({
      query: '중복 결제',
      cursor: null,
      sort: 'updatedAt:desc,ticketNumber:desc',
    })

    await user.click(screen.getByRole('button', { name: '다음 페이지' }))
    await waitFor(() => {
      expect(
        requests
          .filter((request) => request.url.endsWith('/api/v1/agent/search'))
          .at(-1)?.body,
      ).toMatchObject({ query: '중복 결제', cursor: 'opaque-next' })
    })

    await user.click(
      screen.getByRole('link', { name: '티켓 #1042 중복 결제 확인' }),
    )
    expect(
      await screen.findByText('origin: 33333333-3333-4333-8333-333333333333'),
    ).toBeVisible()
  })

  it('preserves server order and applies draft filters only on explicit submit', async () => {
    const user = userEvent.setup()
    const searches: Array<{
      body: AgentTicketSearchInput
      interactionId: string | null
    }> = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        if (url.endsWith('/api/v1/agent/assignment-options'))
          return json({ groups: [] })
        if (url.endsWith('/api/v1/agent/csrf'))
          return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
        if (url.endsWith('/api/v1/agent/search')) {
          const body = JSON.parse(String(init?.body)) as AgentTicketSearchInput
          searches.push({
            body,
            interactionId: new Headers(init?.headers).get('X-Interaction-Id'),
          })
          return json({
            searchEventId: crypto.randomUUID(),
            searchInteractionId: crypto.randomUUID(),
            items: [{ ...ticket, ticketNumber: 1041 }, ticket],
            resultCount: body.cursor
              ? { value: 4, relation: 'EXACT' }
              : { value: 3, relation: 'LOWER_BOUND' },
            sort: body.sort,
            nextCursor: body.cursor ? null : 'opaque-next',
          })
        }
        return json({})
      }),
    )
    renderPage()
    await user.type(screen.getByLabelText('티켓 검색어'), '결제')
    await user.selectOptions(screen.getByLabelText('상태 검색 필터'), 'OPEN')
    await user.click(screen.getByRole('button', { name: '티켓 검색' }))
    await screen.findByText('결과 3개 이상')

    const links = screen.getAllByRole('link', { name: /티켓 #10/ })
    expect(links.map((link) => link.getAttribute('aria-label'))).toEqual([
      '티켓 #1041 중복 결제 확인',
      '티켓 #1042 중복 결제 확인',
    ])
    await user.click(screen.getByRole('button', { name: '다음 페이지' }))
    await waitFor(() => expect(searches).toHaveLength(2))
    expect(searches[1]?.interactionId).toBe(searches[0]?.interactionId)
    await user.selectOptions(screen.getByLabelText('상태 검색 필터'), 'SOLVED')
    await user.clear(screen.getByLabelText('티켓 검색어'))
    await user.type(screen.getByLabelText('티켓 검색어'), '환불')
    await new Promise((resolve) => window.setTimeout(resolve, 0))
    expect(searches).toHaveLength(2)
    expect(
      screen.getByRole('region', { name: '적용된 검색 조건' }),
    ).toHaveTextContent('검색어: 결제')

    const previousInteraction = searches[1]?.interactionId
    await user.click(screen.getByRole('button', { name: '검색 조건 적용' }))
    await waitFor(() => expect(searches).toHaveLength(3))
    expect(searches[2]?.body).toMatchObject({
      query: '환불',
      filters: { status: 'SOLVED' },
      cursor: null,
    })
    expect(searches[2]?.interactionId).not.toBe(previousInteraction)

    await user.selectOptions(
      screen.getByLabelText('정렬 검색 필터'),
      'score:desc,ticketNumber:desc',
    )
    expect(searches).toHaveLength(3)
    expect(
      screen.getByText('아직 적용하지 않은 검색 조건이 있습니다'),
    ).toBeVisible()
    expect(
      screen.getByRole('region', { name: '적용된 검색 조건' }),
    ).toHaveTextContent('최근 업데이트 순')
    await user.click(screen.getByRole('button', { name: '검색 조건 적용' }))
    await waitFor(() => expect(searches).toHaveLength(4))
    expect(searches[3]?.body).toMatchObject({
      sort: 'score:desc,ticketNumber:desc',
      cursor: null,
    })
    expect(
      screen.queryByText('아직 적용하지 않은 검색 조건이 있습니다'),
    ).toBeNull()
    expect(
      screen.getByRole('region', { name: '적용된 검색 조건' }),
    ).toHaveTextContent('관련도 높은 순')
    await user.click(screen.getByRole('button', { name: '필터·정렬 초기화' }))
    expect(searches).toHaveLength(4)
    expect(screen.getByLabelText('티켓 검색어')).toHaveValue('환불')
    expect(
      screen.getByRole('region', { name: '적용된 검색 조건' }),
    ).toHaveTextContent('상태: 해결')
    await user.click(screen.getByRole('button', { name: '검색 조건 적용' }))
    await waitFor(() => expect(searches).toHaveLength(5))
    expect(searches[4]?.body).toMatchObject({
      query: '환불',
      filters: {},
      cursor: null,
      sort: 'updatedAt:desc,ticketNumber:desc',
    })
  })

  it('retries the applied input after failure without discarding pending criteria', async () => {
    const user = userEvent.setup()
    const searches: AgentTicketSearchInput[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        if (url.endsWith('/assignment-options')) return json({ groups: [] })
        if (url.endsWith('/csrf'))
          return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
        if (url.endsWith('/search')) {
          searches.push(JSON.parse(String(init?.body)))
          if (searches.length === 1)
            return new Response(
              JSON.stringify({
                status: 503,
                title: 'Unavailable',
                requestId: 'search-example',
              }),
              {
                status: 503,
                headers: { 'Content-Type': 'application/problem+json' },
              },
            )
          return json({
            searchEventId: crypto.randomUUID(),
            searchInteractionId: crypto.randomUUID(),
            items: [ticket],
            resultCount: { value: null, relation: 'UNAVAILABLE' },
            sort: 'updatedAt:desc,ticketNumber:desc',
            nextCursor: null,
          })
        }
        return json({})
      }),
    )
    renderPage()
    await user.type(screen.getByLabelText('티켓 검색어'), '중복 결제')
    await user.click(screen.getByRole('button', { name: '티켓 검색' }))
    await screen.findByText('티켓 검색을 완료하지 못했습니다')
    await user.selectOptions(screen.getByLabelText('상태 검색 필터'), 'OPEN')
    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(
      await screen.findByText('전체 결과 수는 계산하지 않았습니다'),
    ).toBeVisible()
    expect(searches[1]).toEqual(searches[0])
    expect(screen.getByLabelText('상태 검색 필터')).toHaveValue('OPEN')
    expect(
      screen.getByText('아직 적용하지 않은 검색 조건이 있습니다'),
    ).toBeVisible()
  })

  it('shows actionable guidance for an unfiltered short broad query', async () => {
    const user = userEvent.setup()
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input)
        if (url.endsWith('/api/v1/agent/assignment-options'))
          return json({ groups: [] })
        if (url.endsWith('/api/v1/agent/csrf'))
          return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
        if (url.endsWith('/api/v1/agent/search')) {
          return new Response(
            JSON.stringify({
              type: '/problems/agent-search-too-broad',
              title: 'Ticket search is too broad',
              status: 422,
            }),
            {
              status: 422,
              headers: { 'Content-Type': 'application/problem+json' },
            },
          )
        }
        return json({})
      }),
    )

    renderPage()
    await user.type(screen.getByLabelText('티켓 검색어'), '결제')
    await user.click(screen.getByRole('button', { name: '티켓 검색' }))

    expect(await screen.findByText('검색 범위를 더 좁혀 주세요')).toBeVisible()
    expect(
      screen.getByText(
        '검색어를 세 글자 이상 입력하거나 상태·우선순위·그룹·담당자·SLA 필터를 추가해 주세요.',
      ),
    ).toBeVisible()
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull()
  })
})
