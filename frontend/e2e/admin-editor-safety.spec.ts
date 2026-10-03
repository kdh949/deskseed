import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

const admin = {
  id: '11111111-1111-4111-8111-111111111111',
  email: 'admin@example.test',
  displayName: '운영 관리자',
  role: 'ADMIN',
  capabilities: ['ADMIN_MANAGE'],
}
const editors = [
  {
    path: '/admin/triggers',
    menu: '트리거',
    create: '트리거 만들기',
    field: '트리거 이름',
    confirmation: '저장하지 않은 트리거 변경',
  },
  {
    path: '/admin/automations',
    menu: '시간 자동화',
    create: '자동화 만들기',
    field: '자동화 이름',
    confirmation: '저장하지 않은 자동화 변경',
  },
  {
    path: '/admin/business-rules/schedules',
    menu: '영업 시간표',
    create: '새 영업 시간표',
    field: '시간표 이름',
    confirmation: '저장하지 않은 시간표 변경',
  },
]
for (const width of [1280, 1440, 1920]) {
  for (const editor of editors) {
    test(`${editor.menu} preserves drafts on back, close and reload at ${width}px`, async ({
      page,
    }, testInfo) => {
      await page.setViewportSize({ width, height: 900 })
      await page.route('**/api/v1/**', async (route) => {
        const path = new URL(route.request().url()).pathname
        if (path === '/api/v1/agent/me') return route.fulfill({ json: admin })
        if (
          [
            '/api/v1/admin/triggers',
            '/api/v1/admin/automations',
            '/api/v1/admin/business-schedules',
          ].includes(path)
        )
          return route.fulfill({ json: [] })
        return route.fulfill({ status: 503, json: { status: 503 } })
      })
      const start = editor.menu === '트리거' ? editors[1]! : editors[0]!
      await page.goto(start.path)
      await page.getByRole('link', { name: editor.menu, exact: true }).click()
      await page
        .getByRole('button', { name: editor.create, exact: true })
        .click()
      await page
        .getByRole('textbox', { name: editor.field, exact: true })
        .fill('입력을 보존할 설정')
      if (editor.menu === '영업 시간표')
        await page.getByRole('button', { name: '작성 닫기' }).click()
      else await page.keyboard.press('Escape')
      await expect(
        page.getByRole('dialog', { name: editor.confirmation }),
      ).toBeVisible()
      await page.getByRole('button', { name: '계속 편집' }).click()
      await expect(
        page.getByRole('textbox', { name: editor.field, exact: true }),
      ).toHaveValue('입력을 보존할 설정')
      await page.evaluate(() => window.history.back())
      await expect(
        page.getByRole('dialog', { name: editor.confirmation }),
      ).toBeVisible()
      await page.getByRole('button', { name: '계속 편집' }).click()
      await expect(page).toHaveURL(new RegExp(editor.path + '$'))
      const reloadDialog = page.waitForEvent('dialog')
      void page.reload().catch(() => undefined)
      const dialog = await reloadDialog
      expect(dialog.type()).toBe('beforeunload')
      await dialog.dismiss()
      await expect(
        page.getByRole('textbox', { name: editor.field, exact: true }),
      ).toHaveValue('입력을 보존할 설정')
      expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
      ).toBe(true)
      await page.screenshot({
        path: testInfo.outputPath(`editor-${width}.png`),
        fullPage: true,
      })
      await page.evaluate(() => window.history.back())
      await page.getByRole('button', { name: '변경 버리기' }).click()
      await expect(page).toHaveURL(new RegExp(start.path + '$'))
    })
  }
}
