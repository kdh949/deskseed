import { readFileSync } from 'node:fs'
import AxeBuilder from '@axe-core/playwright'
import {
  expect,
  test,
  type APIRequestContext,
  type BrowserContext,
  type Download,
  type Page,
} from '@playwright/test'

const requestAccessToken = 'a'.repeat(43)
const magicLinkToken = 'opaque-magic-link-token'
const customerCsrfToken = 'c'.repeat(32)
const authenticatedAttachmentId = '55555555-5555-4555-8555-555555555555'

type PublicAttachment = {
  contentType: string
  fileName: string
  id: string
  sizeBytes: number
}

type PublicComment = {
  attachments: PublicAttachment[]
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
        attachments: [],
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
        attachments: [],
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

test('magic link → My Requests → authenticated PUBLIC attachment follow-up → reload/download → logout', async ({
  page,
}) => {
  const detail = initialDetail()
  let customerSessionEstablished = false
  let sessionDeleted = false
  let uploadCount = 0
  let downloadCount = 0
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
      url.pathname === '/api/v1/customer/requests/1042/attachments/uploads' &&
      request.method() === 'POST'
    ) {
      expect(request.headers()['x-csrf-token']).toBe(customerCsrfToken)
      expect(request.headers()['content-type']).toMatch(
        /^multipart\/form-data; boundary=/,
      )
      uploadCount += 1
      return route.fulfill({
        status: 201,
        json: {
          id: authenticatedAttachmentId,
          fileName: 'approval.pdf',
          sizeBytes: 4,
          contentType: 'application/pdf',
          scanStatus: 'CLEAN',
          expiresAt: '2099-08-17T05:00:00Z',
        },
      })
    }
    if (
      url.pathname === '/api/v1/customer/requests/1042/comments' &&
      request.method() === 'POST'
    ) {
      const command = request.postDataJSON() as {
        attachmentIds: string[]
        body: string
        clientCommandId: string
      }
      expect(request.headers()['x-csrf-token']).toBe(customerCsrfToken)
      expect(command.clientCommandId).toMatch(/^[0-9a-f-]{36}$/i)
      expect(command.attachmentIds).toEqual([authenticatedAttachmentId])
      const comment = {
        attachments: [
          {
            id: authenticatedAttachmentId,
            fileName: 'approval.pdf',
            sizeBytes: 4,
            contentType: 'application/pdf',
          },
        ],
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
      url.pathname ===
        `/api/v1/customer/requests/1042/attachments/${authenticatedAttachmentId}/download` &&
      request.method() === 'GET'
    ) {
      downloadCount += 1
      return route.fulfill({
        status: 200,
        headers: {
          'Cache-Control': 'no-store',
          'Content-Disposition': 'attachment; filename="approval.pdf"',
          'Content-Type': 'application/octet-stream',
        },
        body: Buffer.from('safe'),
      })
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
  await page.getByLabel('PUBLIC 첨부 파일').setInputFiles({
    name: 'approval.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('safe'),
  })
  await expect(page.getByText(/CLEAN/)).toBeVisible()
  await page.getByLabel('추가 답변').fill('인증 고객의 첨부 정보입니다.')
  await page.getByRole('button', { name: '답변 보내기' }).click()
  await expect(page.getByText('답변이 저장되었습니다.')).toBeVisible()
  await expect(page.getByText('인증 고객의 첨부 정보입니다.')).toBeVisible()
  expect(uploadCount).toBe(1)

  await page.reload()
  await expect(page.getByText('approval.pdf')).toBeVisible()
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '다운로드' }).click()
  expect((await download).suggestedFilename()).toBe('approval.pdf')
  expect(downloadCount).toBe(1)

  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page).toHaveURL(/\/$/)
  await expect(
    page.getByRole('heading', { name: '문의부터 답변 확인까지 한곳에서' }),
  ).toBeVisible()
  await expectNoAxeViolations(page)
})

