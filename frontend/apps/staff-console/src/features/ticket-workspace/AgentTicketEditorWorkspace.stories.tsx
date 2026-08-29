import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse, type HttpHandler } from 'msw'
import { expect, userEvent, waitFor, within } from 'storybook/test'
import { mswHandlers } from '../../../.storybook/msw-handlers'
import type { AgentTicketDetail } from '../../api/types'
import { AgentTicketEditorWorkspace } from './AgentTicketEditorWorkspace'

const staffId = '11111111-1111-4111-8111-111111111111'

const detail: AgentTicketDetail = {
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
    sla: {
      metric: 'FIRST_REPLY',
      state: 'AT_RISK',
      dueAt: '2026-08-15T12:00:00Z',
      targetMinutes: 180,
      policyVersion: 2,
      scheduleVersion: 4,
    },
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
      body: '결제가 완료되었는지 확인하고 싶습니다.',
      content: {
        format: 'PLAIN_TEXT',
        text: '결제가 완료되었는지 확인하고 싶습니다.',
      },
      createdAt: '2026-08-15T09:00:00Z',
      source: 'WEB',
      attachments: [],
    },
    {
      id: 'comment-3001-agent-public',
      visibility: 'PUBLIC',
      actor: {
        id: 'staff-3001',
        type: 'STAFF',
        displayName: '상담사 A',
      },
      body: '안녕하세요. 결제 승인 기록을 확인하고 있습니다.\n\n잠시만 기다려 주세요.',
      content: {
        format: 'PLAIN_TEXT',
        text: '안녕하세요. 결제 승인 기록을 확인하고 있습니다.\n\n잠시만 기다려 주세요.',
      },
      createdAt: '2026-08-15T09:18:00Z',
      source: 'STAFF_WEB',
      attachments: [],
    },
    {
      id: 'comment-3001-internal',
      visibility: 'INTERNAL',
      actor: {
        id: 'staff-3001',
        type: 'STAFF',
        displayName: '상담사 A',
      },
      body: 'PG사 응답 코드와 이전 승인 요청의 중복 여부를 확인합니다.',
      content: {
        format: 'PLAIN_TEXT',
        text: 'PG사 응답 코드와 이전 승인 요청의 중복 여부를 확인합니다.',
      },
      createdAt: '2026-08-15T09:32:00Z',
      source: 'STAFF_WEB',
      attachments: [],
    },
    {
      id: 'comment-3001-follow-up',
      visibility: 'PUBLIC',
      actor: {
        id: 'customer-3001',
        type: 'CUSTOMER',
        displayName: '고객 A',
      },
      body: '확인했습니다. 추가 정보가 필요하면 알려 주세요.',
      content: {
        format: 'PLAIN_TEXT',
        text: '확인했습니다. 추가 정보가 필요하면 알려 주세요.',
      },
      createdAt: '2026-08-15T09:48:00Z',
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
    children: [
      {
        ticketNumber: 2998,
        subject: '결제 승인 상태 재확인',
        status: 'SOLVED',
        priority: 'NORMAL',
        requester: {
          id: 'customer-3001',
          type: 'CUSTOMER',
          displayName: '고객 A',
        },
        group: { id: 'group-payments', name: '결제 지원' },
        assignee: { id: 'staff-3001', displayName: '상담사 A' },
        createdAt: '2026-08-13T08:00:00Z',
        updatedAt: '2026-08-13T09:10:00Z',
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
      id: 'history-3001-created',
      eventType: 'TICKET_CREATED',
      actor: { id: 'customer-3001', type: 'CUSTOMER', displayName: '고객 A' },
      occurredAt: '2026-08-15T09:00:00Z',
    },
    {
      id: 'history-3001-comment',
      eventType: 'COMMENT_CREATED',
      actor: { id: 'staff-3001', type: 'STAFF', displayName: '상담사 A' },
      occurredAt: '2026-08-15T09:32:00Z',
    },
  ],
  warnings: [],
}

