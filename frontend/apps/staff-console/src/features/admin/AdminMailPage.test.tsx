import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DeskseedThemeProvider } from '../../design-system'
import { AdminMailPage } from './AdminMailPage'

const intent = {
  id: '11111111-1111-4111-8111-111111111111',
  template: 'REQUEST_RECEIVED',
  templateVersion: 1,
  status: 'FAILED',
  recipientMasked: '***@example.test',
  attemptCount: 3,
  maxAttempts: 3,
  retryCycle: 0,
  manualRetryCount: 0,
  nextAttemptAt: null,
  leaseExpiresAt: null,
  lastErrorCode: 'MAIL_DELIVERY_FAILURE',
  queuedAt: '2026-08-15T10:00:00Z',
  sentAt: null,
  failedAt: '2026-08-15T10:03:00Z',
  attempts: [],
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <DeskseedThemeProvider>
      <QueryClientProvider client={queryClient}>
        <AdminMailPage />
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

describe('AdminMailPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('keeps a retry reason after a concurrent retry conflict without rendering mail content', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = new URL(String(input), 'http://deskseed.test').pathname
        if (path === '/api/v1/admin/mail/summary') {
          return json({
            deliveryEnabled: true,
            schedulingEnabled: true,
            transport: 'SMTP',
            queuedCount: 0,
            sendingCount: 0,
            retryWaitCount: 0,
            failedCount: 1,
            sentCount: 0,
            oldestPendingAt: null,
          })
        }
        if (path === '/api/v1/admin/mail/intents') {
          return json({ items: [intent], nextCursor: null })
        }
        if (path === `/api/v1/admin/mail/intents/${intent.id}`)
          return json(intent)
        if (path === '/api/v1/agent/csrf') {
          return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
        }
        if (path.endsWith('/retry') && init?.method === 'POST') {
          return json({ status: 409 }, 409)
        }
        throw new Error(`Unexpected request: ${path}`)
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    await screen.findByText('***@example.test')
    await user.click(screen.getByRole('button', { name: '운영 상세 보기' }))
    await user.click(
      await screen.findByRole('button', { name: '실패 메일 재시도' }),
    )
    const reason = screen.getByRole('textbox', { name: '재시도 사유' })
    await user.type(reason, '주소 정정 후 재시도')
    await user.click(screen.getByRole('button', { name: '사유와 함께 재시도' }))

    expect(
      await screen.findByText(
        '다른 운영자가 이미 이 전송 의도를 변경했습니다.',
      ),
    ).toBeVisible()
    expect(reason).toHaveValue('주소 정정 후 재시도')
    expect(document.body.textContent).not.toContain('customer@example.test')
    expect(document.body.textContent).not.toContain(
      'mail body must never render',
    )
  })
})
