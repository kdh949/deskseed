import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

const actorId = '11111111-1111-4111-8111-111111111111'
const policyId = '22222222-2222-4222-8222-222222222222'
const scheduleId = '33333333-3333-4333-8333-333333333333'
const createdBy = { actorType: 'STAFF', actorId, displayName: '운영 관리자' }
const policyName = '결제·환불과 해외 고객의 장기 이용 문의 최초 답변 정책'

for (const viewport of [
  { width: 1280, height: 800 },
  { width: 1440, height: 900 },
  { width: 1920, height: 1080 },
]) {
  test(`SLA reporting scope and denominator remain usable at ${viewport.width}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize(viewport)
    await page.clock.setFixedTime(new Date('2026-10-03T03:00:00Z'))
    const analyticsRequests: URL[] = []
    await page.route('**/api/v1/**', async (route) => {
      const url = new URL(route.request().url())
      if (url.pathname === '/api/v1/agent/me') {
        return route.fulfill({
          json: {
            id: actorId,
            email: 'admin@example.test',
            displayName: '운영 관리자',
            role: 'ADMIN',
            capabilities: ['ADMIN_MANAGE'],
          },
        })
      }
      if (url.pathname === '/api/v1/admin/sla-policies') {
        return route.fulfill({
          json: [
            {
              id: policyId,
              name: policyName,
              position: 10,
              scheduleId,
              scheduleVersion: 1,
              conditions: { groupId: null, channel: 'WEB' },
              targets: { LOW: 480, NORMAL: 240, HIGH: 120, URGENT: 60 },
              pauseStatuses: ['PENDING'],
              version: 1,
              activeVersion: 1,
              aggregateVersion: 2,
              active: true,
              createdAt: '2026-08-15T10:00:00Z',
              createdBy,
            },
          ],
        })
      }
      if (url.pathname === '/api/v1/admin/business-schedules') {
        return route.fulfill({
          json: [
            {
              id: scheduleId,
              name: '한국 고객지원 운영시간',
              timeZone: 'Asia/Seoul',
              weekdays: [
                'MONDAY',
                'TUESDAY',
                'WEDNESDAY',
                'THURSDAY',
                'FRIDAY',
                'SATURDAY',
                'SUNDAY',
              ].map((weekday, index) => ({
                weekday,
                enabled: index < 5,
                intervals: index < 5 ? [{ start: '09:00', end: '18:00' }] : [],
              })),
              exceptions: [],
              version: 1,
              activeVersion: 1,
              activeTimeZone: 'Asia/Seoul',
              aggregateVersion: 2,
              active: true,
              createdAt: '2026-08-15T10:00:00Z',
              createdBy,
            },
          ],
        })
      }
      if (url.pathname === '/api/v1/admin/groups') {
        return route.fulfill({
          json: [],
          headers: {
            'X-Page-Number': '0',
            'X-Page-Size': '100',
            'X-Total-Count': '0',
            'X-Total-Pages': '0',
          },
        })
      }
      if (url.pathname === '/api/v1/analytics/first-reply-sla') {
        analyticsRequests.push(url)
        const filtered =
          url.searchParams.get('policyId') === policyId &&
          url.searchParams.get('priority') === 'HIGH'
        return route.fulfill({
          json: {
            metric: 'FIRST_REPLY',
            calculationVersion: 'v1',
            active: 1200,
            paused: 14,
            achieved: filtered ? 8 : 2000,
            breached: filtered ? 2 : 200,
            cancelled: 31,
            noPolicy: 27,
            achievedRateDenominator: filtered ? 10 : 2200,
            achievedRate: filtered ? 0.8 : 0.909,
          },
        })
      }
      return route.fulfill({ status: 404, json: { status: 404 } })
    })

    await page.goto('/_staff/admin/business-rules/sla')
    const region = page.getByRole('region', {
      name: '현재 First Reply SLA 성과',
    })
    await expect(region.getByText('2,200건')).toBeVisible()
    await expect(
      region.getByText('기간 필터는 적용하지 않습니다.', { exact: false }),
    ).toBeVisible()
    await expect(region.locator('time')).toHaveAttribute(
      'datetime',
      '2026-10-03T03:00:00.000Z',
    )
    await page.getByLabel('집계 정책').focus()
    await page.keyboard.press('Tab')
    await expect(page.getByLabel('집계 우선순위')).toBeFocused()
    await page.getByLabel('집계 정책').selectOption(policyId)
    await page.getByLabel('집계 우선순위').selectOption('HIGH')
    await expect(region.getByText('80%')).toBeVisible()
    await expect(region.getByText('10건')).toBeVisible()
    expect(analyticsRequests.at(-1)?.searchParams.get('policyId')).toBe(
      policyId,
    )
    expect(analyticsRequests.at(-1)?.searchParams.get('priority')).toBe('HIGH')
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true)
    if (viewport.width === 1440) {
      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
        .analyze()
      expect(results.violations).toEqual([])
    }
    const screenshotPath = testInfo.outputPath(
      `sla-analytics-${viewport.width}.png`,
    )
    await page.screenshot({ path: screenshotPath, fullPage: true })
    await testInfo.attach(`sla-analytics-${viewport.width}`, {
      path: screenshotPath,
      contentType: 'image/png',
    })
  })
}
