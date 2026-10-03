import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import {
  article,
  category,
  revision,
  section,
} from '../apps/staff-console/src/extensions/knowledge-workflow/fixtures'

for (const width of [390, 768]) {
  test(`customer announcements empty and retry at ${width}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    let status = 404
    await page.route('**/api/v1/**', async (route) => {
      const path = new URL(route.request().url()).pathname
      if (path === '/api/v1/customer/me')
        return route.fulfill({ status: 401, json: {} })
      if (path === '/api/v1/help/categories') return route.fulfill({ json: [] })
      if (path === '/api/v1/help/sections/announcements')
        return route.fulfill({
          status,
          json:
            status === 200
              ? {
                  id: section.id,
                  categoryId: category.id,
                  slug: 'announcements',
                  title: '공지사항',
                  description: '',
                  articles: [],
                  hasMore: false,
                  nextCursor: null,
                }
              : {},
        })
      throw new Error(`Unexpected ${route.request().method()} ${path}`)
    })
    await page.goto(`${process.env.PLAYWRIGHT_CUSTOMER_BASE_URL ?? ''}/`)
    await expect(page.getByText('등록된 공지사항이 없습니다.')).toBeVisible()
    await page.getByRole('link', { name: '전체 보기' }).click()
    await expect(page).toHaveURL(/\/sections\/announcements$/)
    await expect(page.getByText('등록된 공지사항이 없습니다.')).toBeVisible()
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
    await page.screenshot({
      path: testInfo.outputPath(`announcements-empty-${width}.png`),
      fullPage: true,
    })
    status = 503
    await page.getByRole('button', { name: '다시 시도' }).click()
    await expect(page.getByText('공지사항을 불러올 수 없습니다.')).toBeVisible()
    status = 200
    await page.getByRole('button', { name: '다시 시도' }).focus()
    await page.keyboard.press('Enter')
    await expect(page.getByText('등록된 공지사항이 없습니다.')).toBeVisible()
    await expect(
      page.getByRole('link', { name: '도움말 홈으로' }),
    ).toBeVisible()
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBeTruthy()
  })
}

test('customer announcements removed while loading the next page', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 390, height: 900 })
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/v1/customer/me')
      return route.fulfill({ status: 401, json: {} })
    if (url.pathname === '/api/v1/help/sections/announcements') {
      if (url.searchParams.has('cursor'))
        return route.fulfill({ status: 404, json: {} })
      return route.fulfill({
        json: {
          id: section.id,
          categoryId: category.id,
          slug: 'announcements',
          title: '공지사항',
          description: '',
          articles: [
            {
              slug: 'customer-update',
              title: '고객 포털 업데이트 안내',
              summary: '합성 공지',
              audience: 'PUBLIC',
            },
          ],
          hasMore: true,
          nextCursor: 'announcement-page-2',
        },
      })
    }
    throw new Error(`Unexpected ${route.request().method()} ${url.pathname}`)
  })
  await page.goto(
    `${process.env.PLAYWRIGHT_CUSTOMER_BASE_URL ?? ''}/sections/announcements`,
  )
  await expect(
    page.getByRole('link', { name: '고객 포털 업데이트 안내' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '문서 더 보기' }).click()
  await expect(page.getByText('등록된 공지사항이 없습니다.')).toBeVisible()
  await expect(
    page.getByRole('link', { name: '고객 포털 업데이트 안내' }),
  ).toHaveCount(0)
  await expect(page.getByRole('button', { name: '문서 더 보기' })).toHaveCount(
    0,
  )
  await expect(
    page.getByRole('heading', { name: '공지사항', level: 1 }),
  ).toBeVisible()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  await page.screenshot({
    path: testInfo.outputPath('announcements-next-page-404.png'),
    fullPage: true,
  })
  await page.getByRole('button', { name: '다시 시도' }).focus()
  await page.keyboard.press('Enter')
  await expect(
    page.getByRole('link', { name: '고객 포털 업데이트 안내' }),
  ).toBeVisible()
  await expect(page.getByText('등록된 공지사항이 없습니다.')).toHaveCount(0)
})

test('admin read-only readiness uses filters and keeps failure distinct at 1448', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 1448, height: 1000 })
  let ready = false
  let reads = 0
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    expect(request.method()).toBe('GET')
    if (path === '/api/v1/agent/me')
      return route.fulfill({
        json: {
          id: category.id,
          email: 'synthetic@example.test',
          displayName: '합성 관리자',
          role: 'ADMIN',
          capabilities: ['ADMIN_MANAGE'],
        },
      })
    if (path === '/api/v1/admin/settings/customer-access-mode')
      return route.fulfill({
        json: {
          mode: 'REGISTRATION_REQUIRED',
          version: 1,
          updatedAt: '2026-10-03T00:00:00Z',
        },
      })
    reads++
    if (path === '/api/v1/admin/customer-consent-policies')
      return route.fulfill({
        json: { items: [], totalCount: 0, totalPages: 0, page: 0, size: 20 },
      })
    if (path === '/api/v1/admin/knowledge/categories')
      return route.fulfill({ json: [category] })
    if (path === '/api/v1/admin/knowledge/sections')
      return route.fulfill({ json: [{ ...section, slug: 'announcements' }] })
    if (path === '/api/v1/admin/knowledge/articles') {
      expect(url.searchParams.get('sectionId')).toBe(section.id)
      expect(url.searchParams.get('audience')).toBe('PUBLIC')
      expect(url.searchParams.get('lifecycle')).toBe('PUBLISHED')
      return route.fulfill(
        ready
          ? {
              json: {
                items: [
                  {
                    ...article,
                    lifecycle: 'PUBLISHED',
                    currentPublishedRevision: revision,
                  },
                ],
                nextCursor: null,
              },
            }
          : { status: 503, json: {} },
      )
    }
    throw new Error(`Unexpected ${path}`)
  })
  await page.goto('/_staff/admin/settings/customer-access-mode')
  await expect(
    page.getByRole('heading', { name: '고객 포털 준비 확인' }),
  ).toBeVisible()
  expect(reads).toBe(0)
  await page
    .getByRole('button', { name: '준비 상태 확인', exact: true })
    .focus()
  await page.keyboard.press('Enter')
  await expect(page.getByText('가입 정책 발행이 필요합니다.')).toBeVisible()
  await expect(
    page.getByText('공지 준비 상태를 확인할 수 없습니다.'),
  ).toBeVisible()
  await expect(page.getByText('공지 섹션이 없습니다.')).toHaveCount(0)
  ready = true
  await page.getByRole('button', { name: '준비 상태 다시 확인' }).click()
  await expect(page.getByText('공개 공지가 발행되어 있습니다.')).toBeVisible()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy()
  await page.screenshot({
    path: testInfo.outputPath('readiness-admin-1448.png'),
    fullPage: true,
  })
  await expect(
    page.getByRole('link', { name: '지식 문서 관리' }),
  ).toHaveAttribute('href', '/_staff/admin/knowledge')
})
