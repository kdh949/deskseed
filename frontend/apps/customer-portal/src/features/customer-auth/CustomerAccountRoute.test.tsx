import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CustomerAccountRoute } from './CustomerAccountRoute'
import { CustomerRouteLayout } from './CustomerRouteLayout'

function renderCustomerRoutes(initialEntry: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route element={<CustomerRouteLayout />}>
            <Route element={<CustomerAccountRoute />}>
              <Route
                path="/account/requests"
                element={<p>private-customer-account-content</p>}
              />
            </Route>
            <Route path="/" element={<p>customer-home</p>} />
            <Route path="/customer/sign-in" element={<p>customer-sign-in</p>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('CustomerAccountRoute', () => {
  it('does not construct account content before an anonymous visitor is redirected to customer sign-in', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ title: 'Unauthorized', status: 401 }), {
          status: 401,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      ),
    )

    renderCustomerRoutes('/account/requests')

    expect(await screen.findByText('customer-sign-in')).toBeVisible()
    expect(
      screen.queryByText('private-customer-account-content'),
    ).not.toBeInTheDocument()
  })

  it('does not show a logout failure after an already-expired customer session is cleared', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/v1/customer/me')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              id: '11111111-1111-4111-8111-111111111111',
              email: 'customer@example.test',
              displayName: '고객',
              companyName: '테스트 회사',
              verifiedAt: '2026-08-15T00:00:00Z',
              credentialState: 'PASSWORD',
              registrationState: 'COMPLETE',
              availableAuthenticationMethods: ['PASSWORD'],
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        )
      }
      if (url.endsWith('/api/v1/customer/csrf')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              token: 'a'.repeat(32),
              headerName: 'X-CSRF-TOKEN',
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        )
      }
      if (
        url.endsWith('/api/v1/customer/session') &&
        init?.method === 'DELETE'
      ) {
        return Promise.resolve(
          new Response(JSON.stringify({ status: 401 }), {
            status: 401,
            headers: { 'Content-Type': 'application/problem+json' },
          }),
        )
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    renderCustomerRoutes('/account/requests')

    await user.click(await screen.findByRole('button', { name: '로그아웃' }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/customer/session',
        expect.objectContaining({ method: 'DELETE' }),
      )
    })
    expect(
      screen.queryByText('로그아웃을 완료하지 못했습니다.'),
    ).not.toBeInTheDocument()
  })
})
