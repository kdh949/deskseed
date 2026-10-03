import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import {
  article,
  category,
  revision,
  section,
} from '../apps/staff-console/src/extensions/knowledge-workflow/fixtures'
const prefix = process.env.PLAYWRIGHT_STAFF_PATH_PREFIX ?? ''
const admin = {
  id: '11111111-1111-4111-8111-111111111111',
  email: 'admin@example.test',
  displayName: '관리자',
  role: 'ADMIN',
  capabilities: ['ADMIN_MANAGE'],
}
async function setup(page: Page) {
  const searches: Array<Record<string, unknown>> = []
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request(),
      url = new URL(request.url()),
      path = url.pathname
    if (path.endsWith('/agent/me')) return route.fulfill({ json: admin })
    if (path.endsWith('/csrf'))
      return route.fulfill({
        json: { token: 'c'.repeat(32), headerName: 'X-CSRF-TOKEN' },
      })
    if (path.endsWith('/categories')) return route.fulfill({ json: [category] })
    if (path.endsWith('/sections')) return route.fulfill({ json: [section] })
    if (path.endsWith('/articles/search')) {
      const body = request.postDataJSON()
      searches.push(body)
      expect(request.method()).toBe('POST')
      expect(url.search).toBe('')
      expect(request.headers()['x-deskseed-expected-staff-id']).toBe(admin.id)
      expect(request.headers()['x-csrf-token']).toBe('c'.repeat(32))
      return route.fulfill({
        json: {
          items: [
            {
              ...article,
              latestRevision: {
                title: '전체 목록의 마지막 문서',
                summary: revision.summary,
              },
            },
          ],
          hasMore: !body.cursor,
          nextCursor: body.cursor ? null : 'opaque-next',
          resultCount: 51,
        },
      })
    }
    if (path.endsWith('/articles'))
      return route.fulfill({
        json: {
          items: Array.from({ length: 50 }, (_, index) => ({
            ...article,
            id: `article-${index}`,
            latestRevision: {
              title: `문서 ${index + 1}`,
              summary: revision.summary,
            },
          })),
          nextCursor: null,
        },
      })
    if (path.endsWith('/revisions')) return route.fulfill({ json: [revision] })
    if (path.includes('/articles/')) return route.fulfill({ json: article })
    if (path.endsWith('/groups')) return route.fulfill({ json: [] })
    return route.abort()
  })
  return searches
}
for (const width of [1280, 1440, 1920])
  test(`knowledge title search and editor focus at ${width}`, async ({
    page,
  }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const requests = await setup(page)
    await page.goto(`${prefix}/admin/knowledge`)
    await page
      .getByRole('button', { name: '열기: 문서 50', exact: true })
      .click()
    await expect(
      page.getByRole('heading', { name: revision.title, exact: true }),
    ).toBeFocused()
    const box = await page
      .getByRole('heading', { name: revision.title, exact: true })
      .boundingBox()
    expect(box!.y).toBeGreaterThanOrEqual(0)
    expect(box!.y).toBeLessThan(250)
    await page.getByRole('button', { name: '문서 닫기' }).click()
    await expect(
      page.getByRole('button', { name: '열기: 문서 50', exact: true }),
    ).toBeFocused()
    await page.getByLabel('문서 제목 검색').fill('전체 검색 비공개어')
    await page.getByRole('button', { name: '검색', exact: true }).click()
    await expect(page.getByText('검색 결과 51개 · 현재 목록 1개')).toBeVisible()
    await page.getByRole('button', { name: '다음 목록' }).click()
    await expect(page.getByRole('button', { name: '첫 목록' })).toBeVisible()
    expect(requests.at(-1)?.cursor).toBe('opaque-next')
    await page.getByLabel('문서 상태 필터').selectOption('DRAFT')
    await expect(page.getByRole('button', { name: '첫 목록' })).toHaveCount(0)
    expect(requests.at(-1)).toMatchObject({
      query: '전체 검색 비공개어',
      lifecycle: 'DRAFT',
    })
    expect(requests.at(-1)?.cursor).toBeUndefined()
    expect(page.url()).not.toContain('검색')
    expect(
      await page.evaluate(() =>
        JSON.stringify({ ...localStorage, ...sessionStorage }),
      ),
    ).not.toContain('비공개어')
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true)
    if (width === 1440)
      expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
    await page.screenshot({
      path: info.outputPath(`admin-knowledge-${width}.png`),
      fullPage: true,
    })
  })
test('knowledge editor guards dirty close filters route back and pending mutation', async ({
  page,
}, info) => {
  await setup(page)
  await page.goto(`${prefix}/admin/groups`)
  await page.getByRole('link', { name: '지식 문서' }).click()
  await page.getByRole('button', { name: '열기: 문서 1', exact: true }).click()
  await page.getByLabel(/문서 제목(?! 검색)/).fill('유지해야 하는 초안')
  await page.getByRole('button', { name: '문서 닫기' }).click()
  await expect(
    page.getByRole('dialog', { name: '저장하지 않은 변경 사항' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '계속 편집' }).click()
  await page.getByLabel('문서 상태 필터').selectOption('PUBLISHED')
  await page.getByRole('button', { name: '계속 편집' }).click()
  await expect(page.getByLabel('문서 상태 필터')).toHaveValue('')
  await page.goBack()
  await expect(
    page.getByRole('dialog', { name: '저장하지 않은 변경 사항' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '계속 편집' }).click()
  await expect(page.getByLabel(/문서 제목(?! 검색)/)).toHaveValue(
    '유지해야 하는 초안',
  )
  expect(
    await page.evaluate(() => {
      const event = new Event('beforeunload', { cancelable: true })
      dispatchEvent(event)
      return event.defaultPrevented
    }),
  ).toBe(true)
  let release!: () => void
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route('**/api/v1/admin/knowledge/articles/*', async (route) => {
    if (route.request().method() !== 'PATCH') return route.fallback()
    expect(route.request().headers()['if-match']).toBe('"0"')
    await gate
    return route.fulfill({ status: 412, json: { status: 412 } })
  })
  await page.getByRole('button', { name: '초안 저장' }).click()
  await expect(page.getByLabel(/문서 제목(?! 검색)/)).toBeDisabled()
  await page.getByRole('link', { name: '그룹', exact: true }).click()
  await expect(
    page.getByRole('dialog', { name: '저장 완료를 기다려 주세요' }),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: '변경 사항 버리고 이동' }),
  ).toBeDisabled()
  release()
  await expect(
    page.getByRole('dialog', { name: '저장하지 않은 변경 사항' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '계속 편집' }).click()
  await expect(page.getByLabel(/문서 제목(?! 검색)/)).toHaveValue(
    '유지해야 하는 초안',
  )
  await expect(page.getByText('최신 내용을 확인하세요.')).toBeVisible()
  await page.screenshot({
    path: info.outputPath('admin-knowledge-conflict.png'),
    fullPage: true,
  })
})
