import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

test('production TicketWorkspace preserves separate PUBLIC and INTERNAL drafts', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1472, height: 1046 })
  await page.goto('/__fixtures__/frontend-system/workspace')
  await expect(
    page.getByRole('main', { name: '티켓 #1042 작업 공간' }),
  ).toBeVisible()

  await page.getByRole('tab', { name: '공개 답변 작성 모드로 전환' }).click()
  await page
    .getByRole('textbox', { name: '공개 답변 내용' })
    .fill('고객 안내 초안')
  await page.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }).click()
  await page
    .getByRole('textbox', { name: '내부 메모 내용' })
    .fill('팀 확인 메모')
  await page.getByRole('tab', { name: '공개 답변 작성 모드로 전환' }).click()
  await expect(
    page.getByRole('textbox', { name: '공개 답변 내용' }),
  ).toHaveValue('고객 안내 초안')
  await page.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }).click()
  await expect(
    page.getByRole('textbox', { name: '내부 메모 내용' }),
  ).toHaveValue('팀 확인 메모')
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})
