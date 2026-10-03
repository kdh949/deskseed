import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
const staff = {
  id: '11111111-1111-4111-8111-111111111111',
  email: 'agent@example.test',
  displayName: '상담사 A',
  role: 'AGENT',
  capabilities: ['AGENT_WORKSPACE'],
}
const csrfToken = 'c'.repeat(32)

function createDetail() {
  return {
    ticket: {
      ticketNumber: 3001,
      subject: '결제 승인 상태 확인 요청',
      status: 'OPEN',
      priority: 'HIGH',
      requester: {
        id: 'customer-3001',
        type: 'CUSTOMER',
        displayName: '고객 A',
      },
      group: { id: 'group-payments', name: '결제 지원' },
      assignee: { id: 'staff-3001', displayName: '상담사 A' },
      createdAt: '2026-08-15T09:00:00Z',
      updatedAt: '2026-08-15T10:02:00Z',
      version: 3,
      isChild: false,
      openChildCount: 0,
      sla: null,
    },
    comments: [
      {
        id: 'comment-3001-public',
        visibility: 'PUBLIC',
        actor: {
          id: 'customer-3001',
          type: 'CUSTOMER',
          displayName: '고객 A',
        },
        body: '결제가 완료됐는지 확인하고 싶습니다.',
        content: {
          format: 'PLAIN_TEXT',
          text: '결제가 완료됐는지 확인하고 싶습니다.',
        },
        createdAt: '2026-08-15T09:00:00Z',
        source: 'WEB',
        attachments: [],
      },
    ],
    capabilities: ['READ', 'UPDATE'],
    assignmentOptions: {
      groups: [
        {
          id: 'group-payments',
          name: '결제 지원',
          members: [{ id: 'staff-3001', displayName: '상담사 A' }],
        },
        {
          id: 'group-shipping',
          name: '배송 지원',
          members: [{ id: 'staff-3002', displayName: '상담사 B' }],
        },
      ],
    },
    context: {
      customer: {
        id: 'customer-3001',
        displayName: '고객 A',
        email: 'customer-a@example.test',
      },
      parent: null,
      children: [],
      externalReferenceCount: 0,
    },
    history: [],
    warnings: [],
  }
}

for (const width of [1280, 1920]) {
  test(`AI capability changes refresh the ticket without sending the draft at ${width}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 })
    const detail = createDetail()
    detail.capabilities = ['READ', 'UPDATE', 'AI_SUMMARY', 'FUTURE_UNKNOWN']
    const readIntents: string[] = []
    let createCount = 0
    let jobReads = 0
    await page.route('**/api/v1/**', async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      if (path === '/api/v1/agent/me') return route.fulfill({ json: staff })
      if (path === '/api/v1/agent/csrf')
        return route.fulfill({
          json: { token: csrfToken, headerName: 'X-CSRF-TOKEN' },
        })
      if (path === '/api/v1/agent/tickets/3001' && request.method() === 'GET') {
        readIntents.push(request.headers()['x-deskseed-read-intent'] ?? '')
        return route.fulfill({ json: detail })
      }
      if (path === '/api/v1/agent/tickets/3001/ai/jobs') {
        if (request.method() === 'GET') {
          jobReads++
          return route.fulfill({ json: { items: [] } })
        }
        expect(request.method()).toBe('POST')
        expect(request.postDataJSON()).toMatchObject({
          feature: 'ticket.summary',
          expectedTicketVersion: 3,
        })
        expect(request.headers()['x-csrf-token']).toBe(csrfToken)
        expect(request.headers()['x-deskseed-expected-staff-id']).toBe(staff.id)
        expect(request.headers()['idempotency-key']).toBeTruthy()
        createCount++
        detail.capabilities = ['READ', 'UPDATE']
        return route.fulfill({
          status: 403,
          json: { title: 'Forbidden', status: 403 },
        })
      }
      if (path.includes('/drafts/') && request.method() === 'PUT')
        return route.fulfill({ status: 503, json: {} })
      if (path === '/api/v1/agent/macros') return route.fulfill({ json: [] })
      if (
        path.includes('/collaboration-notes') ||
        path.includes('/external-references')
      )
        return route.fulfill({ json: { items: [], nextCursor: null } })
      expect(request.method()).toBe('GET')
      return route.fulfill({ status: 404, json: {} })
    })
    await page.goto(
      `${process.env.PLAYWRIGHT_STAFF_BASE_URL ?? '/_staff'}/agent/tickets/3001`,
    )
    await expect(
      page.getByRole('region', { name: '티켓 #3001 작업 공간' }),
    ).toBeVisible()
    const ai = page.getByRole('region', { name: 'DeskSeed AI 어시스턴트' })
    await expect(ai).toBeVisible()
    const buttons = ai.getByRole('button', { name: '생성하기' })
    await expect(buttons.nth(0)).toBeEnabled()
    await expect(buttons.nth(1)).toBeDisabled()
    await expect(buttons.nth(2)).toBeDisabled()
    const draft = page.getByRole('textbox', { name: '공개 답변 내용' })
    await draft.fill('전송하지 않은 합성 상담 초안')
    await buttons.nth(0).focus()
    await page.keyboard.press('Enter')
    await expect(
      ai.getByText('현재 이 티켓에서는 대화 요약을 사용할 수 없습니다.'),
    ).toBeVisible()
    await expect(buttons.nth(0)).toBeDisabled()
    await expect(draft).toHaveText('전송하지 않은 합성 상담 초안')
    expect(createCount).toBe(1)
    expect(jobReads).toBe(1)
    expect(readIntents).toEqual(['NAVIGATION', 'BACKGROUND'])
    expect(
      (await new AxeBuilder({ page }).include('.ai-assistant').analyze())
        .violations,
    ).toEqual([])
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBeTruthy()
    await page.screenshot({
      path: testInfo.outputPath(`ai-availability-${width}.png`),
      fullPage: true,
    })
  })
}
