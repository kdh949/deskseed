import { useEffect } from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { delay, http, HttpResponse } from 'msw'
import { expect, userEvent, within } from 'storybook/test'
import { Route, Routes, useNavigate } from 'react-router'
import { mswHandlers } from '../../../.storybook/msw-handlers'
import { AgentShellLayout } from '../agent-shell/AgentShellLayout'
import { StaffSessionProvider } from '../staff-auth/StaffSessionContext'
import { AgentTicketWorkspacePage } from './AgentTicketWorkspacePage'
import { ticketDraftStorageKey } from './model/ticketEditorModel'
import { removeLocalTicketDraft } from '../collaboration/draftRecovery'

const detail = {
  ticket: {
    ticketNumber: 1042,
    subject: '결제 승인 오류',
    status: 'OPEN',
    priority: 'HIGH',
    requester: { id: 'customer-1', type: 'CUSTOMER', displayName: '김민수' },
    group: { id: 'group-payments', name: '결제 지원' },
    assignee: { id: 'agent-2', displayName: 'Mina Park' },
    createdAt: '2026-08-10T09:00:00Z',
    updatedAt: '2026-08-10T10:02:00Z',
    version: 3,
    isChild: false,
    openChildCount: 0,
    sla: {
      metric: 'FIRST_REPLY',
      state: 'ACTIVE',
      dueAt: '2026-08-10T12:00:00Z',
      targetMinutes: 180,
      policyVersion: 2,
      scheduleVersion: 4,
    },
  },
  comments: [
    {
      id: 'comment-public',
      visibility: 'PUBLIC',
      actor: { id: 'customer-1', type: 'CUSTOMER', displayName: '김민수' },
      body: '결제가 계속 실패합니다. 확인 부탁드립니다.\n\n승인 요청을 다시 보내도 같은 오류 메시지가 표시됩니다.\n\n오늘 안에 결제를 완료해야 합니다.',
      content: {
        format: 'PLAIN_TEXT',
        text: '결제가 계속 실패합니다. 확인 부탁드립니다.\n\n승인 요청을 다시 보내도 같은 오류 메시지가 표시됩니다.\n\n오늘 안에 결제를 완료해야 합니다.',
      },
      createdAt: '2026-08-10T09:00:00Z',
      source: 'WEB',
      attachments: [],
    },
    {
      id: 'comment-agent-public',
      visibility: 'PUBLIC',
      actor: { id: 'agent-2', type: 'STAFF', displayName: 'Mina Park' },
      body: '안녕하세요, 김민수님. 결제 승인 상태를 확인하고 있습니다.\n\n먼저 브라우저를 새로고침한 뒤 결제 수단을 다시 선택해 주세요.\n\n같은 문제가 계속되면 승인 요청 기록을 결제팀에 바로 전달하겠습니다.\n\n잠시만 기다려 주세요.',
      content: {
        format: 'RICH_TEXT_V1',
        document: {
          type: 'doc',
          content: [
            {
              type: 'paragraph',
              content: [
                {
                  type: 'text',
                  text: '안녕하세요, 김민수님. 결제 승인 상태를 확인하고 있습니다.',
                },
              ],
            },
            {
              type: 'paragraph',
              content: [
                {
                  type: 'text',
                  text: '먼저 브라우저를 새로고침한 뒤 결제 수단을 다시 선택해 주세요.',
                },
              ],
            },
            {
              type: 'paragraph',
              content: [
                {
                  type: 'text',
                  text: '같은 문제가 계속되면 승인 요청 기록을 결제팀에 바로 전달하겠습니다.',
                },
              ],
            },
          ],
        },
      },
      createdAt: '2026-08-10T09:18:00Z',
      source: 'STAFF_WEB',
      attachments: [],
    },
    {
      id: 'comment-internal',
      visibility: 'INTERNAL',
      actor: { id: 'agent-2', type: 'STAFF', displayName: 'Mina Park' },
      body: 'PG사 응답 코드 확인이 필요합니다. 이전 승인 요청과 중복 여부도 함께 확인합니다.\n\n고객 계정과 승인 기록의 동기화 지연 가능성이 있습니다.',
      content: {
        format: 'PLAIN_TEXT',
        text: 'PG사 응답 코드 확인이 필요합니다. 이전 승인 요청과 중복 여부도 함께 확인합니다.\n\n고객 계정과 승인 기록의 동기화 지연 가능성이 있습니다.',
      },
      createdAt: '2026-08-10T09:30:00Z',
      source: 'STAFF_WEB',
      attachments: [],
    },
    {
      id: 'comment-public-follow-up',
      visibility: 'PUBLIC',
      actor: { id: 'customer-1', type: 'CUSTOMER', displayName: '김민수' },
      body: '네, 확인했습니다. 추가 정보가 필요하면 알려 주세요.',
      content: {
        format: 'PLAIN_TEXT',
        text: '네, 확인했습니다. 추가 정보가 필요하면 알려 주세요.',
      },
      createdAt: '2026-08-10T09:42:00Z',
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
        members: [{ id: 'agent-2', displayName: 'Mina Park' }],
      },
    ],
  },
  context: {
    customer: {
      id: 'customer-1',
      displayName: '김민수',
      email: 'minsu@example.test',
    },
    parent: null,
    children: [
      {
        ticketNumber: 1038,
        subject: '결제 승인 상태 재확인',
        status: 'SOLVED',
        priority: 'NORMAL',
        requester: {
          id: 'customer-1',
          type: 'CUSTOMER',
          displayName: '김민수',
        },
        group: { id: 'group-payments', name: '결제 지원' },
        assignee: { id: 'agent-2', displayName: 'Mina Park' },
        createdAt: '2026-08-08T07:30:00Z',
        updatedAt: '2026-08-08T08:00:00Z',
        version: 2,
        isChild: true,
        openChildCount: 0,
        sla: null,
      },
    ],
    externalReferenceCount: 1,
  },
  history: [
    {
      id: 'history-1',
      eventType: 'TICKET_CREATED',
      actor: { id: 'customer-1', type: 'CUSTOMER', displayName: '김민수' },
      occurredAt: '2026-08-10T09:00:00Z',
    },
    {
      id: 'history-2',
      eventType: 'COMMENT_CREATED',
      actor: { id: 'agent-2', type: 'STAFF', displayName: 'Mina Park' },
      occurredAt: '2026-08-10T09:30:00Z',
    },
  ],
  warnings: [],
}

