import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type WebSocketRoute } from '@playwright/test'

const staff = {
  id: '11111111-1111-4111-8111-111111111111',
  email: 'agent@example.test',
  displayName: '상담사 A',
  role: 'AGENT',
  capabilities: ['AGENT_WORKSPACE'],
}

const detail = {
  ticket: {
    ticketNumber: 3001,
    subject: '결제 승인 상태 확인 요청',
    status: 'ON_HOLD',
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

async function mockWritableTicket(page: Page) {
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/v1/agent/me') {
      return route.fulfill({ status: 200, json: staff })
    }
    if (url.pathname === '/api/v1/agent/tickets/3001') {
      return route.fulfill({ status: 200, json: detail })
    }
    if (url.pathname === '/api/v1/agent/tickets/3001/external-references') {
      return route.fulfill({
        status: 200,
        json: { items: [], nextCursor: null },
      })
    }
    if (url.pathname === '/api/v1/agent/drafts/3001') {
      if (request.method() === 'PUT') {
        return route.fulfill({
          status: 200,
          json: {
            ticketNumber: 3001,
            version: 3,
            publicBody: request.postDataJSON()?.publicBody ?? '',
            internalBody: request.postDataJSON()?.internalBody ?? '',
            updatedAt: '2026-08-15T10:03:00Z',
          },
        })
      }
      return route.fulfill({ status: 204, body: '' })
    }
    return route.abort()
  })
}

for (const width of [1280, 1440, 1920]) {
  test(`presence reconnect preserves the draft and rechecks permission at ${width}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 })
    await mockWritableTicket(page)
    const sockets: WebSocketRoute[] = []
    const messages: Record<string, unknown>[] = []
    await page.routeWebSocket('**/ws/agent/collaboration', (socket) => {
      socket.onMessage((raw) => {
        const message = JSON.parse(String(raw))
        messages.push(message)
        if (message.type === 'subscribe') {
          if (!sockets.includes(socket)) sockets.push(socket)
          const first = sockets.length === 1
          socket.send(
            JSON.stringify({
              version: 1,
              type: 'presence.snapshot',
              ticketNumber: 3001,
              members: first
                ? [
                    {
                      staffId: 'other-agent',
                      displayName: '협업 상담사',
                      state: 'VIEWING',
                      lastSeenAt: '2026-10-03T00:00:00Z',
                    },
                  ]
                : [],
            }),
          )
        }
      })
    })
    await page.goto('/agent/tickets/3001')
    const editor = page.getByRole('textbox', { name: '공개 답변 내용' })
    await editor.fill('연결이 끊겨도 유지할 공개 답변')
    await page.getByRole('button', { name: '티켓 컨텍스트 열기' }).click()
    await page.getByRole('tab', { name: '협업', exact: true }).click()
    const presence = page.getByRole('complementary', {
      name: '함께 작업 중인 상담사',
    })
    await expect(presence.getByText('협업 상담사')).toBeVisible()
    await sockets[0]!.close()
    await expect(
      presence.getByText(/이전 상담사 목록은 표시하지 않습니다/),
    ).toBeVisible()
    await expect(presence.getByText('협업 상담사')).toHaveCount(0)
    await expect(presence.getByText(/마지막 상태 확인/)).toBeVisible()
    await presence
      .getByRole('button', { name: '연결 다시 확인' })
      .press('Enter')
    await expect(presence.getByText(/다른 상담사가 없습니다/)).toBeVisible()
    expect(sockets).toHaveLength(2)
    expect(
      messages.filter(
        (message) =>
          message.type === 'presence.state' &&
          message.state === 'EDITING_PUBLIC',
      ).length,
    ).toBeGreaterThanOrEqual(2)
    sockets[1]!.send(
      JSON.stringify({
        version: 1,
        type: 'error',
        code: 'FORBIDDEN',
        retryable: false,
      }),
    )
    await expect(presence.getByText('권한 없음')).toBeVisible()
    await expect(presence.getByText(/다음 시도/)).toHaveCount(0)
    await page.screenshot({
      path: testInfo.outputPath(`presence-${width}.png`),
      fullPage: true,
    })
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
    await presence.getByRole('button', { name: '연결 다시 확인' }).click()
    await expect(presence.getByText(/다른 상담사가 없습니다/)).toBeVisible()
    await page.getByRole('button', { name: '패널 닫기', exact: true }).click()
    await expect(editor).toHaveText('연결이 끊겨도 유지할 공개 답변')
    expect(
      messages.every(
        (message) => !('body' in message) && !('content' in message),
      ),
    ).toBe(true)
  })
}
