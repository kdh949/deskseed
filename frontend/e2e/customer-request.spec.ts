import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { pressSequentialTab } from './keyboard'

const ACCESS_TOKEN = 'browser-memory-only-token-1234567890'
const INVALID_ACCESS_TOKEN = 'browser-invalid-token-123456789012345'

const createdRequest = {
  ticketNumber: 1042,
  status: 'NEW',
  accessToken: ACCESS_TOKEN,
  createdAt: '2026-08-10T00:00:00Z',
}

const publicRequest = {
  ticketNumber: 1042,
  subject: '결제 오류 문의',
  status: 'OPEN',
  createdAt: '2026-08-10T00:00:00Z',
  updatedAt: '2026-08-10T01:00:00Z',
  comments: [
    {
      id: 'public-comment-1',
      authorDisplayName: '김고객',
      body: '결제 버튼을 누르면 오류가 납니다.',
      createdAt: '2026-08-10T00:00:00Z',
    },
  ],
  internalComments: [{ body: '내부 메모 비밀' }],
  childTickets: [{ subject: '하위 티켓 비밀' }],
  assignee: { name: '담당 상담사' },
}

async function fillForm(page: Page) {
  await page.getByRole('textbox', { name: /이름/ }).fill('김고객')
  await page
    .getByRole('textbox', { name: /이메일/ })
    .fill('customer@example.com')
  await page.getByRole('textbox', { name: /제목/ }).fill('결제 오류 문의')
  await page
    .getByRole('textbox', { name: /문의 내용/ })
    .fill('결제 버튼을 누르면 오류가 납니다.')
}

async function expectNoAxeViolations(page: Page) {
  const results = await new AxeBuilder({ page }).analyze()
  expect(results.violations).toEqual([])
}

test('keyboard-only create to public detail keeps the token out of browser storage and URL', async ({
  browserName,
  page,
}) => {
  let observedTokenHeader = ''
  await page.route('**/api/v1/requests', (route) =>
    route.fulfill({ status: 201, json: createdRequest }),
  )
  await page.route('**/api/v1/requests/1042', (route) => {
    observedTokenHeader =
      route.request().headers()['x-request-access-token'] ?? ''
    return route.fulfill({ status: 200, json: publicRequest })
  })
  await page.goto('/requests/new')

  await page.getByRole('textbox', { name: /이름/ }).focus()
  await page.keyboard.type('김고객')
  await pressSequentialTab(page, browserName)
  await page.keyboard.type('customer@example.com')
  await pressSequentialTab(page, browserName)
  await page.keyboard.type('결제 오류 문의')
  await pressSequentialTab(page, browserName)
  await page.keyboard.type('결제 버튼을 누르면 오류가 납니다.')
  await pressSequentialTab(page, browserName)
  await expect(page.getByRole('button', { name: '문의 접수' })).toBeFocused()
  await page.keyboard.press('Enter')

  const successHeading = page.getByRole('heading', { name: '문의 #1042' })
  await expect(successHeading).toBeFocused()
  await expect(page.getByLabel('문의 조회 키')).toHaveText(ACCESS_TOKEN)
  const browserStorage = await page.evaluate(() => ({
    local: Object.keys(localStorage),
    session: Object.keys(sessionStorage),
  }))
  expect(browserStorage).toEqual({ local: [], session: [] })
  expect(page.url()).not.toContain(ACCESS_TOKEN)

  await page.getByRole('link', { name: '문의 내용 보기' }).click()
  await expect(
    page.getByRole('heading', { name: '결제 오류 문의' }),
  ).toBeVisible()
  expect(observedTokenHeader).toBe(ACCESS_TOKEN)
  expect(page.url()).not.toContain(ACCESS_TOKEN)
  await expect(
    page.getByText('결제 버튼을 누르면 오류가 납니다.'),
  ).toBeVisible()
  for (const privateText of [
    '내부 메모 비밀',
    '하위 티켓 비밀',
    '담당 상담사',
  ]) {
    await expect(page.getByText(privateText)).toHaveCount(0)
  }
  await expectNoAxeViolations(page)
})

test('server validation and temporary failure preserve the form and move focus', async ({
  page,
}) => {
  let attempt = 0
  await page.route('**/api/v1/requests', (route) => {
    attempt += 1
    if (attempt === 1) {
      return route.fulfill({
        status: 400,
        contentType: 'application/problem+json',
        json: {
          title: 'Request validation failed',
          status: 400,
          fieldErrors: [{ field: 'email', message: '이메일을 확인해 주세요.' }],
          requestId: 'e2e-validation',
        },
      })
    }
    return route.fulfill({
      status: 503,
      contentType: 'application/problem+json',
      json: {
        title: 'Storage unavailable',
        status: 503,
        requestId: 'e2e-storage',
      },
    })
  })
  await page.goto('/requests/new')
  await fillForm(page)

  await page.getByRole('button', { name: '문의 접수' }).click()
  const validation = page.getByRole('alert', {
    name: '입력 내용을 확인해 주세요',
  })
  await expect(validation).toBeFocused()
  await expect(validation).toContainText('요청 ID: e2e-validation')
  await expect(
    page.getByRole('textbox', { name: /이메일/ }),
  ).toHaveAccessibleDescription('이메일을 확인해 주세요.')

  await page.getByRole('button', { name: '문의 접수' }).click()
  const failure = page.getByRole('alert', { name: '문의 접수 오류' })
  await expect(failure).toBeFocused()
  await expect(failure).toContainText('요청 ID: e2e-storage')
  await expect(page.getByRole('textbox', { name: /제목/ })).toHaveValue(
    '결제 오류 문의',
  )
  await expect(page.getByRole('textbox', { name: /문의 내용/ })).toHaveValue(
    '결제 버튼을 누르면 오류가 납니다.',
  )
})

