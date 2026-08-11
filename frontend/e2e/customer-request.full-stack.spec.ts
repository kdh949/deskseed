import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { resolve } from 'node:path'
import { expect, test } from '@playwright/test'

const fullStackEnabled = process.env.E2E_FULL_STACK === '1'
const composeProject = process.env.DESKSEED_E2E_COMPOSE_PROJECT ?? ''
const composeFile = resolve(process.cwd(), '../compose.yaml')
const internalBody = 'E2E_INTERNAL_COMMENT_DO_NOT_EXPOSE'
const unrelatedInternalTicketSubject = 'E2E_INTERNAL_TICKET_DO_NOT_EXPOSE'
const adminEmail = process.env.DESKSEED_E2E_ADMIN_EMAIL ?? ''
const adminPassword = process.env.DESKSEED_E2E_ADMIN_PASSWORD ?? ''

function addPrivateFixtures(ticketNumber: number) {
  if (!composeProject || !Number.isSafeInteger(ticketNumber)) {
    throw new Error(
      'The isolated Compose project and ticket number are required',
    )
  }
  const sql = `
    with target as (
      select id, requester_id from tickets where ticket_number = ${ticketNumber}
    )
    insert into ticket_comments
      (id, ticket_id, author_type, author_id, visibility, body, created_at)
    select '${randomUUID()}', id, 'AGENT', null, 'INTERNAL', '${internalBody}', now()
    from target;

    with target as (
      select requester_id from tickets where ticket_number = ${ticketNumber}
    )
    insert into tickets
      (id, ticket_number, requester_id, kind, subject, status, priority,
       group_id, assignee_id, channel, version, created_at, updated_at, solved_at)
    select '${randomUUID()}', nextval('ticket_number_seq'), requester_id,
      'INTERNAL_CHILD', '${unrelatedInternalTicketSubject}', 'NEW', 'NORMAL', null, null,
      'AGENT', 0, now(), now(), null
    from target;
  `
  execFileSync(
    'docker',
    [
      'compose',
      '--project-name',
      composeProject,
      '--file',
      composeFile,
      'exec',
      '-T',
      'db',
      'psql',
      '-U',
      'deskseed',
      '-d',
      'deskseed',
      '-v',
      'ON_ERROR_STOP=1',
    ],
    { input: sql, stdio: ['pipe', 'pipe', 'pipe'] },
  )
}

function assignToBootstrapAdmin(ticketNumber: number) {
  if (!composeProject || !Number.isSafeInteger(ticketNumber) || !adminEmail) {
    throw new Error(
      'The isolated stack, ticket, and bootstrap admin are required',
    )
  }
  const sql = `
    with admin as (
      select id from staff_accounts where email_normalized = '${adminEmail}'
    ), created_group as (
      insert into support_groups
        (id, name, status, created_at, updated_at, version)
      values
        (gen_random_uuid(), 'E2E Composer ${ticketNumber}', 'ACTIVE', now(), now(), 0)
      returning id
    ), created_membership as (
      insert into group_memberships
        (id, group_id, staff_id, status, created_at, updated_at, version)
      select gen_random_uuid(), created_group.id, admin.id, 'ACTIVE', now(), now(), 0
      from created_group cross join admin
    )
    update tickets
    set group_id = (select id from created_group),
        assignee_id = (select id from admin)
    where ticket_number = ${ticketNumber};
  `
  execFileSync(
    'docker',
    [
      'compose',
      '--project-name',
      composeProject,
      '--file',
      composeFile,
      'exec',
      '-T',
      'db',
      'psql',
      '-U',
      'deskseed',
      '-d',
      'deskseed',
      '-v',
      'ON_ERROR_STOP=1',
    ],
    { input: sql, stdio: ['pipe', 'pipe', 'pipe'] },
  )
}

test.skip(!fullStackEnabled, 'Runs only against the isolated Compose stack')

