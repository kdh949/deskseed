import AxeBuilder from '@axe-core/playwright'
import {
  expect,
  test,
  type Browser,
  type BrowserContext,
  type Page,
} from '@playwright/test'
import { pressSequentialTab } from './keyboard'

type EditableField = 'status' | 'priority' | 'groupId' | 'assigneeId'

interface CommandBody {
  expectedVersion: number
  changedFields: EditableField[]
  status?: string
  priority?: string
  groupId?: string | null
  assigneeId?: string | null
  comment?: { visibility: 'PUBLIC' | 'INTERNAL'; body: string } | null
  clientCommandId: string
}

interface MockComment {
  id: string
  visibility: 'PUBLIC' | 'INTERNAL'
  actor: { id: string; type: string; displayName: string }
  body: string
  createdAt: string
  source: string
  attachments: never[]
}

function createServer() {
  const server = {
    version: 7,
    status: 'OPEN',
    priority: 'NORMAL',
    groupId: 'payments',
    assigneeId: 'agent-e2e',
    comments: [
      {
        id: 'public-1',
        visibility: 'PUBLIC',
        actor: {
          id: 'customer-1',
          type: 'CUSTOMER',
          displayName: '김민수',
        },
        body: '결제 인증 후 주문이 만들어지지 않았습니다.',
        createdAt: '2026-08-10T09:00:00Z',
        source: 'WEB',
        attachments: [],
      },
    ] as MockComment[],
    fieldHistory: [] as Array<{ version: number; fields: EditableField[] }>,
    commands: [] as CommandBody[],
  }
  return server
}

type MockServer = ReturnType<typeof createServer>

const views = [
  {
    key: 'my-open',
    name: '내 open',
    ticketCount: 1,
    scope: 'SYSTEM',
    categoryPath: ['Views'],
    readScope: 'ALL_TICKETS',
  },
]

function detailPayload(server: MockServer) {
  const group =
    server.groupId === null
      ? null
      : {
          id: server.groupId,
          name: server.groupId === 'payments' ? '결제 지원' : '고객 지원',
        }
  const assignee =
    server.assigneeId === null
      ? null
      : {
          id: server.assigneeId,
          displayName: server.assigneeId === 'agent-e2e' ? '한서윤' : '김도윤',
        }
  return {
    ticket: {
      ticketNumber: 1042,
      subject: '결제 승인 오류',
      status: server.status,
      priority: server.priority,
      requester: {
        id: 'customer-1',
        type: 'CUSTOMER',
        displayName: '김민수',
      },
      group,
      assignee,
      updatedAt: '2026-08-11T10:02:00Z',
      version: server.version,
      isChild: false,
      openChildCount: 0,
      sla: null,
    },
    comments: server.comments,
    capabilities: ['READ', 'UPDATE'],
    assignmentOptions: {
      groups: [
        {
          id: 'payments',
          name: '결제 지원',
          members: [
            { id: 'agent-e2e', displayName: '한서윤' },
            { id: 'agent-2', displayName: '김도윤' },
          ],
        },
        {
          id: 'support',
          name: '고객 지원',
          members: [{ id: 'agent-e2e', displayName: '한서윤' }],
        },
      ],
    },
    context: {
      customer: {
        id: 'customer-1',
        displayName: '김민수',
        email: 'minsu@example.com',
      },
      parent: null,
      children: [],
      externalReferences: [],
    },
    history: [],
    warnings: [],
  }
}

