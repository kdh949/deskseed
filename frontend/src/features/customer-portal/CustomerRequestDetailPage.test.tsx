import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CustomerRequestDetailPage } from './CustomerRequestDetailPage'

const detail = {
  ticketNumber: 1042,
  subject: '결제 확인 요청',
  status: 'OPEN',
  createdAt: '2026-08-15T00:00:00Z',
  updatedAt: '2026-08-15T01:00:00Z',
  comments: [
    {
      id: 'comment-public-1',
      authorDisplayName: '김민아',
      body: '결제 승인 내역을 확인해 주세요.',
      createdAt: '2026-08-15T00:00:00Z',
      internalNote: 'must-not-render',
    },
  ],
  staffAssignee: 'must-not-render',
  auditMetadata: { actor: 'staff-1' },
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/account/requests/1042']}>
        <Routes>
          <Route
            path="/account/requests/:ticketNumber"
            element={<CustomerRequestDetailPage />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('CustomerRequestDetailPage', () => {
  it('loads the owned PUBLIC detail, writes an authenticated follow-up through CSRF, and refreshes the projection after confirmed success', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/v1/customer/requests/1042') && !init?.method) {
        return Promise.resolve(
          new Response(JSON.stringify(detail), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        )
      }
      if (url.endsWith('/api/v1/customer/csrf')) {
        return Promise.resolve(
          new Response(JSON.stringify({ token: 'csrf-token' }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        )
      }
      if (
        url.endsWith('/api/v1/customer/requests/1042/comments') &&
        init?.method === 'POST'
      ) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              id: 'comment-public-2',
              authorDisplayName: '김민아',
              body: '추가 정보입니다.',
              createdAt: '2026-08-15T02:00:00Z',
            }),
            { status: 201, headers: { 'Content-Type': 'application/json' } },
          ),
        )
      }
      return Promise.resolve(new Response(null, { status: 404 }))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderPage()

    expect(
      await screen.findByRole('heading', { name: '#1042 결제 확인 요청' }),
    ).toBeVisible()
    expect(screen.queryByText('must-not-render')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('추가 답변'), '추가 정보입니다.')
    await user.click(screen.getByRole('button', { name: '답변 보내기' }))

    expect(await screen.findByText('답변이 저장되었습니다.')).toBeVisible()
    const writeCall = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith('/api/v1/customer/requests/1042/comments') &&
        (init as RequestInit | undefined)?.method === 'POST',
    )
    expect(writeCall?.[1]).toMatchObject({
      credentials: 'include',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      headers: {
        'Content-Type': 'application/json',
        'X-CSRF-TOKEN': 'csrf-token',
      },
    })
    expect(JSON.parse(String(writeCall?.[1]?.body))).toMatchObject({
      body: '추가 정보입니다.',
      clientCommandId: expect.any(String),
    })
    await waitFor(() => {
      expect(
        fetchMock.mock.calls.filter(
          ([input, init]) =>
            String(input).endsWith('/api/v1/customer/requests/1042') &&
            !(init as RequestInit | undefined)?.method,
        ),
      ).toHaveLength(2)
    })
  })
})
