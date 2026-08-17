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
    ...[
      ['박정훈', '배송이 지연되고 있어요', 'PENDING', 'NORMAL', '배송 지원'],
      ['최예린', '비밀번호 재설정이 안 돼요', 'OPEN', 'NORMAL', '계정 관리'],
      ['정우성', '로그인 시 오류가 발생해요', 'NEW', 'NORMAL', '기술 지원'],
      [
        '한지영',
        '재고가 있는지 확인 부탁드려요',
        'PENDING',
        'HIGH',
        '상품 문의',
      ],
      ['오태준', '쿠폰 적용이 안 됩니다', 'OPEN', 'NORMAL', '결제 지원'],
      ['김하늘', '배송지 주소 변경 요청', 'ON_HOLD', 'LOW', '배송 지원'],
      ['이준호', '환불이 언제 처리되나요?', 'PENDING', 'HIGH', '환불 지원'],
      ['강서연', '계정이 잠겼어요', 'NEW', 'NORMAL', '계정 관리'],
      ['배민재', '앱에서 자꾸 로그아웃돼요', 'OPEN', 'LOW', '기술 지원'],
      ['유다은', '상품 상세 정보가 궁금해요', 'PENDING', 'NORMAL', '상품 문의'],
      ['조민규', '배송 일정 변경 가능한가요?', 'ON_HOLD', 'LOW', '배송 지원'],
      ['심규리', '결제 수단 변경 방법 문의', 'OPEN', 'NORMAL', '결제 지원'],
      ['황도윤', '부분 환불이 가능한가요?', 'PENDING', 'NORMAL', '환불 지원'],
    ].map(([displayName, subject, status, priority, group], index) => ({
      ...ticket,
      ticketNumber: 1040 - index,
      subject,
      status,
      priority,
      requester: {
        id: `customer-${index + 3}`,
        type: 'CUSTOMER',
        displayName,
      },
      group: { id: `group-${index + 3}`, name: group },
      assignee:
        index % 3 === 0
          ? null
          : {
              id: `agent-${index + 3}`,
              displayName: ['이지은', '현우 이', '소연 조'][index % 3],
            },
      updatedAt: `2026-08-10T${String(9 - Math.floor(index / 2)).padStart(2, '0')}:${index % 2 ? '35' : '05'}:00Z`,
    })),
  ],
  nextCursor: 'next-page',
  totalApproximate: 28,
  sort: 'updatedAt:desc,ticketNumber:desc',
}

