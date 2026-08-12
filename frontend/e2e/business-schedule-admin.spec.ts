import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Route } from '@playwright/test'

const CSRF_TOKEN = 'schedule-e2e-csrf'
const SCHEDULE_ID = '51000000-0000-0000-0000-000000000001'

function weekdays(saturdayOpen = false) {
  return [
    'MONDAY',
    'TUESDAY',
    'WEDNESDAY',
    'THURSDAY',
    'FRIDAY',
    'SATURDAY',
    'SUNDAY',
  ].map((weekday, index) => ({
    weekday,
    enabled: index < 5 || (weekday === 'SATURDAY' && saturdayOpen),
    intervals:
      index < 5 || (weekday === 'SATURDAY' && saturdayOpen)
        ? [{ start: '09:00', end: '18:00' }]
        : [],
  }))
}

test('admin previews, versions, and activates a weekend and holiday schedule', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 1100 })
  const consoleProblems: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error' || message.type() === 'warning') {
      consoleProblems.push(message.text())
    }
  })
  let currentVersion = 1
  let aggregateVersion = 0
  let activeVersion = 1
  let latestDefinition = {
    name: 'Default Support Hours',
    timeZone: 'Asia/Seoul',
    weekdays: weekdays(),
    exceptions: [] as Array<Record<string, unknown>>,
  }
  const definitions = new Map([[1, structuredClone(latestDefinition)]])

  function schedule(version: number) {
    const definition = definitions.get(version)!
    return {
      id: SCHEDULE_ID,
      ...definition,
      version,
      aggregateVersion,
      active: version === activeVersion,
      createdAt:
        version === 1 ? '2026-08-10T00:00:00Z' : '2026-08-12T10:00:00Z',
      createdBy: {
        actorType: version === 1 ? 'SYSTEM' : 'STAFF',
        actorId: version === 1 ? null : '11000000-0000-4000-8000-000000000001',
        displayName: version === 1 ? 'Deskseed seed' : '김관리',
      },
    }
  }

  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    if (path === '/api/v1/agent/me') {
      return route.fulfill({
        status: 200,
        json: {
          id: '11000000-0000-4000-8000-000000000001',
          email: 'admin@example.com',
          displayName: '김관리',
          role: 'ADMIN',
          capabilities: ['ADMIN_MANAGE', 'AGENT_WORKSPACE'],
        },
      })
    }
    if (path === '/api/v1/agent/csrf') {
      return route.fulfill({
        status: 200,
        json: { token: CSRF_TOKEN, headerName: 'X-CSRF-TOKEN' },
      })
    }
    if (path === '/api/v1/admin/business-schedules' && method === 'GET') {
      return route.fulfill({ status: 200, json: [schedule(currentVersion)] })
    }
    if (
      path === `/api/v1/admin/business-schedules/${SCHEDULE_ID}/versions` &&
      method === 'GET'
    ) {
      return route.fulfill({
        status: 200,
        json: [...definitions.keys()]
          .sort((left, right) => right - left)
          .map(schedule),
      })
    }
    if (
      path === '/api/v1/admin/business-schedules/preview' &&
      method === 'POST'
    ) {
      expect(request.headers()['x-csrf-token']).toBe(CSRF_TOKEN)
      const body = request.postDataJSON()
      expect(body.schedule.weekdays[5]).toEqual({
        weekday: 'SATURDAY',
        enabled: true,
        intervals: [
          { start: '09:00', end: '18:00' },
          { start: '19:00', end: '21:00' },
        ],
      })
      return route.fulfill({
        status: 200,
        json: {
          dueAt: '2026-08-17T01:00:00Z',
          elapsedBusinessMinutes: 120,
          nextOpenAt: '2026-08-14T08:00:00Z',
          nextCloseAt: '2026-08-14T09:00:00Z',
          dstPolicy: 'GAP_SHIFT_FORWARD_OVERLAP_INCLUDE_BOTH',
        },
      })
    }
    if (
      path === `/api/v1/admin/business-schedules/${SCHEDULE_ID}/versions` &&
      method === 'POST'
    ) {
      expect(request.headers()['x-csrf-token']).toBe(CSRF_TOKEN)
      expect(request.headers()['if-match']).toBe('"0"')
      latestDefinition = request.postDataJSON()
      currentVersion = 2
      aggregateVersion = 1
      definitions.set(2, structuredClone(latestDefinition))
      return route.fulfill({ status: 201, json: schedule(2) })
    }
    if (
      path ===
        `/api/v1/admin/business-schedules/${SCHEDULE_ID}/versions/2/activation` &&
      method === 'PUT'
    ) {
      expect(request.headers()['x-csrf-token']).toBe(CSRF_TOKEN)
      expect(request.headers()['if-match']).toBe('"1"')
      aggregateVersion = 2
      activeVersion = 2
      return route.fulfill({ status: 200, json: schedule(2) })
    }
    return route.abort()
  })

  await page.goto('/admin/business-rules/schedules')
  await expect(
    page.getByRole('heading', { name: '업무 시간 일정' }),
  ).toBeVisible()
  await expect(page.getByText('활성 버전', { exact: true })).toBeVisible()
  await expect(page).toHaveScreenshot(
    'business-schedule-admin-default-1440.png',
    {
      fullPage: true,
    },
  )

  await page.getByRole('checkbox', { name: '토요일 영업' }).check()
  await page.getByRole('button', { name: '토요일 시간 구간 추가' }).click()
  await page.getByLabel('토요일 구간 2 시작').fill('19:00')
  await page.getByLabel('토요일 구간 2 종료').fill('21:00')
  await page.getByRole('button', { name: '예외 추가' }).click()
  await page.getByLabel('날짜').fill('2026-08-17')
  await page.getByLabel('설명').fill('광복절 대체 휴일')
  await page.getByRole('button', { name: '미저장 일정 미리보기' }).click()
  await expect(page.getByText('2026. 8. 17. 오전 10:00')).toBeVisible()
  await page.getByRole('heading', { name: '미저장 일정 미리보기' }).click()
  await expect(page).toHaveScreenshot(
    'business-schedule-admin-preview-1440.png',
    {
      fullPage: true,
    },
  )
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await page.getByRole('button', { name: '새 버전 저장' }).click()
  await expect(page.getByText('버전 2가 저장되었습니다.')).toBeVisible()
  await page.getByRole('button', { name: '버전 2 활성화' }).click()
  await expect(page.getByText('버전 2가 활성화되었습니다.')).toBeVisible()
  expect(consoleProblems).toEqual([])
})
