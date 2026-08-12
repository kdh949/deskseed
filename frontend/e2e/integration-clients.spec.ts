import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route } from '@playwright/test'

const CSRF_TOKEN = 'integration-csrf'
const ADMIN_ID = '10000000-0000-4000-8000-000000000001'
const CLIENT_ID = '20000000-0000-4000-8000-000000000001'

function requireCsrf(route: Route) {
  expect(route.request().headers()['x-csrf-token']).toBe(CSRF_TOKEN)
}

function client(status = 'ACTIVE') {
  return {
    id: CLIENT_ID,
    name: '주문 운영 시스템',
    description: '주문 어드민에서 사용하는 machine principal',
    status,
    scopes: ['tickets:read', 'tickets:update'],
    resourceConstraints: {
      allowedGroupIds: null,
      allowedTicketKinds: ['CUSTOMER_REQUEST'],
      allowedFields: ['status', 'priority'],
      ipAllowlist: ['10.20.0.0/16'],
    },
    credentials: [
      {
        id: '30000000-0000-4000-8000-000000000001',
        sequence: 1,
        publicKeyId: 'publicKeyId12345',
        status: status === 'REVOKED' ? 'REVOKED' : 'ACTIVE',
        expiresAt: '2026-12-31T00:00:00Z',
        overlapExpiresAt: null,
        createdAt: '2026-08-12T00:00:00Z',
        revokedAt: status === 'REVOKED' ? '2026-08-12T01:00:00Z' : null,
        lastUsedAt: null,
        lastUsedIp: null,
      },
    ],
    expiresAt: '2026-12-31T00:00:00Z',
    lastUsedAt: null,
    lastUsedIp: null,
    createdAt: '2026-08-12T00:00:00Z',
  }
}

async function stubSession(
  page: Page,
  role: 'ADMIN' | 'AGENT' | 'SECURITY_AUDITOR',
) {
  await page.route('**/api/v1/agent/me', (route) =>
    route.fulfill({
      status: 200,
      json: {
        id: ADMIN_ID,
        email: `${role.toLowerCase()}@example.com`,
        displayName: role,
        role,
        capabilities:
          role === 'ADMIN'
            ? ['ADMIN_MANAGE', 'AGENT_WORKSPACE', 'integration:clients:manage']
            : role === 'AGENT'
              ? ['AGENT_WORKSPACE']
              : ['audit:activity:read'],
      },
    }),
  )
}

for (const role of ['AGENT', 'SECURITY_AUDITOR'] as const) {
  test(`${role} direct integration client URL is denied without listing secrets`, async ({
    page,
  }) => {
    await stubSession(page, role)
    let apiCalls = 0
    await page.route('**/api/v1/admin/integration-clients**', (route) => {
      apiCalls += 1
      return route.fulfill({
        status: 403,
        json: { type: '/problems/staff-access-denied', status: 403 },
      })
    })
    await page.goto('/integrations/clients')
    await expect(page).toHaveURL(/\/integrations\/clients$/)
    await expect(
      page.getByRole('heading', {
        name: '연동 클라이언트 관리 권한이 필요합니다.',
      }),
    ).toBeVisible()
    await expect(
      page.getByRole('heading', { name: 'API 클라이언트' }),
    ).toHaveCount(0)
    expect(apiCalls).toBe(0)
  })
}

