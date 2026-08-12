import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route } from '@playwright/test'

const CSRF_TOKEN = 'external-reference-csrf'
const STAFF_ID = '10000000-0000-4000-8000-000000000001'
const SYSTEM_ID = '20000000-0000-4000-8000-000000000001'
const REFERENCE_ID = '30000000-0000-4000-8000-000000000001'

const system = {
  id: SYSTEM_ID,
  systemKey: 'shop-order',
  displayName: '주문 운영',
  status: 'ACTIVE',
  allowedHostnames: ['admin.shop.example'],
  createdAt: '2026-08-12T00:00:00Z',
  updatedAt: '2026-08-12T00:00:00Z',
  version: 0,
}

function reference(id = REFERENCE_ID, externalId = 'ORDER-2026-0812-100') {
  return {
    id,
    system,
    objectType: 'ORDER',
    externalId,
    displayLabel: '주문 #100 · 결제 완료',
    linkState: 'AVAILABLE',
    safeDeepLink: `https://admin.shop.example/orders/${externalId}`,
    metadata: {
      status: 'paid',
      storeName: 'Deskseed 성수점',
      amountDisplay: '₩129,000',
    },
    metadataObservedAt: '2026-08-12T01:30:00Z',
    createdBy: { actorId: STAFF_ID, displayName: '한서윤' },
    createdAt: '2026-08-12T01:31:00Z',
  }
}

const ticket = {
  ticketNumber: 1042,
  subject: '결제 완료 후 주문 내역이 보이지 않습니다',
  status: 'OPEN',
  priority: 'URGENT',
  requester: { id: 'customer-1', type: 'CUSTOMER', displayName: '김민수' },
  group: { id: 'payments', name: '결제 지원' },
  assignee: { id: STAFF_ID, displayName: '한서윤' },
  updatedAt: '2026-08-12T01:32:00Z',
  version: 7,
  isChild: false,
  openChildCount: 0,
  sla: null,
}

function ticketDetail(version: number) {
  return {
    ticket: { ...ticket, version },
    comments: [
      {
        id: 'public-1',
        visibility: 'PUBLIC',
        actor: ticket.requester,
        body: '결제는 완료됐는데 주문 내역에서 찾을 수 없습니다.',
        createdAt: '2026-08-12T01:00:00Z',
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
          members: [{ id: STAFF_ID, displayName: '한서윤' }],
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

async function installWorkspaceApi(page: Page) {
  let version = 7
  let references = [reference()]
  const referenceReads: Record<string, string>[] = []
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/v1/agent/me') {
      return route.fulfill({
        status: 200,
        json: {
          id: STAFF_ID,
          email: 'agent@example.com',
          displayName: '한서윤',
          role: 'ADMIN',
          capabilities: [
            'ADMIN_MANAGE',
            'AGENT_WORKSPACE',
            'integration:systems:manage',
          ],
        },
      })
    }
    if (url.pathname === '/api/v1/agent/csrf') {
      return route.fulfill({
        status: 200,
        json: { token: CSRF_TOKEN, headerName: 'X-CSRF-TOKEN' },
      })
    }
    if (url.pathname === '/api/v1/agent/tickets/1042') {
      return route.fulfill({ status: 200, json: ticketDetail(version) })
    }
    if (
      url.pathname === '/api/v1/agent/tickets/1042/external-references' &&
      request.method() === 'GET'
    ) {
      referenceReads.push(request.headers())
      return route.fulfill({
        status: 200,
        json: {
          ticketVersion: version,
          canManage: true,
          availableSystems: [system],
          items: references,
        },
      })
    }
    if (
      url.pathname === '/api/v1/agent/tickets/1042/external-references' &&
      request.method() === 'POST'
    ) {
      requireMutation(route, version)
      const body = request.postDataJSON()
      expect(body.metadata).toEqual({ status: 'paid' })
      version += 1
      const created = reference(
        '40000000-0000-4000-8000-000000000001',
        body.externalId,
      )
      references = [...references, created]
      return route.fulfill({
        status: 201,
        json: { ticketVersion: version, reference: created },
      })
    }
    if (
      url.pathname.startsWith(
        '/api/v1/agent/tickets/1042/external-references/',
      ) &&
      request.method() === 'DELETE'
    ) {
      requireMutation(route, version)
      const removedReferenceId = url.pathname.split('/').at(-1)!
      references = references.filter((item) => item.id !== removedReferenceId)
      version += 1
      return route.fulfill({
        status: 200,
        json: { ticketVersion: version, removedReferenceId },
      })
    }
    return route.abort()
  })
  return { referenceReads, getReferences: () => references }
}

function requireMutation(route: Route, version: number) {
  const headers = route.request().headers()
  expect(headers['x-csrf-token']).toBe(CSRF_TOKEN)
  expect(headers['if-match']).toBe(`"${version}"`)
}

for (const width of [1280, 1440, 1920]) {
  test(`Workspace External context ${width}px visual and accessibility`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 })
    const server = await installWorkspaceApi(page)
    await page.goto('/agent/tickets/1042')
    await page.getByRole('tab', { name: 'External' }).click()
    await expect(page.getByText('주문 #100 · 결제 완료')).toBeVisible()
    const link = page.getByRole('link', { name: '원본 새 창에서 열기' })
    await expect(link).toHaveAttribute('target', '_blank')
    await expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(server.referenceReads).toHaveLength(1)
    expect(server.referenceReads[0]!['x-interaction-id']).toBeTruthy()
    await expect(page).toHaveScreenshot(
      `external-reference-context-${width}.png`,
      { fullPage: true },
    )
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  })
}

