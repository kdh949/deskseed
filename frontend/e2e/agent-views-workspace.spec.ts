import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

const ticket = {
  ticketNumber: 1042,
  subject: '결제 승인 오류 — 카드 인증 후 주문이 생성되지 않음',
  status: 'OPEN',
  priority: 'URGENT',
  requester: { id: 'customer-1', type: 'CUSTOMER', displayName: '김민수' },
  group: { id: 'payments', name: '결제 지원' },
  assignee: { id: 'agent-other', displayName: '박서연' },
  updatedAt: '2026-08-10T10:02:00Z',
  version: 7,
  isChild: false,
  openChildCount: 0,
  sla: null,
}

const views = [
  ['my-open', '내 open', 4],
  ['unassigned-my-groups', '내 그룹 미배정', 7],
  ['pending', 'Pending', 12],
  ['recently-solved', '최근 solved', 31],
  ['my-child-tasks', '내 child tasks', 2],
].map(([key, name, count]) => ({
  key,
  name,
  ticketCount: count,
  scope: 'SYSTEM',
  categoryPath: ['Views'],
  readScope: 'ALL_TICKETS',
}))

const ticketPage = {
  items: [
    ticket,
    {
      ...ticket,
      ticketNumber: 1041,
      subject: '환불 처리 상태를 확인하고 싶습니다',
      status: 'PENDING',
      priority: 'NORMAL',
      requester: { id: 'customer-2', type: 'CUSTOMER', displayName: '이수진' },
      assignee: null,
    },
    {
      ...ticket,
      ticketNumber: 1039,
      subject: '법인 카드 영수증 재발급 요청',
      priority: 'HIGH',
      requester: { id: 'customer-3', type: 'CUSTOMER', displayName: '오지훈' },
      group: { id: 'billing', name: '청구 지원' },
      assignee: { id: 'agent-3', displayName: '정유나' },
    },
  ],
  nextCursor: 'stable-cursor',
  totalApproximate: null,
  sort: 'updatedAt:desc,ticketNumber:desc',
}

const ticketDetail = {
  ticket,
  comments: [
    {
      id: 'public-1',
      visibility: 'PUBLIC',
      actor: ticket.requester,
      body: '어제 저녁 결제 인증은 완료됐는데 주문 내역이 만들어지지 않았습니다. 같은 카드로 두 번 시도했고 모두 동일했습니다.',
      createdAt: '2026-08-10T09:00:00Z',
      source: 'WEB',
      attachments: [],
    },
    {
      id: 'internal-1',
      visibility: 'INTERNAL',
      actor: { id: 'agent-other', type: 'STAFF', displayName: '박서연' },
      body: 'PG 승인 번호는 확인됨. 주문 생성 로그와 멱등키 처리 여부를 다음 담당자가 확인해야 합니다.',
      createdAt: '2026-08-10T09:28:00Z',
      source: 'STAFF_WEB',
      attachments: [],
    },
    {
      id: 'public-2',
      visibility: 'PUBLIC',
      actor: { id: 'agent-other', type: 'STAFF', displayName: '박서연' },
      body: '확인 중이며 중복 결제는 발생하지 않았습니다. 처리 결과를 이 티켓으로 안내드리겠습니다.',
      createdAt: '2026-08-10T09:44:00Z',
      source: 'STAFF_WEB',
      attachments: [],
    },
  ],
  capabilities: ['READ'],
  assignmentOptions: {
    groups: [
      {
        id: 'payments',
        name: '결제 지원',
        members: [{ id: 'agent-other', displayName: '박서연' }],
      },
    ],
  },
  context: {
    customer: {
      id: 'customer-1',
      displayName: '김민수',
      email: 'minsu.kim@example.com',
    },
    parent: null,
    children: [],
    externalReferences: [],
  },
  history: [
    {
      id: 'history-1',
      eventType: 'TICKET_CREATED',
      actor: ticket.requester,
      occurredAt: '2026-08-10T09:00:00Z',
    },
    {
      id: 'history-2',
      eventType: 'ASSIGNEE_CHANGED',
      actor: { id: 'agent-other', type: 'STAFF', displayName: '박서연' },
      occurredAt: '2026-08-10T09:24:00Z',
    },
  ],
  warnings: [],
}

