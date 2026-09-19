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
      createdAt: '2026-08-15T09:00:00Z',
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
        content: {
          format: 'PLAIN_TEXT',
          text: '결제가 완료됐는지 확인하고 싶습니다.',
        },
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
    page.getByRole('region', { name: '티켓 #3001 작업 공간' }),
  ).toBeVisible()
}

async function selectChoice(page: Page, label: string, option: string) {
  await page.getByRole('combobox', { name: label }).click()
  await page
    .getByRole('listbox', { name: `${label} 선택지` })
    .getByText(option, { exact: true })
    .click()
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
            content: {
              format: 'PLAIN_TEXT',
              text: '결제 승인 상태를 확인해 보겠습니다.',
            },
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

  await selectChoice(page, '우선순위', '긴급')
  await page
    .getByRole('textbox', { name: '공개 답변 내용' })
    .fill('결제 승인 상태를 확인해 보겠습니다.')
  await page.getByRole('button', { name: '답변 보내기', exact: true }).click()

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
        aiAttribution: {
          contractVersion: 'AI_SENT_V1',
          state: 'NO_AI_LINEAGE',
          sources: [],
        },
        content: {
          format: 'RICH_TEXT_V1',
          document: {
            type: 'doc',
            content: [
              {
                type: 'paragraph',
                content: [
                  {
                    type: 'text',
                    text: '결제 승인 상태를 확인해 보겠습니다.',
                  },
                ],
              },
            ],
          },
        },
      },
    }),
  ])
  expect(commands[0]?.clientCommandId).toMatch(
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
  )
})