const billingSystem = {
  id: '55555555-5555-4555-8555-555555555555',
  systemKey: 'billing',
  displayName: '결제 플랫폼',
  status: 'ACTIVE',
  allowedHostnames: ['billing.example.test'],
  createdAt: '2026-07-01T00:00:00Z',
  updatedAt: '2026-08-01T00:00:00Z',
  version: 2,
}

const externalReferences = {
  ticketVersion: 3,
  canManage: true,
  availableSystems: [billingSystem],
  items: [
    {
      id: '66666666-6666-4666-8666-666666666666',
      system: billingSystem,
      objectType: 'PAYMENT',
      externalId: 'PAY-20260810-1042',
      displayLabel: '결제 승인 기록',
      linkState: 'AVAILABLE',
      safeDeepLink: 'https://billing.example.test/payments/PAY-20260810-1042',
      metadata: {},
      metadataObservedAt: '2026-08-10T09:50:00Z',
      createdBy: {
        actorId: '22222222-2222-4222-8222-222222222222',
        displayName: 'Mina Park',
      },
      createdAt: '2026-08-10T09:50:00Z',
    },
  ],
}

function WorkspaceScreenRoute({ to = '/agent/tickets/1042' }: { to?: string }) {
  const navigate = useNavigate()
  useEffect(() => {
    navigate(to, { replace: true })
  }, [navigate, to])
  return (
    <Routes>
      <Route element={<AgentShellLayout />} path="/agent">
        <Route
          element={<AgentTicketWorkspacePage />}
          path="tickets/:ticketNumber"
        />
      </Route>
    </Routes>
  )
}