const hundredCommentDetail: AgentTicketDetail = {
  ...detail,
  comments: Array.from({ length: 100 }, (_, index) => {
    const internal = index % 5 === 4
    const fromCustomer = index % 2 === 0 && !internal
    const body = internal
      ? `내부 확인 메모 ${index + 1}: 결제 승인 추적 정보를 확인합니다.`
      : `대화 ${index + 1}: 결제 승인 상태를 순서대로 확인하고 있습니다.`
    return {
      id: `comment-3001-performance-${index + 1}`,
      visibility: internal ? ('INTERNAL' as const) : ('PUBLIC' as const),
      actor: fromCustomer
        ? {
            id: 'customer-3001',
            type: 'CUSTOMER' as const,
            displayName: '고객 A',
          }
        : { id: 'staff-3001', type: 'STAFF' as const, displayName: '상담사 A' },
      body,
      content: { format: 'PLAIN_TEXT' as const, text: body },
      createdAt: new Date(Date.UTC(2026, 7, 15, 9, index)).toISOString(),
      source: fromCustomer ? 'WEB' : 'STAFF_WEB',
      attachments: [],
    }
  }),
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
      externalId: 'PAY-20260815-3001',
      displayLabel: '결제 승인 기록',
      linkState: 'AVAILABLE',
      safeDeepLink: 'https://billing.example.test/payments/PAY-20260815-3001',
      metadata: {},
      metadataObservedAt: '2026-08-15T09:55:00Z',
      createdBy: { actorId: staffId, displayName: '상담사 A' },
      createdAt: '2026-08-15T09:55:00Z',
    },
  ],
}

const externalReferenceHandler = http.get(
  '/api/v1/agent/tickets/3001/external-references',
  () => HttpResponse.json(externalReferences),
)

const collaborationHandler = http.get(
  '/api/v1/agent/tickets/3001/collaboration-notes',
  () =>
    HttpResponse.json({
      items: [
        {
          id: '77777777-7777-4777-8777-777777777771',
          ticketNumber: 3001,
          author: {
            id: '22222222-2222-4222-8222-222222222222',
            type: 'STAFF',
            displayName: 'Sam Lee',
          },
          body: '@상담사 A 최근 결제 문의와 같은 현상인지 확인해 주세요.',
          mentionedStaff: [
            {
              id: '11111111-1111-4111-8111-111111111111',
              displayName: '상담사 A',
            },
          ],
          createdAt: '2026-08-15T10:02:00Z',
        },
        {
          id: '77777777-7777-4777-8777-777777777772',
          ticketNumber: 3001,
          author: {
            id: '33333333-3333-4333-8333-333333333333',
            type: 'STAFF',
            displayName: 'Priya Nair',
          },
          body: '비슷한 문의가 추가되는지 모니터링하겠습니다.',
          mentionedStaff: [],
          createdAt: '2026-08-15T10:05:00Z',
        },
      ],
      nextCursor: null,
    }),
)

const macroHandler = http.get('/api/v1/agent/macros', () =>
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
      updatedAt: '2026-08-15T08:00:00Z',
    },
  ]),
)

const macroPreviewHandler = http.post(
  '/api/v1/agent/tickets/3001/macros/88888888-8888-4888-8888-888888888888/preview',
  () =>
    HttpResponse.json({
      macroId: '88888888-8888-4888-8888-888888888888',
      macroVersion: 2,
      ticketNumber: 3001,
      ticketVersion: 3,
      changes: [{ field: 'priority', before: 'HIGH', after: 'NORMAL' }],
      comment: {
        visibility: 'PUBLIC',
        body: '결제 승인 기록을 확인하고 있습니다. 잠시만 기다려 주세요.',
        content: {
          format: 'RICH_TEXT_V1',
          document: {
            type: 'doc',
            content: [
              {
                type: 'paragraph',
                content: [
                  { type: 'text', text: '결제 승인 기록을 확인하고 있습니다.' },
                ],
              },
              {
                type: 'paragraph',
                content: [
                  {
                    type: 'text',
                    text: '잠시만 기다려 주세요.',
                    marks: [{ type: 'bold' }],
                  },
                ],
              },
            ],
          },
        },
      },
    }),
)

const csrfHandler = http.get('/api/v1/agent/csrf', () =>
  HttpResponse.json({ token: 'a'.repeat(32), headerName: 'X-CSRF-TOKEN' }),
)

const workspaceHandlers = (...overrides: HttpHandler[]) => [
  ...overrides,
  externalReferenceHandler,
  collaborationHandler,
  macroHandler,
  macroPreviewHandler,
  csrfHandler,
  ...mswHandlers,
]

