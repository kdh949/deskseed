import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route } from '@playwright/test'

const CSRF_TOKEN = 'e2e-csrf-token'

function problem(status: number, code?: string) {
  return {
    status,
    contentType: 'application/problem+json',
    json: {
      type: '/problems/admin-organization-conflict',
      title: 'Request failed',
      status,
      detail: 'Sensitive server-only diagnostic detail.',
      ...(code ? { code } : {}),
    },
  }
}

function requireCsrf(route: Route) {
  expect(route.request().headers()['x-csrf-token']).toBe(CSRF_TOKEN)
}

async function expectNoAxeViolations(page: Page) {
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
}

test('agent direct admin URL is guarded, admin API returns 403, and logout clears the session', async ({
  page,
}) => {
  let authenticated = false
  let rejectedOnce = false
  let logoutObserved = false
  let adminApiCalls = 0

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/agent/csrf') {
      return route.fulfill({
        status: 200,
        json: { token: CSRF_TOKEN, headerName: 'X-CSRF-TOKEN' },
      })
    }
    if (path === '/api/v1/agent/session' && request.method() === 'POST') {
      requireCsrf(route)
      if (!rejectedOnce) {
        rejectedOnce = true
        return route.fulfill(problem(401))
      }
      authenticated = true
      return route.fulfill({ status: 204, body: '' })
    }
    if (path === '/api/v1/agent/session' && request.method() === 'DELETE') {
      requireCsrf(route)
      authenticated = false
      logoutObserved = true
      return route.fulfill({ status: 204, body: '' })
    }
    if (path === '/api/v1/agent/me') {
      return authenticated
        ? route.fulfill({
            status: 200,
            json: {
              id: 'agent-id',
              email: 'agent@example.com',
              displayName: '상담사',
              role: 'AGENT',
              capabilities: ['AGENT_WORKSPACE'],
            },
          })
        : route.fulfill(problem(401))
    }
    if (path.startsWith('/api/v1/admin/')) {
      adminApiCalls += 1
      return route.fulfill(problem(403))
    }
    return route.abort()
  })

  await page.goto('/admin/staff')
  await expect(page).toHaveURL(/\/agent\/login$/)
  await page.getByRole('textbox', { name: '이메일' }).fill('agent@example.com')
  await page.getByLabel('비밀번호').fill('Agent password 42')
  await page.getByRole('button', { name: '로그인' }).click()
  const loginError = page.getByRole('alert')
  await expect(loginError).toContainText(
    '이메일 또는 비밀번호가 올바르지 않습니다.',
  )
  await expect(loginError).not.toContainText('Sensitive server-only')

  await page.getByRole('button', { name: '로그인' }).click()
  await expect(page).toHaveURL(/\/agent\/home$/)
  await expect(
    page.getByRole('main', { name: '상담사 작업 공간' }),
  ).toBeVisible()
  await expect(page.getByRole('link', { name: '관리자 설정' })).toHaveCount(0)
  await expectNoAxeViolations(page)
  expect(adminApiCalls).toBe(0)
  expect(
    await page.evaluate(
      async () => (await fetch('/api/v1/admin/staff')).status,
    ),
  ).toBe(403)
  expect(adminApiCalls).toBe(1)

  await page.goto('/admin/groups')
  await expect(page).toHaveURL(/\/admin\/groups$/)
  await expect(
    page.getByRole('heading', { name: '관리자 권한이 필요합니다.' }),
  ).toBeVisible()
  await expect(
    page.getByRole('heading', { name: '그룹과 멤버십' }),
  ).toHaveCount(0)
  expect(adminApiCalls).toBe(1)
  await page.getByRole('link', { name: '상담사 작업 공간으로 이동' }).click()
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page).toHaveURL(/\/agent\/login$/)
  expect(logoutObserved).toBe(true)
})

