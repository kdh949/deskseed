import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import { CustomerSessionProvider } from './CustomerSessionContext'
import { CustomerRegistrationCompletePage } from './CustomerRegistrationCompletePage'
import { CustomerAccountRoute } from './CustomerAccountRoute'
import { CustomerSignInPage } from './CustomerSignInPage'
import {
  customerAuthDestination,
  rememberCustomerAuthDestination,
  takeCustomerAuthDestination,
} from './customerAuthDestination'
import type { CurrentCustomer } from './api/customerAuthClient'
const customer: CurrentCustomer = {
  id: 'customer-completion',
  email: 'customer@example.test',
  displayName: '고객',
  companyName: '회사',
  verifiedAt: '2026-09-01T00:00:00Z',
  credentialState: 'PASSWORDLESS',
  registrationState: 'REGISTRATION_REQUIRED',
  availableAuthenticationMethods: ['MAGIC_LINK'],
}
afterEach(() => vi.unstubAllGlobals())
it('completes a passwordless account with CSRF and returns to the protected request without claiming tickets', async () => {
  const writes: RequestInit[] = []
  const paths: string[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      paths.push(url)
      const data = url.endsWith('/me')
        ? customer
        : url.includes('consent-policies')
          ? {
              policies: [
                {
                  policyKey: 'terms',
                  version: 1,
                  title: '가입 약관',
                  required: true,
                  document: {
                    schemaVersion: 1,
                    blocks: [{ type: 'paragraph', text: '약관 내용' }],
                  },
                },
              ],
            }
          : url.endsWith('/csrf')
            ? { token: 's'.repeat(32), headerName: 'X-CSRF-TOKEN' }
            : {
                ...customer,
                credentialState: 'PASSWORD',
                registrationState: 'COMPLETE',
                availableAuthenticationMethods: ['PASSWORD'],
              }
      if (init?.method === 'PUT') writes.push(init)
      return new Response(JSON.stringify(data), { status: 200 })
    }),
  )
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <CustomerSessionProvider>
        <MemoryRouter initialEntries={['/account/requests/24']}>
          <Routes>
            <Route element={<CustomerAccountRoute />}>
              <Route path="/account/requests/24" element={<h1>문의 24</h1>} />
            </Route>
            <Route
              path="/customer/register/complete"
              element={<CustomerRegistrationCompletePage />}
            />
          </Routes>
        </MemoryRouter>
      </CustomerSessionProvider>
    </QueryClientProvider>,
  )
  const user = userEvent.setup()
  expect(
    await screen.findByRole('heading', { name: '가입 마무리' }),
  ).toBeVisible()
  expect(
    screen.queryByRole('heading', { name: '문의 24' }),
  ).not.toBeInTheDocument()
  await user.type(screen.getByLabelText(/비밀번호/), 'synthetic-password-123')
  await user.click(
    screen.getByRole('checkbox', { name: '가입 약관에 동의합니다. (필수)' }),
  )
  await user.click(screen.getByRole('button', { name: '가입 완료' }))
  expect(await screen.findByRole('heading', { name: '문의 24' })).toBeVisible()
  expect(writes).toHaveLength(1)
  expect(writes[0]).toMatchObject({
    headers: { 'X-CSRF-TOKEN': 's'.repeat(32) },
    credentials: 'include',
    referrerPolicy: 'no-referrer',
  })
  expect(JSON.parse(writes[0]!.body as string)).not.toHaveProperty('email')
  expect(paths.some((path) => path.includes('claim'))).toBe(false)
})
it.each([
  'https://example.test',
  '//example.test',
  '/admin/staff',
  '/account/requests/24?token=secret',
])('ignores an unsafe login destination %s', (path) =>
  expect(
    customerAuthDestination(
      { ...customer, registrationState: 'COMPLETE' },
      path,
    ),
  ).toBe('/account/requests'),
)
it('uses password by default and returns to the requested detail', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            ...customer,
            credentialState: 'PASSWORD',
            registrationState: 'COMPLETE',
            availableAuthenticationMethods: ['PASSWORD'],
          }),
          { status: 200 },
        ),
    ),
  )
  render(
    <MemoryRouter
      initialEntries={[
        {
          pathname: '/customer/sign-in',
          state: { from: '/account/requests/24' },
        },
      ]}
    >
      <Routes>
        <Route path="/customer/sign-in" element={<CustomerSignInPage />} />
        <Route path="/account/requests/24" element={<h1>문의 24</h1>} />
      </Routes>
    </MemoryRouter>,
  )
  const user = userEvent.setup()
  expect(screen.getByRole('button', { name: '비밀번호' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  await user.type(screen.getByLabelText(/이메일 주소/), 'customer@example.test')
  await user.type(screen.getByLabelText(/비밀번호/), 'synthetic-password-123')
  await user.click(screen.getByRole('button', { name: '로그인' }))
  expect(await screen.findByRole('heading', { name: '문의 24' })).toBeVisible()
})

it('retains only a safe temporary return path and removes it when consumed', () => {
  rememberCustomerAuthDestination('/account/requests/24')
  expect(takeCustomerAuthDestination()).toBe('/account/requests/24')
  expect(takeCustomerAuthDestination()).toBeNull()
  rememberCustomerAuthDestination('//example.test')
  expect(takeCustomerAuthDestination()).toBeNull()
})
