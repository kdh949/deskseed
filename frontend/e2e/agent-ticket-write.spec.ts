import { expect, test, type Page, type Route } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

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

for (const width of [1280, 1440, 1920]) {
  test(`transfer reconciles ownership before a field-only save at ${width}px`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    const commands: Command[] = []
    const transferred = {
      ...createDetail(),
      ticket: {
        ...createDetail().ticket,
        version: 4,
        group: { id: 'group-shipping', name: '배송 지원' },
        assignee: { id: 'staff-3002', displayName: '상담사 B' },
      },
    }
    const api = await mockWritableTicket(page, async ({ command, route }) => {
      commands.push(command)
      api.updateDetail({
        ...transferred,
        ticket: { ...transferred.ticket, version: 5, priority: 'URGENT' },
      })
      await route.fulfill({
        json: { ticketNumber: 3001, version: 5, auditId, warnings: [] },
      })
    })
    await page.route('**/api/v1/agent/tickets/3001/transfer', async (route) => {
      expectMutationHeaders(route.request().headers())
      expect(route.request().postDataJSON()).toMatchObject({
        expectedVersion: 3,
        groupId: 'group-shipping',
        assigneeId: 'staff-3002',
      })
      api.updateDetail(transferred)
      await route.fulfill({
        json: { ticketNumber: 3001, version: 4, auditId, warnings: [] },
      })
    })
    await openWorkspace(page)
    await page.getByRole('button', { name: '협업 작업', exact: true }).click()
    await page.getByRole('button', { name: '티켓 이관', exact: true }).click()
    const drawer = page.getByRole('dialog', { name: '티켓 이관' })
    await drawer.getByLabel('대상 그룹').selectOption('group-shipping')
    await drawer.getByLabel('대상 담당자').selectOption('staff-3002')
    await drawer
      .getByLabel('이관 사유')
      .fill('배송 상담사가 이어서 처리합니다.')
    await drawer.getByRole('button', { name: '이관 실행', exact: true }).click()
    await expect(drawer).toHaveCount(0)
    const context = page.getByRole('dialog', { name: '티켓 컨텍스트' })
    if (await context.isVisible()) await page.keyboard.press('Escape')
    await expect(page.getByRole('combobox', { name: '담당자' })).toContainText(
      '상담사 B',
    )
    await expect(page.getByRole('combobox', { name: '그룹' })).toContainText(
      '배송 지원',
    )
    await selectChoice(page, '우선순위', '긴급')
    await expect(
      page.getByRole('listbox', {
        name: '우선순위 선택지',
        includeHidden: true,
      }),
    ).toBeHidden()
    const save = page.getByRole('button', {
      name: '변경사항 저장',
      exact: true,
    })
    await expect(save).toBeEnabled()
    await page.screenshot({
      path: testInfo.outputPath(`transferred-field-save-${width}.png`),
      fullPage: true,
    })
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
    await save.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByText('변경사항을 저장했습니다.')).toBeVisible()
    expect(commands).toHaveLength(1)
    expect(commands[0]).toMatchObject({
      expectedVersion: 4,
      changedFields: ['priority'],
      priority: 'URGENT',
      comment: null,
    })
    expect(commands[0]).not.toHaveProperty('assigneeId')
    expect(commands[0]).not.toHaveProperty('groupId')
  })
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
  await page
    .getByRole('button', { name: '답변과 변경사항 저장', exact: true })
    .click()

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
  await page
    .getByRole('button', { name: '답변과 변경사항 저장', exact: true })
    .click()
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
  await page.keyboard.press('Control+k')
  await page.keyboard.press('Meta+k')
  await expect(page).toHaveURL(/\/agent\/tickets\/3001$/)
  await expect(
    page.getByRole('dialog', { name: '저장하지 않은 변경사항' }),
  ).toHaveCount(0)
  await page.getByRole('button', { name: '검색', exact: true }).focus()
  await page.keyboard.press('Control+k')
  const shortcutGuard = page.getByRole('dialog', {
    name: '저장하지 않은 변경사항',
  })
  await expect(shortcutGuard).toBeVisible()
  await shortcutGuard.getByRole('button', { name: '계속 작성' }).click()
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

