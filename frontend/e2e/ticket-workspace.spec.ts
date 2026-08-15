import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

const staff = {
  id: '11111111-1111-4111-8111-111111111111',
  email: 'agent@example.test',
  displayName: '상담사 A',
  role: 'AGENT',
  capabilities: ['AGENT_WORKSPACE'],
}

const detail = {
  ticket: {
    ticketNumber: 3001,
    subject: '결제 승인 상태 확인 요청',
    status: 'ON_HOLD',
    priority: 'HIGH',
    requester: {
      id: 'customer-3001',
      type: 'CUSTOMER',
      displayName: '고객 A',
    },
    group: { id: 'group-payments', name: '결제 지원' },
    assignee: { id: 'staff-3001', displayName: '상담사 A' },
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

async function mockWritableTicket(page: Page) {
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/v1/agent/me') {
      return route.fulfill({ status: 200, json: staff })
    }
    if (url.pathname === '/api/v1/agent/tickets/3001') {
      return route.fulfill({ status: 200, json: detail })
    }
    return route.abort()
  })
}

test('production workspace preserves separate PUBLIC and INTERNAL drafts from a live ticket projection', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await mockWritableTicket(page)
  await page.goto('/agent/tickets/3001')

  await expect(
    page.getByRole('main', { name: '티켓 #3001 작업 공간' }),
  ).toBeVisible()
  await expect(
    page.getByLabel('티켓 대화 및 답변').getByText('보류', { exact: true }),
  ).toBeVisible()
  await expect(page.getByText('Pending', { exact: true })).toHaveCount(0)

  await page.getByRole('tab', { name: '공개 답변 작성 모드로 전환' }).click()
  await page
    .getByRole('textbox', { name: '공개 답변 내용' })
    .fill('고객 안내 초안')
  await page.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }).click()
  await page
    .getByRole('textbox', { name: '내부 메모 내용' })
    .fill('팀 확인 메모')
  await page.getByRole('tab', { name: '공개 답변 작성 모드로 전환' }).click()
  await expect(
    page.getByRole('textbox', { name: '공개 답변 내용' }),
  ).toHaveValue('고객 안내 초안')
  await page.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }).click()
  await expect(
    page.getByRole('textbox', { name: '내부 메모 내용' }),
  ).toHaveValue('팀 확인 메모')
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})
