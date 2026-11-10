import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

const staff = {
  id: '22222222-2222-4222-8222-222222222222',
  email: 'agent@example.test',
  displayName: '상담사',
  role: 'AGENT',
  capabilities: ['AGENT_WORKSPACE'],
}

for (const width of [1280, 1440, 1920]) {
  test(`macro editor replaces empty state, protects drafts and returns to saved list at ${width}px`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    let saved: Record<string, unknown> | null = null
    const posts: string[] = []
    await page.route('**/api/v1/**', async (route) => {
      const request = route.request()
      const pathname = new URL(request.url()).pathname
      if (pathname === '/api/v1/agent/me') return route.fulfill({ json: staff })
      if (pathname === '/api/v1/agent/csrf')
        return route.fulfill({
          json: { token: 'c'.repeat(32), headerName: 'X-CSRF-TOKEN' },
        })
      if (pathname === '/api/v1/agent/personal-macros') {
        if (request.method() === 'GET')
          return route.fulfill({ json: saved ? [saved] : [] })
        posts.push(pathname)
        saved = {
          ...request.postDataJSON(),
          id: '11111111-1111-4111-8111-111111111111',
          scope: 'PERSONAL',
          ownerStaffId: staff.id,
          currentVersion: 1,
          activeVersion: null,
          aggregateVersion: 1,
          createdAt: '2026-10-03T01:00:00Z',
          updatedAt: '2026-10-03T01:00:00Z',
        }
        return route.fulfill({ status: 201, json: saved })
      }
      return route.abort()
    })
    await page.goto('/agent/personal-macros')
    await expect(page.getByText('매크로 / 내 매크로')).toBeVisible()
    await expect(page.getByText('등록된 매크로가 없습니다.')).toBeVisible()
    await page.getByRole('button', { name: '매크로 만들기' }).click()
    const name = page.getByLabel(/매크로 이름/)
    await expect(name).toBeFocused()
    await expect(page.getByText('등록된 매크로가 없습니다.')).toHaveCount(0)
    await name.fill('다음 단계 안내')
    await page
      .getByLabel('답변 문구')
      .fill('확인 결과를 안내합니다.\n'.repeat(40))
    await page
      .getByRole('region', { name: '저장 내용 미리보기' })
      .locator('p')
      .last()
      .scrollIntoViewIfNeeded()
    const save = page.getByRole('button', { name: '매크로 저장' })
    await expect(save).toBeInViewport()
    expect(
      await save.evaluate((button) => {
        const bounds = button.getBoundingClientRect()
        return button.contains(
          document.elementFromPoint(
            bounds.x + bounds.width / 2,
            bounds.y + bounds.height / 2,
          ),
        )
      }),
    ).toBe(true)
    await page.screenshot({
      path: testInfo.outputPath(`macro-sticky-${width}.png`),
    })
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true)
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
    await page.screenshot({
      path: testInfo.outputPath(`macro-editor-${width}.png`),
      fullPage: true,
    })

    await page.getByRole('button', { name: '편집 닫기' }).click()
    await page.getByRole('button', { name: '계속 편집' }).click()
    await expect(name).toHaveValue('다음 단계 안내')
    await page.getByRole('button', { name: '검색', exact: true }).click()
    await expect(
      page.getByRole('dialog', { name: '저장하지 않은 매크로 변경' }),
    ).toBeVisible()
    await page.getByRole('button', { name: '계속 편집' }).click()
    await expect(page).toHaveURL(/\/agent\/personal-macros$/)
    const reloadDialog = page.waitForEvent('dialog')
    void page.reload().catch(() => undefined)
    const dialog = await reloadDialog
    expect(dialog.type()).toBe('beforeunload')
    await dialog.dismiss()
    await expect(name).toHaveValue('다음 단계 안내')
    await save.click()
    await expect(page.getByRole('status')).toHaveText(
      '버전 1을 저장했습니다. 확인 후 활성화하세요.',
    )
    await expect(
      page.getByRole('button', { name: '매크로 만들기' }),
    ).toBeFocused()
    await expect(
      page.getByRole('button', { name: '최신 버전 활성화: 다음 단계 안내' }),
    ).toBeVisible()
    expect(posts).toEqual(['/api/v1/agent/personal-macros'])
    await page.screenshot({
      path: testInfo.outputPath(`macro-list-${width}.png`),
      fullPage: true,
    })
    await page.getByRole('button', { name: '편집: 다음 단계 안내' }).click()
    await page.getByLabel(/매크로 이름/).fill('버릴 변경')
    await page.getByRole('button', { name: '검색', exact: true }).click()
    await page.getByRole('button', { name: '변경 버리기' }).click()
    await expect(page).toHaveURL(/\/agent\/search$/)
  })
}
