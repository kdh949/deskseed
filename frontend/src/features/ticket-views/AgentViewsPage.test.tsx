import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AgentViewsPage } from './AgentViewsPage'

const sessionState = vi.hoisted(() => ({ staffId: 'agent-a' }))

vi.mock('../staff-auth/StaffSessionContext', () => ({
  useStaffSession: () => ({
    status: 'authenticated',
    staff: {
      id: sessionState.staffId,
      email: `${sessionState.staffId}@example.com`,
      displayName: sessionState.staffId,
      role: 'AGENT',
      capabilities: ['AGENT_WORKSPACE'],
    },
  }),
}))

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
    group: { id: 'group-payments', name: '결제 지원' },
    assignee:
      ticketNumber === 1042 ? { id: 'agent-id', displayName: '상담사' } : null,
    updatedAt: '2026-08-10T10:02:00Z',
    version: 0,
    isChild: false,
    openChildCount: 0,
    sla:
      ticketNumber === 1042
        ? {
            metric: 'FIRST_REPLY',
            state: 'BREACHED',
            dueAt: '2026-08-10T09:00:00Z',
            targetMinutes: 60,
            policyVersion: 2,
            scheduleVersion: 1,
          }
        : {
            metric: 'FIRST_REPLY',
            state: 'NO_POLICY',
            dueAt: null,
            targetMinutes: null,
            policyVersion: null,
            scheduleVersion: null,
          },
  }
}

function renderPage(
  path = '/agent/views/pending',
  queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  }),
) {
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

afterEach(() => {
  sessionState.staffId = 'agent-a'
  vi.unstubAllGlobals()
})

describe('AgentViewsPage', () => {
  it('renders a dense accessible queue and keeps filters in the URL', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response(
          JSON.stringify({
            items: [ticket(1042, '결제 승인 오류'), ticket(1041, '환불 문의')],
            nextCursor: 'next-page',
            totalApproximate: null,
            sort: 'updatedAt:desc,ticketNumber:desc',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    renderPage()

    expect(
      await screen.findByRole('heading', { name: 'Pending' }),
    ).toBeVisible()
    expect(
      await screen.findByRole('table', { name: 'Pending 티켓' }),
    ).toBeVisible()
    expect(screen.getByRole('option', { name: 'ON_HOLD' })).toBeVisible()
    expect(screen.getByRole('option', { name: 'CLOSED' })).toBeVisible()
    expect(screen.getByRole('columnheader', { name: '상태' })).toBeVisible()
    expect(
      screen.getByRole('columnheader', { name: 'First Reply SLA' }),
    ).toBeVisible()
    expect(screen.getByText('위반')).toBeVisible()
    expect(screen.getByText('정책 없음')).toBeVisible()
    expect(
      screen.getByRole('link', { name: '#1042 결제 승인 오류 열기' }),
    ).toBeVisible()
    expect(screen.getByText('URGENT', { selector: 'span' })).toBeVisible()

    await user.selectOptions(screen.getByLabelText('상태 필터'), 'PENDING')
    await screen.findByRole('table', { name: 'Pending 티켓' })
    await user.selectOptions(screen.getByLabelText('우선순위 필터'), 'URGENT')
    await screen.findByRole('table', { name: 'Pending 티켓' })
    expect(fetchMock.mock.calls.at(-1)?.[0]).toContain('status=PENDING')
    expect(fetchMock.mock.calls.at(-1)?.[0]).toContain('priority=URGENT')
    await user.selectOptions(
      screen.getByLabelText('First Reply SLA 필터'),
      'BREACHED',
    )
    await screen.findByRole('table', { name: 'Pending 티켓' })
    expect(fetchMock.mock.calls.at(-1)?.[0]).toContain('slaState=BREACHED')

    await user.click(await screen.findByRole('button', { name: '다음 페이지' }))
    expect(fetchMock.mock.calls.at(-1)?.[0]).toContain('cursor=next-page')
  })

  it('opens a row with the keyboard and never prefetches ticket detail', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          items: [ticket(1042, '결제 승인 오류')],
          nextCursor: null,
          totalApproximate: null,
          sort: 'updatedAt:desc,ticketNumber:desc',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    const ticketLink = await screen.findByRole('link', {
      name: '#1042 결제 승인 오류 열기',
    })
    ticketLink.focus()
    await user.keyboard('{Enter}')

    expect(await screen.findByText('티켓 열림')).toBeVisible()
    expect(
      fetchMock.mock.calls.some(([url]) =>
        String(url).includes('/agent/tickets/'),
      ),
    ).toBe(false)
  })

  it('distinguishes empty and failed queues with recovery actions', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(
          new Response(
            JSON.stringify({
              items: [],
              nextCursor: null,
              totalApproximate: null,
              sort: 'updatedAt:desc,ticketNumber:desc',
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        )
        .mockResolvedValueOnce(
          new Response(
            JSON.stringify({
              type: '/problems/agent-ticket-read',
              title: 'Unavailable',
              status: 503,
              requestId: 'safe-request-id',
            }),
            {
              status: 503,
              headers: { 'Content-Type': 'application/problem+json' },
            },
          ),
        ),
    )

    const first = renderPage()
    expect(
      await screen.findByText('이 View에 표시할 티켓이 없습니다.'),
    ).toBeVisible()
    first.unmount()

    renderPage('/agent/views/my-open?status=OPEN')
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'safe-request-id',
    )
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeVisible()
  })

  it('never reuses another staff account queue from the shared query cache', async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, staleTime: Number.POSITIVE_INFINITY },
      },
    })
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            items: [ticket(1042, 'A 계정 전용 문의')],
            nextCursor: null,
            totalApproximate: null,
            sort: 'updatedAt:desc,ticketNumber:desc',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            items: [ticket(2042, 'B 계정 전용 문의')],
            nextCursor: null,
            totalApproximate: null,
            sort: 'updatedAt:desc,ticketNumber:desc',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
    vi.stubGlobal('fetch', fetchMock)

    const accountA = renderPage('/agent/views/my-open', queryClient)
    expect(await screen.findByText('A 계정 전용 문의')).toBeVisible()
    accountA.unmount()

    sessionState.staffId = 'agent-b'
    renderPage('/agent/views/my-open', queryClient)

    expect(await screen.findByText('B 계정 전용 문의')).toBeVisible()
    expect(screen.queryByText('A 계정 전용 문의')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