test('admin manages the client lifecycle with one-time secrets and no browser storage', async ({
  page,
}) => {
  await stubSession(page, 'ADMIN')
  let clients = [client()]
  const issuedKey = `dsk_live_newPublicKey1234.${'A'.repeat(43)}`
  const rotatedKey = `dsk_live_rotatedKeyId1234.${'B'.repeat(43)}`
  await page.route('**/api/v1/agent/csrf', (route) =>
    route.fulfill({
      status: 200,
      json: { token: CSRF_TOKEN, headerName: 'X-CSRF-TOKEN' },
    }),
  )
  await page.route('**/api/v1/admin/integration-clients**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (request.method() === 'GET') {
      return route.fulfill({
        status: 200,
        headers: {
          'X-Page-Number': '0',
          'X-Page-Size': '20',
          'X-Total-Count': clients.length.toString(),
          'X-Total-Pages': '1',
        },
        json: clients,
      })
    }
    requireCsrf(route)
    if (url.pathname.endsWith('/rotate')) {
      const body = request.postDataJSON()
      expect(body.overlapSeconds).toBe(86_400)
      return route.fulfill({
        status: 200,
        json: {
          client: clients[0],
          credential: clients[0]!.credentials[0],
          apiKey: rotatedKey,
        },
      })
    }
    if (url.pathname.endsWith('/disable') || url.pathname.endsWith('/revoke')) {
      const nextStatus = url.pathname.endsWith('/disable')
        ? 'DISABLED'
        : 'REVOKED'
      const targetId = url.pathname.split('/').at(-2)
      clients = clients.map((item) =>
        item.id === targetId
          ? {
              ...item,
              status: nextStatus,
              credentials:
                nextStatus === 'REVOKED'
                  ? item.credentials.map((credential) => ({
                      ...credential,
                      status: 'REVOKED',
                      revokedAt: '2026-08-12T01:00:00Z',
                    }))
                  : item.credentials,
            }
          : item,
      )
      return route.fulfill({
        status: 200,
        json: clients.find((item) => item.id === targetId),
      })
    }
    const body = request.postDataJSON()
    expect(body.scopes).toEqual(['tickets:read', 'tickets:update'])
    expect(body.resourceConstraints.ipAllowlist).toEqual(['10.40.0.0/16'])
    const created = {
      ...client(),
      id: '40000000-0000-4000-8000-000000000001',
      name: body.name,
    }
    clients = [...clients, created]
    return route.fulfill({
      status: 201,
      json: {
        client: created,
        credential: created.credentials[0],
        apiKey: issuedKey,
      },
    })
  })

  await page.goto('/integrations/clients')
  await expect(
    page.getByRole('heading', { name: 'API 클라이언트', exact: true }),
  ).toBeVisible()
  await expect(page.getByText('주문 운영 시스템')).toBeVisible()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await page.getByLabel('이름').fill('결제 운영 시스템')
  await page.getByLabel('설명').fill('결제 어드민 연동')
  await page.getByLabel(/티켓 필드 수정/).check()
  await page.getByLabel(/IP\/CIDR allowlist/).fill('10.40.0.0/16')
  await page.getByRole('button', { name: '클라이언트와 key 발급' }).click()
  const secretInput = page.getByLabel('발급된 API key')
  await expect(secretInput).toHaveValue(issuedKey)
  const storage = await page.evaluate(() => ({
    local: Object.values(localStorage),
    session: Object.values(sessionStorage),
  }))
  expect(storage.local).not.toContain(issuedKey)
  expect(storage.session).not.toContain(issuedKey)
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  await page.getByRole('button', { name: '복사 완료 · 닫기' }).click()
  await expect(secretInput).toHaveCount(0)

  await page.getByRole('button', { name: 'Key 회전' }).first().click()
  await expect(page.getByRole('heading', { name: /Key 회전/ })).toBeFocused()
  await page.keyboard.press('Shift+Tab')
  await expect(
    page.getByRole('button', { name: '회전하고 새 key 보기' }),
  ).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(page.getByLabel('새 key 만료')).toBeFocused()
  await page.getByLabel('기존 key overlap 시간').fill('24')
  await page.getByRole('button', { name: '회전하고 새 key 보기' }).click()
  await expect(page.getByLabel('발급된 API key')).toHaveValue(rotatedKey)
  await page.getByRole('button', { name: '복사 완료 · 닫기' }).click()

  const orderClient = page
    .locator('article')
    .filter({ hasText: '주문 운영 시스템' })
  await orderClient.getByRole('button', { name: '비활성화' }).click()
  await expect(orderClient.getByText('DISABLED', { exact: true })).toBeVisible()
  await orderClient.getByRole('button', { name: '영구 폐기' }).click()
  await expect(orderClient.getByText('REVOKED', { exact: true })).toBeVisible()
})