async function mockAgentReadApi(page: Page) {
  const detailHeaders: Array<Record<string, string>> = []
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/v1/agent/me') {
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
    if (url.pathname === '/api/v1/agent/views') {
      return route.fulfill({ status: 200, json: views })
    }
    if (
      url.pathname.includes('/api/v1/agent/views/') &&
      url.pathname.endsWith('/tickets')
    ) {
      return route.fulfill({ status: 200, json: ticketPage })
    }
    if (url.pathname === '/api/v1/agent/tickets/1042') {
      detailHeaders.push(request.headers())
      return route.fulfill({ status: 200, json: ticketDetail })
    }
    return route.abort()
  })
  return detailHeaders
}

async function expectNoAxeViolations(page: Page) {
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
}

for (const width of [1280, 1440, 1920]) {
  test(`Views와 3-panel Workspace ${width}px 시각 회귀`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    const detailHeaders = await mockAgentReadApi(page)
    await page.goto('/agent/views/my-open')

    await expect(page.getByRole('heading', { name: '내 open' })).toBeVisible()
    await expect(
      page.getByRole('table', { name: '내 open 티켓' }),
    ).toBeVisible()
    await expect(page).toHaveScreenshot(`agent-views-${width}.png`, {
      fullPage: true,
    })
    await expectNoAxeViolations(page)

    const ticketLink = page.getByRole('link', { name: /#1042 .* 열기/ })
    await page.locator('body').click()
    for (let index = 0; index < 20; index += 1) {
      await page.keyboard.press('Tab')
      if (
        await ticketLink.evaluate(
          (element) => element === document.activeElement,
        )
      )
        break
    }
    await expect(ticketLink).toBeFocused()
    await page.keyboard.press('Enter')

    await expect(page).toHaveURL(/\/agent\/tickets\/1042$/)
    await expect(page.getByRole('region', { name: '대화' })).toContainText(
      '내부 메모',
    )
    expect(detailHeaders).toHaveLength(1)
    expect(detailHeaders[0]['x-deskseed-read-intent']).toBe('NAVIGATION')
    expect(detailHeaders[0]['x-interaction-id']).toBeTruthy()

    await page.getByRole('button', { name: '티켓 새로고침' }).click()
    await expect.poll(() => detailHeaders.length).toBe(2)
    expect(detailHeaders[1]['x-interaction-id']).toBe(
      detailHeaders[0]['x-interaction-id'],
    )
    await expect(page).toHaveScreenshot(`agent-ticket-workspace-${width}.png`, {
      fullPage: true,
    })
    await expectNoAxeViolations(page)
  })
}

test('직접 URL과 키보드 패널 조절은 사용자별 선호도를 유지한다', async ({
  page,
}) => {
  await mockAgentReadApi(page)
  await page.goto('/agent/tickets/1042')
  await expect(
    page.getByRole('heading', { name: ticket.subject }),
  ).toBeVisible()

  const separator = page.getByRole('separator', { name: '속성 패널 너비 조절' })
  await separator.focus()
  await page.keyboard.press('ArrowRight')
  await expect(separator).toHaveAttribute('aria-valuenow', '316')
  await page.getByRole('button', { name: '컨텍스트 패널 접기' }).click()
  await expect(page.getByRole('region', { name: '티켓 컨텍스트' })).toHaveCount(
    0,
  )
  expect(
    await page.evaluate(() =>
      localStorage.getItem('deskseed:agent:agent-e2e:workspace-panels:v1'),
    ),
  ).toContain('"contextCollapsed":true')
})
