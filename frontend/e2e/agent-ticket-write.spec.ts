import { expect, test, type Page, type Route } from '@playwright/test'

const staff = {
  id: '11111111-1111-4111-8111-111111111111',
  email: 'agent@example.test',
  displayName: '상담사 A',
  role: 'AGENT',
  capabilities: ['AGENT_WORKSPACE'],
}
const csrfToken = 'c'.repeat(32)
const auditId = '22222222-2222-4222-8222-222222222222'

function createDetail() {
  return {
    ticket: {
      ticketNumber: 3001,
      subject: '결제 승인 상태 확인 요청',
      status: 'OPEN',
      priority: 'HIGH',
      requester: {
        id: 'customer-3001',
        type: 'CUSTOMER',
        displayName: '고객 A',
      },
      group: { id: 'group-payments', name: '결제 지원' },
      assignee: { id: 'staff-3001', displayName: '상담사 A' },
      updatedAt: '2026-08-15T10:02:00Z',
      version: 3,
      isChild: false,
      openChildCount: 0,
      sla: null,
    },
    comments: [
      {
        id: 'comment-3001-public',
        visibility: 'PUBLIC',
        actor: {
          id: 'customer-3001',
          type: 'CUSTOMER',
          displayName: '고객 A',
        },
        body: '결제가 완료됐는지 확인하고 싶습니다.',
        createdAt: '2026-08-15T09:00:00Z',
        source: 'WEB',
        attachments: [],
      },
    ],
    capabilities: ['READ', 'UPDATE'],
    assignmentOptions: {
      groups: [
        {
          id: 'group-payments',
          name: '결제 지원',
          members: [{ id: 'staff-3001', displayName: '상담사 A' }],
        },
        {
          id: 'group-shipping',
          name: '배송 지원',
          members: [{ id: 'staff-3002', displayName: '상담사 B' }],
        },
      ],
    },
    context: {
      customer: {
        id: 'customer-3001',
        displayName: '고객 A',
        email: 'customer-a@example.test',
      },
      parent: null,
      children: [],
      externalReferenceCount: 0,
    },
    history: [],
    warnings: [],
  }
}

type Command = Record<string, unknown>

async function mockWritableTicket(
  page: Page,
  onCommand: (input: {
    command: Command
    commandCount: number
    detail: ReturnType<typeof createDetail>
    requestHeaders: Record<string, string>
    route: Route
  }) => Promise<void>,
) {
  let detail = createDetail()
  let commandCount = 0
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/v1/agent/me') {
      return route.fulfill({ status: 200, json: staff })
    }
    if (url.pathname === '/api/v1/agent/csrf') {
      return route.fulfill({
        status: 200,
        json: { token: csrfToken, headerName: 'X-CSRF-TOKEN' },
      })
    }
    if (
      url.pathname === '/api/v1/agent/tickets/3001' &&
      request.method() === 'GET'
    ) {
      return route.fulfill({ status: 200, json: detail })
    }
    if (
      url.pathname === '/api/v1/agent/tickets/3001/commands' &&
      request.method() === 'POST'
    ) {
      commandCount += 1
      const command = request.postDataJSON() as Command
      const requestHeaders = request.headers()
      await onCommand({ command, commandCount, detail, requestHeaders, route })
      return
    }
    return route.abort()
  })
  return {
    updateDetail(next: ReturnType<typeof createDetail>) {
      detail = next
    },
  }
}

async function openWorkspace(page: Page) {
  await page.goto('/agent/tickets/3001')
  await expect(
    page.getByRole('main', { name: '티켓 #3001 작업 공간' }),
  ).toBeVisible()
}

function expectMutationHeaders(headers: Record<string, string>) {
  expect(headers['x-csrf-token']).toBe(csrfToken)
  expect(headers['x-deskseed-expected-staff-id']).toBe(staff.id)
}

