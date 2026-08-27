import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { requestAccessTokenStorageKey } from '../customer-portal/customerAccessToken'
import { AnonymousRequestDetailPage } from './AnonymousRequestDetailPage'

function renderPage(path = '/requests/1042') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route
            path="/requests/:ticketNumber"
            element={<AnonymousRequestDetailPage />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  sessionStorage.clear()
  window.history.replaceState(null, '', '/')
  vi.unstubAllGlobals()
})

describe('AnonymousRequestDetailPage', () => {
  it('removes an email-link fragment before the first API request and only renders the PUBLIC projection', async () => {
    const accessToken = 'a'.repeat(43)
    window.history.replaceState(null, '', `/requests/1042#token=${accessToken}`)
    const fetchMock = vi.fn(() => {
      expect(window.location.hash).toBe('')
      return Promise.resolve(
        new Response(
          JSON.stringify({
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
                attachments: [],
                internalNote: 'must-not-render',
              },
            ],
            auditMetadata: 'must-not-render',
            children: [{ ticketNumber: 1043 }],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
    })
    vi.stubGlobal('fetch', fetchMock)

    renderPage()

    expect(
      await screen.findByRole('heading', { name: '#1042 결제 확인 요청' }),
    ).toBeVisible()
    expect(window.location.hash).toBe('')
    expect(sessionStorage.getItem(requestAccessTokenStorageKey(1042))).toBe(
      accessToken,
    )
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/requests/1042', {
      credentials: 'include',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      headers: { 'X-Request-Access-Token': accessToken },
    })
    expect(screen.queryByText('must-not-render')).not.toBeInTheDocument()
    expect(screen.queryByText('1043')).not.toBeInTheDocument()
  })

  it('does not make a detail request without a ticket-scoped email-link proof', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    renderPage()

    expect(
      await screen.findByRole('heading', {
        name: '이메일 문의 링크가 필요합니다.',
      }),
    ).toBeVisible()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
