import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useEffect, useState, type PropsWithChildren } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  RequestAccessProvider,
  useRequestAccess,
} from '../features/customer-requests/RequestAccessContext'
import { RequestDetailPage } from './RequestDetailPage'

function SeedAccess({ children }: PropsWithChildren) {
  const access = useRequestAccess()
  const [ready, setReady] = useState(false)
  useEffect(() => {
    access.setAccessToken(1042, 'memory-only-token')
    setReady(true)
  }, [])
  return ready ? children : null
}

function renderPage({ withToken = false }: { withToken?: boolean } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const content = withToken ? (
    <SeedAccess>
      <Routes>
        <Route path="/requests/:ticketNumber" element={<RequestDetailPage />} />
      </Routes>
    </SeedAccess>
  ) : (
    <Routes>
      <Route path="/requests/:ticketNumber" element={<RequestDetailPage />} />
    </Routes>
  )
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RequestAccessProvider>
        <MemoryRouter initialEntries={['/requests/1042']}>
          {content}
        </MemoryRouter>
      </RequestAccessProvider>
    </QueryClientProvider>,
  )
  return { ...view, queryClient }
}

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('RequestDetailPage', () => {
  it('asks for a key after direct navigation without sending a request', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    expect(
      screen.getByRole('heading', { name: '조회 키가 필요합니다.' }),
    ).toBeVisible()
    expect(screen.getByRole('textbox', { name: /조회 키/ })).toHaveAttribute(
      'autocomplete',
      'off',
    )
    expect(screen.getByText(/브라우저에 저장하지 않습니다/)).toBeVisible()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('announces the loading state', () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockReturnValue(new Promise(() => undefined)),
    )
    renderPage({ withToken: true })

    expect(
      screen.getByRole('status', { name: '문의 내용을 불러오는 중' }),
    ).toHaveAttribute('aria-busy', 'true')
  })

  it('uses the same denied UI for an invalid key or missing ticket', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        response(
          {
            title: 'Request not found for ticket 1042',
            detail: 'Token hash mismatch',
            requestId: 'req-denied',
          },
          404,
        ),
      ),
    )
    renderPage({ withToken: true })

    const alert = await screen.findByRole('alert', {
      name: '문의 정보를 확인할 수 없습니다',
    })
    expect(alert).toHaveTextContent('접수 번호와 조회 키를 확인해 주세요.')
    expect(alert).not.toHaveTextContent('Token hash mismatch')
    expect(alert).not.toHaveTextContent('Request not found')
    expect(alert).not.toHaveTextContent('req-denied')
  })

  it('shows a safe retry state and request ID for temporary failures', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        response(
          {
            title: 'Database unavailable',
            detail: 'connection refused',
            requestId: 'req-read-failure',
          },
          503,
        ),
      ),
    )
    renderPage({ withToken: true })

    const alert = await screen.findByRole('alert', { name: '문의 조회 오류' })
    expect(alert).toHaveTextContent('잠시 후 다시 시도해 주세요.')
    expect(alert).toHaveTextContent('요청 ID: req-read-failure')
    expect(alert).not.toHaveTextContent('connection refused')
  })

  it('renders only the public projection even when unexpected private fields exist', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        response({
          ticketNumber: 1042,
          subject: '결제 오류',
          status: 'OPEN',
          createdAt: '2026-08-10T00:00:00Z',
          updatedAt: '2026-08-10T01:00:00Z',
          comments: [
            {
              id: 'public-1',
              authorDisplayName: '김고객',
              body: '공개 문의 본문',
              createdAt: '2026-08-10T00:00:00Z',
            },
          ],
          internalComments: [{ body: '내부 메모 비밀' }],
          childTickets: [{ subject: '하위 티켓 비밀' }],
          group: { name: '보안 그룹' },
          assignee: { name: '담당 상담사' },
          audit: [{ metadata: '감사 비밀' }],
          externalReferences: [
            {
              externalId: 'order-private-100',
              displayLabel: '외부 주문 비밀',
              deepLinkUrl: 'https://admin.shop.example/orders/private-100',
              metadataSnapshot: { status: '환불 검토 비밀' },
            },
          ],
        }),
      ),
    )
    const { queryClient } = renderPage({ withToken: true })

    expect(
      await screen.findByRole('heading', { name: '결제 오류' }),
    ).toBeVisible()
    expect(screen.getByText('공개 문의 본문')).toBeVisible()
    expect(screen.getByText('김고객')).toBeVisible()
    for (const privateText of [
      '내부 메모 비밀',
      '하위 티켓 비밀',
      '보안 그룹',
      '담당 상담사',
      '감사 비밀',
      'order-private-100',
      '외부 주문 비밀',
      'https://admin.shop.example/orders/private-100',
      '환불 검토 비밀',
    ]) {
      expect(screen.queryByText(privateText)).not.toBeInTheDocument()
    }
    const cachedPublicData = JSON.stringify(
      queryClient
        .getQueryCache()
        .findAll({ queryKey: ['public-request', 1042] })
        .map((query) => query.state.data),
    )
    expect(cachedPublicData).not.toContain('내부 메모 비밀')
    expect(cachedPublicData).not.toContain('하위 티켓 비밀')
    expect(cachedPublicData).not.toContain('보안 그룹')
    expect(cachedPublicData).not.toContain('담당 상담사')
    expect(cachedPublicData).not.toContain('감사 비밀')
    expect(cachedPublicData).not.toContain('order-private-100')
    expect(cachedPublicData).not.toContain('외부 주문 비밀')
    expect(cachedPublicData).not.toContain('admin.shop.example')
    expect(cachedPublicData).not.toContain('환불 검토 비밀')
  })

  it('shows an explicit empty conversation state', async () => {
    const user = userEvent.setup()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        response({
          ticketNumber: 1042,
          subject: '빈 공개 대화',
          status: 'NEW',
          createdAt: '2026-08-10T00:00:00Z',
          updatedAt: '2026-08-10T00:00:00Z',
          comments: [],
        }),
      ),
    )
    renderPage()
    await user.type(
      screen.getByRole('textbox', { name: /조회 키/ }),
      'fresh-token-that-is-at-least-32-characters',
    )
    await user.click(screen.getByRole('button', { name: '문의 확인' }))

    expect(await screen.findByText('공개 대화가 아직 없습니다.')).toBeVisible()
  })
})
