import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RequestAccessProvider } from '../features/customer-requests/RequestAccessContext'
import { NewRequestPage } from './NewRequestPage'

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <RequestAccessProvider>
        <MemoryRouter>
          <NewRequestPage />
        </MemoryRouter>
      </RequestAccessProvider>
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
  it('keeps submit disabled until valid and associates client errors with fields', async () => {
    const user = userEvent.setup()
    renderPage()

    const submit = screen.getByRole('button', { name: '문의 접수' })
    const email = screen.getByLabelText(/이메일/)
    expect(submit).toBeDisabled()

    await user.type(email, 'not-an-email')
    await user.tab()

    expect(email).toHaveAttribute('aria-invalid', 'true')
    expect(email).toHaveAccessibleDescription(
      '올바른 이메일 주소를 입력해 주세요.',
    )
    expect(
      screen.getByRole('alert', { name: '입력 내용을 확인해 주세요' }),
    ).toBeVisible()
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
          accessToken: 'memory-only-token',
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
})