test('agent PUBLIC reply sends one expected-version command and refreshes the ticket', async ({
  page,
}) => {
  const commands: Command[] = []
  const api = await mockWritableTicket(
    page,
    async ({ command, detail, requestHeaders, route }) => {
      commands.push(command)
      expectMutationHeaders(requestHeaders)
      api.updateDetail({
        ...detail,
        ticket: { ...detail.ticket, version: 4, priority: 'URGENT' },
        comments: [
          ...detail.comments,
          {
            id: 'comment-3001-agent-public',
            visibility: 'PUBLIC',
            actor: {
              id: staff.id,
              type: 'STAFF',
              displayName: staff.displayName,
            },
            body: '결제 승인 상태를 확인해 보겠습니다.',
            createdAt: '2026-08-15T10:03:00Z',
            source: 'STAFF_WEB',
            attachments: [],
          },
        ],
      })
      await route.fulfill({
        status: 200,
        json: { ticketNumber: 3001, version: 4, auditId, warnings: [] },
      })
    },
  )
  await openWorkspace(page)

  await page.getByLabel('우선순위').selectOption('URGENT')
  await page
    .getByRole('textbox', { name: '공개 답변 내용' })
    .fill('결제 승인 상태를 확인해 보겠습니다.')
  await page.getByRole('button', { name: '공개 답변 저장' }).click()

  await expect(
    page.getByText('공개 답변과 변경사항을 저장했습니다.'),
  ).toBeVisible()
  expect(commands).toEqual([
    expect.objectContaining({
      expectedVersion: 3,
      changedFields: ['priority'],
      priority: 'URGENT',
      comment: {
        visibility: 'PUBLIC',
        body: '결제 승인 상태를 확인해 보겠습니다.',
      },
    }),
  ])
  expect(commands[0]?.clientCommandId).toMatch(
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
  )
})

test('agent INTERNAL note sends an internal command without a public fallback', async ({
  page,
}) => {
  const commands: Command[] = []
  await mockWritableTicket(page, async ({ command, requestHeaders, route }) => {
    commands.push(command)
    expectMutationHeaders(requestHeaders)
    await route.fulfill({
      status: 200,
      json: { ticketNumber: 3001, version: 4, auditId, warnings: [] },
    })
  })
  await openWorkspace(page)

  await page.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }).click()
  await page
    .getByRole('textbox', { name: '내부 메모 내용' })
    .fill('카드사 응답 코드를 확인해야 합니다.')
  await page.getByRole('button', { name: '내부 메모 저장' }).click()

  await expect(
    page.getByText('내부 메모와 변경사항을 저장했습니다.'),
  ).toBeVisible()
  expect(commands).toEqual([
    expect.objectContaining({
      expectedVersion: 3,
      changedFields: [],
      comment: {
        visibility: 'INTERNAL',
        body: '카드사 응답 코드를 확인해야 합니다.',
      },
    }),
  ])
})

test('agent field update uses only the assignment options returned by the ticket API', async ({
  page,
}) => {
  const commands: Command[] = []
  await mockWritableTicket(page, async ({ command, route }) => {
    commands.push(command)
    await route.fulfill({
      status: 200,
      json: { ticketNumber: 3001, version: 4, auditId, warnings: [] },
    })
  })
  await openWorkspace(page)

  await page.getByLabel('상태').selectOption('CLOSED')
  await page.getByLabel('그룹').selectOption('group-shipping')
  await expect(page.getByLabel('담당자')).toHaveValue('')
  await page.getByLabel('담당자').selectOption('staff-3002')
  await page
    .getByRole('textbox', { name: '공개 답변 내용' })
    .fill('담당 그룹을 변경했습니다.')
  await page.getByRole('button', { name: '공개 답변 저장' }).click()
  await expect(
    page.getByText('공개 답변과 변경사항을 저장했습니다.'),
  ).toBeVisible()

  expect(commands).toEqual([
    expect.objectContaining({
      expectedVersion: 3,
      changedFields: ['status', 'groupId', 'assigneeId'],
      status: 'CLOSED',
      groupId: 'group-shipping',
      assigneeId: 'staff-3002',
    }),
  ])
})

