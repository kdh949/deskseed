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
    externalReferences: [],
  },
  history: [],
  warnings: [],
}

async function mockAgentReadApi(page: Page) {
  const detailHeaders: Array<Record<string, string>> = []
  const viewRequestUrls: string[] = []
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
    if (url.pathname === '/api/v1/agent/views') {
      return route.fulfill({
        status: 200,
        json: [
          {
            key: 'my-open',
            name: '내 open',
            ticketCount: 28,
            scope: 'SYSTEM',
            categoryPath: ['Views'],
            readScope: 'ALL_TICKETS',
          },
          {
            key: 'unassigned-my-groups',
            name: '미배정 티켓',
            ticketCount: 16,
            scope: 'SYSTEM',
            categoryPath: ['Views'],
            readScope: 'ALL_TICKETS',
          },
          {
            key: 'all-open',
            name: '모든 미해결 티켓',
            ticketCount: 142,
            scope: 'SYSTEM',
            categoryPath: ['Views'],
            readScope: 'ALL_TICKETS',
          },
          {
            key: 'urgent',
            name: '긴급 티켓',
            ticketCount: 12,
            scope: 'SHARED',
            categoryPath: ['Views'],
            readScope: 'ALL_TICKETS',
          },
          {
            key: 'today-updated',
            name: '오늘 업데이트된 티켓',
            ticketCount: 36,
            scope: 'SHARED',
            categoryPath: ['Views'],
            readScope: 'ALL_TICKETS',
          },
          {
            key: 'recently-solved',
            name: '최근 해결된 티켓',
            ticketCount: 24,
            scope: 'SYSTEM',
            categoryPath: ['Views'],
            readScope: 'ALL_TICKETS',
          },
          {
            key: 'customer-reply-pending',
            name: '고객 응답 대기',
            ticketCount: 18,
            scope: 'SHARED',
            categoryPath: ['Views'],
            readScope: 'ALL_TICKETS',
          },
          {
            key: 'created-by-me',
            name: '내가 생성한 티켓',
            ticketCount: 7,
            scope: 'PERSONAL',
            categoryPath: ['Views'],
            readScope: 'ALL_TICKETS',
          },
          {
            key: 'following',
            name: '내가 팔로우 중인 티켓',
            ticketCount: 5,
            scope: 'PERSONAL',
            categoryPath: ['Views'],
            readScope: 'ALL_TICKETS',
          },
          {
            key: 'drafts',
            name: '임시 보관함',
            ticketCount: 3,
            scope: 'PERSONAL',
            categoryPath: ['Views'],
            readScope: 'ALL_TICKETS',
          },
        ],
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
  return { detailHeaders, viewRequestUrls }
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
      page.getByText('INTERNAL', { exact: true }).first(),
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

test('Personal view configuration stays local and returns focus to its trigger', async ({
  page,
}) => {
  const { viewRequestUrls } = await mockAgentReadApi(page)
  await page.goto('/agent/views/my-open')
  await expect(page.getByRole('table', { name: '내 티켓 티켓' })).toBeVisible()
  const createButton = page.getByRole('button', { name: '새 보기 만들기' })
  const initialTicketRequestCount = viewRequestUrls.length

  await createButton.click()
  await expect(
    page.getByRole('dialog', { name: '새 개인 보기 만들기' }),
  ).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(createButton).toBeFocused()

  await createButton.click()
  const dialog = page.getByRole('dialog', { name: '새 개인 보기 만들기' })
  await dialog.getByLabel('보기 이름').fill('검토 전용 보기')
  await dialog.getByRole('button', { name: '중요 아이콘 선택' }).click()
  await dialog.getByRole('button', { name: '보기 만들기', exact: true }).click()

  await expect(
    page.getByRole('heading', { name: '아직 연결된 티켓 조건이 없습니다.' }),
  ).toBeVisible()
  await expect(page.getByRole('link', { name: '검토 전용 보기' })).toBeVisible()
  expect(viewRequestUrls).toHaveLength(initialTicketRequestCount)
  await expectNoAxeViolations(page)
})

test('Workspace refresh reuses its navigation interaction and panel controls work', async ({
  page,
}) => {
  const { detailHeaders } = await mockAgentReadApi(page)
  await page.goto('/agent/tickets/1042')
  await page.getByRole('button', { name: '티켓 새로고침' }).click()
  await expect.poll(() => detailHeaders.length).toBe(2)
  expect(detailHeaders[1]?.['x-interaction-id']).toBe(
    detailHeaders[0]?.['x-interaction-id'],
  )
  expect(detailHeaders[1]?.['x-deskseed-read-intent']).toBe('BACKGROUND')
  await page.getByRole('button', { name: '티켓 속성 접기' }).click()
  await expect(
    page.getByRole('button', { name: '티켓 속성 펼치기' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '고객 맥락 열기' }).click()
  await expect(
    page.getByRole('button', { name: '고객 맥락 닫기' }),
  ).toBeVisible()
})
