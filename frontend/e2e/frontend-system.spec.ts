import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

async function mockReadOnlyTicket(page: Page) {
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/v1/agent/me') {
      return route.fulfill({
        status: 200,
        json: {
          id: '11111111-1111-4111-8111-111111111111',
          email: 'agent@example.test',
          displayName: '상담사 A',
          role: 'AGENT',
          capabilities: ['AGENT_WORKSPACE'],
        },
      })
    }
    if (url.pathname === '/api/v1/agent/tickets/3001') {
      return route.fulfill({
        status: 200,
        json: {
          ticket: {
            ticketNumber: 3001,
            subject: '실제 API 응답만 표시하는 티켓',
            status: 'CLOSED',
            priority: 'NORMAL',
            requester: {
              id: 'customer-3001',
              type: 'CUSTOMER',
              displayName: '고객 A',
            },
            group: null,
            assignee: null,
            updatedAt: '2026-08-15T10:02:00Z',
            version: 8,
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
              body: '이 본문은 API에서 왔습니다.',
              createdAt: '2026-08-15T09:00:00Z',
              source: 'WEB',
              attachments: [],
            },
          ],
          capabilities: ['READ'],
          assignmentOptions: { groups: [] },
          context: {
            customer: {
              id: 'customer-3001',
              displayName: '고객 A',
              email: 'customer-a@example.test',
            },
            parent: null,
            children: [],
            externalReferences: [],
          },
          history: [],
          warnings: [],
        },
      })
    }
    return route.abort()
  })
}

test('legacy frontend fixture paths are not routable in the production application', async ({
  page,
}) => {
  await page.goto('/__fixtures__/frontend-system/workspace')

  await expect(
    page.getByRole('heading', { name: '페이지를 찾을 수 없습니다.' }),
  ).toBeVisible()
  await expect(
    page.getByRole('main', { name: '티켓 #1042 작업 공간' }),
  ).toHaveCount(0)
})

test('production agent UI renders only the ticket API projection and its capability surface', async ({
  page,
}) => {
  await mockReadOnlyTicket(page)
  await page.goto('/agent/tickets/3001')

  await expect(
    page.getByRole('heading', { name: '실제 API 응답만 표시하는 티켓' }),
  ).toBeVisible()
  await expect(
    page.getByLabel('티켓 대화 및 답변').getByText('종료', { exact: true }),
  ).toBeVisible()
  await expect(page.getByText('이 본문은 API에서 왔습니다.')).toBeVisible()
  await expect(
    page.getByText('현재 권한으로는 티켓을 수정할 수 없습니다.'),
  ).toBeVisible()
  await expect(page.getByText('김지연')).toHaveCount(0)
  await expect(page.getByText('카드사 승인 로그')).toHaveCount(0)
  await expect(page.getByText('Available', { exact: true })).toHaveCount(0)
  await expect(page.locator('img[src*="agent-mina-park"]')).toHaveCount(0)
  await expect(
    page.getByRole('navigation', { name: '열린 티켓 탭' }),
  ).toHaveCount(0)
  await expect(
    page.getByRole('textbox', { name: '공개 답변 내용' }),
  ).toHaveCount(0)
  await expect(page.getByRole('button', { name: /이관|자식/ })).toHaveCount(0)
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})
