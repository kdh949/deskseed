import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Request } from '@playwright/test'

const CHANGE_ID = 'a0000000-0000-0000-0000-000000000001'
const SEARCH_ID = 'a0000000-0000-0000-0000-000000000002'
const ADMIN_ID = 'a0000000-0000-0000-0000-000000000003'
const EVENT_ID = 'b0000000-0000-0000-0000-000000000001'
const ACTOR_ID = 'c0000000-0000-4000-8000-000000000001'
const RAW_QUERY = 'incident-owner@example.com priority:urgent'

const actor = {
  id: ACTOR_ID,
  type: 'STAFF',
  displayName: '김보안 감사자',
}

const change = {
  id: CHANGE_ID,
  ledger: 'TICKET_CHANGE',
  action: 'STATUS_CHANGED',
  actor,
  occurredAt: '2026-08-11T01:42:00Z',
  ticketNumber: 1042,
  groupId: 'd0000000-0000-4000-8000-000000000001',
  field: 'status',
  resourceType: 'TICKET',
  resourceId: 'e0000000-0000-4000-8000-000000000001',
  summary: 'status changed · ticket #1042',
  source: 'AGENT_UI',
  outcome: 'SUCCEEDED',
  requestId: 'req-ticket-1042',
  correlationId: 'corr-incident-42',
  protectedContentAvailable: false,
  searchFingerprint: null,
}

const search = {
  ...change,
  id: SEARCH_ID,
  ledger: 'ACCESS_SEARCH',
  action: 'SEARCH_EXECUTED',
  occurredAt: '2026-08-11T01:40:12Z',
  ticketNumber: null,
  groupId: null,
  field: null,
  resourceType: 'SEARCH',
  resourceId: null,
  summary: 'ticket search executed · 4 authorized results',
  protectedContentAvailable: true,
  searchFingerprint: 'hmac-v2:08dd5f1cb5ad448b',
}

const admin = {
  ...change,
  id: ADMIN_ID,
  ledger: 'ADMIN_SECURITY',
  action: 'STAFF_ACCOUNT_DISABLED',
  occurredAt: '2026-08-11T01:37:41Z',
  ticketNumber: null,
  groupId: null,
  field: null,
  resourceType: 'STAFF_ACCOUNT',
  resourceId: 'e0000000-0000-4000-8000-000000000002',
  summary: 'staff account disabled',
  source: 'ADMIN_UI',
  outcome: 'SUCCEEDED',
}