const meta = {
  title: '06 Domain & Workspace/AgentTicketEditorWorkspace',
  component: AgentTicketEditorWorkspace,
  args: {
    detail,
    refreshLatest: async () => detail,
    staffId,
  },
  parameters: {
    docs: {
      description: {
        component:
          'REQ-TKT-010~015의 production 상담사 workspace입니다. 상세 API가 제공한 capability와 assignment option만 표시하고, PUBLIC/INTERNAL 초안을 분리하며, 제출은 하나의 expected-version command로 묶습니다.',
      },
    },
    layout: 'fullscreen',
    msw: {
      handlers: workspaceHandlers(),
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof AgentTicketEditorWorkspace>

export default meta
type Story = StoryObj<typeof meta>

export const Writable: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('textbox', { name: '공개 답변 내용' }),
    ).toBeVisible()
    await expect(
      canvas.getByRole('combobox', { name: '그룹' }),
    ).toHaveTextContent('결제 지원')
  },
}

export const HundredCommentPerformance: Story = {
  args: {
    detail: hundredCommentDetail,
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('textbox', { name: '공개 답변 내용' }),
    ).toBeVisible()
    await expect(canvas.getAllByRole('article')).toHaveLength(100)
    await userEvent.click(canvas.getByRole('tab', { name: /^INTERNAL 20$/ }))
    await expect(canvas.getAllByRole('article')).toHaveLength(20)
    await userEvent.click(canvas.getByRole('tab', { name: /^대화 100$/ }))
    await expect(canvas.getAllByRole('article')).toHaveLength(100)
  },
}

export const RecoversRemoteDrafts: Story = {
  args: {
    staffId: '55555555-5555-4555-8555-555555555555',
  },
  parameters: {
    msw: {
      handlers: workspaceHandlers(
        http.get('/api/v1/agent/tickets/3001/drafts/PUBLIC_REPLY', () =>
          HttpResponse.json({
            ticketNumber: 3001,
            channel: 'PUBLIC_REPLY',
            body: '저장된 공개 답변 초안입니다.',
            content: {
              format: 'PLAIN_TEXT',
              text: '저장된 공개 답변 초안입니다.',
            },
            attachmentIds: [],
            clientDeviceId: '33333333-3333-4333-8333-333333333333',
            baseTicketVersion: 3,
            draftVersion: 2,
            updatedAt: '2026-08-22T00:00:00Z',
            expiresAt: '2026-09-21T00:00:00Z',
          }),
        ),
        http.get('/api/v1/agent/tickets/3001/drafts/INTERNAL_NOTE', () =>
          HttpResponse.json({
            ticketNumber: 3001,
            channel: 'INTERNAL_NOTE',
            body: '저장된 내부 메모 초안입니다.',
            content: {
              format: 'PLAIN_TEXT',
              text: '저장된 내부 메모 초안입니다.',
            },
            attachmentIds: [],
            clientDeviceId: '33333333-3333-4333-8333-333333333333',
            baseTicketVersion: 3,
            draftVersion: 3,
            updatedAt: '2026-08-22T00:00:00Z',
            expiresAt: '2026-09-21T00:00:00Z',
          }),
        ),
      ),
    },
  },
  play: async ({ canvas, userEvent }) => {
    const publicEditor = await canvas.findByRole('textbox', {
      name: '공개 답변 내용',
    })
    await waitFor(() => {
      expect(publicEditor).toHaveTextContent('저장된 공개 답변 초안입니다.')
    })

    await userEvent.click(
      canvas.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }),
    )

    const internalEditor = await canvas.findByRole('textbox', {
      name: '내부 메모 내용',
    })
    await waitFor(() => {
      expect(internalEditor).toHaveTextContent('저장된 내부 메모 초안입니다.')
    })
  },
}

export const SavingLocksInputs: Story = {
  parameters: {
    msw: {
      handlers: workspaceHandlers(
        http.post('/api/v1/agent/tickets/3001/commands', async () => {
          await new Promise((resolve) => window.setTimeout(resolve, 1_000))
          return HttpResponse.json({
            ticketNumber: 3001,
            version: 4,
            auditId: '22222222-2222-4222-8222-222222222222',
            warnings: [],
          })
        }),
      ),
    },
  },
  play: async ({ canvas }) => {
    const editor = await canvas.findByRole('textbox', {
      name: '공개 답변 내용',
    })
    await userEvent.type(editor, '저장 중 입력 잠금 확인')
    await userEvent.click(canvas.getByRole('button', { name: '답변 보내기' }))
    await expect(editor).toHaveAttribute('contenteditable', 'false')
    await expect(
      canvas.getByRole('combobox', { name: '상태' }),
    ).toHaveAttribute('aria-disabled', 'true')
    await expect(
      canvas.getByRole('combobox', { name: '우선순위' }),
    ).toHaveAttribute('aria-disabled', 'true')
    await expect(
      canvas.getByRole('combobox', { name: '그룹' }),
    ).toHaveAttribute('aria-disabled', 'true')
    await expect(
      canvas.getByRole('combobox', { name: '담당자' }),
    ).toHaveAttribute('aria-disabled', 'true')
  },
}

