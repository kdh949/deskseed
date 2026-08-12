import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { pressSequentialTab } from './keyboard'

interface RelatedCommand {
  path: string
  headers: Record<string, string>
  body: Record<string, unknown>
}

function createCollaborationServer() {
  return {
    version: 7,
    groupId: 'payments',
    assigneeId: 'agent-e2e',
    status: 'OPEN',
    children: [] as Array<{ ticketNumber: number; subject: string }>,
    commands: [] as RelatedCommand[],
  }
}

type CollaborationServer = ReturnType<typeof createCollaborationServer>

function summary(
  ticketNumber: number,
  subject: string,
  server: CollaborationServer,
  isChild = false,
) {
  return {
    ticketNumber,
    subject,
    status: isChild ? 'NEW' : server.status,
    priority: isChild ? 'HIGH' : 'URGENT',
    requester: {
      id: 'customer-1',
      type: 'CUSTOMER',
      displayName: '김민수',
    },
    group: {
      id: isChild ? 'support' : server.groupId,
      name:
        (isChild ? 'support' : server.groupId) === 'payments'
          ? '결제 지원'
          : '고객 지원',
    },
    assignee: {
      id: server.assigneeId,
      displayName: '한서윤',
    },
    updatedAt: '2026-08-11T10:02:00Z',
    version: isChild ? 0 : server.version,
    isChild,
    openChildCount: isChild ? 0 : server.children.length,
    sla: null,
  }
}

function detailPayload(server: CollaborationServer) {
  return {
    ticket: summary(1042, '결제 승인 오류', server),
    comments: [
      {
        id: 'public-1',
        visibility: 'PUBLIC',
        actor: {
          id: 'customer-1',
          type: 'CUSTOMER',
          displayName: '김민수',
        },
        body: '결제 인증 뒤 주문이 만들어지지 않았습니다.',
        createdAt: '2026-08-11T09:00:00Z',
        source: 'WEB',
        attachments: [],
      },
    ],
    capabilities: ['READ', 'UPDATE'],
    assignmentOptions: {
      groups: [
        {
          id: 'payments',
          name: '결제 지원',
          members: [{ id: 'agent-e2e', displayName: '한서윤' }],
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
      children: server.children.map((child) =>
        summary(child.ticketNumber, child.subject, server, true),
      ),
      externalReferences: [],
    },
    history: [],
    warnings: [],
  }
}

async function installApi(page: Page, server: CollaborationServer) {
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/agent/me') {
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
    if (path === '/api/v1/agent/views') {
      return route.fulfill({ status: 200, json: [] })
    }
    if (path === '/api/v1/agent/csrf') {
      return route.fulfill({
        status: 200,
        json: { token: 'csrf-e2e', headerName: 'X-CSRF-TOKEN' },
      })
    }
    if (path === '/api/v1/agent/tickets/1042' && request.method() === 'GET') {
      return route.fulfill({ status: 200, json: detailPayload(server) })
    }
    if (
      path === '/api/v1/agent/tickets/1042/children' &&
      request.method() === 'POST'
    ) {
      const body = request.postDataJSON() as Record<string, unknown>
      server.commands.push({ path, headers: request.headers(), body })
      server.children.push({
        ticketNumber: 1043,
        subject: String(body.subject),
      })
      server.version += 1
      return route.fulfill({
        status: 201,
        headers: { ETag: `"${server.version}"` },
        json: {
          parentTicketNumber: 1042,
          parentVersion: server.version,
          childTicketNumber: 1043,
          parentAuditId: 'parent-audit-id',
          childAuditId: 'child-audit-id',
        },
      })
    }
    if (
      path === '/api/v1/agent/tickets/1042/transfer' &&
      request.method() === 'POST'
    ) {
      const body = request.postDataJSON() as Record<string, unknown>
      server.commands.push({ path, headers: request.headers(), body })
      server.groupId = String(body.groupId)
      server.assigneeId = String(body.assigneeId)
      server.version += 1
      return route.fulfill({
        status: 200,
        headers: { ETag: `"${server.version}"` },
        json: {
          ticketNumber: 1042,
          version: server.version,
          auditId: 'transfer-audit-id',
          warnings: [],
        },
      })
    }
    if (
      path === '/api/v1/agent/tickets/1042/commands' &&
      request.method() === 'POST'
    ) {
      const body = request.postDataJSON() as Record<string, unknown>
      server.status = 'SOLVED'
      server.version += 1
      server.commands.push({ path, headers: request.headers(), body })
      return route.fulfill({
        status: 200,
        json: {
          ticketNumber: 1042,
          version: server.version,
          auditId: 'solve-audit-id',
          warnings: [
            {
              code: 'OPEN_CHILD_TICKETS',
              message: '열린 child ticket 1개가 있지만 저장되었습니다.',
              count: 1,
              relatedTicketNumbers: [1043],
            },
          ],
        },
      })
    }
    return route.abort()
  })
}