test('agent rewrites a current AI reply with closed options and keeps explicit insertion', async ({
  page,
}) => {
  const sourceJobId = '51111111-1111-4111-8111-111111111111'
  const rewriteJobId = '58888888-8888-4888-8888-888888888888'
  const sourceAnswer = '결제 승인 기록을 확인한 뒤 안내드리겠습니다.'
  const rewrittenAnswer =
    '결제 승인 기록을 확인한 뒤 정식으로 안내드리겠습니다.'
  const citation = {
    articleId: '53333333-3333-4333-8333-333333333333',
    revisionId: '54444444-4444-4444-8444-444444444444',
    chunkId: '55555555-5555-4555-8555-555555555555',
    title: '결제 승인 상태 확인 안내',
    url: '/help/articles/payment-approval-status',
  }
  const baseReceipt = {
    status: 'SUCCEEDED',
    phase: 'COMPLETE',
    requestRevision: 3,
    createdAt: '2026-09-19T00:00:00Z',
    deadlineAt: '2026-09-19T00:05:00Z',
    completedAt: '2026-09-19T00:00:04Z',
    resultExpiresAt: '2026-09-26T00:00:04Z',
    pollAfterMs: 1000,
    cancelRequested: false,
    contextRevision: 'a'.repeat(64),
    contextPolicyVersion: 'public-comments-v1',
    stale: false,
    canInsert: true,
    errorCode: null,
    generationMode: 'REUSE_OR_CREATE',
    reuseKind: 'GENERATED',
  }
  const sourceJob = {
    ...baseReceipt,
    jobId: sourceJobId,
    candidateId: '52222222-2222-4222-8222-222222222222',
    feature: 'ticket.reply_draft',
    inputScope: 'PUBLIC_ONLY',
    result: {
      type: 'ticket.reply_draft',
      answer: sourceAnswer,
      citations: [citation],
    },
  }
  const rewriteJob = {
    ...baseReceipt,
    jobId: rewriteJobId,
    candidateId: '59999999-9999-4999-8999-999999999999',
    feature: 'ticket.reply_rewrite',
    sourceJobId,
    inputScope: 'PUBLIC_DRAFT_ONLY',
    result: {
      type: 'ticket.reply_rewrite',
      answer: rewrittenAnswer,
      citations: [citation],
      language: 'ko',
      tone: 'formal',
      length: 'concise',
    },
  }
  const rewriteRequests: Array<Record<string, unknown>> = []

  await mockWritableTicket(page, async ({ route }) => {
    await route.fulfill({
      status: 200,
      json: { ticketNumber: 3001, version: 4, auditId, warnings: [] },
    })
  })
  await page.route('**/api/v1/agent/tickets/3001/ai/jobs**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/v1/agent/tickets/3001/ai/jobs') {
      if (request.method() === 'GET') {
        return route.fulfill({ status: 200, json: { items: [sourceJob] } })
      }
      if (request.method() === 'POST') {
        rewriteRequests.push(request.postDataJSON() as Record<string, unknown>)
        return route.fulfill({ status: 202, json: rewriteJob })
      }
    }
    if (
      request.method() === 'GET' &&
      url.pathname === `/api/v1/agent/tickets/3001/ai/jobs/${sourceJobId}`
    ) {
      return route.fulfill({ status: 200, json: sourceJob })
    }
    if (
      request.method() === 'GET' &&
      url.pathname === `/api/v1/agent/tickets/3001/ai/jobs/${rewriteJobId}`
    ) {
      return route.fulfill({ status: 200, json: rewriteJob })
    }
    if (
      request.method() === 'POST' &&
      url.pathname ===
        `/api/v1/agent/tickets/3001/ai/jobs/${rewriteJobId}/feedback`
    ) {
      return route.fulfill({
        status: 201,
        json: {
          jobId: rewriteJobId,
          type: 'inserted',
          replayed: false,
          recordedAt: '2026-09-19T00:00:05Z',
        },
      })
    }
    return route.fallback()
  })
  await openWorkspace(page)

  await page.getByRole('button', { name: '티켓 컨텍스트 열기' }).click()
  const context = page.getByLabel('티켓 컨텍스트', { exact: true })
  await expect(context.getByText(sourceAnswer)).toBeVisible()
  await context
    .getByRole('combobox', { name: '재작성 문체' })
    .selectOption('formal')
  await context
    .getByRole('combobox', { name: '재작성 길이' })
    .selectOption('concise')
  await context.getByRole('button', { name: '문체·길이 재작성' }).click()

  await expect(context.getByText('재작성 결과', { exact: true })).toBeVisible()
  await expect(context.getByText('격식 있게 · 간결하게')).toBeVisible()
  expect(rewriteRequests).toEqual([
    {
      feature: 'ticket.reply_rewrite',
      expectedTicketVersion: 3,
      generationMode: 'REUSE_OR_CREATE',
      sourceJobId,
      options: { language: 'ko', tone: 'formal', length: 'concise' },
    },
  ])

  await context
    .getByRole('button', { name: '재작성 결과를 PUBLIC 작성기에 사용' })
    .click()
  await expect(
    page.getByRole('textbox', { name: '공개 답변 내용' }),
  ).toHaveText(rewrittenAnswer)
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
  await page
    .getByRole('button', { name: '내부 메모 저장', exact: true })
    .click()

  await expect(
    page.getByText('내부 메모와 변경사항을 저장했습니다.'),
  ).toBeVisible()
  expect(commands).toEqual([
    expect.objectContaining({
      expectedVersion: 3,
      changedFields: [],
      comment: {
        visibility: 'INTERNAL',
        content: {
          format: 'RICH_TEXT_V1',
          document: {
            type: 'doc',
            content: [
              {
                type: 'paragraph',
                content: [
                  {
                    type: 'text',
                    text: '카드사 응답 코드를 확인해야 합니다.',
                  },
                ],
              },
            ],
          },
        },
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

  await selectChoice(page, '상태', '종료')
  await selectChoice(page, '그룹', '배송 지원')
  await expect(page.getByRole('combobox', { name: '담당자' })).toHaveText(
    '미배정',
  )
  await selectChoice(page, '담당자', '상담사 B')
  await page
    .getByRole('textbox', { name: '공개 답변 내용' })
    .fill('담당 그룹을 변경했습니다.')
  await page.getByRole('button', { name: '답변 보내기', exact: true }).click()
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
  await page.getByRole('button', { name: '답변 보내기', exact: true }).click()
  await expect(page.getByText(/저장 결과를 확인할 수 없습니다/)).toBeVisible()
  await page.getByRole('button', { name: '답변 보내기', exact: true }).click()

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
  await page.getByRole('button', { name: '검색', exact: true }).click()

  const guard = page.getByRole('dialog', { name: '저장하지 않은 변경사항' })
  await expect(guard).toBeVisible()
  await guard.getByRole('button', { name: '계속 작성' }).click()
  await expect(guard).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '검색', exact: true }),
  ).toBeFocused()
  await expect(
    page.getByRole('textbox', { name: '공개 답변 내용' }),
  ).toHaveText('이 초안은 이동 전에도 남아 있어야 합니다.')
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

  await selectChoice(page, '상태', '해결됨')
  await page
    .getByRole('textbox', { name: '공개 답변 내용' })
    .fill('초안을 보존해야 합니다.')
  await page.getByRole('button', { name: '답변 보내기', exact: true }).click()

  await expect(page.getByRole('alert', { name: '저장 충돌' })).toBeVisible()
  await expect(
    page.getByRole('textbox', { name: '공개 답변 내용' }),
  ).toHaveText('초안을 보존해야 합니다.')
  await page.getByRole('button', { name: '내 초안 유지' }).click()
  await expect(page.getByRole('alert', { name: '저장 충돌' })).toHaveCount(0)
  await expect(page.getByRole('combobox', { name: '상태' })).toHaveText(
    '해결됨',
  )
})