test('admin creates staff and manages a group membership through the UI with CSRF on every write', async ({
  page,
}) => {
  const admin = {
    id: 'admin-id',
    email: 'admin@example.com',
    displayName: '관리자',
    role: 'ADMIN',
    status: 'ACTIVE',
    memberships: [],
    lastLoginAt: null,
  }
  const staff = [admin]
  const groups: Array<{
    id: string
    name: string
    status: 'ACTIVE' | 'DISABLED'
    memberCount: number
  }> = []
  const memberships: Array<{
    groupId: string
    staffId: string
    staffDisplayName: string
    role: 'ADMIN' | 'AGENT'
  }> = []
  let csrfWriteCount = 0

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    if (path === '/api/v1/agent/me') {
      return route.fulfill({
        status: 200,
        json: {
          id: admin.id,
          email: admin.email,
          displayName: admin.displayName,
          role: admin.role,
          capabilities: ['ADMIN_MANAGE', 'AGENT_WORKSPACE'],
        },
      })
    }
    if (path === '/api/v1/agent/csrf') {
      return route.fulfill({
        status: 200,
        json: { token: CSRF_TOKEN, headerName: 'X-CSRF-TOKEN' },
      })
    }
    if (!['GET', 'HEAD'].includes(method)) {
      requireCsrf(route)
      csrfWriteCount += 1
    }
    if (path === '/api/v1/admin/staff' && method === 'GET') {
      return route.fulfill({ status: 200, json: staff })
    }
    if (path === '/api/v1/admin/staff' && method === 'POST') {
      const body = request.postDataJSON()
      expect(body.password).toBe('Temporary 42!pass')
      const created = {
        id: 'agent-id',
        email: body.email,
        displayName: body.displayName,
        role: body.role,
        status: 'ACTIVE',
        memberships: [],
        lastLoginAt: null,
      }
      staff.push(created)
      return route.fulfill({ status: 201, json: created })
    }
    if (path === '/api/v1/admin/groups' && method === 'GET') {
      return route.fulfill({ status: 200, json: groups })
    }
    if (path === '/api/v1/admin/groups' && method === 'POST') {
      const created = {
        id: 'group-id',
        name: request.postDataJSON().name,
        status: 'ACTIVE' as const,
        memberCount: 0,
      }
      groups.push(created)
      return route.fulfill({ status: 201, json: created })
    }
    if (path === '/api/v1/admin/groups/group-id' && method === 'PATCH') {
      groups[0].name = request.postDataJSON().name
      return route.fulfill({ status: 200, json: groups[0] })
    }
    if (path === '/api/v1/admin/groups/group-id' && method === 'DELETE') {
      groups[0].status = 'DISABLED'
      return route.fulfill({ status: 204, body: '' })
    }
    if (path === '/api/v1/admin/groups/group-id/members' && method === 'GET') {
      return route.fulfill({ status: 200, json: memberships })
    }
    if (path === '/api/v1/admin/groups/group-id/members' && method === 'POST') {
      const member = {
        groupId: 'group-id',
        staffId: request.postDataJSON().staffId,
        staffDisplayName: '새 상담사',
        role: 'AGENT' as const,
      }
      memberships.push(member)
      groups[0].memberCount = 1
      return route.fulfill({ status: 201, json: member })
    }
    if (
      path === '/api/v1/admin/groups/group-id/members/agent-id' &&
      method === 'DELETE'
    ) {
      memberships.splice(0)
      groups[0].memberCount = 0
      return route.fulfill({ status: 204, body: '' })
    }
    return route.abort()
  })

  await page.goto('/admin/staff')
  await expect(page.getByRole('heading', { name: '직원 계정' })).toBeVisible()
  await page.getByRole('textbox', { name: '이름' }).fill('새 상담사')
  await page
    .getByRole('textbox', { name: '이메일' })
    .fill('new-agent@example.com')
  await page.getByLabel('초기 비밀번호').fill('Temporary 42!pass')
  await page.getByRole('button', { name: '직원 추가' }).click()
  await expect(page.getByText('new-agent@example.com')).toBeVisible()
  await expectNoAxeViolations(page)

  await page.getByRole('link', { name: '그룹' }).click()
  await page.getByRole('textbox', { name: '새 그룹 이름' }).fill('고객 지원')
  await page.getByRole('button', { name: '그룹 추가' }).click()
  await expect(
    page.getByRole('heading', { name: '고객 지원 멤버십' }),
  ).toBeVisible()
  await page.getByLabel('직원 추가').selectOption('agent-id')
  await page.getByRole('button', { name: '멤버 추가' }).click()
  await expect(
    page.getByText('새 상담사', { selector: '.membership-list strong' }),
  ).toBeVisible()
  await page
    .getByRole('textbox', { name: '그룹 이름', exact: true })
    .fill('결제 지원')
  await page.getByRole('button', { name: '이름 변경' }).click()
  await expect(
    page.getByRole('heading', { name: '결제 지원 멤버십' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '멤버 제거' }).click()
  await expect(page.getByText('이 그룹에 활성 멤버가 없습니다.')).toBeVisible()
  await expectNoAxeViolations(page)
  expect(csrfWriteCount).toBe(5)
})