test('authenticated customer attachment real stack preserves ownership and PUBLIC visibility', async ({
  browser,
  page,
  playwright,
  request: staffRequest,
}) => {
  test.setTimeout(120_000)
  const mailpitBaseUrl = process.env.DESKSEED_REAL_STACK_MAILPIT_URL
  const adminPasswordFile = process.env.DESKSEED_REAL_STACK_ADMIN_PASSWORD_FILE
  if (!mailpitBaseUrl || !adminPasswordFile) {
    test.skip(
      true,
      'requires the ownership-isolated authenticated-customer Compose runner',
    )
    return
  }

  const mailpit = await playwright.request.newContext({
    baseURL: mailpitBaseUrl,
  })
  const customerBContext = await browser.newContext()
  try {
    expect((await mailpit.delete('/api/v1/messages')).ok()).toBe(true)

    const unique = `${Date.now()}-${process.pid}`
    const customerAEmail = `attachment-a-${unique}@example.test`
    const customerBEmail = `attachment-b-${unique}@example.test`
    const createdRequest = await createRealCustomerRequest(
      page.context(),
      customerAEmail,
      'Real-stack attachment request',
    )
    const ticketNumber = Number(createdRequest.ticketNumber)
    expect(ticketNumber).toBeGreaterThan(0)

    await signInRealCustomer(page.context(), mailpit, customerAEmail)
    await claimRealCustomerRequest(
      page.context(),
      ticketNumber,
      createdRequest.accessToken,
    )
    await page.goto(`/account/requests/${ticketNumber}`)
    await expect(
      page.getByRole('heading', {
        name: `#${ticketNumber} Real-stack attachment request`,
      }),
    ).toBeVisible()

    const publicBytes = Buffer.from(
      '%PDF-1.4\nAuthenticated customer public attachment\n%%EOF\n',
    )
    const uploadResponsePromise = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname ===
          `/api/v1/customer/requests/${ticketNumber}/attachments/uploads` &&
        response.request().method() === 'POST',
    )
    await page.getByLabel('PUBLIC 첨부 파일').setInputFiles({
      name: 'customer-public.pdf',
      mimeType: 'application/pdf',
      buffer: publicBytes,
    })
    const uploadResponse = await uploadResponsePromise
    expect(uploadResponse.status()).toBe(201)
    const uploaded = (await uploadResponse.json()) as { id: string }
    expect(uploaded.id).toMatch(/^[0-9a-f-]{36}$/i)
    await expect(page.getByText(/CLEAN/)).toBeVisible()

    await page
      .getByLabel('추가 답변')
      .fill('Real-stack PUBLIC attachment follow-up')
    await page.getByRole('button', { name: '답변 보내기' }).click()
    await expect(page.getByText('답변이 저장되었습니다.')).toBeVisible()

    await page.reload()
    await expect(page.getByText('customer-public.pdf')).toBeVisible()
    const downloadResponsePromise = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname ===
        `/api/v1/customer/requests/${ticketNumber}/attachments/${uploaded.id}/download`,
    )
    const downloadPromise = page.waitForEvent('download')
    await page.getByRole('button', { name: '다운로드' }).click()
    const [downloadResponse, download] = await Promise.all([
      downloadResponsePromise,
      downloadPromise,
    ])
    expect(downloadResponse.status()).toBe(200)
    expect(downloadResponse.headers()['cache-control']).toContain('no-store')
    expect(downloadResponse.headers()['content-type']).toBe(
      'application/octet-stream',
    )
    expect(download.suggestedFilename()).toBe('customer-public.pdf')
    expect(await readDownload(download.createReadStream())).toEqual(publicBytes)

    const internalAttachmentId = await addInternalRealStackAttachment(
      staffRequest,
      adminPasswordFile,
      ticketNumber,
    )
    const internalDownload = await page
      .context()
      .request.get(
        `/api/v1/customer/requests/${ticketNumber}/attachments/${internalAttachmentId}/download`,
      )
    expect(internalDownload.status()).toBe(404)
    await page.reload()
    await expect(page.getByText('staff-internal.pdf')).toHaveCount(0)
    await expect(page.getByText('Real-stack INTERNAL attachment')).toHaveCount(
      0,
    )

    await createRealCustomerRequest(
      customerBContext,
      customerBEmail,
      'Customer B identity fixture',
    )
    await signInRealCustomer(customerBContext, mailpit, customerBEmail)
    const customerBDownload = await customerBContext.request.get(
      `/api/v1/customer/requests/${ticketNumber}/attachments/${uploaded.id}/download`,
    )
    expect(customerBDownload.status()).toBe(404)
    const customerBCsrf = await customerBContext.request.get(
      '/api/v1/customer/csrf',
    )
    expect(customerBCsrf.status()).toBe(200)
    const customerBUpload = await customerBContext.request.post(
      `/api/v1/customer/requests/${ticketNumber}/attachments/uploads`,
      {
        headers: {
          'X-CSRF-TOKEN': String((await customerBCsrf.json()).token),
        },
        multipart: {
          file: {
            name: 'customer-b.pdf',
            mimeType: 'application/pdf',
            buffer: publicBytes,
          },
        },
      },
    )
    expect(customerBUpload.status()).toBe(404)
    await expectNoAxeViolations(page)
  } finally {
    await customerBContext.close()
    await mailpit.dispose()
  }
})

