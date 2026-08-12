import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

const customer = {
  id: '11111111-1111-4111-8111-111111111111',
  email: 'customer@example.com',
  displayName: '김고객',
  verifiedAt: '2026-08-10T00:00:00Z',
}

const requestSummary = {
  ticketNumber: 1042,
  subject: '결제 오류 문의',
  status: 'OPEN',
  createdAt: '2026-08-10T00:00:00Z',
  updatedAt: '2026-08-10T01:00:00Z',
}

async function authenticate(page: Page) {
  await page.route('**/api/v1/customer/me', (route) =>
    route.fulfill({ status: 200, json: customer }),
  )
}

async function expectNoAxeViolations(page: Page) {
  const results = await new AxeBuilder({ page }).analyze()
  expect(results.violations).toEqual([])
}

for (const viewport of [
  { width: 1280, height: 800 },
  { width: 1440, height: 900 },
]) {
  test(`My Requests customer-safe visual at ${viewport.width}`, async ({ browserName, page }) => {
    await page.setViewportSize(viewport)
    await authenticate(page)
    await page.route('**/api/v1/customer/requests', (route) =>
      route.fulfill({
        status: 200,
        json: {
          items: [
            {
              ...requestSummary,
              internalComments: [{ body: '내부 메모 비밀' }],
              children: [{ subject: '하위 티켓 비밀' }],
              audit: [{ eventType: 'STAFF_ONLY' }],
            },
          ],
          nextCursor: null,
        },
      }),
    )
    await page.goto('/account/requests')

    await expect(
      page.getByRole('heading', { name: '내 문의', exact: true }),
    ).toBeVisible()
    await expect(page.getByRole('link', { name: /결제 오류 문의/ })).toBeVisible()
    for (const secret of ['내부 메모 비밀', '하위 티켓 비밀', 'STAFF_ONLY']) {
      await expect(page.getByText(secret)).toHaveCount(0)
    }
    await expectNoAxeViolations(page)
    if (browserName === 'chromium') {
      await expect(page).toHaveScreenshot(`customer-my-requests-${viewport.width}.png`, {
        fullPage: true,
      })
    }
  })
}

test('expired claim proof keeps input and moves focus to a safe state', async ({ page }) => {
  await authenticate(page)
  await page.route('**/api/v1/customer/requests', (route) =>
    route.fulfill({ status: 200, json: { items: [], nextCursor: null } }),
  )
  await page.route('**/api/v1/customer/csrf', (route) =>
    route.fulfill({ status: 200, json: { token: 'csrf-token', headerName: 'X-CSRF-TOKEN' } }),
  )
  await page.route('**/api/v1/customer/requests/1099/claim', (route) =>
    route.fulfill({
      status: 404,
      contentType: 'application/problem+json',
      json: { type: '/problems/customer-request-not-found', status: 404, requestId: 'claim-safe-id' },
    }),
  )
  await page.goto('/account/requests')
  await page.getByRole('spinbutton', { name: '접수 번호' }).fill('1099')
  await page.getByRole('textbox', { name: '연결 증명' }).fill('x'.repeat(43))
  await page.getByRole('button', { name: '문의 연결' }).click()

  const state = page.getByRole('status', { name: '연결 증명을 사용할 수 없습니다.' })
  await expect(state).toBeVisible()
  await expect(page.getByRole('textbox', { name: '연결 증명' })).toHaveValue('x'.repeat(43))
  await expectNoAxeViolations(page)
})

test('PUBLIC follow-up failure preserves draft and retry uses the same command id', async ({ page }) => {
  await authenticate(page)
  let followUpAttempts = 0
  const commandIds: string[] = []
  let comments = [
    {
      id: 'comment-1',
      authorDisplayName: '김고객',
      body: '최초 문의입니다.',
      createdAt: requestSummary.createdAt,
    },
  ]
  await page.route('**/api/v1/customer/csrf', (route) =>
    route.fulfill({ status: 200, json: { token: 'csrf-token', headerName: 'X-CSRF-TOKEN' } }),
  )
  await page.route(
    '**/api/v1/customer/requests/1042/comments',
    async (route) => {
      followUpAttempts += 1
      const body = route.request().postDataJSON() as { clientCommandId: string }
      commandIds.push(body.clientCommandId)
      if (followUpAttempts === 1) {
        return route.fulfill({
          status: 503,
          contentType: 'application/problem+json',
          json: { status: 503, requestId: 'follow-up-safe-id' },
        })
      }
      const created = {
        id: 'comment-2',
        authorDisplayName: '김고객',
        body: '추가 정보입니다.',
        createdAt: requestSummary.updatedAt,
      }
      comments = [...comments, created]
      return route.fulfill({ status: 201, json: created })
    },
  )
  await page.route('**/api/v1/customer/requests/1042', async (route) => {
    return route.fulfill({ status: 200, json: { ...requestSummary, comments } })
  })
  await page.goto('/account/requests/1042')
  const draft = page.getByRole('textbox', { name: '공개 후속 답변' })
  await draft.fill('추가 정보입니다.')
  await page.getByRole('button', { name: '공개 답변 보내기' }).click()
  await expect(page.getByRole('alert', { name: '후속 답변을 보내지 못했습니다.' })).toContainText(
    'follow-up-safe-id',
  )
  await expect(draft).toHaveValue('추가 정보입니다.')

  await page.getByRole('button', { name: '공개 답변 보내기' }).click()
  await expect(draft).toHaveValue('')
  expect(commandIds).toHaveLength(2)
  expect(new Set(commandIds).size).toBe(1)
  await expectNoAxeViolations(page)
})