// M1 has no parent-child relation column yet. This fixture proves that another internal ticket
// cannot leak into the public projection; a true child-relation fixture belongs with that schema.
test('real create to detail flow excludes internal comments and other tickets from API and DOM', async ({
  page,
}) => {
  await page.goto('/requests/new')
  await page.getByRole('textbox', { name: /이름/ }).fill('브라우저 고객')
  await page
    .getByRole('textbox', { name: /이메일/ })
    .fill(`browser-${Date.now()}@example.com`)
  await page.getByRole('textbox', { name: /제목/ }).fill('실제 스택 공개 문의')
  await page
    .getByRole('textbox', { name: /문의 내용/ })
    .fill('실제 backend에 저장되는 첫 PUBLIC Comment입니다.')
  await page.getByRole('button', { name: '문의 접수' }).click()

  const heading = page.getByRole('heading', { name: /문의 #\d+/ })
  await expect(heading).toBeVisible()
  const headingText = await heading.textContent()
  const ticketNumber = Number(headingText?.match(/\d+/)?.[0])
  expect(Number.isSafeInteger(ticketNumber)).toBe(true)
  const token = await page.getByLabel('문의 조회 키').textContent()
  if (!token)
    throw new Error('The creation response did not render an access token')
  expect(page.url()).not.toContain(token)

  addPrivateFixtures(ticketNumber)

  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      response.url().endsWith(`/api/v1/requests/${ticketNumber}`),
  )
  await page.getByRole('link', { name: '문의 내용 보기' }).click()
  const response = await responsePromise
  expect(response.status()).toBe(200)
  expect(response.headers()['cache-control']).toContain('no-store')
  const payload = (await response.json()) as Record<string, unknown>
  expect(Object.keys(payload).sort()).toEqual([
    'comments',
    'createdAt',
    'status',
    'subject',
    'ticketNumber',
    'updatedAt',
  ])
  const comments = payload.comments as Array<Record<string, unknown>>
  expect(comments).toHaveLength(1)
  expect(Object.keys(comments[0]).sort()).toEqual([
    'authorDisplayName',
    'body',
    'createdAt',
    'id',
  ])
  const serializedPayload = JSON.stringify(payload)
  expect(serializedPayload).not.toContain(internalBody)
  expect(serializedPayload).not.toContain(unrelatedInternalTicketSubject)

  await expect(
    page.getByRole('heading', { name: '실제 스택 공개 문의' }),
  ).toBeVisible()
  await expect(
    page.getByText('실제 backend에 저장되는 첫 PUBLIC Comment입니다.'),
  ).toBeVisible()
  await expect(page.getByText(internalBody)).toHaveCount(0)
  await expect(page.getByText(unrelatedInternalTicketSubject)).toHaveCount(0)
  expect(page.url()).not.toContain(token)
  const storage = await page.evaluate(() => ({
    local: Object.keys(localStorage),
    session: Object.keys(sessionStorage),
  }))
  expect(storage).toEqual({ local: [], session: [] })
})

test('real staff composer exposes PUBLIC reply but keeps INTERNAL note out of customer projection', async ({
  browser,
  baseURL,
  page: customerPage,
}) => {
  if (!adminEmail || !adminPassword || !baseURL) {
    throw new Error('Bootstrap admin credentials are required')
  }
  const unique = Date.now()
  const publicReply = `E2E 공개 답변 ${unique}`
  const internalNote = `E2E 내부 메모 ${unique}`

  await customerPage.goto('/requests/new')
  await customerPage
    .getByRole('textbox', { name: /이름/ })
    .fill('Composer 고객')
  await customerPage
    .getByRole('textbox', { name: /이메일/ })
    .fill(`composer-${unique}@example.com`)
  await customerPage
    .getByRole('textbox', { name: /제목/ })
    .fill('Composer 공개 경계 검증')
  await customerPage
    .getByRole('textbox', { name: /문의 내용/ })
    .fill('고객이 작성한 최초 공개 문의')
  await customerPage.getByRole('button', { name: '문의 접수' }).click()

  const heading = customerPage.getByRole('heading', { name: /문의 #\d+/ })
  await expect(heading).toBeVisible()
  const ticketNumber = Number((await heading.textContent())?.match(/\d+/)?.[0])
  expect(Number.isSafeInteger(ticketNumber)).toBe(true)
  assignToBootstrapAdmin(ticketNumber)

  const staffContext = await browser.newContext()
  const staffPage = await staffContext.newPage()
  try {
    await staffPage.goto(`${baseURL}/agent/login`)
    await staffPage.getByLabel('이메일').fill(adminEmail)
    await staffPage.getByLabel('비밀번호').fill(adminPassword)
    await staffPage.getByRole('button', { name: '로그인' }).click()
    await expect(staffPage).toHaveURL(/\/admin\/staff$/)
    await staffPage.goto(`${baseURL}/agent/tickets/${ticketNumber}`)
    await expect(
      staffPage.getByRole('heading', { name: 'Composer 공개 경계 검증' }),
    ).toBeVisible()

    await staffPage
      .getByRole('textbox', { name: '공개 답변' })
      .fill(publicReply)
    await staffPage.getByRole('button', { name: '변경사항 저장' }).click()
    await expect(staffPage.getByText(/공개 답변과 변경사항/)).toBeVisible()

    await staffPage.getByRole('tab', { name: '내부 메모' }).click()
    await staffPage
      .getByRole('textbox', { name: '내부 메모' })
      .fill(internalNote)
    await staffPage.getByRole('button', { name: '변경사항 저장' }).click()
    await expect(staffPage.getByText(/내부 메모와 변경사항/)).toBeVisible()
    await expect(staffPage.getByText(publicReply)).toBeVisible()
    await expect(staffPage.getByText(internalNote)).toBeVisible()

    const responsePromise = customerPage.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        response.url().endsWith(`/api/v1/requests/${ticketNumber}`),
    )
    await customerPage.getByRole('link', { name: '문의 내용 보기' }).click()
    const response = await responsePromise
    const payload = JSON.stringify(await response.json())
    expect(payload).toContain(publicReply)
    expect(payload).not.toContain(internalNote)
    await expect(customerPage.getByText(publicReply)).toBeVisible()
    await expect(customerPage.getByText(internalNote)).toHaveCount(0)
  } finally {
    await staffContext.close()
  }
})
