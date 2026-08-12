import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AgentSearchPage } from './AgentSearchPage'

const searchEventId = '11111111-1111-4111-8111-111111111111'

function ticket(ticketNumber: number, subject: string) {
  return {
    ticketNumber,
    subject,
    status: 'OPEN',
    priority: 'HIGH',
    requester: {
      id: `customer-${ticketNumber}`,
      type: 'CUSTOMER',
      displayName: '김민수',
    },
    group: { id: 'group-payments', name: '결제 지원' },
    assignee: { id: 'agent-id', displayName: '상담사' },
    updatedAt: '2026-08-10T10:02:00Z',
    version: 0,
    isChild: false,
    openChildCount: 0,
    sla: null,
  }
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/agent/search']}>
        <Routes>
          <Route path="/agent/search" element={<AgentSearchPage />} />
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

describe('AgentSearchPage', () => {
  it('keeps the raw query out of the URL and links results to the canonical search event', async () => {
    const user = userEvent.setup()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            searchEventId,
            searchInteractionId: '22222222-2222-4222-8222-222222222222',
            items: [ticket(1042, '결제 승인 오류')],
            resultCount: 1,
            sort: 'updatedAt:desc,ticketNumber:desc',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    await user.type(
      screen.getByRole('searchbox', { name: '티켓 검색어' }),
      'customer@example.com secret value',
    )
    await user.selectOptions(screen.getByLabelText('검색 상태 필터'), 'OPEN')
    await user.click(screen.getByRole('button', { name: '티켓 검색' }))

    expect(await screen.findByText('검색 결과 1개')).toBeVisible()
    const result = screen.getByRole('link', {
      name: '#1042 결제 승인 오류 열기',
    })
    expect(result).toHaveAttribute(
      'href',
      `/agent/tickets/1042?originSearchEventId=${searchEventId}`,
    )
    expect(result.getAttribute('href')).not.toContain('customer')
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/v1/agent/search')
  })

  it('shows empty and fail-closed audit states without stale results', async () => {
    const user = userEvent.setup()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            searchEventId,
            searchInteractionId: '22222222-2222-4222-8222-222222222222',
            items: [],
            resultCount: 0,
            sort: 'updatedAt:desc,ticketNumber:desc',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            type: '/problems/audit-write-unavailable',
            title: 'Protected search unavailable',
            status: 503,
            requestId: 'safe-request-id',
          }),
          {
            status: 503,
            headers: { 'Content-Type': 'application/problem+json' },
          },
        ),
      )
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    const input = screen.getByRole('searchbox', { name: '티켓 검색어' })
    await user.type(input, 'no match')
    await user.click(screen.getByRole('button', { name: '티켓 검색' }))
    expect(await screen.findByText('검색 결과가 없습니다.')).toBeVisible()

    await user.clear(input)
    await user.type(input, 'audit failure')
    await user.click(screen.getByRole('button', { name: '티켓 검색' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '감사 기록을 안전하게 저장할 수 없어 검색 결과를 표시하지 않았습니다.',
    )
    expect(screen.getByRole('alert')).toHaveTextContent('safe-request-id')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })
})
