import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

const primaryFixtures = [
  ['agent-home', '처리할 티켓을 선택하세요'],
  ['view-queue', '내 티켓'],
  ['workspace', /#1042.*결제 버튼을 누르면 오류가 납니다/],
] as const

for (const [fixture, heading] of primaryFixtures) {
  test(`${fixture} uses production components`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    const response = await page.goto(`/__fixtures__/frontend-system/${fixture}`)
    expect(response?.ok()).toBe(true)
    await expect(
      page.getByRole('heading', { name: heading }).first(),
    ).toBeVisible()
    await expect(
      page.getByRole('navigation', { name: '상담사 전역 탐색' }),
    ).toBeVisible()
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  })
}

for (const fixture of [
  'view-queue-loading',
  'view-queue-empty',
  'view-queue-no-results',
  'view-queue-error',
  'view-queue-denied',
  'view-queue-bulk',
  'workspace-conflict',
]) {
  test(`${fixture} canonical state`, async ({ page }) => {
    await page.goto(`/__fixtures__/frontend-system/${fixture}`)
    if (fixture === 'view-queue-bulk') {
      await expect(
        page.getByRole('region', { name: '선택된 티켓' }),
      ).toContainText('2개 선택됨')
    } else if (fixture === 'workspace-conflict') {
      await expect(
        page.getByRole('region', { name: '담당자 저장 충돌' }),
      ).toBeVisible()
    } else {
      await expect(
        page.getByRole('status').or(page.getByRole('alert')).first(),
      ).toBeVisible()
    }
  })
}

test('view queue fixture exposes the selected view, toolbar controls, and sorting', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/__fixtures__/frontend-system/view-queue')

  await expect(page.getByRole('link', { name: 'Views' })).toHaveAttribute(
    'aria-current',
    'page',
  )
  await expect(
    page.locator('.ds-view-navigation a[aria-current="page"]'),
  ).toHaveCount(1)
  await expect(page.getByRole('button', { name: '필터 열기' })).toBeVisible()
  await expect(page.getByRole('button', { name: '작업' })).toBeVisible()
  await expect(
    page.getByRole('button', { name: '티켓 ID 내림차순' }),
  ).toBeVisible()

  await page.getByRole('button', { name: '필터 열기' }).click()
  await expect(page.getByLabel('내 티켓 필터')).toBeVisible()
  await page.getByRole('button', { name: '티켓 ID 내림차순' }).click()
  await expect(
    page.getByRole('columnheader', { name: '티켓 ID' }),
  ).toHaveAttribute('aria-sort', 'ascending')
})
