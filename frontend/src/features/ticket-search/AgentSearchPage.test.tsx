import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
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
            resultCount: 1,
            sort: 'score:desc,ticketNumber:desc',
            nextCursor: body.cursor ? null : 'opaque-next',
          })
        }
        return json({})
      }),
    )

    renderPage()
    await user.type(screen.getByLabelText('서버 전체 티켓 검색어'), '중복 결제')
    await user.click(screen.getByRole('button', { name: '서버 전체 검색' }))

    expect(await screen.findByText('정확한 전체 결과 1개')).toBeVisible()
    const firstSearch = requests.find((request) =>
      request.url.endsWith('/api/v1/agent/search'),
    )
    expect(firstSearch?.url).not.toContain('중복')
    expect(firstSearch?.body).toMatchObject({
      query: '중복 결제',
      cursor: null,
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
})