const meta = {
  title: '07 Screens/Agent Ticket Workspace Page',
  component: AgentTicketWorkspacePage,
  parameters: {
    layout: 'fullscreen',
    msw: {
      handlers: [
        http.get('/api/v1/agent/me', () =>
          HttpResponse.json({
            id: 'agent-1',
            email: 'agent@example.test',
            displayName: '상담사',
            role: 'AGENT',
            capabilities: ['AGENT_WORKSPACE'],
          }),
        ),
        http.get('/api/v1/agent/tickets/1042', ({ request }) =>
          HttpResponse.json(
            request.headers.get('X-Deskseed-Read-Intent') === 'BACKGROUND'
              ? { ...detail, ticket: { ...detail.ticket, version: 4 } }
              : detail,
          ),
        ),
        http.get('/api/v1/agent/tickets/1042/external-references', () =>
          HttpResponse.json(externalReferences),
        ),
        http.get('/api/v1/agent/tickets/1042/collaboration-notes', () =>
          HttpResponse.json({
            items: [
              {
                id: '77777777-7777-4777-8777-777777777771',
                ticketNumber: 1042,
                author: {
                  id: '22222222-2222-4222-8222-222222222222',
                  type: 'STAFF',
                  displayName: 'Sam Lee',
                },
                body: '@Mina 최근 결제 오류와 같은 현상인지 확인해 주세요.',
                mentionedStaff: [
                  {
                    id: '11111111-1111-4111-8111-111111111111',
                    displayName: 'Mina Park',
                  },
                ],
                createdAt: '2026-08-10T10:02:00Z',
              },
              {
                id: '77777777-7777-4777-8777-777777777772',
                ticketNumber: 1042,
                author: {
                  id: '33333333-3333-4333-8333-333333333333',
                  type: 'STAFF',
                  displayName: 'Priya Nair',
                },
                body: '비슷한 문의가 추가되는지 모니터링하겠습니다.',
                mentionedStaff: [],
                createdAt: '2026-08-10T10:05:00Z',
              },
            ],
            nextCursor: null,
          }),
        ),
        http.get('/api/v1/agent/macros', () =>
          HttpResponse.json([
            {
              id: '88888888-8888-4888-8888-888888888888',
              name: '결제 승인 확인 안내',
              scope: 'SHARED',
              ownerStaffId: null,
              currentVersion: 2,
              activeVersion: 2,
              aggregateVersion: 3,
              actions: [{ type: 'ADD_COMMENT' }],
              createdAt: '2026-08-01T00:00:00Z',
              updatedAt: '2026-08-10T08:00:00Z',
            },
          ]),
        ),
        http.get('/api/v1/agent/notifications', () =>
          HttpResponse.json({
            items: [
              {
                id: '99999999-9999-4999-8999-999999999999',
                type: 'COLLABORATION_MENTION',
                ticketNumber: 1042,
                noteId: '77777777-7777-4777-8777-777777777771',
                actor: {
                  id: '22222222-2222-4222-8222-222222222222',
                  type: 'STAFF',
                  displayName: 'Sam Lee',
                },
                createdAt: '2026-08-10T10:02:00Z',
                readAt: null,
              },
            ],
            nextCursor: null,
            unreadCount: 1,
          }),
        ),
        http.get('/api/v1/agent/csrf', () =>
          HttpResponse.json({
            token: 'a'.repeat(32),
            headerName: 'X-CSRF-TOKEN',
          }),
        ),
        http.post('/api/v1/agent/tickets/1042/commands', () =>
          HttpResponse.json(
            {
              type: '/problems/ticket-field-conflict',
              title: 'Ticket fields changed concurrently',
              status: 409,
              requestId: 'request-workspace-conflict',
              currentVersion: 4,
              conflictingFields: ['status'],
            },
            { status: 409 },
          ),
        ),
        ...mswHandlers,
      ],
    },
  },
  render: () => (
    <StaffSessionProvider>
      <WorkspaceScreenRoute />
    </StaffSessionProvider>
  ),
  tags: ['autodocs'],
} satisfies Meta<typeof AgentTicketWorkspacePage>

export default meta
type Story = StoryObj<typeof meta>

export const ConversationFocus: Story = {
  beforeEach: async () => {
    localStorage.removeItem(ticketDraftStorageKey('agent-1', 1042))
    await Promise.all([
      removeLocalTicketDraft('agent-1', 1042, 'PUBLIC_REPLY'),
      removeLocalTicketDraft('agent-1', 1042, 'INTERNAL_NOTE'),
    ])
  },
  parameters: {
    docs: {
      description: {
        story:
          '선택된 3번 시안의 실제 상담 화면. 고정 합성 데이터로 접힌 컨텍스트, 키보드 포커스 복귀와 공개/내부 초안 분리를 검증합니다.',
      },
    },
    msw: {
      handlers: [
        http.get('/api/v1/agent/tickets/1042', () =>
          HttpResponse.json({
            ...detail,
            comments: detail.comments.map((comment, index) => {
              const text =
                [
                  '결제가 계속 실패합니다. 승인 요청을 다시 보내도 같은 오류가 표시됩니다.\n오늘 안에 결제를 완료해야 합니다.',
                  '안녕하세요, 김민수님. 결제 승인 상태를 확인하고 있습니다.\n브라우저를 새로고침한 뒤 결제 수단을 다시 선택해 주세요.',
                  'PG사 응답 코드와 중복 승인 여부를 확인합니다.\n결제팀에 확인을 요청했습니다.',
                  '네, 확인 감사합니다. 추가 정보가 필요하면 알려 주세요.',
                ][index] ?? comment.body
              return {
                ...comment,
                body: text,
                content: {
                  format: 'RICH_TEXT_V1',
                  document: {
                    type: 'doc',
                    content: text.split('\n').map((paragraph) => ({
                      type: 'paragraph',
                      content: [{ type: 'text', text: paragraph }],
                    })),
                  },
                },
              }
            }),
          }),
        ),
        ...meta.parameters.msw.handlers,
      ],
    },
  },
  play: async ({ canvas, canvasElement }) => {
    await expect(
      await canvas.findByRole('heading', { name: '결제 승인 오류' }),
    ).toBeVisible()
    const body = within(canvasElement.ownerDocument.body)
    await expect(
      body.queryByRole('dialog', { name: '티켓 컨텍스트' }),
    ).not.toBeInTheDocument()
    const contextButton = canvas.getByRole('button', {
      name: '고객 및 관련 정보 열기',
    })
    await userEvent.click(contextButton)
    await expect(
      await body.findByRole('dialog', { name: '티켓 컨텍스트' }),
    ).toBeVisible()
    await userEvent.keyboard('{Escape}')
    await expect(contextButton).toHaveFocus()
    const publicEditor = await canvas.findByRole('textbox', {
      name: '공개 답변 내용',
    })
    await expect(
      await canvas.findByText('저장됨', { exact: true }),
    ).toBeVisible()
    await userEvent.click(publicEditor)
    await userEvent.paste('김민수님, 확인 후 다시 안내드리겠습니다.')
    await userEvent.click(
      canvas.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }),
    )
    const internalEditor = await canvas.findByRole('textbox', {
      name: '내부 메모 내용',
    })
    await expect(internalEditor).not.toHaveTextContent('김민수님')
    await userEvent.click(internalEditor)
    await userEvent.paste('결제팀 확인 대기')
    await userEvent.click(
      canvas.getByRole('tab', { name: '공개 답변 작성 모드로 전환' }),
    )
    await expect(
      await canvas.findByRole('textbox', { name: '공개 답변 내용' }),
    ).toHaveTextContent('김민수님, 확인 후 다시 안내드리겠습니다.')
    ;(canvasElement.ownerDocument.activeElement as HTMLElement | null)?.blur()
  },
}

