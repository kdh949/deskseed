import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

const fixtures = [
  ['agent-home', /좋은 오후예요/],
  ['view-queue', /내 open/],
  ['workspace', /결제 승인 오류/],
  ['admin', /직원 계정/],
  ['public-form', /무엇을 도와드릴까요/],
  ['public-detail', /결제 오류 문의/],
] as const

const viewports = [
  { width: 1280, height: 800 },
  { width: 1440, height: 900 },
  { width: 1920, height: 1080 },
]

const stateFixtures = [
  ['workspace-internal', /결제 승인 오류/],
  ['workspace-conflict', /결제 승인 오류/],
  ['states', /Deskseed 상태 프리미티브/],
] as const

for (const [fixture, heading] of fixtures) {
  for (const viewport of viewports) {
    test(`${fixture} ${viewport.width}px 결정론적 시각 회귀`, async ({
      page,
    }) => {
      await page.setViewportSize(viewport)
      await page.goto(`/__fixtures__/frontend-system/${fixture}`)
      await expect(
        page.getByRole('heading', { name: heading }).first(),
      ).toBeVisible()
      await expect(page).toHaveScreenshot(
        `frontend-system-${fixture}-${viewport.width}.png`,
        { fullPage: true },
      )
      if (viewport.width === 1440) await expectNoAxeViolations(page)
    })
  }
}

for (const [fixture, heading] of stateFixtures) {
  test(`${fixture} 핵심 상태 시각 회귀`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/__fixtures__/frontend-system/${fixture}`)
    await expect(
      page.getByRole('heading', { name: heading }).first(),
    ).toBeVisible()
    if (fixture === 'workspace-conflict') {
      await expect(page.getByRole('alert')).toContainText(
        '담당자 변경이 충돌했습니다.',
      )
    }
    await expect(page).toHaveScreenshot(`frontend-system-${fixture}-1440.png`, {
      fullPage: true,
    })
    await expectNoAxeViolations(page)
  })
}

test('skip link, context tabs, composer modes, and resize handles preserve keyboard semantics', async ({
  page,
}) => {
  await page.goto('/__fixtures__/frontend-system/public-form')
  await expect(
    page.getByRole('heading', { name: '무엇을 도와드릴까요?' }),
  ).toBeVisible()
  await page.evaluate(() => {
    if (document.activeElement instanceof HTMLElement) {
      document.activeElement.blur()
    }
  })
  await page.keyboard.press('Tab')
  await expect(
    page.getByRole('link', { name: '본문으로 건너뛰기' }),
  ).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.locator('#main-content')).toBeFocused()

  await page.goto('/__fixtures__/frontend-system/workspace-internal')
  const propertySeparator = page.getByRole('separator', {
    name: '속성 패널 너비 조절',
  })
  await propertySeparator.focus()
  await page.keyboard.press('ArrowRight')
  await expect(propertySeparator).toHaveAttribute('aria-valuenow', '316')

  const customerTab = page.getByRole('tab', { name: '고객' })
  await customerTab.focus()
  await page.keyboard.press('ArrowRight')
  await expect(page.getByRole('tab', { name: '기록' })).toBeFocused()
  await expect(page.getByRole('tabpanel')).toContainText('ASSIGNEE CHANGED')

  await expect(page.getByRole('status')).toContainText(
    '고객에게 공개되지 않습니다',
  )
  await page.getByRole('textbox', { name: '내부 메모' }).fill('팀 확인 메모')
  await page.getByRole('tab', { name: '공개 답변' }).click()
  await page.getByRole('textbox', { name: '공개 답변' }).fill('고객 안내 답변')
  await page.getByRole('tab', { name: '내부 메모' }).click()
  await expect(page.getByRole('textbox', { name: '내부 메모' })).toHaveValue(
    '팀 확인 메모',
  )

  await expect(page.locator('.visibility-internal').first()).toContainText(
    '내부 메모',
  )
  await expect(page.locator('.status-badge').first()).toContainText('처리 중')
  await expectNoAxeViolations(page)
})

async function expectNoAxeViolations(page: Page) {
  const results = await new AxeBuilder({ page }).analyze()
  expect(results.violations).toEqual([])
}
