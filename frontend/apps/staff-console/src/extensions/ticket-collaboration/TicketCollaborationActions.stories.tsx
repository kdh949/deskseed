import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse, delay } from 'msw'
import { expect, userEvent, waitFor } from 'storybook/test'
import type { AgentTicketDetail } from '../../api/types'
import { TicketCollaborationActions } from './TicketCollaborationActions'

const groupId = '11111111-1111-4111-8111-111111111111'
const memberId = '22222222-2222-4222-8222-222222222222'
const detail: AgentTicketDetail = {
  ticket: {
    ticketNumber: 3001,
    subject: '환불 확인',
    status: 'OPEN',
    priority: 'NORMAL',
    requester: { id: 'customer', type: 'CUSTOMER', displayName: '고객' },
    group: { id: 'old-group', name: '고객지원' },
    assignee: { id: 'old-staff', displayName: '현재 상담사' },
    createdAt: '2026-09-08T00:00:00Z',
    updatedAt: '2026-09-08T00:00:00Z',
    version: 3,
    isChild: false,
    openChildCount: 0,
    sla: null,
  },
  comments: [],
  capabilities: ['READ', 'UPDATE'],
  assignmentOptions: {
    groups: [
      {
        id: groupId,
        name: '결제 지원',
        members: [{ id: memberId, displayName: '결제 담당자' }],
      },
    ],
  },
  context: {
    customer: null,
    parent: null,
    children: [],
    externalReferenceCount: 0,
  },
  history: [],
  warnings: [],
}
const handlers = [
  http.get('/api/v1/agent/tickets/3001', async ({ request }) => {
    await expect(request.headers.get('X-Deskseed-Read-Intent')).toBe(
      'BACKGROUND',
    )
    return HttpResponse.json(detail)
  }),
  http.get('/api/v1/agent/csrf', () =>
    HttpResponse.json({ token: 'storybook-csrf', headerName: 'X-CSRF-TOKEN' }),
  ),
]
const meta = {
  title: '07 Screens/Ticket Collaboration Actions',
  component: TicketCollaborationActions,
  args: { ticketNumber: 3001 },
  parameters: { msw: { handlers } },
} satisfies Meta<typeof TicketCollaborationActions>
export default meta
type Story = StoryObj<typeof meta>

