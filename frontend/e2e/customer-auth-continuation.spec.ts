import { expect, test } from '@playwright/test'

const customer = {
  id: '22222222-2222-4222-8222-222222222222',
  email: 'customer@example.test',
  displayName: '고객',
  companyName: '회사',
  verifiedAt: '2026-09-01T00:00:00Z',
  credentialState: 'PASSWORD',
  registrationState: 'COMPLETE',
  availableAuthenticationMethods: ['PASSWORD'],
}

test('email opened in another tab retains the requested ticket through expired-link reissue', async ({
  context,
  page,
}) => {
  let signedIn = false
  let consumes = 0
  await context.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/customer/me'))
      return route.fulfill(
        signedIn ? { json: customer } : { status: 401, json: { status: 401 } },
      )
    if (url.pathname.endsWith('/magic-link-requests'))
      return route.fulfill({ status: 202, json: {} })
    if (url.pathname.endsWith('/magic-link-sessions')) {
      consumes++
      signedIn = consumes > 1
      return route.fulfill(
        signedIn ? { json: customer } : { status: 401, json: { status: 401 } },
      )
    }
    return route.fulfill({ status: 404, json: { status: 404 } })
  })
  await page.goto('/account/requests/24')
  await page.getByRole('button', { name: '이메일 링크', exact: true }).click()
  await page.getByLabel('이메일 주소', { exact: false }).fill(customer.email)
  await page.getByRole('button', { name: '로그인 링크 보내기' }).click()
  await expect(
    page.getByRole('heading', { name: '받은 편지함을 확인해 주세요' }),
  ).toBeVisible()
  const mailTab = await context.newPage()
  await mailTab.goto('/customer/sign-in/consume#token=expired-synthetic-link')
  await mailTab.getByRole('link', { name: '새 로그인 링크 요청' }).click()
  await mailTab
    .getByRole('button', { name: '이메일 링크', exact: true })
    .click()
  await mailTab.getByLabel('이메일 주소', { exact: false }).fill(customer.email)
  await mailTab.getByRole('button', { name: '로그인 링크 보내기' }).click()
  await expect(
    mailTab.getByRole('heading', { name: '받은 편지함을 확인해 주세요' }),
  ).toBeVisible()
  const secondMailTab = await context.newPage()
  await secondMailTab.goto(
    '/customer/sign-in/consume#token=valid-synthetic-link',
  )
  await expect(secondMailTab).toHaveURL(/\/account\/requests\/24$/)
  expect(
    await secondMailTab.evaluate(() =>
      localStorage.getItem('deskseed-customer-login-return'),
    ),
  ).toBeNull()
  expect(consumes).toBe(2)
})
