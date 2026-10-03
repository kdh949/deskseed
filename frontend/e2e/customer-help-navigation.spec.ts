import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

for (const width of [390, 768, 1448]) {
  test(`direct article follows actual parent hierarchy at ${width}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 950 })
    let categoryTitle = '결제와 계정 관리를 위한 도움말'
    await page.route('**/api/v1/**', async (route) => {
      const path = new URL(route.request().url()).pathname
      expect(route.request().method()).toBe('GET')
      if (path === '/api/v1/customer/me')
        return route.fulfill({ status: 401, json: {} })
      const category = { slug: 'actual-category', title: categoryTitle }
      const section = {
        slug: 'actual-section',
        title: '정기 결제 및 구독 요금 관리',
      }
      if (path === '/api/v1/help/articles/direct')
        return route.fulfill({
          json: {
            slug: 'direct',
            category,
            section,
            currentPublishedRevision: {
              title: '직접 연 문서',
              summary: '결제 주기 변경 안내',
              document: {
                schemaVersion: 1,
                blocks: [
                  {
                    type: 'paragraph',
                    text: '설정에서 결제 주기를 확인해 주세요.',
                  },
                ],
              },
            },
          },
        })
      if (path === '/api/v1/help/sections/actual-section')
        return route.fulfill({
          json: {
            ...section,
            category,
            description: '구독 요금과 정기 결제 안내',
            articles: [
              {
                slug: 'direct',
                title: '직접 연 문서',
                summary: '결제 주기를 변경하는 방법을 살펴보세요.',
              },
            ],
            hasMore: false,
            nextCursor: null,
          },
        })
      if (path === '/api/v1/help/categories/actual-category')
        return route.fulfill({
          json: {
            id: 'actual-category-id',
            ...category,
            description: '계정 결제 문서',
            sections: [
              {
                ...section,
                category,
                description: '결제 주기와 구독을 관리하는 방법을 확인하세요.',
              },
            ],
          },
        })
      throw new Error(`Unexpected ${path}`)
    })
    await page.goto('/articles/direct')
    const navigation = page.getByRole('navigation', { name: '문서 경로' })
    await expect(
      navigation.getByRole('link', { name: categoryTitle }),
    ).toHaveAttribute('href', '/categories/actual-category')
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
    await page.screenshot({
      path: testInfo.outputPath(`article-path-${width}.png`),
      fullPage: true,
    })
    categoryTitle = '서버에서 이름이 변경된 현재 주제'
    await page.reload()
    await expect(
      navigation.getByRole('link', { name: categoryTitle }),
    ).toBeVisible()
    await navigation
      .getByRole('link', { name: '정기 결제 및 구독 요금 관리' })
      .focus()
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL(/\/sections\/actual-section$/)
    await expect(page.getByRole('list', { name: '문서 목록' })).toBeVisible()
    await expect(
      page.getByText('결제 주기를 변경하는 방법을 살펴보세요.'),
    ).toBeVisible()
    await navigation.getByRole('link', { name: categoryTitle }).click()
    await expect(page.getByRole('list', { name: '섹션 목록' })).toBeVisible()
    await expect(
      page.getByText('결제 주기와 구독을 관리하는 방법을 확인하세요.'),
    ).toBeVisible()
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBeTruthy()
    await page.screenshot({
      path: testInfo.outputPath(`category-list-${width}.png`),
      fullPage: true,
    })
  })
}