export const Transfer: Story = {
  parameters: {
    msw: {
      handlers: [
        ...handlers,
        http.post(
          '/api/v1/agent/tickets/3001/transfer',
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            await expect(body).toMatchObject({
              expectedVersion: 3,
              groupId,
              assigneeId: memberId,
              reason: '결제 내역 확인 요청',
            })
            await expect(request.headers.get('If-Match')).toBe('"3"')
            return HttpResponse.json({
              ticketNumber: 3001,
              version: 4,
              auditId: 'audit',
              warnings: [],
            })
          },
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole('button', { name: '티켓 이관' }))
    await userEvent.selectOptions(
      await canvas.findByLabelText(/대상 그룹/),
      groupId,
    )
    await userEvent.selectOptions(
      canvas.getByLabelText(/대상 담당자/),
      memberId,
    )
    await userEvent.type(
      canvas.getByLabelText(/이관 사유/),
      '결제 내역 확인 요청',
    )
    await userEvent.click(canvas.getByRole('button', { name: '이관 실행' }))
    await expect(await canvas.findByText('티켓을 이관했습니다.')).toBeVisible()
  },
}
let attempts: unknown[] = []
export const RetryChildWithoutDuplicate: Story = {
  beforeEach: () => {
    attempts = []
  },
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/tickets/3001', () =>
          HttpResponse.json(
            attempts.length
              ? {
                  ...detail,
                  ticket: { ...detail.ticket, version: 4 },
                  context: {
                    ...detail.context,
                    children: [
                      {
                        ...detail.ticket,
                        ticketNumber: 3002,
                        subject: '승인 취소 확인',
                        isChild: true,
                      },
                    ],
                  },
                }
              : detail,
          ),
        ),
        ...handlers,
        http.post(
          '/api/v1/agent/tickets/3001/children',
          async ({ request }) => {
            const body = await request.json()
            attempts.push(body)
            if (attempts.length === 1)
              return HttpResponse.json({ status: 503 }, { status: 503 })
            await expect(attempts[1]).toEqual(attempts[0])
            await expect(body).toMatchObject({
              expectedVersion: 3,
              groupId,
              assigneeId: null,
              subject: '승인 취소 확인',
              body: '이중 승인 내역을 확인해 주세요.',
              priority: 'NORMAL',
            })
            return HttpResponse.json(
              { status: 409, type: '/problems/client-command-id-reused' },
              { status: 409 },
            )
          },
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '내부 협업 요청' }),
    )
    await userEvent.selectOptions(
      await canvas.findByLabelText(/대상 그룹/),
      groupId,
    )
    await userEvent.type(
      canvas.getByLabelText(/협업 요청 제목/),
      '승인 취소 확인',
    )
    await userEvent.type(
      canvas.getByLabelText(/내부 요청 내용/),
      '이중 승인 내역을 확인해 주세요.',
    )
    await userEvent.click(
      canvas.getByRole('button', { name: '내부 협업 요청 만들기' }),
    )
    await expect(
      await canvas.findByText('저장 결과가 확인되지 않았습니다.'),
    ).toBeVisible()
    await expect(canvas.getByLabelText(/협업 요청 제목/)).toBeDisabled()
    await userEvent.click(
      canvas.getByRole('button', { name: '같은 요청 다시 확인' }),
    )
    await expect(
      await canvas.findByText('기존 협업 요청을 확인해 주세요.'),
    ).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '같은 요청 다시 확인' }),
    ).toBeDisabled()
    await userEvent.click(
      canvas.getByRole('button', { name: '관련 협업 티켓 새로고침' }),
    )
    await expect(
      await canvas.findByRole('link', { name: '#3002 승인 취소 확인' }),
    ).toHaveAttribute('href', '/agent/tickets/3002')
    await expect(canvas.getByLabelText(/협업 요청 제목/)).toBeDisabled()
    await expect(attempts).toHaveLength(2)
    await expect(attempts[1]).toEqual(attempts[0])
    await userEvent.click(
      canvas.getByRole('button', { name: '기존 협업 요청을 확인했습니다' }),
    )
    await userEvent.click(
      canvas.getByRole('button', { name: '내부 협업 요청' }),
    )
    await expect(await canvas.findByLabelText(/협업 요청 제목/)).toHaveValue('')
  },
}
export const VersionConflict: Story = {
  parameters: {
    msw: {
      handlers: [
        ...handlers,
        http.post('/api/v1/agent/tickets/3001/transfer', () =>
          HttpResponse.json({ status: 412 }, { status: 412 }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole('button', { name: '티켓 이관' }))
    await userEvent.selectOptions(
      await canvas.findByLabelText(/대상 그룹/),
      groupId,
    )
    await userEvent.type(
      canvas.getByLabelText(/이관 사유/),
      '고객 요청으로 담당 변경',
    )
    await userEvent.click(canvas.getByRole('button', { name: '이관 실행' }))
    await expect(
      await canvas.findByText('최신 티켓을 확인해 주세요.'),
    ).toBeVisible()
    await expect(canvas.getByLabelText(/이관 사유/)).toHaveValue(
      '고객 요청으로 담당 변경',
    )
    await userEvent.click(
      canvas.getByRole('button', { name: '입력을 유지하고 최신 버전 확인' }),
    )
    await waitFor(() =>
      expect(canvas.getByRole('button', { name: '이관 실행' })).toBeEnabled(),
    )
    await userEvent.keyboard('{Escape}')
    await expect(
      canvas.getByRole('button', { name: '티켓 이관' }),
    ).toHaveFocus()
  },
}
export const Denied: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/tickets/3001', () =>
          HttpResponse.json({ ...detail, capabilities: ['READ'] }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole('button', { name: '티켓 이관' }))
    await expect(
      await canvas.findByText(
        '이 티켓에서 이관이나 협업 요청을 할 수 없습니다.',
      ),
    ).toBeVisible()
  },
}
export const EmptyGroups: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/tickets/3001', () =>
          HttpResponse.json({ ...detail, assignmentOptions: { groups: [] } }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '내부 협업 요청' }),
    )
    await expect(
      await canvas.findByText('요청할 수 있는 활성 그룹이 없습니다.'),
    ).toBeVisible()
  },
}
export const Loading: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/tickets/3001', async () => {
          await delay('infinite')
          return HttpResponse.json(detail)
        }),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole('button', { name: '티켓 이관' }))
  },
}
export const Error: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/tickets/3001', () =>
          HttpResponse.json({ status: 503 }, { status: 503 }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole('button', { name: '티켓 이관' }))
    await expect(
      await canvas.findByText('티켓 정보를 확인할 수 없습니다.'),
    ).toBeVisible()
  },
}