export const InternalDraft: Story = {
  args: {
    detail: {
      ...detail,
      ticket: { ...detail.ticket, ticketNumber: 3010 },
      context: { ...detail.context, externalReferenceCount: 0 },
    },
  },
  parameters: {
    msw: {
      handlers: workspaceHandlers(
        http.get('/api/v1/agent/tickets/3010/external-references', () =>
          HttpResponse.json({
            ticketVersion: 3,
            canManage: true,
            availableSystems: [],
            items: [],
          }),
        ),
        http.get('/api/v1/agent/tickets/3010/collaboration-notes', () =>
          HttpResponse.json({ items: [], nextCursor: null }),
        ),
      ),
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }),
    )
    const editor = await canvas.findByRole('textbox', {
      name: '내부 메모 내용',
    })
    await userEvent.type(editor, '직원 전용 확인 사항입니다.')
    await expect(editor).toHaveTextContent('직원 전용 확인 사항입니다.')
  },
}

export const ReadOnly: Story = {
  args: {
    detail: { ...detail, capabilities: ['READ'] },
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByText('현재 권한으로는 티켓을 수정할 수 없습니다.'),
    ).toBeVisible()
    await expect(
      canvas.queryByRole('textbox', { name: '공개 답변 내용' }),
    ).not.toBeInTheDocument()
  },
}

export const ChildTicket: Story = {
  args: {
    detail: {
      ...detail,
      ticket: { ...detail.ticket, isChild: true },
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('textbox', { name: '내부 메모 내용' }),
    ).toBeVisible()
    await expect(
      canvas.queryByRole('textbox', { name: '공개 답변 내용' }),
    ).not.toBeInTheDocument()
  },
}

export const ReadOnlyRefreshFailure: Story = {
  args: {
    detail: { ...detail, capabilities: ['READ'] },
    refreshLatest: async () => {
      throw new Error('service unavailable')
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '최신 정보 새로고침' }),
    )
    await expect(
      canvas.getByText(
        '최신 티켓 정보를 확인하지 못했습니다. 다시 시도해 주세요.',
      ),
    ).toBeVisible()
  },
}

export const ConflictComparison: Story = {
  args: {
    staffId: '44444444-4444-4444-8444-444444444444',
    refreshLatest: async () => ({
      ...detail,
      ticket: { ...detail.ticket, status: 'PENDING', version: 4 },
    }),
  },
  parameters: {
    msw: {
      handlers: workspaceHandlers(
        http.post('/api/v1/agent/tickets/3001/commands', () =>
          HttpResponse.json(
            {
              type: '/problems/ticket-field-conflict',
              title: 'Ticket fields changed concurrently',
              status: 409,
              requestId: 'request-story-conflict',
              currentVersion: 4,
              conflictingFields: ['status'],
            },
            { status: 409 },
          ),
        ),
      ),
    },
  },
  play: async ({ canvas }) => {
    const editor = await canvas.findByRole('textbox', {
      name: '공개 답변 내용',
    })
    await userEvent.type(editor, '고객 안내 초안을 보존합니다.')
    await userEvent.click(canvas.getByRole('combobox', { name: '상태' }))
    await userEvent.keyboard('{End}{ArrowUp}{Enter}')
    await userEvent.click(canvas.getByRole('button', { name: '답변 보내기' }))
    await expect(
      await canvas.findByRole('alert', { name: '저장 충돌' }),
    ).toBeVisible()
    const compareButton = canvas.getByRole('button', { name: '비교' })
    await userEvent.click(compareButton)
    const drawer = await canvas.findByRole('dialog', {
      name: '티켓 저장 충돌 비교',
    })
    await expect(within(drawer).getByText('서버 최신 값')).toBeVisible()
    await userEvent.keyboard('{Escape}')
    await expect(compareButton).toHaveFocus()
  },
}