export const Workspace: Story = {
  play: async ({ canvas, canvasElement }) => {
    await expect(
      await canvas.findByRole('heading', { name: '결제 승인 오류' }),
    ).toBeVisible()
    await expect(
      canvas.getByText(/PG사 응답 코드 확인이 필요합니다/),
    ).toBeVisible()
    await expect(canvas.getAllByText('INTERNAL')).toHaveLength(2)
    await expect(
      await canvas.findByRole('textbox', { name: '공개 답변 내용' }),
    ).toBeVisible()
    const editor = await canvas.findByRole('textbox', {
      name: '공개 답변 내용',
    })
    await userEvent.type(editor, '확인 후 다시 안내드리겠습니다.')
    await userEvent.click(canvas.getByRole('button', { name: '답변 보내기' }))
    await expect(
      await canvas.findByRole('alert', { name: '저장 충돌' }),
    ).toBeVisible()
    ;(canvasElement.ownerDocument.activeElement as HTMLElement | null)?.blur()
  },
}

export const Loading: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/me', () =>
          HttpResponse.json({
            id: 'agent-1',
            email: 'agent@example.test',
            displayName: '상담사',
            role: 'AGENT',
            capabilities: ['AGENT_WORKSPACE'],
          }),
        ),
        http.get('/api/v1/agent/tickets/1042', async () => {
          await delay('infinite')
          return HttpResponse.json(detail)
        }),
        ...mswHandlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('티켓을 불러오는 중입니다.'),
    ).toBeVisible()
  },
}

export const Error: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/me', () =>
          HttpResponse.json({
            id: 'agent-1',
            email: 'agent@example.test',
            displayName: '상담사',
            role: 'AGENT',
            capabilities: ['AGENT_WORKSPACE'],
          }),
        ),
        http.get('/api/v1/agent/tickets/1042', () =>
          HttpResponse.json(
            {
              title: 'Unavailable',
              status: 503,
              requestId: 'request-workspace-error',
            },
            { status: 503 },
          ),
        ),
        ...mswHandlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('티켓을 열 수 없습니다.'),
    ).toBeVisible()
    await expect(canvas.getByText(/request-workspace-error/)).toBeVisible()
  },
}

export const Denied: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/me', () =>
          HttpResponse.json({
            id: 'agent-1',
            email: 'agent@example.test',
            displayName: '상담사',
            role: 'AGENT',
            capabilities: ['AGENT_WORKSPACE'],
          }),
        ),
        http.get('/api/v1/agent/tickets/1042', () =>
          HttpResponse.json(
            {
              title: 'Forbidden',
              status: 403,
              requestId: 'request-workspace-denied',
            },
            { status: 403 },
          ),
        ),
        ...mswHandlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('티켓을 열 수 없습니다.'),
    ).toBeVisible()
    await expect(
      canvas.getByText(/현재 계정으로 이 티켓을 볼 수 없습니다/),
    ).toBeVisible()
  },
}

export const Validation: Story = {
  render: () => (
    <StaffSessionProvider>
      <WorkspaceScreenRoute to="/agent/tickets/not-a-number" />
    </StaffSessionProvider>
  ),
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('티켓 번호를 확인할 수 없습니다.'),
    ).toBeVisible()
  },
}
