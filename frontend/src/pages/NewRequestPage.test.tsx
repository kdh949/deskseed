import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RequestAccessProvider } from '../features/customer-requests/RequestAccessContext'
import { RequestSubmissionProvider } from '../features/customer-requests/RequestSubmissionContext'
import { CustomerSessionProvider } from '../features/customer-auth/CustomerSessionContext'
import { NewRequestPage } from './NewRequestPage'

function renderPage(withCustomerSession = false) {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  })
  const router = createMemoryRouter(
    [{ path: '/requests/new', element: <NewRequestPage /> }],
    { initialEntries: ['/requests/new'] },
  )
  const page = (
    <RequestAccessProvider>
      <RequestSubmissionProvider>
        <RouterProvider router={router} />
      </RequestSubmissionProvider>
    </RequestAccessProvider>
  )
  return render(
    <QueryClientProvider client={queryClient}>
      {withCustomerSession ? (
        <CustomerSessionProvider>{page}</CustomerSessionProvider>
      ) : (
        page
      )}
    </QueryClientProvider>,
  )
}

async function fillValidForm(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/이름/), '김고객')
  await user.type(screen.getByLabelText(/이메일/), 'customer@example.com')
  await user.type(screen.getByLabelText(/제목/), '결제 오류')
  await user.type(
    screen.getByLabelText(/문의 내용/),
    '결제 버튼을 누르면 오류가 납니다.',
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('NewRequestPage', () => {
  it('uses authenticated identity and customer CSRF when registration is required', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/v1/customer/me')) {
        return new Response(
          JSON.stringify({
            id: 'customer-id',
            email: 'account@example.com',
            displayName: '계정 고객',
            verifiedAt: '2026-08-10T00:00:00Z',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        )
      }
      if (url.endsWith('/api/v1/customer/access-mode')) {
        return new Response(JSON.stringify({ mode: 'REGISTRATION_REQUIRED' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      if (url.endsWith('/api/v1/customer/csrf')) {
        return new Response(JSON.stringify({ token: 'customer-csrf-token' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      if (url.endsWith('/api/v1/requests')) {
        return new Response(
          JSON.stringify({
            ticketNumber: 1042,
            status: 'NEW',
            accessToken: 'request-access-token-that-is-long-enough',
            createdAt: '2026-08-10T00:00:00Z',
          }),
          { status: 201, headers: { 'Content-Type': 'application/json' } },
        )
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage(true)

    expect(await screen.findByLabelText(/이름 \(로그인 계정\)/)).toHaveValue(
      '계정 고객',
    )
    expect(screen.getByLabelText(/이메일 \(로그인 계정\)/)).toHaveValue(
      'account@example.com',
    )
    await user.type(screen.getByLabelText(/제목/), '인증 문의')
    await user.type(screen.getByLabelText(/문의 내용/), '인증 고객 문의 본문')
    await user.click(screen.getByRole('button', { name: '문의 접수' }))

    await screen.findByText('문의 #1042')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/requests',
      expect.objectContaining({
        credentials: 'include',
        headers: expect.objectContaining({
          'X-CSRF-TOKEN': 'customer-csrf-token',
        }),
      }),
    )
  })

  it('blocks an anonymous form when registration is required', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) =>
        String(input).endsWith('/api/v1/customer/me')
          ? new Response(null, { status: 401 })
          : new Response(JSON.stringify({ mode: 'REGISTRATION_REQUIRED' }), {
              status: 200,
              headers: { 'Content-Type': 'application/json' },
            }),
      ),
    )
    renderPage(true)

    expect(
      await screen.findByRole('heading', {
        name: '로그인 후 문의를 접수할 수 있습니다.',
      }),
    ).toBeVisible()
    expect(
      screen.queryByRole('button', { name: '문의 접수' }),
    ).not.toBeInTheDocument()
  })

  it('keeps keyboard focus on the next field after blur and focuses the summary on submit', async () => {
    const user = userEvent.setup()
    renderPage()

    const submit = screen.getByRole('button', { name: '문의 접수' })
    const email = screen.getByLabelText(/이메일/)
    expect(submit).toBeEnabled()

    await user.type(email, 'not-an-email')
    await user.tab()

    expect(email).toHaveAttribute('aria-invalid', 'true')
    expect(email).toHaveAccessibleDescription(
      '올바른 이메일 주소를 입력해 주세요.',
    )
    expect(screen.getByLabelText(/제목/)).toHaveFocus()
    expect(
      screen.queryByRole('alert', { name: '입력 내용을 확인해 주세요' }),
    ).not.toBeInTheDocument()

    await user.click(submit)

    expect(
      await screen.findByRole('alert', { name: '입력 내용을 확인해 주세요' }),
    ).toHaveFocus()
  })

  it('maps server field errors and moves focus to the preserved error summary', async () => {
    const user = userEvent.setup()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            type: '/problems/validation',
            title: 'Request validation failed',
            status: 400,
            fieldErrors: [
              { field: 'email', message: '이미 사용할 수 없는 주소입니다.' },
            ],
            requestId: 'req-validation',
          }),
          {
            status: 400,
            headers: { 'Content-Type': 'application/problem+json' },
          },
        ),
      ),
    )
    renderPage()
    await fillValidForm(user)

    await user.click(screen.getByRole('button', { name: '문의 접수' }))

    const summary = await screen.findByRole('alert', {
      name: '입력 내용을 확인해 주세요',
    })
    expect(summary).toHaveFocus()
    expect(screen.getByLabelText(/이메일/)).toHaveValue('customer@example.com')
    expect(screen.getByLabelText(/이메일/)).toHaveAccessibleDescription(
      '이미 사용할 수 없는 주소입니다.',
    )
    expect(summary).toHaveTextContent('요청 ID: req-validation')
  })

  it('blocks duplicate submission while the first request is pending', async () => {
    const user = userEvent.setup()
    let resolveRequest: ((response: Response) => void) | undefined
    const fetchMock = vi.fn().mockReturnValue(
      new Promise<Response>((resolve) => {
        resolveRequest = resolve
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    renderPage()
    await fillValidForm(user)

    const submit = screen.getByRole('button', { name: '문의 접수' })
    await user.click(submit)
    await user.click(submit)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(submit).toBeDisabled()
    expect(submit).toHaveTextContent('안전하게 접수하는 중')

    resolveRequest?.(
      new Response(
        JSON.stringify({
          ticketNumber: 1042,
          status: 'NEW',
          accessToken: 'memory-only-token-that-is-at-least-32-characters',
          createdAt: '2026-08-10T00:00:00Z',
        }),
        { status: 201, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    await waitFor(() => expect(screen.getByText('문의 #1042')).toBeVisible())
  })

  it('preserves all input and request ID for server failures', async () => {
    const user = userEvent.setup()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            type: '/problems/request-write-unavailable',
            title: 'Request storage unavailable',
            status: 503,
            requestId: 'req-storage',
          }),
          {
            status: 503,
            headers: { 'Content-Type': 'application/problem+json' },
          },
        ),
      ),
    )
    renderPage()
    await fillValidForm(user)

    await user.click(screen.getByRole('button', { name: '문의 접수' }))

    expect(
      await screen.findByRole('alert', { name: '문의 접수 오류' }),
    ).toHaveTextContent('요청 ID: req-storage')
    expect(screen.getByLabelText(/제목/)).toHaveValue('결제 오류')
    expect(screen.getByLabelText(/문의 내용/)).toHaveValue(
      '결제 버튼을 누르면 오류가 납니다.',
    )
  })

  it('turns an HTTP-date Retry-After value into safe retry guidance', async () => {
    const user = userEvent.setup()
    const retryAt = new Date(Date.now() + 60_000).toUTCString()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ status: 429 }), {
          status: 429,
          headers: {
            'Content-Type': 'application/problem+json',
            'Retry-After': retryAt,
          },
        }),
      ),
    )
    renderPage()
    await fillValidForm(user)

    await user.click(screen.getByRole('button', { name: '문의 접수' }))

    const summary = await screen.findByRole('alert', {
      name: '잠시 후 다시 시도해 주세요',
    })
    expect(summary).toHaveTextContent(/\d+초 뒤에 다시 접수해 주세요/)
    expect(summary).not.toHaveTextContent(retryAt)
  })
})
