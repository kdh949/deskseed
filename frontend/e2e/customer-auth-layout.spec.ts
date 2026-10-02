import { expect, test } from '@playwright/test'

for (const viewport of [
  { width: 390, height: 844 },
  { width: 768, height: 1024 },
  { width: 1448, height: 1086 },
]) {
  test(`customer authentication stays in reading order at ${viewport.width}px`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize(viewport)
    await page.route('**/api/v1/**', (route) =>
      route.fulfill({ status: 401, json: { status: 401 } }),
    )
    await page.goto('/customer/password-reset')
    const heading = page.getByRole('heading', { name: '비밀번호 재설정' })
    const guidance = page.getByText(
      '비밀번호로 가입한 계정의 이메일을 입력해 주세요.',
    )
    const email = page.getByLabel('이메일 주소', { exact: true })
    await expect(email).toBeVisible()
    const headingBox = await heading.boundingBox()
    const guidanceBox = await guidance.boundingBox()
    const emailBox = await email.boundingBox()
    expect(guidanceBox!.y).toBeGreaterThanOrEqual(
      headingBox!.y + headingBox!.height,
    )
    expect(emailBox!.y).toBeGreaterThanOrEqual(
      guidanceBox!.y + guidanceBox!.height,
    )
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBeLessThanOrEqual(viewport.width)
    await email.focus()
    await page.keyboard.press('Tab')
    await expect(
      page.getByRole('button', { name: '재설정 링크 요청' }),
    ).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(
      page.getByRole('link', { name: '로그인으로 돌아가기' }),
    ).toBeFocused()
    await page.screenshot({
      path: testInfo.outputPath('reset-request.png'),
      fullPage: true,
    })

    await page.goto(
      '/customer/password/reset#token=synthetic-reset-proof-0000000000000000',
    )
    const password = page.getByLabel('새 비밀번호', { exact: true })
    await expect(password).toBeVisible()
    await expect(page).toHaveURL(/\/customer\/password\/reset$/)
    const resetHeading = await page
      .getByRole('heading', { name: '새 비밀번호 설정' })
      .boundingBox()
    const resetGuidance = await page
      .getByText(/12~128자의 새 비밀번호/)
      .boundingBox()
    const passwordBox = await password.boundingBox()
    expect(resetGuidance!.y).toBeGreaterThanOrEqual(
      resetHeading!.y + resetHeading!.height,
    )
    expect(passwordBox!.y).toBeGreaterThanOrEqual(
      resetGuidance!.y + resetGuidance!.height,
    )
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBeLessThanOrEqual(viewport.width)
    await page.screenshot({
      path: testInfo.outputPath('reset-ready.png'),
      fullPage: true,
    })

    await page.goto('/customer/sign-in')
    await page.getByRole('button', { name: '비밀번호', exact: true }).focus()
    await page.keyboard.press('Tab')
    await expect(
      page.getByRole('button', { name: '이메일 링크', exact: true }),
    ).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(
      page.getByText(
        '비밀번호 없이 문의한 고객은 이메일 링크로 로그인할 수 있습니다.',
      ),
    ).toBeVisible()
    await expect(
      page.getByRole('link', { name: '비밀번호를 잊으셨나요?' }),
    ).toHaveCount(0)
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBeLessThanOrEqual(viewport.width)
    await page.screenshot({
      path: testInfo.outputPath('sign-in-email-link.png'),
      fullPage: true,
    })
  })
}
