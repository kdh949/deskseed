import { expect, test } from '@playwright/test'

for (const viewport of [
  { width: 390, height: 844 },
  { width: 1448, height: 1086 },
]) {
  test(`customer corrects a rejected field without losing the request at ${viewport.width}px`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize(viewport)
    const submissions: Record<string, unknown>[] = []
    await page.route('**/api/v1/**', async (route) => {
      const pathname = new URL(route.request().url()).pathname
      if (pathname.endsWith('/customer/me'))
        return route.fulfill({ status: 401, json: { status: 401 } })
      if (pathname.endsWith('/customer/access-mode'))
        return route.fulfill({ json: { mode: 'ANONYMOUS_ALLOWED' } })
      if (pathname.endsWith('/customer/ticket-forms'))
        return route.fulfill({
          status: 404,
          json: { type: '/problems/customer-ticket-form-unavailable' },
        })
      if (pathname.endsWith('/customer/consent-policies'))
        return route.fulfill({
          json: { context: 'REQUEST_SUBMISSION', policies: [] },
        })
      if (
        pathname === '/api/v1/requests' &&
        route.request().method() === 'POST'
      ) {
        submissions.push(route.request().postDataJSON())
        if (submissions.length === 1)
          return route.fulfill({
            status: 400,
            json: {
              type: '/problems/customer-request-validation-failed',
              title: 'Request validation failed',
              status: 400,
              requestId: 'req-browser-fields',
              fieldErrors: [
                {
                  field: 'requester.email',
                  message: '이메일 주소를 확인해 주세요.',
                },
                { field: 'subject', message: '제목을 확인해 주세요.' },
              ],
            },
          })
        return route.fulfill({
          status: 201,
          json: {
            ticketNumber: 1042,
            status: 'NEW',
            replayed: false,
            createdAt: '2026-08-15T00:00:00Z',
            accessToken: 'a'.repeat(43),
          },
        })
      }
      return route.fulfill({ status: 404, json: { status: 404 } })
    })
    await page.goto('/requests/new')
    await page
      .getByRole('textbox', { name: '이름', exact: true })
      .fill('김민아')
    const email = page.getByRole('textbox', { name: '이메일', exact: true })
    const subject = page.getByRole('textbox', { name: '제목', exact: true })
    await email.fill('mina@example.test')
    await subject.fill('결제 확인 요청')
    await page
      .getByRole('textbox', { name: '문의 내용', exact: true })
      .fill('결제를 확인해 주세요.')
    await page.getByRole('button', { name: '문의 접수', exact: true }).click()
    await expect(email).toBeFocused()
    await expect(email).toHaveAccessibleDescription(
      '이메일 주소를 확인해 주세요.',
    )
    await expect(subject).toHaveAccessibleDescription('제목을 확인해 주세요.')
    await expect(page.getByText('요청 ID: req-browser-fields')).toBeVisible()
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBeLessThanOrEqual(viewport.width)
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({
      path: testInfo.outputPath('field-errors.png'),
      fullPage: true,
    })
    await email.fill('corrected@example.test')
    await expect(email).not.toHaveAttribute('aria-invalid')
    await expect(subject).toHaveAccessibleDescription('제목을 확인해 주세요.')
    await subject.fill('결제 확인 요청 수정')
    await expect(
      page.getByRole('textbox', { name: '문의 내용', exact: true }),
    ).toHaveValue('결제를 확인해 주세요.')
    await page.getByRole('button', { name: '문의 접수', exact: true }).click()
    await expect(page).toHaveURL(/\/requests\/submitted\/1042$/)
    expect(submissions).toHaveLength(2)
    expect(submissions[1]).toMatchObject({
      requester: { name: '김민아', email: 'corrected@example.test' },
      subject: '결제 확인 요청 수정',
      message: '결제를 확인해 주세요.',
    })
    expect(submissions[1]!.clientCommandId).not.toBe(
      submissions[0]!.clientCommandId,
    )
  })
}