async function installApi(page: Page, server: MockServer) {
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (pathname === '/api/v1/agent/me') {
      return route.fulfill({
        status: 200,
        json: {
          id: 'agent-e2e',
          email: 'agent@example.com',
          displayName: '한서윤',
          role: 'AGENT',
          capabilities: ['AGENT_WORKSPACE'],
        },
      })
    }
    if (pathname === '/api/v1/agent/views') {
      return route.fulfill({ status: 200, json: views })
    }
    if (pathname === '/api/v1/agent/csrf') {
      return route.fulfill({
        status: 200,
        json: { token: 'csrf-e2e', headerName: 'X-CSRF-TOKEN' },
      })
    }
    if (
      pathname === '/api/v1/agent/tickets/1042' &&
      request.method() === 'GET'
    ) {
      return route.fulfill({ status: 200, json: detailPayload(server) })
    }
    if (
      pathname === '/api/v1/agent/tickets/1042/commands' &&
      request.method() === 'POST'
    ) {
      const command = request.postDataJSON() as CommandBody
      server.commands.push(command)
      const changedSinceExpected = new Set(
        server.fieldHistory
          .filter((entry) => entry.version > command.expectedVersion)
          .flatMap((entry) => entry.fields),
      )
      const conflicts = command.changedFields.filter((field) =>
        changedSinceExpected.has(field),
      )
      if (conflicts.length > 0) {
        return route.fulfill({
          status: 409,
          headers: { 'X-Request-Id': 'request-conflict-409' },
          json: {
            type: '/problems/ticket-field-conflict',
            title: 'Ticket fields changed',
            status: 409,
            detail: '선택한 필드가 다른 상담사에 의해 변경되었습니다.',
            requestId: 'request-conflict-409',
            currentVersion: server.version,
            conflictingFields: conflicts,
          },
        })
      }
      for (const field of command.changedFields) {
        if (field === 'status' && command.status) server.status = command.status
        if (field === 'priority' && command.priority) {
          server.priority = command.priority
        }
        if (field === 'groupId') server.groupId = command.groupId ?? null
        if (field === 'assigneeId') {
          server.assigneeId = command.assigneeId ?? null
        }
      }
      server.version += 1
      server.fieldHistory.push({
        version: server.version,
        fields: [...command.changedFields],
      })
      if (command.comment?.body) {
        server.comments.push({
          id: `comment-${server.version}`,
          visibility: command.comment.visibility,
          actor: { id: 'agent-e2e', type: 'STAFF', displayName: '한서윤' },
          body: command.comment.body,
          createdAt: '2026-08-11T10:05:00Z',
          source: 'AGENT_UI',
          attachments: [],
        })
      }
      return route.fulfill({
        status: 200,
        json: {
          ticketNumber: 1042,
          version: server.version,
          auditId: `audit-${server.version}`,
          warnings: [],
        },
      })
    }
    return route.abort()
  })
}

async function openContext(
  browser: Browser,
  baseURL: string,
  server: MockServer,
) {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
  })
  const page = await context.newPage()
  await installApi(page, server)
  await page.goto(`${baseURL}/agent/tickets/1042`)
  await expect(
    page.getByRole('heading', { name: '결제 승인 오류' }),
  ).toBeVisible()
  return { context, page }
}

async function openFixturePage(
  page: Page,
  baseURL: string,
  server: MockServer,
) {
  await page.setViewportSize({ width: 1440, height: 900 })
  await installApi(page, server)
  await page.goto(`${baseURL}/agent/tickets/1042`)
  await expect(
    page.getByRole('heading', { name: '결제 승인 오류' }),
  ).toBeVisible()
}

async function closeContexts(contexts: BrowserContext[]) {
  await Promise.all(contexts.map((context) => context.close()))
}