export const MacroPreviewReview: Story = {
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: /매크로 라이브러리/ }),
    )
    await userEvent.click(
      await canvas.findByRole('menuitem', { name: /결제 승인 확인 안내/ }),
    )
    const drawer = await canvas.findByRole('dialog', {
      name: '결제 승인 확인 안내 검토',
    })
    await expect(await within(drawer).findByText('HIGH')).toBeVisible()
    await expect(
      await within(drawer).findByRole('textbox', {
        name: '매크로 답변 검토',
      }),
    ).toHaveTextContent('결제 승인 기록을 확인하고 있습니다.')
  },
}

export const MacroStaleApplyConflict: Story = {
  parameters: {
    msw: {
      handlers: workspaceHandlers(
        http.post(
          '/api/v1/agent/tickets/3001/macros/88888888-8888-4888-8888-888888888888/apply',
          () =>
            HttpResponse.json(
              {
                title: 'Macro preview stale',
                status: 409,
                requestId: 'request-macro-stale',
              },
              { status: 409 },
            ),
        ),
      ),
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: /매크로 라이브러리/ }),
    )
    await userEvent.click(
      await canvas.findByRole('menuitem', { name: /결제 승인 확인 안내/ }),
    )
    const drawer = await canvas.findByRole('dialog', {
      name: '결제 승인 확인 안내 검토',
    })
    await userEvent.click(
      await within(drawer).findByRole('button', { name: '매크로 적용' }),
    )
    await expect(
      await canvas.findByText(/티켓 또는 매크로 버전이 바뀌었습니다/),
    ).toBeVisible()
  },
}

export const CollaborationDenied: Story = {
  parameters: {
    msw: {
      handlers: workspaceHandlers(
        http.get('/api/v1/agent/tickets/3001/collaboration-notes', () =>
          HttpResponse.json(
            {
              title: 'Forbidden',
              status: 403,
              requestId: 'request-collaboration-denied',
            },
            { status: 403 },
          ),
        ),
      ),
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '티켓 컨텍스트 열기' }),
    )
    const drawer = await canvas.findByRole('dialog', { name: '티켓 컨텍스트' })
    await expect(
      await within(drawer).findByText('협업 메모를 볼 권한이 없습니다'),
    ).toBeVisible()
  },
}

export const ExternalReferencesEmpty: Story = {
  parameters: {
    msw: {
      handlers: workspaceHandlers(
        http.get('/api/v1/agent/tickets/3001/external-references', () =>
          HttpResponse.json({
            ticketVersion: 3,
            canManage: false,
            availableSystems: [],
            items: [],
          }),
        ),
      ),
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '티켓 컨텍스트 열기' }),
    )
    const drawer = await canvas.findByRole('dialog', { name: '티켓 컨텍스트' })
    await expect(
      await within(drawer).findByText('연결된 외부 참조가 없습니다.'),
    ).toBeVisible()
  },
}

export const ExternalReferencesDenied: Story = {
  parameters: {
    msw: {
      handlers: workspaceHandlers(
        http.get('/api/v1/agent/tickets/3001/external-references', () =>
          HttpResponse.json(
            {
              title: 'Forbidden',
              status: 403,
              requestId: 'request-external-denied',
            },
            { status: 403 },
          ),
        ),
      ),
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '티켓 컨텍스트 열기' }),
    )
    const drawer = await canvas.findByRole('dialog', { name: '티켓 컨텍스트' })
    await expect(
      await within(drawer).findByText(/외부 참조를 볼 권한이 없습니다/),
    ).toBeVisible()
  },
}

export const ExternalReferencesError: Story = {
  parameters: {
    msw: {
      handlers: workspaceHandlers(
        http.get('/api/v1/agent/tickets/3001/external-references', () =>
          HttpResponse.json(
            {
              title: 'Unavailable',
              status: 503,
              requestId: 'request-external-error',
            },
            { status: 503 },
          ),
        ),
      ),
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '티켓 컨텍스트 열기' }),
    )
    const drawer = await canvas.findByRole('dialog', { name: '티켓 컨텍스트' })
    await expect(await within(drawer).findByRole('alert')).toHaveTextContent(
      /외부 참조를 불러오지 못했습니다/,
    )
  },
}
