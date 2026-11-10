import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

for (const width of [390, 768, 1448]) {
  test(`customer can find lookup, browse categories and search at ${width}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    let requestReads = 0
    let searchQuery = ''
    await page.route('**/api/v1/**', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      if (url.pathname === '/api/v1/customer/me')
        return route.fulfill({ status: 401, json: { status: 401 } })
      if (url.pathname === '/api/v1/help/categories')
        return route.fulfill({
          json: [
            {
              id: 'orders',
              slug: 'orders',
              title: '주문',
              description: '주문 도움말',
            },
          ],
        })
      if (url.pathname === '/api/v1/help/search') {
        searchQuery = request.postDataJSON().query
        return route.fulfill({
          json: { items: [], hasMore: false, nextCursor: null },
        })
      }
      if (url.pathname === '/api/v1/requests/1042') {
        requestReads += 1
        expect(request.headers()['x-request-access-token']).toBe('a'.repeat(43))
        return route.fulfill({
          json: {
            ticketNumber: 1042,
            subject: '합성 문의',
            status: 'OPEN',
            createdAt: '2026-10-03T00:00:00Z',
            updatedAt: '2026-10-03T00:00:00Z',
            comments: [],
          },
        })
      }
      return route.abort()
    })
    await page.goto('/requests/lookup')
    const menu = page.getByRole('navigation', { name: '고객 메뉴' })
    await expect(menu.getByRole('link', { name: '문의 조회' })).toBeVisible()
    await expect(
      menu.getByRole('link', { name: '문서 둘러보기' }),
    ).toBeVisible()
    await expect(
      page.getByRole('search', { name: '전체 도움말 검색' }),
    ).toBeVisible()
    await page.getByLabel('문의 번호', { exact: true }).fill('#DS-1042')
    await page.getByRole('button', { name: '문의 열기' }).click()
    await expect(
      page.getByText('이메일로 받은 문의 링크를 다시 열어 주세요.'),
    ).toBeVisible()
    expect(requestReads).toBe(0)
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true)
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
    await page.screenshot({
      path: testInfo.outputPath('lookup-navigation.png'),
      fullPage: true,
    })
    await menu.getByRole('link', { name: '문서 둘러보기' }).click()
    await expect(page).toHaveURL(/\/categories$/)
    await expect(page.getByRole('link', { name: /주문 도움말/ })).toBeVisible()
    const search = page.getByLabel('도움말 검색', { exact: true })
    await search.fill('결제 오류')
    await search.press('Enter')
    await expect(page).toHaveURL(/\/search\?q=/)
    await expect.poll(() => searchQuery).toBe('결제 오류')
    await page.goto(`/requests/1042#token=${'a'.repeat(43)}`)
    await expect(
      page.getByRole('heading', { name: '#1042 합성 문의' }),
    ).toBeVisible()
    await expect(page).toHaveURL(/\/requests\/1042$/)
    await menu.getByRole('link', { name: '문의 조회' }).click()
    await page.getByLabel('문의 번호', { exact: true }).fill(' DS-1042 ')
    await page.getByLabel('문의 번호', { exact: true }).press('Enter')
    await expect(
      page.getByRole('heading', { name: '#1042 합성 문의' }),
    ).toBeVisible()
    await expect(page).toHaveURL(/\/requests\/1042$/)
    expect(requestReads).toBeGreaterThan(0)
  })
}

test('signed-in customer keeps my requests and lookup reachable on a narrow screen', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 320, height: 900 })
  await page.route('**/api/v1/customer/me', (route) =>
    route.fulfill({
      json: {
        id: '11111111-1111-4111-8111-111111111111',
        displayName: '긴 이름을 가진 고객 지원 이용자',
        email: 'synthetic@example.test',
        companyName: null,
        verifiedAt: '2026-10-03T00:00:00Z',
        credentialState: 'PASSWORDLESS',
        registrationState: 'COMPLETE',
        availableAuthenticationMethods: ['MAGIC_LINK'],
      },
    }),
  )
  await page.goto('/requests/lookup')
  const menu = page.getByRole('navigation', { name: '고객 메뉴' })
  await expect(menu.getByRole('link', { name: '내 문의' })).toBeVisible()
  await expect(menu.getByRole('link', { name: '문의 조회' })).toBeVisible()
  await expect(menu.getByRole('button', { name: '로그아웃' })).toBeVisible()
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  await page.screenshot({
    path: testInfo.outputPath('signed-in-navigation.png'),
    fullPage: true,
  })
})