test('transfer와 child는 독립 command이며 관계·경고 dialog가 접근 가능하다', async ({
  browserName,
  page,
}) => {
  const server = createCollaborationServer()
  await page.setViewportSize({ width: 1440, height: 900 })
  const consoleProblems: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error' || message.type() === 'warning') {
      consoleProblems.push(message.text())
    }
  })
  await installApi(page, server)
  await page.goto('/agent/tickets/1042')
  await expect(
    page.getByRole('heading', { name: '결제 승인 오류' }),
  ).toBeVisible()
  await page.getByRole('tab', { name: '관련' }).click()

  const childTrigger = page.getByRole('button', { name: '내부 child 만들기' })
  await childTrigger.click()
  const childDialog = page.getByRole('dialog', { name: '내부 child 만들기' })
  await expect(childDialog).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Child 제목' })).toBeFocused()
  await page.getByRole('textbox', { name: 'Child 제목' }).fill('승인 로그 확인')
  await page
    .getByRole('textbox', { name: '내부 작업 설명' })
    .fill('고객에게 공개되지 않는 내부 조사')
  await page
    .getByRole('combobox', { name: '대상 그룹' })
    .selectOption('support')
  await page
    .getByRole('combobox', { name: '대상 담당자' })
    .selectOption('agent-e2e')
  await page
    .getByRole('combobox', { name: 'Child 우선순위' })
    .selectOption('HIGH')
  const childClose = page.getByRole('button', {
    name: '내부 child 만들기 닫기',
  })
  await childClose.focus()
  await pressSequentialTab(page, browserName, true)
  await expect(page.getByRole('button', { name: 'Child 생성' })).toBeFocused()
  await pressSequentialTab(page, browserName)
  await expect(childClose).toBeFocused()
  await expect(page).toHaveScreenshot('child-ticket-dialog.png', {
    fullPage: true,
  })
  expect(
    (await new AxeBuilder({ page }).include('.ticket-action-dialog').analyze())
      .violations,
  ).toEqual([])
  await page.getByRole('button', { name: 'Child 생성' }).click()
  await expect(childDialog).toHaveCount(0)
  await expect(childTrigger).toBeFocused()
  await expect(
    page.getByRole('link', { name: /#1043 승인 로그 확인/ }),
  ).toBeVisible()
  await expect(page.getByRole('combobox', { name: '그룹' })).toHaveValue(
    'payments',
  )

  const transferTrigger = page.getByRole('button', { name: '티켓 이관' })
  await transferTrigger.click()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(transferTrigger).toBeFocused()
  await transferTrigger.click()
  await page
    .getByRole('combobox', { name: '대상 그룹' })
    .selectOption('support')
  await page
    .getByRole('combobox', { name: '대상 담당자' })
    .selectOption('agent-e2e')
  await page
    .getByRole('textbox', { name: '이관 사유 (내부 메모)' })
    .fill('고객 지원 그룹이 응답 책임을 인수합니다.')
  await page.getByRole('button', { name: '소유권 이관' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(transferTrigger).toBeFocused()
  await expect(page.getByRole('combobox', { name: '그룹' })).toHaveValue(
    'support',
  )

  await page.getByRole('combobox', { name: '상태' }).selectOption('SOLVED')
  await page.getByRole('button', { name: '변경사항 저장' }).click()
  const warning = page.getByRole('alert', { name: /열린 child ticket/ })
  await expect(warning).toContainText('#1043')
  await expect(warning).toContainText('1개')
  await expect(page).toHaveScreenshot('transfer-child-solve-warning.png', {
    fullPage: true,
  })
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  expect(server.commands.map((command) => command.path)).toEqual([
    '/api/v1/agent/tickets/1042/children',
    '/api/v1/agent/tickets/1042/transfer',
    '/api/v1/agent/tickets/1042/commands',
  ])
  expect(server.commands[0]).toMatchObject({
    headers: { 'if-match': '"7"', 'x-csrf-token': 'csrf-e2e' },
    body: { expectedVersion: 7, groupId: 'support', assigneeId: 'agent-e2e' },
  })
  expect(server.commands[1]).toMatchObject({
    headers: { 'if-match': '"8"', 'x-csrf-token': 'csrf-e2e' },
    body: { expectedVersion: 8, groupId: 'support', assigneeId: 'agent-e2e' },
  })
  expect(consoleProblems).toEqual([])
})
