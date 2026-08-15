import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DeskseedThemeProvider } from '../../design-system'
import { AdminCustomerAccessModePage } from './AdminCustomerAccessModePage'

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <DeskseedThemeProvider>
      <QueryClientProvider client={queryClient}>
        <AdminCustomerAccessModePage />
      </QueryClientProvider>
    </DeskseedThemeProvider>,
  )
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('AdminCustomerAccessModePage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('keeps the selected policy after a 409 conflict', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = new URL(String(input), 'http://deskseed.test').pathname
        if (path === '/api/v1/admin/settings/customer-access-mode') {
          if (init?.method === 'PUT') return json({ status: 409 }, 409)
          return json({
            mode: 'ANONYMOUS_ALLOWED',
            version: 3,
            updatedAt: '2026-08-15T10:00:00Z',
          })
        }
        if (path === '/api/v1/agent/csrf') {
          return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
        }
        throw new Error(`Unexpected request: ${path}`)
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    const select = await screen.findByLabelText('고객 접근 모드')
    await user.selectOptions(select, 'REGISTRATION_REQUIRED')
    await user.click(screen.getByRole('button', { name: '정책 저장' }))

    expect(
      await screen.findByText('다른 관리자가 고객 접근 정책을 변경했습니다.'),
    ).toBeVisible()
    expect(select).toHaveValue('REGISTRATION_REQUIRED')
  })

  it('renders a denied state when the protected setting rejects the session', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(json({ status: 403 }, 403)),
    )
    renderPage()

    expect(
      await screen.findByText('고객 접근 정책 권한이 없습니다.'),
    ).toBeVisible()
  })
})