const ticketDetail = {
  ticket,
  comments: [
    {
      id: 'public-1',
      visibility: 'PUBLIC',
      actor: ticket.requester,
      body: '어제 저녁 결제 인증은 완료됐는데 주문 내역이 만들어지지 않았습니다.',
      createdAt: '2026-08-10T09:00:00Z',
      source: 'WEB',
      attachments: [],
    },
    {
      id: 'internal-1',
      visibility: 'INTERNAL',
      actor: { id: 'agent-other', type: 'STAFF', displayName: '박서연' },
      body: 'PG 승인 번호는 확인됨. 주문 생성 로그를 다음 담당자가 확인해야 합니다.',
      createdAt: '2026-08-10T09:28:00Z',
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
    externalReferenceCount: 0,
  },
  history: [],
  warnings: [],
}

const viewContract = {
  active: true,
  description: '',
  definitionVersion: 1,
  orderVersion: 1,
  conditions: {
    version: 1,
    all: [{ field: 'STATUS', operator: 'LESS_THAN_SOLVED', values: [] }],
    any: [],
  },
  columns: ['TICKET_NUMBER', 'SUBJECT', 'STATUS'],
  sort: 'updatedAt:desc,ticketNumber:desc',
  ticketCountState: 'EXACT',
  ticketCountAsOf: '2026-08-18T03:04:05Z',
  readScope: 'ALL_TICKETS',
  createdAt: '2026-08-10T00:00:00Z',
  updatedAt: '2026-08-10T00:00:00Z',
} as const

async function mockAgentReadApi(page: Page) {
  const detailHeaders: Array<Record<string, string>> = []
  const viewRequestUrls: string[] = []
  const savedViewWrites: unknown[] = []
  let serverViews = [
    ['my-open', '내 open', 'SYSTEM', 28],
    ['unassigned-my-groups', '미배정 티켓', 'SYSTEM', 16],
    ['all-open', '모든 미해결 티켓', 'SYSTEM', 142],
    ['urgent', '긴급 티켓', 'SHARED', 12],
    ['today-updated', '오늘 업데이트된 티켓', 'SHARED', 36],
    ['recently-solved', '최근 해결된 티켓', 'SYSTEM', 24],
    ['customer-reply-pending', '고객 응답 대기', 'SHARED', 18],
    ['created-by-me', '내가 생성한 티켓', 'PERSONAL', 7],
    ['following', '내가 팔로우 중인 티켓', 'PERSONAL', 5],
    ['drafts', '임시 보관함', 'PERSONAL', 3],
  ].map(([key, name, scope, ticketCount], index) => ({
    ...viewContract,
    id: `00000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
    key,
    name,
    scope,
    ownerStaffId:
      scope === 'PERSONAL' ? '00000000-0000-4000-8000-000000000099' : null,
    categoryPath: ['Views'],
    ticketCount,
  }))
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/v1/agent/me') {
      return route.fulfill({
        status: 200,
        json: {
          id: 'agent-e2e',
          email: 'agent@example.com',
          displayName: 'Mina Park',
          role: 'AGENT',
          capabilities: ['AGENT_WORKSPACE'],
        },
      })
    }
    if (url.pathname === '/api/v1/agent/csrf') {
      return route.fulfill({
        status: 200,
        json: { token: 'csrf', headerName: 'X-CSRF-TOKEN' },
      })
    }
    if (url.pathname === '/api/v1/agent/assignment-options') {
      return route.fulfill({ status: 200, json: { groups: [] } })
    }
    if (url.pathname === '/api/v1/agent/views' && request.method() === 'POST') {
      const body = request.postDataJSON()
      savedViewWrites.push(body)
      const created = {
        ...viewContract,
        ...body,
        id: '00000000-0000-4000-8000-000000000011',
        key: 'review-only',
        ownerStaffId: '00000000-0000-4000-8000-000000000099',
        categoryPath: ['Views'],
        ticketCount: null,
        ticketCountState: 'OMITTED_VISIBLE_LIMIT',
        ticketCountAsOf: null,
      }
      serverViews = [...serverViews, created]
      return route.fulfill({ status: 201, json: created })
    }
    if (url.pathname === '/api/v1/agent/views') {
      return route.fulfill({
        status: 200,
        json: serverViews,
      })
    }
    if (
      url.pathname.includes('/api/v1/agent/views/') &&
      url.pathname.endsWith('/tickets')
    ) {
      viewRequestUrls.push(url.toString())
      return route.fulfill({ status: 200, json: ticketPage })
    }
    if (url.pathname === '/api/v1/agent/tickets/1042') {
      detailHeaders.push(request.headers())
      return route.fulfill({ status: 200, json: ticketDetail })
    }
    return route.abort()
  })
  return { detailHeaders, savedViewWrites, viewRequestUrls }
}

async function expectNoAxeViolations(page: Page) {
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
}

for (const viewport of [
  { width: 1280, height: 900 },
  { width: 1440, height: 900 },
  { width: 1920, height: 900 },
]) {
  test(`canonical Queue와 Workspace ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport)
    const { detailHeaders } = await mockAgentReadApi(page)
    await page.goto('/agent/views/my-open')

    await expect(page.getByRole('heading', { name: '내 티켓' })).toBeVisible()
    await expect(
      page.getByRole('table', { name: '내 티켓 티켓' }),
    ).toBeVisible()
    await expect(
      page.getByRole('navigation', { name: '상담사 전역 탐색' }),
    ).toBeVisible()
    await expect(page).toHaveScreenshot(
      `frontend-system-view-queue-${viewport.width}.png`,
      { fullPage: true },
    )
    await expectNoAxeViolations(page)

    await page.getByRole('link', { name: /티켓 #1042/ }).click()
    await expect(page).toHaveURL(/\/agent\/tickets\/1042$/)
    await expect(
      page.getByRole('main', { name: '티켓 #1042 작업 공간' }),
    ).toBeVisible()
    await expect(
      page.getByText('INTERNAL · 직원 전용', { exact: true }).first(),
    ).toBeVisible()
    expect(detailHeaders).toHaveLength(1)
    expect(detailHeaders[0]?.['x-deskseed-read-intent']).toBe('NAVIGATION')
    expect(detailHeaders[0]?.['x-interaction-id']).toBeTruthy()
    await expect(page).toHaveScreenshot(
      `frontend-system-workspace-${viewport.width}.png`,
      { fullPage: true },
    )
    await expectNoAxeViolations(page)
  })
}

test('Queue filters and keyboard selection keep their product behavior', async ({
  page,
}) => {
  const { viewRequestUrls } = await mockAgentReadApi(page)
  await page.goto('/agent/views/my-open?status=OPEN')
  await page.getByLabel('상태 필터').selectOption('PENDING')
  await expect.poll(() => viewRequestUrls.at(-1)).toContain('status=PENDING')
  await page.getByLabel('우선순위 필터').selectOption('URGENT')
  await expect.poll(() => viewRequestUrls.at(-1)).toContain('status=PENDING')
  await expect.poll(() => viewRequestUrls.at(-1)).toContain('priority=URGENT')
  await expect(page.getByLabel('채널 필터')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'View 저장' })).toHaveCount(0)

  const first = page.getByRole('link', { name: /티켓 #1042/ })
  const second = page.getByRole('link', { name: /티켓 #1041/ })
  await first.focus()
  await page.keyboard.press('ArrowDown')
  await expect(second).toBeFocused()
  await page.keyboard.press('Space')
  await expect(page.getByRole('region', { name: '선택된 티켓' })).toContainText(
    '1개 선택됨',
  )
})

test('Personal view configuration persists on the server and returns focus to its trigger', async ({
  page,
}) => {
  const { savedViewWrites } = await mockAgentReadApi(page)
  await page.goto('/agent/views/my-open')
  await expect(page.getByRole('table', { name: '내 티켓 티켓' })).toBeVisible()
  const createButton = page.getByRole('button', { name: '새 보기 만들기' })
  await createButton.click()
  await expect(
    page.getByRole('dialog', { name: '새 보기 만들기' }),
  ).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(createButton).toBeFocused()

  await createButton.click()
  const dialog = page.getByRole('dialog', { name: '새 보기 만들기' })
  await dialog.getByLabel('보기 이름').fill('검토 전용 보기')
  await dialog.getByLabel('설명').fill('결제 문의 검토가 필요한 티켓')
  await dialog.getByRole('button', { name: '보기 만들기', exact: true }).click()

  await expect(page.getByRole('link', { name: '검토 전용 보기' })).toBeVisible()
  expect(savedViewWrites).toHaveLength(1)
  expect(savedViewWrites[0]).toMatchObject({
    name: '검토 전용 보기',
    description: '결제 문의 검토가 필요한 티켓',
    scope: 'PERSONAL',
  })
  await page.reload()
  await expect(page.getByRole('link', { name: '검토 전용 보기' })).toBeVisible()
  await expect(page.getByText('결제 문의 검토가 필요한 티켓')).toBeVisible()
  await expectNoAxeViolations(page)
})

test('Workspace refresh reuses its navigation interaction without creating a second view event', async ({
  page,
}) => {
  const { detailHeaders } = await mockAgentReadApi(page)
  await page.goto('/agent/tickets/1042')
  await page.getByRole('button', { name: '최신 정보 새로고침' }).click()
  await expect.poll(() => detailHeaders.length).toBe(2)
  expect(detailHeaders[1]?.['x-interaction-id']).toBe(
    detailHeaders[0]?.['x-interaction-id'],
  )
  expect(detailHeaders[1]?.['x-deskseed-read-intent']).toBe('BACKGROUND')
})