test('an ambiguous retry reuses one clientCommandId and does not create a duplicate command', async ({
  page,
}) => {
  const commands: Command[] = []
  await mockWritableTicket(page, async ({ command, commandCount, route }) => {
    commands.push(command)
    if (commandCount === 1) {
      await route.abort('failed')
      return
    }
    await route.fulfill({
      status: 200,
      json: { ticketNumber: 3001, version: 4, auditId, warnings: [] },
    })
  })
  await openWorkspace(page)

  await page
    .getByRole('textbox', { name: '공개 답변 내용' })
    .fill('동일 명령으로 다시 저장합니다.')
  await page.getByRole('button', { name: '공개 답변 저장' }).click()
  await expect(page.getByText(/저장 결과를 확인할 수 없습니다/)).toBeVisible()
  await page.getByRole('button', { name: '공개 답변 저장' }).click()

  await expect(
    page.getByText('공개 답변과 변경사항을 저장했습니다.'),
  ).toBeVisible()
  expect(commands).toHaveLength(2)
  expect(commands[1]?.clientCommandId).toBe(commands[0]?.clientCommandId)
  expect(commands[1]?.comment).toEqual(commands[0]?.comment)
})

test('a local draft blocks in-app navigation until the agent explicitly keeps editing', async ({
  page,
}) => {
  await mockWritableTicket(page, async ({ route }) => {
    await route.fulfill({
      status: 200,
      json: { ticketNumber: 3001, version: 4, auditId, warnings: [] },
    })
  })
  await openWorkspace(page)

  await page
    .getByRole('textbox', { name: '공개 답변 내용' })
    .fill('이 초안은 이동 전에도 남아 있어야 합니다.')
  await page.getByRole('link', { name: 'Back to Views' }).click()

  const guard = page.getByRole('dialog', { name: '저장하지 않은 변경사항' })
  await expect(guard).toBeVisible()
  await guard.getByRole('button', { name: '계속 작성' }).click()
  await expect(guard).toHaveCount(0)
  await expect(
    page.getByRole('textbox', { name: '공개 답변 내용' }),
  ).toHaveValue('이 초안은 이동 전에도 남아 있어야 합니다.')
})

test('a 409 conflict preserves the draft and requires a field-by-field decision', async ({
  page,
}) => {
  const api = await mockWritableTicket(page, async ({ detail, route }) => {
    api.updateDetail({
      ...detail,
      ticket: { ...detail.ticket, status: 'PENDING', version: 4 },
    })
    await route.fulfill({
      status: 409,
      headers: { 'Content-Type': 'application/problem+json' },
      json: {
        type: '/problems/ticket-field-conflict',
        title: 'Ticket fields changed concurrently',
        status: 409,
        detail: 'Some fields were changed by another actor.',
        requestId: 'request-conflict-3001',
        currentVersion: 4,
        conflictingFields: ['status'],
      },
    })
  })
  await openWorkspace(page)

  await page.getByLabel('상태').selectOption('SOLVED')
  await page
    .getByRole('textbox', { name: '공개 답변 내용' })
    .fill('초안을 보존해야 합니다.')
  await page.getByRole('button', { name: '공개 답변 저장' }).click()

  await expect(
    page.getByRole('region', { name: '티켓 저장 충돌' }),
  ).toBeVisible()
  await expect(
    page.getByRole('textbox', { name: '공개 답변 내용' }),
  ).toHaveValue('초안을 보존해야 합니다.')
  await page.getByRole('button', { name: '상태에서 내 초안 유지' }).click()
  await expect(
    page.getByRole('region', { name: '티켓 저장 충돌' }),
  ).toHaveCount(0)
  await expect(page.getByLabel('상태')).toHaveValue('SOLVED')
})
