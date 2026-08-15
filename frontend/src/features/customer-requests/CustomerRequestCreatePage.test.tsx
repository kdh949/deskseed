import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useCustomerSession } from '../customer-auth/CustomerSessionContext'
import { CustomerRequestCreatePage } from './CustomerRequestCreatePage'

vi.mock('../customer-auth/CustomerSessionContext', () => ({
  useCustomerSession: vi.fn(),
}))

function LocationProbe() {
  const location = useLocation()
  return <p>{`${location.pathname}${location.hash}`}</p>
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/requests/new']}>
        <Routes>
          <Route path="/requests/new" element={<CustomerRequestCreatePage />} />
          <Route path="/requests/:ticketNumber" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function anonymousSession() {
  return {
    acceptAuthenticatedCustomer: vi.fn(),
    customer: null,
    retry: vi.fn(),
    signOut: vi.fn(),
    signingOut: false,
    status: 'anonymous' as const,
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe('CustomerRequestCreatePage', () => {
  it('does not render an anonymous submission form when the production access mode requires sign-in', async () => {
    vi.mocked(useCustomerSession).mockReturnValue(anonymousSession())
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ mode: 'REGISTRATION_REQUIRED' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )

    renderPage()

    expect(
      await screen.findByRole('heading', {
        name: '로그인이 필요한 문의 접수입니다.',
      }),
    ).toBeVisible()
    expect(screen.getByRole('link', { name: '고객 로그인' })).toHaveAttribute(
      'href',
      '/customer/sign-in',
    )
    expect(
      screen.queryByRole('button', { name: '문의 접수' }),
    ).not.toBeInTheDocument()
  })

  it('submits through createCustomerRequest and transfers its one-time proof only into the detail fragment', async () => {
    const user = userEvent.setup()
    vi.mocked(useCustomerSession).mockReturnValue(anonymousSession())
    const accessToken = 'a'.repeat(43)
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/v1/customer/access-mode')) {
        return Promise.resolve(
          new Response(JSON.stringify({ mode: 'ANONYMOUS_ALLOWED' }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        )
      }
      if (url.endsWith('/api/v1/requests') && init?.method === 'POST') {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              ticketNumber: 1042,
              status: 'NEW',
              accessToken,
              createdAt: '2026-08-15T00:00:00Z',
            }),
            { status: 201, headers: { 'Content-Type': 'application/json' } },
          ),
        )
      }
      return Promise.resolve(new Response(null, { status: 404 }))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderPage()

    await screen.findByRole('heading', { name: '문의하기' })
    await user.type(screen.getByLabelText('이름'), '김민아')
    await user.type(screen.getByLabelText('이메일'), 'mina@example.test')
    await user.type(screen.getByLabelText('제목'), '결제 확인 요청')
    await user.type(
      screen.getByLabelText('문의 내용'),
      '결제 승인 내역을 확인해 주세요.',
    )
    await user.click(screen.getByRole('button', { name: '문의 접수' }))

    expect(
      await screen.findByText(`/requests/1042#token=${accessToken}`),
    ).toBeVisible()
    const createCall = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith('/api/v1/requests') &&
        (init as RequestInit | undefined)?.method === 'POST',
    )
    expect(createCall?.[1]).toMatchObject({
      credentials: 'include',
      referrerPolicy: 'no-referrer',
      body: JSON.stringify({
        name: '김민아',
        email: 'mina@example.test',
        subject: '결제 확인 요청',
        message: '결제 승인 내역을 확인해 주세요.',
      }),
    })
    expect(String(createCall?.[0])).not.toContain(accessToken)
  })
})