test('duplicate form submission retains the one-time result while navigation is blocked', async ({
  browserName,
  page,
}) => {
  let requestCount = 0
  let releaseResponse: (() => void) | undefined
  await page.route('**/api/v1/requests', async (route) => {
    requestCount += 1
    await new Promise<void>((resolve) => {
      releaseResponse = resolve
    })
    await route.fulfill({ status: 201, json: createdRequest })
  })
  await page.goto('/requests/new')
  await fillForm(page)

  await page.locator('form').evaluate((form: HTMLFormElement) => {
    form.requestSubmit()
    form.requestSubmit()
  })
  await expect(
    page.getByRole('button', { name: /안전하게 접수하는 중/ }),
  ).toBeDisabled()
  await expect(
    page.getByText('접수를 완료하는 동안 이 화면을 벗어날 수 없습니다.'),
  ).toBeVisible()
  await page.getByRole('link', { name: '문의 조회' }).click()
  await expect(page).toHaveURL(new RegExp('/requests/new$'))
  await expect(
    page.getByText('접수 결과를 안전하게 표시한 뒤 이동할 수 있습니다.'),
  ).toBeVisible()
  if (browserName === 'chromium') {
    const beforeUnload = page.waitForEvent('dialog')
    await page.evaluate(() => window.history.back())
    const unloadDialog = await beforeUnload
    expect(unloadDialog.type()).toBe('beforeunload')
    await unloadDialog.dismiss()
  } else {
    const unloadBoundary = await page.evaluate(() => {
      const event = new Event('beforeunload', { cancelable: true })
      return {
        dispatched: window.dispatchEvent(event),
        defaultPrevented: event.defaultPrevented,
      }
    })
    expect(unloadBoundary).toEqual({
      dispatched: false,
      defaultPrevented: true,
    })
  }
  await expect(page).toHaveURL(new RegExp('/requests/new$'))
  await expect(page.getByRole('textbox', { name: /제목/ })).toHaveValue(
    '결제 오류 문의',
  )
  await expect(
    page.getByRole('button', { name: /안전하게 접수하는 중/ }),
  ).toBeDisabled()

  releaseResponse?.()
  await expect(page.getByRole('heading', { name: '문의 #1042' })).toBeVisible()
  expect(requestCount).toBe(1)
})

test('a replacement key never reuses a previous public-request cache entry', async ({
  page,
}) => {
  let requestCount = 0
  await page.route('**/api/v1/requests/1042', (route) => {
    requestCount += 1
    if (route.request().headers()['x-request-access-token'] === ACCESS_TOKEN) {
      return route.fulfill({ status: 200, json: publicRequest })
    }
    return route.fulfill({
      status: 404,
      contentType: 'application/problem+json',
      json: {
        title: 'Request not found for ticket 1042',
        detail: 'Token hash mismatch',
        requestId: 'secret-request-id',
      },
    })
  })
  await page.goto('/requests/lookup')
  await page.getByRole('textbox', { name: /접수 번호/ }).fill('1042')
  await page.getByRole('textbox', { name: /조회 키/ }).fill(ACCESS_TOKEN)
  await page.getByRole('button', { name: '문의 보기' }).click()

  await expect(
    page.getByRole('heading', { name: '결제 오류 문의' }),
  ).toBeVisible()
  await page.getByRole('link', { name: '문의 조회' }).click()
  await page.getByRole('textbox', { name: /접수 번호/ }).fill('1042')
  await page
    .getByRole('textbox', { name: /조회 키/ })
    .fill(INVALID_ACCESS_TOKEN)
  await page.getByRole('button', { name: '문의 보기' }).click()

  const denied = page.getByRole('alert', {
    name: '문의 정보를 확인할 수 없습니다',
  })
  await expect(denied).toContainText('접수 번호와 조회 키를 확인해 주세요.')
  await expect(denied).not.toContainText('Token hash mismatch')
  await expect(denied).not.toContainText('Request not found')
  await expect(denied).not.toContainText('secret-request-id')
  await expect(
    page.getByRole('heading', { name: '결제 오류 문의' }),
  ).toHaveCount(0)
  await expect(page.getByText('결제 버튼을 누르면 오류가 납니다.')).toHaveCount(
    0,
  )
  expect(requestCount).toBe(2)
  expect(page.url()).not.toContain(ACCESS_TOKEN)
})

test('responsive public screens match reviewed snapshots and pass axe', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  await page.goto('/requests/new')
  await expectNoAxeViolations(page)
  await expect(page).toHaveScreenshot('request-form-1280.png', {
    fullPage: true,
  })

  await page.route('**/api/v1/requests', (route) =>
    route.fulfill({ status: 201, json: createdRequest }),
  )
  await page.setViewportSize({ width: 1440, height: 900 })
  await fillForm(page)
  await page.getByRole('button', { name: '문의 접수' }).click()
  await expect(page.getByRole('heading', { name: '문의 #1042' })).toBeVisible()
  await expectNoAxeViolations(page)
  await expect(page).toHaveScreenshot('request-success-1440.png', {
    fullPage: true,
  })

  await page.route('**/api/v1/requests/1042', (route) =>
    route.fulfill({ status: 200, json: publicRequest }),
  )
  await page.getByRole('link', { name: '문의 내용 보기' }).click()
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(
    page.getByRole('heading', { name: '결제 오류 문의' }),
  ).toBeVisible()
  await expectNoAxeViolations(page)
  await expect(page).toHaveScreenshot('request-detail-mobile-390.png', {
    fullPage: true,
  })
})