test('external create and remove preserve PUBLIC and INTERNAL drafts', async ({
  page,
}) => {
  const server = await installWorkspaceApi(page)
  await page.goto('/agent/tickets/1042')
  await page
    .getByRole('textbox', { name: '공개 답변' })
    .fill('보존되어야 하는 공개 초안')
  await page.getByRole('tab', { name: '내부 메모' }).click()
  await page
    .getByRole('textbox', { name: '내부 메모' })
    .fill('보존되어야 하는 내부 초안')

  await page.getByRole('tab', { name: 'External' }).click()
  await page.getByText('외부 참조 연결').click()
  await page.getByLabel('외부 ID').fill('ORDER-2026-0812-200')
  await page.getByLabel('표시 이름').fill('주문 #200')
  await page
    .getByLabel('HTTPS 원본 링크')
    .fill('https://admin.shop.example/orders/ORDER-2026-0812-200')
  await page.getByRole('textbox', { name: '상태' }).fill('paid')
  await page.getByRole('button', { name: '참조 연결' }).click()
  await expect(page.getByText('외부 참조를 연결했습니다.')).toBeVisible()
  await expect.poll(() => server.getReferences().length).toBe(2)

  await page.getByRole('tab', { name: '내부 메모' }).click()
  await expect(page.getByRole('textbox', { name: '내부 메모' })).toHaveValue(
    '보존되어야 하는 내부 초안',
  )
  await page.getByRole('tab', { name: '공개 답변' }).click()
  await expect(page.getByRole('textbox', { name: '공개 답변' })).toHaveValue(
    '보존되어야 하는 공개 초안',
  )

  await page.getByRole('tab', { name: 'External' }).click()
  await page.getByRole('button', { name: '연결 해제' }).first().click()
  await page.getByRole('button', { name: '연결 해제 확인' }).click()
  await expect(page.getByText(/외부 원본은 변경하지 않았습니다/)).toBeVisible()
  await expect.poll(() => server.getReferences().length).toBe(1)
})

test('admin external system registry handles create update and keyboard dialog', async ({
  page,
}) => {
  let systems = [system]
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/v1/agent/me') {
      return route.fulfill({
        status: 200,
        json: {
          id: STAFF_ID,
          email: 'admin@example.com',
          displayName: '관리자',
          role: 'ADMIN',
          capabilities: ['ADMIN_MANAGE', 'integration:systems:manage'],
        },
      })
    }
    if (url.pathname === '/api/v1/agent/csrf') {
      return route.fulfill({
        status: 200,
        json: { token: CSRF_TOKEN, headerName: 'X-CSRF-TOKEN' },
      })
    }
    if (
      url.pathname === '/api/v1/admin/external-systems' &&
      request.method() === 'GET'
    ) {
      return route.fulfill({ status: 200, json: systems })
    }
    if (
      url.pathname === '/api/v1/admin/external-systems' &&
      request.method() === 'POST'
    ) {
      expect(request.headers()['x-csrf-token']).toBe(CSRF_TOKEN)
      const body = request.postDataJSON()
      systems = [
        ...systems,
        {
          ...system,
          id: '50000000-0000-4000-8000-000000000001',
          systemKey: body.systemKey,
          displayName: body.displayName,
          allowedHostnames: body.allowedHostnames,
        },
      ]
      return route.fulfill({ status: 201, json: systems.at(-1) })
    }
    if (
      url.pathname.startsWith('/api/v1/admin/external-systems/') &&
      request.method() === 'PUT'
    ) {
      expect(request.headers()['x-csrf-token']).toBe(CSRF_TOKEN)
      expect(request.headers()['if-match']).toBe('"0"')
      const body = request.postDataJSON()
      systems = systems.map((item) =>
        item.id === SYSTEM_ID ? { ...item, ...body, version: 1 } : item,
      )
      return route.fulfill({ status: 200, json: systems[0] })
    }
    return route.abort()
  })

  await page.goto('/integrations/systems')
  await expect(
    page.getByRole('heading', { name: '외부 시스템', exact: true }),
  ).toBeVisible()
  await page.getByLabel(/System key/).fill('shop-payment')
  await page.getByLabel('표시 이름').fill('결제 운영')
  await page.getByLabel(/허용 HTTPS hostname ·/).fill('pay.shop.example')
  await page.getByRole('button', { name: '외부 시스템 등록' }).click()
  await expect(page.getByText('결제 운영')).toBeVisible()

  await page.getByRole('button', { name: '정책 편집' }).first().click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByRole('dialog').getByLabel('표시 이름')).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '정책 편집' }).first(),
  ).toBeFocused()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  await expect(page).toHaveScreenshot('external-system-admin.png', {
    fullPage: true,
  })
})

for (const role of ['AGENT', 'SECURITY_AUDITOR'] as const) {
  test(`${role} direct external-system URL is denied before API listing`, async ({
    page,
  }) => {
    let calls = 0
    await page.route('**/api/v1/agent/me', (route) =>
      route.fulfill({
        status: 200,
        json: {
          id: STAFF_ID,
          email: `${role.toLowerCase()}@example.com`,
          displayName: role,
          role,
          capabilities:
            role === 'AGENT' ? ['AGENT_WORKSPACE'] : ['audit:activity:read'],
        },
      }),
    )
    await page.route('**/api/v1/admin/external-systems**', (route) => {
      calls += 1
      return route.fulfill({ status: 403 })
    })
    await page.goto('/integrations/systems')
    await expect(
      page.getByRole('heading', {
        name: '외부 시스템 관리 권한이 필요합니다.',
      }),
    ).toBeVisible()
    expect(calls).toBe(0)
  })
}
