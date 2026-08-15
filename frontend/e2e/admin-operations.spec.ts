import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

const admin = {
  id: '11111111-1111-4111-8111-111111111111',
  email: 'admin@example.test',
  displayName: '운영 관리자',
  role: 'ADMIN',
  capabilities: ['ADMIN_MANAGE'],
}
const csrfToken = 'c'.repeat(32)
const intentId = '22222222-2222-4222-8222-222222222222'

function failedIntent() {
  return {
    id: intentId,
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
    attempts: [
      {
        attemptNumber: 3,
        retryCycle: 0,
        cycleAttemptNumber: 3,
        status: 'PERMANENT_FAILED',
        failureClass: 'PERMANENT',
        failureCode: 'MAIL_DELIVERY_FAILURE',
        startedAt: '2026-08-15T10:02:00Z',
        finishedAt: '2026-08-15T10:03:00Z',
        nextRetryAt: null,
      },
    ],
    // The client must ignore unexpected sensitive keys rather than surface them.
    body: 'mail-body-must-never-render',
    rawRecipient: 'recipient@example.test',
    token: 'mail-token-must-never-render',
  }
}

test('admin failed-mail retry requires CSRF and a reason, then requeues the same safe intent', async ({
  page,
}) => {
  const observedRetries: Array<{
    body: unknown
    headers: Record<string, string>
  }> = []
  const intent = failedIntent()

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/v1/agent/me') {
      return route.fulfill({ status: 200, json: admin })
    }
    if (url.pathname === '/api/v1/agent/csrf') {
      return route.fulfill({
        status: 200,
        json: { token: csrfToken, headerName: 'X-CSRF-TOKEN' },
      })
    }
    if (url.pathname === '/api/v1/admin/mail/summary') {
      return route.fulfill({
        status: 200,
        json: {
          deliveryEnabled: true,
          schedulingEnabled: true,
          transport: 'SMTP',
          queuedCount: 0,
          sendingCount: 0,
          retryWaitCount: 0,
          failedCount: 1,
          sentCount: 12,
          oldestPendingAt: null,
        },
      })
    }
    if (
      url.pathname === '/api/v1/admin/mail/intents' &&
      request.method() === 'GET'
    ) {
      return route.fulfill({
        status: 200,
        json: { items: [intent], nextCursor: null },
      })
    }
    if (url.pathname === `/api/v1/admin/mail/intents/${intentId}`) {
      return route.fulfill({ status: 200, json: intent })
    }
    if (
      url.pathname === `/api/v1/admin/mail/intents/${intentId}/retry` &&
      request.method() === 'POST'
    ) {
      observedRetries.push({
        body: request.postDataJSON(),
        headers: request.headers(),
      })
      return route.fulfill({
        status: 200,
        json: {
          ...intent,
          status: 'QUEUED',
          manualRetryCount: 1,
          nextAttemptAt: '2026-08-15T10:10:00Z',
        },
      })
    }
    return route.abort()
  })

  await page.goto('/admin/operations/mail')
  await expect(page.getByRole('main', { name: '메일 운영' })).toBeVisible()
  await expect(page.getByText('***@example.test')).toBeVisible()
  await expect(page.getByText('recipient@example.test')).toHaveCount(0)
  await expect(page.getByText('mail-body-must-never-render')).toHaveCount(0)
  await expect(page.getByText('mail-token-must-never-render')).toHaveCount(0)

  await page.getByRole('button', { name: '운영 상세 보기' }).click()
  await page.getByRole('button', { name: '실패 메일 재시도' }).click()
  await page.getByLabel('재시도 사유').fill('수신자 주소 정정 후 재시도')
  await page.getByRole('button', { name: '사유와 함께 재시도' }).click()

  await expect(page.getByText('메일 재시도 요청을 등록했습니다.')).toBeVisible()
  expect(observedRetries).toEqual([
    {
      body: { reason: '수신자 주소 정정 후 재시도' },
      headers: expect.objectContaining({
        'x-csrf-token': csrfToken,
        'x-deskseed-expected-staff-id': admin.id,
      }),
    },
  ])
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})
