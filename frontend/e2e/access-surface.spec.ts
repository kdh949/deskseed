import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

async function mockStaff(page: Page, staff: object | null) {
  await page.route('**/api/v1/agent/me', (route) =>
    staff
      ? route.fulfill({ status: 200, json: staff })
      : route.fulfill({ status: 401, json: { status: 401 } }),
  )
}

test('anonymous staff sees the minimum login surface', async ({ page }) => {
  await mockStaff(page, null)
  await page.goto('/agent/login')

  await expect(page.getByRole('heading', { name: '직원 로그인' })).toBeVisible()
  await expect(page.getByLabel('이메일')).toBeEnabled()
  await expect(page.getByLabel('비밀번호')).toBeEnabled()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})

test('removed and unknown routes use the canonical not-found state', async ({
  page,
}) => {
  await page.goto('/admin/staff')

  await expect(
    page.getByRole('heading', { name: '페이지를 찾을 수 없습니다.' }),
  ).toBeVisible()
  await expect(
    page.getByRole('link', { name: '고객 지원 홈으로 이동' }),
  ).toBeVisible()
})

test('SECURITY_AUDITOR remains denied from the Agent Workspace', async ({
  page,
}) => {
  await mockStaff(page, {
    id: 'auditor-e2e',
    email: 'auditor@example.com',
    displayName: 'Security Auditor',
    role: 'SECURITY_AUDITOR',
    capabilities: ['AUDIT_READ'],
  })
  await page.goto('/agent/views/my-open')

  await expect(
    page.getByRole('heading', {
      name: '상담사 작업 공간 권한이 필요합니다.',
    }),
  ).toBeVisible()
})