test('open ticket navigation keeps separate drafts and a cancelled close retains the active ticket', async ({
  page,
}) => {
  await mockWritableTicket(page, async ({ route }) => route.abort())
  await page.route('**/api/v1/agent/tickets/3002', (route) =>
    route.fulfill({
      json: {
        ...createDetail(),
        ticket: {
          ...createDetail().ticket,
          ticketNumber: 3002,
          subject: '두 번째 문의',
        },
      },
    }),
  )
  await page.goto('/agent/tickets/3002')
  await expect(
    page.getByRole('region', { name: '티켓 #3002 작업 공간' }),
  ).toBeVisible()
  await openWorkspace(page)
  const tabs = page.getByRole('navigation', { name: '열린 티켓', exact: true })
  await expect(
    tabs.getByRole('link', { name: '#3002', exact: true }),
  ).toBeVisible()
  await page
    .getByRole('textbox', { name: '공개 답변 내용' })
    .fill('첫 티켓의 공개 초안')
  await page
    .getByRole('textbox', { name: '공개 답변 내용' })
    .press('ControlOrMeta+A')
  await page.getByRole('button', { name: '굵게 (⌘B)' }).click()
  await page.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }).click()
  await page
    .getByRole('textbox', { name: '내부 메모 내용' })
    .fill('첫 티켓의 내부 초안')
  await tabs.getByRole('button', { name: '#3001 닫기' }).click()
  const guard = page.getByRole('dialog', { name: '저장하지 않은 변경사항' })
  await expect(guard).toBeVisible()
  await guard.getByRole('button', { name: '계속 작성' }).click()
  await expect(
    tabs.getByRole('link', { name: '#3001', exact: true }),
  ).toHaveAttribute('aria-current', 'page')
  await expect(
    page.getByRole('textbox', { name: '내부 메모 내용' }),
  ).toHaveText('첫 티켓의 내부 초안')
  await tabs.getByRole('link', { name: '#3002', exact: true }).click()
  await expect(
    tabs.getByRole('link', { name: '#3001', exact: true }),
  ).toHaveAccessibleDescription('미제출')
  await guard.getByRole('button', { name: '초안 유지하고 이동' }).click()
  await expect(
    page.getByRole('region', { name: '티켓 #3002 작업 공간' }),
  ).toBeVisible()
  await expect(
    tabs.getByRole('link', { name: '#3001', exact: true }),
  ).toHaveAccessibleDescription('미제출')
  await tabs.getByRole('link', { name: '#3001', exact: true }).click()
  await expect(
    page.getByRole('textbox', { name: '내부 메모 내용' }),
  ).toHaveText('첫 티켓의 내부 초안')
  await page.getByRole('tab', { name: '공개 답변 작성 모드로 전환' }).click()
  await expect(
    page.getByRole('textbox', { name: '공개 답변 내용' }),
  ).toHaveText('첫 티켓의 공개 초안')
  const stored = await page.evaluate(
    (staffId) =>
      JSON.parse(
        sessionStorage.getItem(`deskseed:open-tickets:v1:${staffId}`) ?? 'null',
      ),
    staff.id,
  )
  expect(stored).toEqual([3002, 3001])
  await expect(
    page.getByRole('textbox', { name: '공개 답변 내용' }).locator('strong'),
  ).toHaveText('첫 티켓의 공개 초안')
  page.once('dialog', (dialog) => dialog.accept())
  await page.reload()
  await expect(
    page.getByRole('textbox', { name: '공개 답변 내용' }).locator('strong'),
  ).toHaveText('첫 티켓의 공개 초안')
  await page.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }).click()
  await expect(
    page.getByRole('textbox', { name: '내부 메모 내용' }),
  ).toHaveText('첫 티켓의 내부 초안')
  await tabs.getByRole('button', { name: '#3001 닫기' }).click()
  await guard.getByRole('button', { name: '초안 유지하고 이동' }).click()
  await expect(page).toHaveURL(/\/agent\/tickets\/3002$/)
  await expect(
    tabs.getByRole('link', { name: '#3001', exact: true }),
  ).toHaveCount(0)
})

