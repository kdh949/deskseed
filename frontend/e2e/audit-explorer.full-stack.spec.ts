import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'
import { expect, test } from '@playwright/test'

const fullStackEnabled = process.env.E2E_FULL_STACK === '1'
const composeProject = process.env.DESKSEED_E2E_COMPOSE_PROJECT ?? ''
const composeFile = resolve(process.cwd(), '../compose.yaml')
const adminEmail = process.env.DESKSEED_E2E_ADMIN_EMAIL ?? ''
const adminPassword = process.env.DESKSEED_E2E_ADMIN_PASSWORD ?? ''
const auditorEmail = 'e2e-security-auditor@deskseed.test'
const auditorPassword = 'Deskseed E2E auditor 42!'

function sqlLiteral(value: string) {
  return `'${value.replaceAll("'", "''")}'`
}

function databaseJson(sql: string): Record<string, unknown> {
  if (!composeProject)
    throw new Error('The isolated Compose project is required')
  const output = execFileSync(
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
      '-tA',
      '-v',
      'ON_ERROR_STOP=1',
      '-c',
      sql,
    ],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
  ).trim()
  return JSON.parse(output) as Record<string, unknown>
}

function assignTicketToAdmin(ticketNumber: number) {
  const sql = `
    with admin as (
      select id from staff_accounts where email_normalized = ${sqlLiteral(adminEmail)}
    ), created_group as (
      insert into support_groups
        (id, name, status, created_at, updated_at, version)
      values
        (gen_random_uuid(), ${sqlLiteral(`Audit E2E ${ticketNumber}`)}, 'ACTIVE', now(), now(), 0)
      returning id
    ), membership as (
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

function backendLogs(): string {
  return execFileSync(
    'docker',
    [
      'compose',
      '--project-name',
      composeProject,
      '--file',
      composeFile,
      'logs',
      '--no-color',
      'backend',
    ],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
  )
}

test.skip(!fullStackEnabled, 'Runs only against the isolated Compose stack')
test.setTimeout(90_000)

test('real ledger, session, API, projection, reveal, and mutation boundaries work together', async ({
  browser,
  baseURL,
  page: customerPage,
}) => {
  if (!adminEmail || !adminPassword || !baseURL) {
    throw new Error('Bootstrap admin credentials are required')
  }
  const unique = Date.now()
  const subject = `통합 감사 조사 ${unique}`
  let searchEventId: string

  await customerPage.goto('/requests/new')
  await customerPage
    .getByRole('textbox', { name: /이름/ })
    .fill('감사 E2E 고객')
  await customerPage
    .getByRole('textbox', { name: /이메일/ })
    .fill(`audit-e2e-${unique}@example.com`)
  await customerPage.getByRole('textbox', { name: /제목/ }).fill(subject)
  await customerPage
    .getByRole('textbox', { name: /문의 내용/ })
    .fill('통합 감사 E2E 공개 문의 본문')
  await customerPage.getByRole('button', { name: '문의 접수' }).click()
  const customerHeading = customerPage.getByRole('heading', {
    name: /문의 #\d+/,
  })
  await expect(customerHeading).toBeVisible()
  const ticketNumber = Number(
    (await customerHeading.textContent())?.match(/\d+/)?.[0],
  )
  expect(Number.isSafeInteger(ticketNumber)).toBe(true)
  assignTicketToAdmin(ticketNumber)

  const adminContext = await browser.newContext()
  const adminPage = await adminContext.newPage()
  try {
    await adminPage.goto(`${baseURL}/agent/login`)
    await adminPage.getByLabel('이메일').fill(adminEmail)
    await adminPage.getByLabel('비밀번호').fill(adminPassword)
    await adminPage.getByRole('button', { name: '로그인' }).click()
    await expect(adminPage).toHaveURL(/\/admin\/staff$/)

    await adminPage.getByLabel('이름').fill('E2E 보안 감사자')
    await adminPage.getByLabel('이메일').fill(auditorEmail)
    await adminPage.getByLabel('역할').selectOption('SECURITY_AUDITOR')
    await adminPage.getByLabel('초기 비밀번호').fill(auditorPassword)
    await adminPage.getByRole('button', { name: '직원 추가' }).click()
    await expect(adminPage.getByText(auditorEmail)).toBeVisible()
    await expect(
      adminPage.getByRole('cell', { name: 'SECURITY_AUDITOR' }),
    ).toBeVisible()
    const auditorRow = adminPage
      .getByRole('row')
      .filter({ hasText: auditorEmail })
    await auditorRow
      .getByRole('button', { name: '검색어 원문 공개 권한 부여' })
      .click()
    await expect(
      auditorRow.getByRole('button', {
        name: '검색어 원문 공개 권한 회수',
      }),
    ).toBeVisible()

    await adminPage.goto(`${baseURL}/agent/search`)
    const searchResponsePromise = adminPage.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().endsWith('/api/v1/agent/search'),
    )
    await adminPage
      .getByRole('searchbox', { name: '티켓 검색어' })
      .fill(subject)
    await adminPage.getByRole('button', { name: '티켓 검색' }).click()
    const searchResponse = await searchResponsePromise
    expect(searchResponse.status()).toBe(200)
    const searchPayload = (await searchResponse.json()) as {
      searchEventId: string
      resultCount: number
    }
    expect(searchPayload.resultCount).toBe(1)
    searchEventId = searchPayload.searchEventId

    const resultLink = adminPage.getByRole('link', {
      name: `#${ticketNumber} ${subject} 열기`,
    })
    await expect(resultLink).toBeVisible()
    await resultLink.click()
    await expect(
      adminPage.getByRole('heading', { name: subject }),
    ).toBeVisible()
    await adminPage
      .getByRole('combobox', { name: '상태' })
      .selectOption('PENDING')
    await adminPage.getByRole('button', { name: '변경사항 저장' }).click()
    await expect(adminPage.getByText(/변경사항을 저장했습니다/)).toBeVisible()
  } finally {
    await adminContext.close()
  }

  const auditorContext = await browser.newContext()
  const auditorPage = await auditorContext.newPage()
  try {
    await auditorPage.goto(`${baseURL}/agent/login`)
    await auditorPage.getByLabel('이메일').fill(auditorEmail)
    await auditorPage.getByLabel('비밀번호').fill(auditorPassword)
    await auditorPage.getByRole('button', { name: '로그인' }).click()
    await expect(auditorPage).toHaveURL(/\/audit\/activity$/)
    await expect(
      auditorPage.getByRole('heading', { name: '활동 조사' }),
    ).toBeVisible()

    await auditorPage.getByLabel('티켓').fill(String(ticketNumber))
    await expect(
      auditorPage.getByRole('button', { name: 'STATUS_CHANGED' }),
    ).toBeVisible()
    await auditorPage.getByRole('button', { name: 'STATUS_CHANGED' }).click()
    await expect(
      auditorPage.getByRole('dialog', { name: '활동 상세' }),
    ).toBeVisible()
    await expect(auditorPage.getByText('NEW')).toBeVisible()
    await expect(auditorPage.getByText('PENDING')).toBeVisible()
    await auditorPage.getByRole('button', { name: '닫기' }).click()

    await auditorPage.goto(`${baseURL}/audit/activity?action=SEARCH_EXECUTED`)
    const searchActivity = auditorPage.getByRole('button', {
      name: 'SEARCH_EXECUTED',
    })
    await expect(searchActivity).toBeVisible()
    await searchActivity.click()
    await expect(auditorPage.getByText(`이 검색에서 연 결과`)).toBeVisible()
    await expect(
      auditorPage.getByRole('button', { name: new RegExp(`#${ticketNumber}`) }),
    ).toBeVisible()
    await expect(auditorPage.getByText(subject)).toHaveCount(0)

    await auditorPage
      .getByRole('textbox', { name: '공개 사유' })
      .fill('E2E incident investigation')
    const revealResponsePromise = auditorPage.waitForResponse((response) =>
      response.url().includes('/search-query-reveal'),
    )
    await auditorPage
      .getByRole('button', { name: '이 event의 raw query 공개' })
      .click()
    const revealResponse = await revealResponsePromise
    expect(revealResponse.status()).toBe(200)
    expect(revealResponse.headers()['cache-control']).toContain('no-store')
    await expect(auditorPage.getByText(subject)).toBeVisible()

    const mutationResult = await auditorPage.evaluate(async () => {
      const csrfResponse = await fetch('/api/v1/agent/csrf', {
        cache: 'no-store',
      })
      const csrf = (await csrfResponse.json()) as {
        token: string
        headerName: string
      }
      const response = await fetch('/api/v1/admin/groups', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          [csrf.headerName]: csrf.token,
        },
        credentials: 'include',
        cache: 'no-store',
        body: JSON.stringify({ name: 'AUDITOR MUST NOT CREATE' }),
      })
      return response.status
    })
    expect(mutationResult).toBe(403)
  } finally {
    await auditorContext.close()
  }

  const evidence = databaseJson(`
    select json_build_object(
      'ticketChanges', (
        select count(*) from audit_activity_projection
        where ledger_type = 'TICKET_CHANGE'
          and action = 'STATUS_CHANGED'
          and ticket_number = ${ticketNumber}
      ),
      'searches', (
        select count(*) from access_audit_events
        where action = 'SEARCH_EXECUTED'
          and id = ${sqlLiteral(searchEventId)}::uuid
      ),
      'resultOpens', (
        select count(*) from access_audit_events
        where action = 'SEARCH_RESULT_OPENED'
          and origin_search_event_id is not null
          and ticket_number = ${ticketNumber}
      ),
      'listViews', (
        select count(*) from admin_security_audit_events event
        join staff_accounts staff on staff.id = event.actor_id
        where staff.email_normalized = ${sqlLiteral(auditorEmail)}
          and event.event_type = 'AUDIT_LOG_VIEWED'
          and event.metadata_json::jsonb ->> 'view' = 'LIST'
      ),
      'detailViews', (
        select count(*) from admin_security_audit_events event
        join staff_accounts staff on staff.id = event.actor_id
        where staff.email_normalized = ${sqlLiteral(auditorEmail)}
          and event.event_type = 'AUDIT_LOG_VIEWED'
          and event.metadata_json::jsonb ->> 'view' = 'DETAIL'
      ),
      'reveals', (
        select count(*) from admin_security_audit_events event
        join staff_accounts staff on staff.id = event.actor_id
        where staff.email_normalized = ${sqlLiteral(auditorEmail)}
          and event.event_type = 'AUDIT_SENSITIVE_CONTENT_REVEALED'
          and event.outcome = 'SUCCEEDED'
      ),
      'rawStoredInAdminAudit', (
        select count(*) from admin_security_audit_events
        where metadata_json like ${sqlLiteral(`%${subject}%`)}
      )
    )
  `)
  expect(evidence.ticketChanges).toBe(1)
  expect(evidence.searches).toBe(1)
  expect(evidence.resultOpens).toBe(1)
  expect(Number(evidence.listViews)).toBeGreaterThanOrEqual(3)
  expect(evidence.detailViews).toBe(2)
  expect(evidence.reveals).toBe(1)
  expect(evidence.rawStoredInAdminAudit).toBe(0)
  expect(backendLogs()).not.toContain(subject)
  expect(backendLogs()).not.toContain('통합 감사 E2E 공개 문의 본문')
})
