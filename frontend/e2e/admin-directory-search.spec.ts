import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

const admin = {
  id: '11111111-1111-4111-8111-111111111111',
  email: 'admin@example.test',
  displayName: '운영 관리자',
  role: 'ADMIN',
  capabilities: ['ADMIN_MANAGE'],
}
const group = {
  id: '22222222-2222-4222-8222-222222222222',
  name: '전체 검색으로 찾은 결제 지원',
  status: 'ACTIVE',
  memberCount: 51,
}
const staff = {
  id: '33333333-3333-4333-8333-333333333333',
  email: 'target@example.test',
  displayName: '마지막 페이지 상담사',
  role: 'AGENT',
  status: 'ACTIVE',
  memberships: [],
  auditAuthorities: [],
  lastLoginAt: null,
}
const staffPathPrefix = process.env.PLAYWRIGHT_STAFF_PATH_PREFIX ?? ''

for (const width of [1280, 1440, 1920]) {
  test(`directory search and member pagination remain usable at ${width}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    const requests: Array<{ path: string; body: Record<string, unknown> }> = []
    await page.route('**/api/v1/**', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      if (url.pathname === '/api/v1/agent/me')
        return route.fulfill({ json: admin })
      if (url.pathname === '/api/v1/agent/csrf')
        return route.fulfill({
          json: { token: 'c'.repeat(32), headerName: 'X-CSRF-TOKEN' },
        })
      if (url.pathname.endsWith('/search')) {
        const body = request.postDataJSON()
        requests.push({ path: url.pathname, body })
        expect(request.method()).toBe('POST')
        expect(url.search).toBe('')
        expect(request.headers()['x-deskseed-expected-staff-id']).toBe(admin.id)
        expect(request.headers()['x-csrf-token']).toBe('c'.repeat(32))
        const groupSearch = url.pathname.includes('/groups/')
        return route.fulfill({
          json: groupSearch ? [group] : [staff],
          headers: {
            'X-Page-Number': String(body.page),
            'X-Page-Size': '50',
            'X-Total-Count': groupSearch || body.excludeGroupId ? '1' : '51',
            'X-Total-Pages': groupSearch || body.excludeGroupId ? '1' : '2',
          },
        })
      }
      if (url.pathname === '/api/v1/admin/staff')
        return route.fulfill({ json: [] })
      if (url.pathname === '/api/v1/admin/groups')
        return route.fulfill({ json: [group] })
      if (url.pathname.endsWith('/members'))
        return route.fulfill({
          json: [
            {
              groupId: group.id,
              staffId: staff.id,
              staffDisplayName: staff.displayName,
              role: staff.role,
            },
          ],
          headers: {
            'X-Page-Number': url.searchParams.get('page') ?? '0',
            'X-Page-Size': '50',
            'X-Total-Count': '51',
            'X-Total-Pages': '2',
          },
        })
      return route.abort()
    })

    await page.goto(`${staffPathPrefix}/admin/staff`)
    await page
      .getByLabel('직원 이름 또는 이메일 검색')
      .fill('target@example.test')
    await page.getByLabel('직원 이름 또는 이메일 검색').press('Enter')
    await expect(page.getByText('검색 결과 51명')).toBeVisible()
    await page.getByRole('button', { name: '다음 페이지', exact: true }).click()
    await expect(page.getByText('2 / 2 페이지')).toBeVisible()
    expect(requests.at(-1)?.body.page).toBe(1)
    await page.getByRole('button', { name: '검색 초기화', exact: true }).click()
    await expect(page.getByLabel('직원 이름 또는 이메일 검색')).toHaveValue('')

    await page.goto(`${staffPathPrefix}/admin/groups`)
    await page.getByLabel('그룹 이름 검색', { exact: true }).fill('결제')
    await page.getByLabel('그룹 이름 검색', { exact: true }).press('Enter')
    await expect(page.getByText('검색 결과 1개')).toBeVisible()
    await page.getByRole('button', { name: '그룹 관리', exact: true }).click()
    await page.getByRole('button', { name: '다음 구성원 페이지' }).click()
    await expect(page.getByText('2 / 2 구성원 페이지')).toBeVisible()
    await page
      .getByLabel('구성원 이름 또는 이메일 검색', { exact: true })
      .fill('target@example.test')
    await page
      .getByLabel('구성원 이름 또는 이메일 검색', { exact: true })
      .press('Enter')
    await expect(page.getByText('검색된 구성원 51명')).toBeVisible()
    expect(requests.at(-1)?.body).toMatchObject({
      memberOfGroupId: group.id,
      page: 0,
    })
    await page.getByRole('button', { name: '다음 구성원 페이지' }).click()
    await expect(page.getByText('2 / 2 구성원 페이지')).toBeVisible()
    expect(requests.at(-1)?.body).toMatchObject({
      memberOfGroupId: group.id,
      page: 1,
    })
    await page
      .getByLabel('추가할 직원 이름 또는 이메일 검색')
      .fill('target@example.test')
    await page.getByLabel('추가할 직원 이름 또는 이메일 검색').press('Enter')
    await expect(page.getByText('추가 가능한 직원 1명')).toBeVisible()
    expect(requests.at(-1)?.body).toMatchObject({
      excludeGroupId: group.id,
      status: 'ACTIVE',
    })
    await page.getByLabel('활성 직원 추가').selectOption(staff.id)
    await expect(
      page.getByRole('button', { name: '구성원 추가', exact: true }),
    ).toBeEnabled()
    expect(
      new Set(requests.map((request) => request.body.interactionId)).size,
    ).toBe(requests.length)
    expect(page.url()).not.toContain('target')
    expect(
      await page.evaluate(() =>
        JSON.stringify({
          local: { ...localStorage },
          session: { ...sessionStorage },
        }),
      ),
    ).not.toContain('target@example.test')
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true)
    if (width === 1440)
      expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
    await page.screenshot({
      path: testInfo.outputPath(`admin-directory-${width}.png`),
      fullPage: true,
    })
  })
}