test('same-field 충돌은 banner로 복구하고 두 mode draft를 보존한다', async ({
  browser,
  baseURL,
  page,
}) => {
  if (!baseURL) throw new Error('Playwright baseURL is required')
  const server = createServer()
  await openFixturePage(page, baseURL, server)
  const first = { page }
  const second = await openContext(browser, baseURL, server)
  try {
    await first.page
      .getByRole('combobox', { name: '우선순위' })
      .selectOption('HIGH')
    await first.page
      .getByRole('textbox', { name: '공개 답변' })
      .fill('첫 상담사의 공개 답변')
    await first.page.getByRole('button', { name: '변경사항 저장' }).click()
    await expect(first.page.getByText(/공개 답변과 변경사항/)).toBeVisible()

    await second.page
      .getByRole('textbox', { name: '공개 답변' })
      .fill('충돌 후에도 남아야 하는 공개 초안')
    await second.page.getByRole('tab', { name: '내부 메모' }).click()
    await second.page
      .getByRole('textbox', { name: '내부 메모' })
      .fill('충돌 후에도 남아야 하는 내부 초안')
    await second.page.getByRole('tab', { name: '공개 답변' }).click()
    await second.page
      .getByRole('combobox', { name: '우선순위' })
      .selectOption('LOW')
    await second.page.getByRole('button', { name: '변경사항 저장' }).click()

    const banner = second.page.getByRole('alert', { name: /변경 충돌/ })
    await expect(banner).toBeVisible()
    await expect(banner).toBeFocused()
    await expect(banner).toContainText('우선순위')
    await expect(banner).toContainText('request-conflict-409')
    await expect(
      second.page.getByRole('combobox', { name: '우선순위' }),
    ).toHaveValue('LOW')
    await expect(
      second.page.getByRole('textbox', { name: '공개 답변' }),
    ).toHaveValue('충돌 후에도 남아야 하는 공개 초안')
    await second.page.getByRole('tab', { name: '내부 메모' }).click()
    await expect(
      second.page.getByRole('textbox', { name: '내부 메모' }),
    ).toHaveValue('충돌 후에도 남아야 하는 내부 초안')
    await second.page.getByRole('tab', { name: '공개 답변' }).click()
    await expect(second.page).toHaveScreenshot(
      'ticket-command-conflict-preserves-drafts.png',
    )

    await second.page
      .getByRole('button', { name: '내 변경으로 재시도' })
      .click()
    await second.page.getByRole('button', { name: '변경사항 저장' }).click()
    await expect(second.page.getByText(/공개 답변과 변경사항/)).toBeVisible()
    expect(server.commands.at(-1)).toMatchObject({
      expectedVersion: 8,
      changedFields: ['priority'],
      priority: 'LOW',
      comment: {
        visibility: 'PUBLIC',
        body: '충돌 후에도 남아야 하는 공개 초안',
      },
    })
  } finally {
    await closeContexts([second.context])
  }
})

test('non-overlap 변경은 최신 version에 병합된다', async ({
  browser,
  baseURL,
  page,
}) => {
  if (!baseURL) throw new Error('Playwright baseURL is required')
  const server = createServer()
  await openFixturePage(page, baseURL, server)
  const first = { page }
  const second = await openContext(browser, baseURL, server)
  try {
    await first.page
      .getByRole('combobox', { name: '우선순위' })
      .selectOption('HIGH')
    await second.page
      .getByRole('combobox', { name: '상태' })
      .selectOption('PENDING')

    await first.page.getByRole('button', { name: '변경사항 저장' }).click()
    await expect.poll(() => server.version).toBe(8)
    await second.page.getByRole('button', { name: '변경사항 저장' }).click()
    await expect.poll(() => server.version).toBe(9)

    await expect(second.page.getByRole('alert')).toHaveCount(0)
    await expect(
      second.page.getByRole('combobox', { name: '우선순위' }),
    ).toHaveValue('HIGH')
    await expect(
      second.page.getByRole('combobox', { name: '상태' }),
    ).toHaveValue('PENDING')
    expect(server.commands.at(-1)).toMatchObject({
      expectedVersion: 7,
      changedFields: ['status'],
      status: 'PENDING',
    })
  } finally {
    await closeContexts([second.context])
  }
})

test('keyboard-only로 INTERNAL mode를 명시적으로 선택하고 저장한다', async ({
  browserName,
  page,
}) => {
  const server = createServer()
  await installApi(page, server)
  await page.goto('/agent/tickets/1042')
  const publicTab = page.getByRole('tab', { name: '공개 답변' })
  for (let index = 0; index < 30; index += 1) {
    await pressSequentialTab(page, browserName)
    if (
      await publicTab.evaluate((element) => element === document.activeElement)
    ) {
      break
    }
  }
  await expect(publicTab).toBeFocused()
  await page.keyboard.press('ArrowRight')
  await expect(page.getByRole('tab', { name: '내부 메모' })).toBeFocused()
  await pressSequentialTab(page, browserName)
  await expect(page.getByRole('textbox', { name: '내부 메모' })).toBeFocused()
  await page.keyboard.type('키보드로 작성한 내부 메모')
  await pressSequentialTab(page, browserName)
  await expect(
    page.getByRole('button', { name: '변경사항 저장' }),
  ).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.getByText(/내부 메모와 변경사항/)).toBeVisible()

  expect(server.comments.at(-1)).toMatchObject({
    visibility: 'INTERNAL',
    body: '키보드로 작성한 내부 메모',
  })
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})