async function installAuditApi(page: Page) {
  let revealRequest: Request | null = null
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const json = (body: unknown, status = 200) =>
      route.fulfill({
        status,
        contentType: 'application/json',
        headers: { 'Cache-Control': 'no-store' },
        body: JSON.stringify(body),
      })

    if (url.pathname === '/api/v1/agent/me') {
      return json({
        id: ACTOR_ID,
        email: 'security-auditor@deskseed.test',
        displayName: actor.displayName,
        role: 'SECURITY_AUDITOR',
        capabilities: [
          'audit:activity:read',
          'audit:search-query:reveal',
          'audit:export',
          'audit:projection:rebuild',
        ],
      })
    }
    if (url.pathname === '/api/v1/audit/activities') {
      expect(request.headers()['x-interaction-id']).toMatch(/^[0-9a-f-]{36}$/)
      return json({
        items: [change, search, admin],
        nextCursor: null,
        snapshotAt: '2026-08-11T02:00:00Z',
        projection: {
          state: 'CURRENT',
          projectedCount: 1_000_003,
          lastRebuiltAt: '2026-08-11T00:00:00Z',
        },
      })
    }
    if (url.pathname === `/api/v1/audit/activities/${CHANGE_ID}`) {
      return json({
        ...change,
        canonicalEventId: EVENT_ID,
        canonicalParentId: 'b0000000-0000-0000-0000-000000000002',
        fieldChange: { field: 'status', before: 'OPEN', after: 'PENDING' },
        interactionId: 'f0000000-0000-4000-8000-000000000001',
        sessionFingerprint: 'hmac-v1:session-safe-fingerprint',
        authType: 'STAFF_SESSION',
        ipAddress: '192.0.2.42',
        userAgent: 'Deskseed approved browser metadata',
        search: null,
        metadata: { visibility: 'PUBLIC' },
      })
    }
    if (url.pathname === `/api/v1/audit/activities/${SEARCH_ID}`) {
      return json({
        ...search,
        canonicalEventId: 'b0000000-0000-0000-0000-000000000003',
        canonicalParentId: null,
        fieldChange: null,
        interactionId: 'f0000000-0000-4000-8000-000000000002',
        sessionFingerprint: 'hmac-v1:session-safe-fingerprint',
        authType: 'STAFF_SESSION',
        ipAddress: '192.0.2.42',
        userAgent: 'Deskseed approved browser metadata',
        search: {
          queryRedacted: '[PROTECTED]',
          queryFingerprint: 'hmac-v2:08dd5f1cb5ad448b',
          filters: { status: 'OPEN', groupId: 'support-emea' },
          sort: 'updatedAt:desc,ticketNumber:desc',
          resultCount: 4,
          originSearchActivityId: null,
          openedActivityCount: 1,
          openedActivitiesTruncated: false,
          openedActivities: [
            {
              activityId: 'a0000000-0000-0000-0000-000000000004',
              ticketNumber: 1042,
              occurredAt: '2026-08-11T01:40:18Z',
            },
          ],
        },
        metadata: { httpStatus: 200 },
      })
    }
    if (url.pathname === '/api/v1/agent/csrf') {
      return json({ token: 'audit-csrf-token', headerName: 'X-CSRF-TOKEN' })
    }
    if (
      url.pathname ===
      `/api/v1/audit/activities/${SEARCH_ID}/search-query-reveal`
    ) {
      revealRequest = request
      expect(request.method()).toBe('POST')
      expect(request.headers()['x-csrf-token']).toBe('audit-csrf-token')
      expect(request.headers()['x-interaction-id']).toMatch(/^[0-9a-f-]{36}$/)
      expect(request.postDataJSON()).toEqual({
        reason: 'incident 42 접근 조사',
      })
      return json({
        activityId: SEARCH_ID,
        state: 'AVAILABLE',
        rawQuery: RAW_QUERY,
        keyVersion: 'audit-local-v2',
        revealedAt: '2026-08-11T02:01:00Z',
      })
    }
    return json(
      {
        type: 'about:blank',
        title: 'Not found',
        status: 404,
        code: 'NOT_FOUND',
      },
      404,
    )
  })
  return () => revealRequest
}

for (const width of [1280, 1440, 1920]) {
  test(`audit investigation is accessible and visually stable at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 1000 })
    const revealRequest = await installAuditApi(page)

    await page.goto('/audit/activity')
    await expect(page.getByRole('heading', { name: '활동 조사' })).toBeVisible()
    await expect(
      page.getByRole('button', { name: 'STATUS_CHANGED' }),
    ).toBeVisible()
    await expect(page.getByText(RAW_QUERY)).toHaveCount(0)
    await expect(page).toHaveScreenshot(`audit-explorer-list-${width}.png`, {
      fullPage: true,
    })
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

    await page.getByRole('button', { name: 'STATUS_CHANGED' }).click()
    await expect(page.getByRole('dialog', { name: '활동 상세' })).toBeVisible()
    await expect(page.getByText('OPEN')).toBeVisible()
    await expect(page.getByText('PENDING')).toBeVisible()
    await expect(page).toHaveScreenshot(`audit-explorer-detail-${width}.png`)
    expect(
      (await new AxeBuilder({ page }).include('.audit-detail-drawer').analyze())
        .violations,
    ).toEqual([])
    await page.getByRole('button', { name: '닫기' }).click()

    await page.getByRole('button', { name: 'SEARCH_EXECUTED' }).click()
    await expect(page.getByText('[PROTECTED]')).toBeVisible()
    const revealButton = page.getByRole('button', {
      name: '이 event의 raw query 공개',
    })
    await expect(revealButton).toBeDisabled()
    await page
      .getByRole('textbox', { name: '공개 사유' })
      .fill('incident 42 접근 조사')
    await revealButton.click()
    await expect(page.getByText(RAW_QUERY)).toBeVisible()
    expect(revealRequest()).not.toBeNull()
    await expect(page).toHaveScreenshot(`audit-explorer-reveal-${width}.png`)
    expect(
      (await new AxeBuilder({ page }).include('.audit-detail-drawer').analyze())
        .violations,
    ).toEqual([])

    await page.getByRole('button', { name: '닫기' }).click()
    await expect(page.getByText(RAW_QUERY)).toHaveCount(0)
  })
}
