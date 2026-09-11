import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  CustomerSessionProvider,
  useCustomerSession,
} from '../customer-auth/CustomerSessionContext'
import { storeRequestAccessToken } from '../customer-portal/customerAccessToken'
import { CustomerRequestSuccessPage } from './CustomerRequestSuccessPage'
afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})
function show(number: string, submitted?: object) {
  return render(
    <MemoryRouter
      initialEntries={[
        { pathname: `/requests/submitted/${number}`, state: { submitted } },
      ]}
    >
      <Routes>
        <Route
          path="/requests/submitted/:ticketNumber"
          element={<CustomerRequestSuccessPage />}
        />
      </Routes>
    </MemoryRouter>,
  )
}
it.each(['not-a-number', '0', '-1', '9007199254740993'])(
  'never invents receipt success for invalid number %s',
  (number) => {
    show(number)
    expect(screen.getByText('문의 번호를 확인해 주세요.')).toBeVisible()
    expect(screen.queryByText(/문의 접수가 완료/)).not.toBeInTheDocument()
  },
)
it.each([
  undefined,
  { ticketNumber: 43, status: 'NEW', createdAt: '2026-09-01' },
  { ticketNumber: 42, status: 'NEW', createdAt: 'invalid' },
])(
  'requires a valid matching server receipt before claiming success',
  (submitted) => {
    show('42', submitted)
    expect(screen.getByText('접수 결과를 확인해 주세요.')).toBeVisible()
    expect(screen.queryByText('방금 전')).not.toBeInTheDocument()
  },
)
it('shows the server receipt status including a replay of an already solved request', () => {
  storeRequestAccessToken(sessionStorage, 42, 'a'.repeat(43))
  show('42', {
    ticketNumber: 42,
    status: 'SOLVED',
    createdAt: '2026-09-01T00:00:00Z',
  })
  expect(screen.getByText('문의 접수가 완료되었습니다')).toBeVisible()
  expect(screen.getAllByText('해결됨')).toHaveLength(2)
  expect(screen.queryByText('접수됨')).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '문의 보기' })).toHaveAttribute(
    'href',
    '/requests/42',
  )
})

it.each([true, false])(
  'does not infer receipt ownership from a later login (held proof: %s)',
  async (heldProof) => {
    if (heldProof) storeRequestAccessToken(sessionStorage, 42, 'a'.repeat(43))
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        Response.json({
          id: '11111111-1111-4111-8111-111111111111',
          email: 'other@example.test',
          displayName: '다른 고객',
          companyName: '',
          verifiedAt: '2026-09-01T00:00:00Z',
          credentialState: 'PASSWORD',
          registrationState: 'COMPLETE',
          availableAuthenticationMethods: ['PASSWORD'],
        }),
      ),
    )
    function SessionStatus() {
      return <output>{useCustomerSession().status}</output>
    }
    render(
      <QueryClientProvider
        client={
          new QueryClient({ defaultOptions: { queries: { retry: false } } })
        }
      >
        <CustomerSessionProvider>
          <SessionStatus />
          <MemoryRouter
            initialEntries={[
              {
                pathname: '/requests/submitted/42',
                state: {
                  submitted: {
                    ticketNumber: 42,
                    status: 'NEW',
                    createdAt: '2026-09-01T00:00:00Z',
                  },
                },
              },
            ]}
          >
            <Routes>
              <Route
                path="/requests/submitted/:ticketNumber"
                element={<CustomerRequestSuccessPage />}
              />
            </Routes>
          </MemoryRouter>
        </CustomerSessionProvider>
      </QueryClientProvider>,
    )
    await screen.findByText('authenticated')
    expect(
      await screen.findByRole('link', { name: '문의 보기' }),
    ).toHaveAttribute('href', heldProof ? '/requests/42' : '/requests/lookup')
  },
)
