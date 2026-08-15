import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

const requestAccessToken = 'a'.repeat(43)
const magicLinkToken = 'opaque-magic-link-token'
const customerCsrfToken = 'c'.repeat(32)

type PublicComment = {
  authorDisplayName: string
  body: string
  createdAt: string
  id: string
}

function initialDetail(): {
  comments: PublicComment[]
  createdAt: string
  status: 'OPEN'
  subject: string
  ticketNumber: number
  updatedAt: string
} {
  return {
    ticketNumber: 1042,
    subject: '결제 확인 요청',
    status: 'OPEN',
    createdAt: '2026-08-15T00:00:00Z',
    updatedAt: '2026-08-15T01:00:00Z',
    comments: [
      {
        id: 'comment-public-1',
        authorDisplayName: '김민아',
        body: '결제 승인 내역을 확인해 주세요.',
        createdAt: '2026-08-15T00:00:00Z',
      },
    ],
  }
}

async function expectNoAxeViolations(page: Page) {
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
}

test('anonymous submit → fragment detail → PUBLIC follow-up uses the production customer API boundaries', async ({
  page,
}) => {
  const detail = initialDetail()
  const observedRequestHeaders: Array<Record<string, string>> = []
  const commandIds: string[] = []
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/v1/customer/me') {
      return route.fulfill({ status: 401, json: { status: 401 } })
    }
    if (url.pathname === '/api/v1/customer/access-mode') {
      return route.fulfill({ status: 200, json: { mode: 'ANONYMOUS_ALLOWED' } })
    }
    if (url.pathname === '/api/v1/requests' && request.method() === 'POST') {
      expect(request.postDataJSON()).toEqual({
        name: '김민아',
        email: 'mina@example.test',
        subject: '결제 확인 요청',
        message: '결제 승인 내역을 확인해 주세요.',
      })
      return route.fulfill({
        status: 201,
        json: {
          ticketNumber: 1042,
          status: 'NEW',
          accessToken: requestAccessToken,
          createdAt: '2026-08-15T00:00:00Z',
        },
      })
    }
    if (
      url.pathname === '/api/v1/requests/1042' &&
      request.method() === 'GET'
    ) {
      observedRequestHeaders.push(request.headers())
      return route.fulfill({
        status: 200,
        json: {
          ...detail,
          internalComment: 'must-not-render',
          children: [{ ticketNumber: 1043 }],
          auditMetadata: { actor: 'staff-1' },
        },
      })
    }
    if (
      url.pathname === '/api/v1/requests/1042/comments' &&
      request.method() === 'POST'
    ) {
      const command = request.postDataJSON() as {
        body: string
        clientCommandId: string
      }
      commandIds.push(command.clientCommandId)
      expect(command.body).toBe('추가 정보입니다.')
      expect(request.headers()['x-request-access-token']).toBe(
        requestAccessToken,
      )
      const comment = {
        id: 'comment-public-2',
        authorDisplayName: '김민아',
        body: command.body,
        createdAt: '2026-08-15T02:00:00Z',
      }
      detail.comments.push(comment)
      detail.updatedAt = comment.createdAt
      return route.fulfill({ status: 201, json: comment })
    }
    return route.abort()
  })

  await page.goto('/')
  await page.getByRole('link', { name: /새 문의 접수/ }).click()
  await expect(page).toHaveURL(/\/requests\/new$/)
  await page.getByLabel('이름').fill('김민아')
  await page.getByLabel('이메일').fill('mina@example.test')
  await page.getByLabel('제목').fill('결제 확인 요청')
  await page.getByLabel('문의 내용').fill('결제 승인 내역을 확인해 주세요.')
  await page.getByRole('button', { name: '문의 접수' }).click()

  await expect(page).toHaveURL(/\/requests\/1042$/)
  await expect(
    page.getByRole('heading', { name: '#1042 결제 확인 요청' }),
  ).toBeVisible()
  await expect(page.getByText('결제 승인 내역을 확인해 주세요.')).toBeVisible()
  await expect(page.getByText('must-not-render')).toHaveCount(0)
  await expect(page.getByText('1043', { exact: true })).toHaveCount(0)
  expect(observedRequestHeaders).toHaveLength(1)
  expect(observedRequestHeaders[0]?.['x-request-access-token']).toBe(
    requestAccessToken,
  )
  expect(observedRequestHeaders[0]?.referer).toBeUndefined()

  await page.getByLabel('추가 답변').fill('추가 정보입니다.')
  await page.getByRole('button', { name: '답변 보내기' }).click()
  await expect(page.getByText('답변이 저장되었습니다.')).toBeVisible()
  await expect(page.getByText('추가 정보입니다.')).toBeVisible()
  expect(commandIds).toHaveLength(1)
  expect(commandIds[0]).toMatch(
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
  )
  await expectNoAxeViolations(page)
})

