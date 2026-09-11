import { StrictMode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import { CustomerRegisterPage } from './CustomerRegisterPage'
import { CustomerCheckEmailPage } from './CustomerCheckEmailPage'
import { CustomerRegistrationVerifyPage } from './CustomerRegistrationVerifyPage'
const policies = [true, false].map((required, index) => ({
  policyKey: `policy-${index}`,
  version: 2,
  title: required ? '필수 약관' : '선택 약관',
  required,
  document: {
    schemaVersion: 1,
    blocks: [{ type: 'paragraph', text: '약관 전체 내용' }],
  },
}))
afterEach(() => {
  vi.unstubAllGlobals()
  window.history.replaceState(null, '', '/')
})
function registration() {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={['/customer/register']}>
        <Routes>
          <Route path="/customer/register" element={<CustomerRegisterPage />} />
          <Route
            path="/customer/sign-in/check-email"
            element={<CustomerCheckEmailPage />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
it('shows policies and submits only selected versions without a magic-link resend', async () => {
  const calls: Array<{ url: string; body?: string }> = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, body: init?.body as string })
      return url.includes('consent-policies')
        ? new Response(JSON.stringify({ policies }), { status: 200 })
        : new Response(null, { status: 202 })
    }),
  )
  registration()
  const user = userEvent.setup()
  await user.click(await screen.findByText('필수 약관 내용 보기'))
  expect(screen.getAllByText('약관 전체 내용')[0]).toBeVisible()
  await user.type(screen.getByLabelText('이름', { exact: false }), '고객')
  await user.type(
    screen.getByLabelText('이메일', { exact: false }),
    'customer@example.test',
  )
  await user.type(screen.getByLabelText('회사명', { exact: false }), '회사')
  await user.type(
    screen.getByLabelText('비밀번호', { exact: false }),
    'synthetic-password-123',
  )
  await user.click(
    screen.getByRole('checkbox', { name: '필수 약관에 동의합니다. (필수)' }),
  )
  await user.click(screen.getByRole('button', { name: '계정 만들기' }))
  expect(await screen.findByText(/가입을 요청한 브라우저/)).toBeVisible()
  expect(
    screen.queryByRole('button', { name: '링크 다시 보내기' }),
  ).not.toBeInTheDocument()
  expect(screen.queryByText(/15분/)).not.toBeInTheDocument()
  expect(
    JSON.parse(
      calls.find((call) => call.url.endsWith('/registrations'))!.body!,
    ),
  ).toMatchObject({ acceptedPolicies: [{ policyKey: 'policy-0', version: 2 }] })
  expect(calls.some((call) => call.url.includes('magic-link'))).toBe(false)
})
it('does not invent agreement text without policies', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(
      async () =>
        new Response(JSON.stringify({ policies: [] }), { status: 200 }),
    ),
  )
  registration()
  expect(
    await screen.findByText('가입 약관을 준비하고 있습니다.'),
  ).toBeVisible()
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
})
it('fails closed on an invalid policy document', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(
      async () =>
        new Response(
          JSON.stringify({ policies: [{ ...policies[0], document: null }] }),
          { status: 200 },
        ),
    ),
  )
  registration()
  expect(
    await screen.findByText('가입 약관을 불러올 수 없습니다.'),
  ).toBeVisible()
})
it('scrubs proof before a single verification request and does not log in', async () => {
  window.history.replaceState(
    null,
    '',
    '/customer/register/verify#token=synthetic-verification-proof',
  )
  const fetch = vi.fn(async () => {
    expect(window.location.hash).toBe('')
    return new Response(null, { status: 204 })
  })
  vi.stubGlobal('fetch', fetch)
  render(
    <StrictMode>
      <MemoryRouter>
        <CustomerRegistrationVerifyPage />
      </MemoryRouter>
    </StrictMode>,
  )
  expect(
    await screen.findByRole('heading', { name: '가입이 완료되었습니다.' }),
  ).toBeVisible()
  expect(fetch).toHaveBeenCalledTimes(1)
  expect(fetch).toHaveBeenCalledWith(
    '/api/v1/customer/registration-verifications',
    expect.objectContaining({
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
    }),
  )
})
it.each([401, 409, 503])(
  'provides recovery for verification status %s',
  async (status) => {
    window.history.replaceState(
      null,
      '',
      '/customer/register/verify#token=synthetic-verification-proof',
    )
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status })),
    )
    render(
      <MemoryRouter>
        <CustomerRegistrationVerifyPage />
      </MemoryRouter>,
    )
    expect(
      await screen.findByRole('link', { name: '가입 다시 요청하기' }),
    ).toBeVisible()
    expect(screen.queryByText('가입이 완료되었습니다.')).not.toBeInTheDocument()
  },
)
