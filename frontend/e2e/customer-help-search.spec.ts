import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

for (const width of [390, 768, 1448]) {
  test(`search preserves order and recovers cursor and next page at ${width}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 })
    const requests: Array<{ query: string; cursor?: string }> = []
    const hits = [
      {
        articleSlug: 'password-title',
        title: '비밀번호 변경 방법',
        excerpt:
          '설정에서 비밀번호를 변경하고 저장해 주세요. <script>는 텍스트입니다.',
        categoryTitle: '계정',
        sectionTitle: '로그인',
      },
      {
        articleSlug: 'password-summary',
        title: '계정 보안 점검',
        excerpt: '비밀번호와 로그인 기록을 확인하세요.',
        categoryTitle: '계정',
        sectionTitle: '보안',
      },
      {
        articleSlug: 'common-body',
        title: '서비스 이용 안내',
        excerpt: '공통 주의 사항으로 비밀번호를 안전하게 관리해 주세요.',
        categoryTitle: '시작하기',
        sectionTitle: '안내',
      },
    ]
    await page.route('**/api/v1/**', async (route) => {
      const path = new URL(route.request().url()).pathname
      if (path === '/api/v1/customer/me')
        return route.fulfill({ status: 401, json: {} })
      if (path === '/api/v1/help/categories')
        return route.fulfill({
          json: [
            {
              id: 'account',
              slug: 'account',
              title: '계정',
              description: '계정과 로그인',
            },
          ],
        })
      if (path === '/api/v1/help/search') {
        expect(route.request().method()).toBe('POST')
        const body = route.request().postDataJSON()
        requests.push(body)
        if (body.cursor && [2, 4].includes(requests.length))
          return route.fulfill({
            status: requests.length === 2 ? 400 : 503,
            json: {},
          })
        return route.fulfill({
          json: {
            items:
              body.query === '환불'
                ? [{ ...hits[0], title: '환불 안내' }]
                : body.cursor
                  ? [
                      {
                        ...hits[0],
                        articleSlug: 'additional',
                        title: '추가 검색 문서',
                      },
                    ]
                  : hits,
            hasMore: !body.cursor,
            nextCursor: body.cursor ? null : 'opaque-v2-example',
          },
        })
      }
      throw new Error(`Unexpected ${path}`)
    })
    await page.goto('/search?q=비밀번호')
    const list = page.getByRole('list', { name: '검색 결과 목록' })
    await expect(list.getByRole('heading')).toHaveText(
      hits.map((hit) => hit.title),
    )
    await expect(page.getByText('가장 관련 높은 결과')).toHaveCount(0)
    await expect(list.locator('mark').first()).toHaveText('비밀번호')
    await expect(list.locator('script')).toHaveCount(0)
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBeTruthy()
    await page.screenshot({
      path: testInfo.outputPath(`search-results-${width}.png`),
      fullPage: true,
    })
    await page.getByRole('button', { name: '검색 결과 더 보기' }).click()
    await page.getByRole('button', { name: '처음부터 다시 검색' }).click()
    await expect(
      page.getByRole('textbox', { name: '도움말 검색어' }),
    ).toBeFocused()
    await expect(list.getByRole('heading')).toHaveText(
      hits.map((hit) => hit.title),
    )
    await page.getByRole('button', { name: '검색 결과 더 보기' }).click()
    await expect(
      page.getByText('추가 검색 결과를 불러올 수 없습니다.'),
    ).toBeVisible()
    await expect(list.getByRole('heading')).toHaveCount(3)
    await page.getByRole('button', { name: '다시 시도' }).focus()
    await page.keyboard.press('Enter')
    await expect(
      list.getByRole('heading', { name: '추가 검색 문서' }),
    ).toBeVisible()
    expect(requests.map((request) => request.cursor)).toEqual([
      undefined,
      'opaque-v2-example',
      undefined,
      'opaque-v2-example',
      'opaque-v2-example',
    ])
    await page.getByRole('textbox', { name: '도움말 검색어' }).fill('환불')
    await page.getByRole('textbox', { name: '도움말 검색어' }).press('Enter')
    await expect(list.getByRole('heading')).toHaveText(['환불 안내'])
    expect(requests.at(-1)).toMatchObject({ query: '환불' })
    expect(requests.at(-1)?.cursor).toBeUndefined()
  })
}