test('magic link → My Requests → authenticated PUBLIC follow-up → logout uses a server customer session', async ({
  page,
}) => {
  const detail = initialDetail()
  let customerSessionEstablished = false
  let sessionDeleted = false
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/v1/customer/me') {
      return route.fulfill(
        customerSessionEstablished && !sessionDeleted
          ? {
              status: 200,
              json: {
                id: 'customer-e2e',
                email: 'mina@example.test',
                displayName: '김민아',
                verifiedAt: '2026-08-15T00:00:00Z',
              },
            }
          : { status: 401, json: { status: 401 } },
      )
    }
    if (url.pathname === '/api/v1/customer/auth/magic-link-requests') {
      expect(request.method()).toBe('POST')
      return route.fulfill({ status: 202, json: { accepted: true } })
    }
    if (url.pathname === '/api/v1/customer/auth/magic-link-sessions') {
      expect(request.method()).toBe('POST')
      expect(request.postDataJSON()).toEqual({ token: magicLinkToken })
      customerSessionEstablished = true
      return route.fulfill({
        status: 200,
        json: {
          id: 'customer-e2e',
          email: 'mina@example.test',
          displayName: '김민아',
          verifiedAt: '2026-08-15T00:00:00Z',
        },
      })
    }
    if (
      url.pathname === '/api/v1/customer/requests' &&
      request.method() === 'GET'
    ) {
      return route.fulfill({
        status: 200,
        json: {
          items: [
            {
              ticketNumber: 1042,
              subject: detail.subject,
              status: detail.status,
              createdAt: detail.createdAt,
              updatedAt: detail.updatedAt,
              internalComment: 'must-not-render',
            },
          ],
          nextCursor: null,
        },
      })
    }
    if (
      url.pathname === '/api/v1/customer/requests/1042' &&
      request.method() === 'GET'
    ) {
      return route.fulfill({
        status: 200,
        json: { ...detail, auditMetadata: { actor: 'staff-1' } },
      })
    }
    if (url.pathname === '/api/v1/customer/csrf') {
      return route.fulfill({
        status: 200,
        json: { token: customerCsrfToken, headerName: 'X-CSRF-TOKEN' },
      })
    }
    if (
      url.pathname === '/api/v1/customer/requests/1042/comments' &&
      request.method() === 'POST'
    ) {
      const command = request.postDataJSON() as {
        body: string
        clientCommandId: string
      }
      expect(request.headers()['x-csrf-token']).toBe(customerCsrfToken)
      expect(command.clientCommandId).toMatch(/^[0-9a-f-]{36}$/i)
      const comment = {
        id: 'comment-public-authenticated',
        authorDisplayName: '김민아',
        body: command.body,
        createdAt: '2026-08-15T03:00:00Z',
      }
      detail.comments.push(comment)
      detail.updatedAt = comment.createdAt
      return route.fulfill({ status: 201, json: comment })
    }
    if (
      url.pathname === '/api/v1/customer/session' &&
      request.method() === 'DELETE'
    ) {
      expect(request.headers()['x-csrf-token']).toBe(customerCsrfToken)
      sessionDeleted = true
      return route.fulfill({ status: 204 })
    }
    return route.abort()
  })

  await page.goto('/customer/sign-in')
  await page
    .getByRole('textbox', { name: '이메일', exact: true })
    .fill('mina@example.test')
  await page.getByRole('button', { name: '로그인 링크 보내기' }).click()
  await expect(
    page.getByText('입력한 이메일 주소가 유효하면 로그인 링크를 보냈습니다.'),
  ).toBeVisible()

  await page.goto(`/customer/sign-in/consume#token=${magicLinkToken}`)
  await expect(page).toHaveURL(/\/account\/requests$/)
  await expect(page.getByText('김민아')).toBeVisible()
  await expect(
    page.getByRole('link', { name: /#1042 결제 확인 요청/ }),
  ).toBeVisible()
  await expect(page.getByText('must-not-render')).toHaveCount(0)

  await page.getByRole('link', { name: /#1042 결제 확인 요청/ }).click()
  await expect(page).toHaveURL(/\/account\/requests\/1042$/)
  await page.getByLabel('추가 답변').fill('인증 고객의 추가 정보입니다.')
  await page.getByRole('button', { name: '답변 보내기' }).click()
  await expect(page.getByText('답변이 저장되었습니다.')).toBeVisible()
  await expect(page.getByText('인증 고객의 추가 정보입니다.')).toBeVisible()

  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page).toHaveURL(/\/$/)
  await expect(
    page.getByRole('heading', { name: '문의부터 답변 확인까지 한곳에서' }),
  ).toBeVisible()
  await expectNoAxeViolations(page)
})