async function createRealCustomerRequest(
  context: BrowserContext,
  email: string,
  subject: string,
) {
  const response = await context.request.post('/api/v1/requests', {
    data: {
      email,
      message: 'Real-stack authenticated attachment fixture',
      name: 'Attachment customer',
      subject,
    },
  })
  expect(response.status()).toBe(201)
  return (await response.json()) as {
    accessToken: string
    ticketNumber: number
  }
}

async function claimRealCustomerRequest(
  context: BrowserContext,
  ticketNumber: number,
  requestAccessToken: string,
) {
  const csrf = await context.request.get('/api/v1/customer/csrf')
  expect(csrf.status()).toBe(200)
  const response = await context.request.post(
    `/api/v1/customer/requests/${ticketNumber}/claim`,
    {
      data: { requestAccessToken },
      headers: {
        'X-CSRF-TOKEN': String((await csrf.json()).token),
      },
    },
  )
  expect(response.status()).toBe(204)
}

async function signInRealCustomer(
  context: BrowserContext,
  mailpit: APIRequestContext,
  email: string,
) {
  const requested = await context.request.post(
    '/api/v1/customer/auth/magic-link-requests',
    { data: { email } },
  )
  expect(requested.status()).toBe(202)
  const token = await waitForMagicLinkToken(mailpit, email)
  const session = await context.request.post(
    '/api/v1/customer/auth/magic-link-sessions',
    { data: { token } },
  )
  expect(session.status()).toBe(200)
}

