import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Route } from '@playwright/test'

const POLICY_ID = '62000000-0000-0000-0000-000000000001'
const SCHEDULE_ID = '51000000-0000-0000-0000-000000000001'

const weekdays = [
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
}))

function policy(version: number) {
  return {
    id: POLICY_ID,
    name: '고객지원 First Reply',
    position: 10,
    scheduleId: SCHEDULE_ID,
    scheduleVersion: 1,
    conditions: { groupId: null, channel: 'WEB' },
    targets: { LOW: 480, NORMAL: 240, HIGH: 120, URGENT: 60 },
    pauseStatuses: ['PENDING'],
    version,
    activeVersion: 1,
    aggregateVersion: 1,
    active: version === 1,
    createdAt: '2026-08-12T00:00:00Z',
    createdBy: {
      actorType: 'STAFF',
      actorId: '11000000-0000-4000-8000-000000000001',
      displayName: '김관리',
    },
  }
}

const schedule = {
  id: SCHEDULE_ID,
  name: 'Default Support Hours',
  timeZone: 'Asia/Seoul',
  weekdays,
  exceptions: [],
  version: 2,
  activeVersion: 1,
  activeTimeZone: 'Asia/Seoul',
  aggregateVersion: 1,
  active: false,
  createdAt: '2026-08-12T00:00:00Z',
  createdBy: {
    actorType: 'STAFF',
    actorId: '11000000-0000-4000-8000-000000000001',
    displayName: '김관리',
  },
}

test('admin previews SLA with independent sample fields and active pointers', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 1100 })
  const consoleProblems: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error' || message.type() === 'warning') {
      consoleProblems.push(message.text())
    }
  })

  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
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
        json: { token: 'sla-e2e-csrf', headerName: 'X-CSRF-TOKEN' },
      })
    }
    if (path === '/api/v1/admin/sla-policies') {
      return route.fulfill({ status: 200, json: [policy(2)] })
    }
    if (path === `/api/v1/admin/sla-policies/${POLICY_ID}/versions`) {
      return route.fulfill({ status: 200, json: [policy(2), policy(1)] })
    }
    if (path === '/api/v1/admin/business-schedules') {
      return route.fulfill({ status: 200, json: [schedule] })
    }
    if (path === '/api/v1/analytics/first-reply-sla') {
      return route.fulfill({
        status: 200,
        json: {
          metric: 'FIRST_REPLY',
          calculationVersion: 'FIRST_REPLY_BUSINESS_TIME_V1',
          active: 3,
          paused: 1,
          achieved: 8,
          breached: 2,
          cancelled: 1,
          noPolicy: 4,
          achievedRateDenominator: 10,
          achievedRate: 0.8,
        },
      })
    }
    if (
      path === '/api/v1/admin/sla-policies/preview' &&
      request.method() === 'POST'
    ) {
      const body = request.postDataJSON()
      expect(body.candidatePolicyId).toBe(POLICY_ID)
      expect(body.ticket.groupId).toBe('71000000-0000-0000-0000-000000000001')
      expect(body.startAt).toBe('2026-08-14T09:30:00.000Z')
      return route.fulfill({
        status: 200,
        json: {
          matched: true,
          dueAt: '2026-08-17T03:00:00Z',
          targetMinutes: 240,
          policyId: null,
          policyVersion: null,
          scheduleId: SCHEDULE_ID,
          scheduleVersion: 1,
          dstPolicy: 'GAP_SHIFT_FORWARD_OVERLAP_INCLUDE_BOTH',
        },
      })
    }
    return route.abort()
  })

  await page.goto('/admin/business-rules/sla')
  await expect(
    page.getByRole('heading', { name: 'First Reply SLA 정책' }),
  ).toBeVisible()
  await expect(page.getByText('활성 v1 · 최신 v2 초안')).toBeVisible()
  await expect(page.getByLabel('SOLVED')).toHaveCount(0)
  await expect(page.getByLabel('CLOSED')).toHaveCount(0)
  await page
    .getByLabel('Sample group ID')
    .fill('71000000-0000-0000-0000-000000000001')
  await page.getByRole('button', { name: '선택·기한 preview' }).click()
  await expect(page.getByText('2026. 8. 17. 오후 12:00')).toBeVisible()
  await expect(page.getByText('Asia/Seoul 기준')).toBeVisible()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  expect(consoleProblems).toEqual([])
  await expect(page).toHaveScreenshot('first-reply-sla-admin-1440.png', {
    fullPage: true,
  })
})