test('a failed local checkpoint retains the editor and leaving without another checkpoint does not claim to discard drafts', async ({
  page,
}) => {
  let commands = 0
  await mockWritableTicket(page, async ({ route }) => {
    commands += 1
    await route.abort()
  })
  await openWorkspace(page)
  await page
    .getByRole('textbox', { name: '공개 답변 내용' })
    .fill('저장소 오류에서도 남길 초안')
  await page.evaluate(() => {
    Object.defineProperty(window, 'indexedDB', {
      configurable: true,
      value: {
        open() {
          throw new DOMException('blocked for the test', 'SecurityError')
        },
      },
    })
  })
  await page.getByRole('button', { name: '검색', exact: true }).click()
  const guard = page.getByRole('dialog', { name: '저장하지 않은 변경사항' })
  await guard.getByRole('button', { name: '초안 유지하고 이동' }).click()
  await expect(guard.getByText('초안 보관 실패', { exact: true })).toBeVisible()
  await expect(page).toHaveURL(/\/agent\/tickets\/3001$/)
  await expect(
    page.getByRole('textbox', { name: '공개 답변 내용' }),
  ).toHaveText('저장소 오류에서도 남길 초안')
  await expect(
    guard.getByText(
      '추가 보관 없이 이동해도 이미 자동 보관된 초안은 다시 열 때 복구될 수 있습니다.',
    ),
  ).toBeVisible()
  await guard.getByRole('button', { name: '추가 보관 없이 이동' }).click()
  await expect(page).toHaveURL(/\/agent\/search$/)
  await expect(
    page
      .getByRole('navigation', { name: '열린 티켓', exact: true })
      .getByRole('link', { name: '#3001', exact: true }),
  ).toHaveAccessibleDescription('미제출')
  expect(commands).toBe(0)
})

test('a failed manual draft save reports the local-only state without a success confirmation', async ({
  page,
}) => {
  let commands = 0
  let serverAvailable = false
  await mockWritableTicket(page, async ({ route }) => {
    commands += 1
    await route.abort()
  })
  await page.route('**/api/v1/agent/tickets/3001/drafts/*', (route) => {
    if (serverAvailable && route.request().method() === 'PUT') {
      const input = route.request().postDataJSON()
      return route.fulfill({
        status: 200,
        json: {
          ...input,
          ticketNumber: 3001,
          channel: 'PUBLIC_REPLY',
          body: '서버 저장에 실패해도 보존할 초안',
          draftVersion: 1,
          updatedAt: '2026-10-03T12:00:00Z',
          expiresAt: '2099-10-10T12:00:00Z',
        },
      })
    }
    const status = route.request().method() === 'GET' ? 404 : 500
    return route.fulfill({
      status,
      json: {
        type: '/problems/draft-test',
        title: 'Draft unavailable',
        status,
      },
    })
  })
  await openWorkspace(page)
  await page
    .getByRole('textbox', { name: '공개 답변 내용' })
    .fill('서버 저장에 실패해도 보존할 초안')
  await page.getByRole('button', { name: '초안 저장', exact: true }).click()
  await expect(
    page.getByText('이 브라우저에만 저장됨', { exact: true }),
  ).toBeVisible()
  await expect(
    page.getByText('복구 초안을 저장했습니다.', { exact: true }),
  ).toHaveCount(0)
  await expect(
    page.getByRole('textbox', { name: '공개 답변 내용' }),
  ).toHaveText('서버 저장에 실패해도 보존할 초안')
  serverAvailable = true
  const retry = page.waitForResponse(
    (response) =>
      response.url().includes('/drafts/PUBLIC_REPLY') &&
      response.status() === 200,
  )
  await page.getByRole('button', { name: '초안 저장', exact: true }).click()
  await retry
  await expect(
    page.getByText('복구 초안 동기화 실패', { exact: true }),
  ).toHaveCount(0)
  expect(commands).toBe(0)
})

test('an uploading attachment keeps the local-preservation action disabled until the agent resolves it', async ({
  page,
}) => {
  await mockWritableTicket(page, async ({ route }) => route.abort())
  let resolveUpload: (() => void) | undefined
  const pendingUpload = new Promise<void>((resolve) => {
    resolveUpload = resolve
  })
  await page.route('**/api/v1/agent/attachments/uploads', async (route) => {
    await pendingUpload
    await route.abort()
  })
  await openWorkspace(page)
  await page.getByLabel('PUBLIC 첨부 파일', { exact: true }).setInputFiles({
    name: 'draft.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('synthetic draft attachment'),
  })
  await page.getByRole('button', { name: '검색', exact: true }).click()
  const guard = page.getByRole('dialog', { name: '저장하지 않은 변경사항' })
  await expect(
    guard.getByText('첨부 상태 확인 필요', { exact: true }),
  ).toBeVisible()
  await expect(
    guard.getByRole('button', { name: '초안 유지하고 이동' }),
  ).toBeDisabled()
  await guard.getByRole('button', { name: '계속 작성' }).click()
  await expect(page).toHaveURL(/\/agent\/tickets\/3001$/)
  await expect(page.getByText('draft.txt', { exact: true })).toBeVisible()
  resolveUpload?.()
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
  await page
    .getByRole('button', { name: '답변과 변경사항 저장', exact: true })
    .click()

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