async function waitForMagicLinkToken(
  mailpit: APIRequestContext,
  email: string,
) {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const response = await mailpit.get('/api/v1/messages?limit=50')
    expect(response.ok()).toBe(true)
    const root = (await response.json()) as Record<string, unknown>
    const messages = messageSummaries(root)
    for (const summary of messages) {
      if (!messageRecipients(summary).includes(email)) continue
      const id = stringField(summary, 'ID', 'id')
      if (!id) continue
      const detailResponse = await mailpit.get(
        `/api/v1/message/${encodeURIComponent(id)}`,
      )
      if (!detailResponse.ok()) continue
      const detail = (await detailResponse.json()) as Record<string, unknown>
      const text = stringField(detail, 'Text', 'text') ?? ''
      const match = text.match(
        /\/customer\/sign-in\/consume#token=([A-Za-z0-9_-]{43})/,
      )
      if (match?.[1]) return match[1]
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error('Magic-link delivery was not observed before the deadline')
}

function messageSummaries(root: Record<string, unknown>) {
  const value = root.messages ?? root.Messages
  return Array.isArray(value)
    ? value.filter(
        (entry): entry is Record<string, unknown> =>
          typeof entry === 'object' && entry !== null,
      )
    : []
}

function messageRecipients(summary: Record<string, unknown>) {
  const value = summary.To ?? summary.to
  if (!Array.isArray(value)) return []
  return value.flatMap((recipient) => {
    if (typeof recipient !== 'object' || recipient === null) return []
    const address = stringField(
      recipient as Record<string, unknown>,
      'Address',
      'address',
    )
    return address ? [address] : []
  })
}

function stringField(value: Record<string, unknown>, ...names: string[]) {
  for (const name of names) {
    if (typeof value[name] === 'string') return value[name]
  }
  return undefined
}

async function addInternalRealStackAttachment(
  staffRequest: APIRequestContext,
  adminPasswordFile: string,
  ticketNumber: number,
) {
  const password = readFileSync(adminPasswordFile, 'utf8').trim()
  const csrfBeforeLogin = await staffRequest.get('/api/v1/agent/csrf')
  expect(csrfBeforeLogin.status()).toBe(200)
  const login = await staffRequest.post('/api/v1/agent/session', {
    data: {
      email: 'p1-customer-attachment-admin@example.test',
      password,
    },
    headers: {
      'X-CSRF-TOKEN': String((await csrfBeforeLogin.json()).token),
    },
  })
  expect(login.status()).toBe(204)
  const currentStaff = await staffRequest.get('/api/v1/agent/me')
  expect(currentStaff.status()).toBe(200)
  const staffId = String((await currentStaff.json()).id)
  const actorHeaders = { 'X-Deskseed-Expected-Staff-Id': staffId }
  const csrf = await staffRequest.get('/api/v1/agent/csrf', {
    headers: actorHeaders,
  })
  expect(csrf.status()).toBe(200)
  const mutationHeaders = {
    ...actorHeaders,
    'X-CSRF-TOKEN': String((await csrf.json()).token),
  }
  const uploaded = await staffRequest.post(
    '/api/v1/agent/attachments/uploads',
    {
      headers: mutationHeaders,
      multipart: {
        file: {
          name: 'staff-internal.pdf',
          mimeType: 'application/pdf',
          buffer: Buffer.from('%PDF-1.4\nStaff internal attachment\n%%EOF\n'),
        },
      },
    },
  )
  expect(uploaded.status()).toBe(201)
  const attachmentId = String((await uploaded.json()).id)
  const detail = await staffRequest.get(
    `/api/v1/agent/tickets/${ticketNumber}`,
    {
      headers: {
        ...actorHeaders,
        'X-Deskseed-Read-Intent': 'NAVIGATION',
        'X-Interaction-Id': '88888888-8888-4888-8888-888888888888',
      },
    },
  )
  expect(detail.status()).toBe(200)
  const ticket = (await detail.json()) as { ticket: { version: number } }
  const command = await staffRequest.post(
    `/api/v1/agent/tickets/${ticketNumber}/commands`,
    {
      data: {
        changedFields: [],
        clientCommandId: '99999999-9999-4999-8999-999999999999',
        comment: {
          attachmentIds: [attachmentId],
          body: 'Real-stack INTERNAL attachment',
          visibility: 'INTERNAL',
        },
        expectedVersion: ticket.ticket.version,
      },
      headers: mutationHeaders,
    },
  )
  expect(command.status()).toBe(200)
  return attachmentId
}

async function readDownload(
  streamPromise: ReturnType<Download['createReadStream']>,
) {
  const stream = await streamPromise
  if (!stream) throw new Error('Browser download stream is unavailable')
  const chunks: Buffer[] = []
  for await (const chunk of stream) chunks.push(Buffer.from(chunk))
  return Buffer.concat(chunks)
}
