import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse, type HttpHandler } from 'msw'
import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { expect, userEvent, waitFor, within } from 'storybook/test'
import { mswHandlers } from '../../../.storybook/msw-handlers'
import type { AgentTicketDetail } from '../../api/types'
import { SeedButton } from '../../design-system/canonical'
import { AgentTicketEditorWorkspace } from './AgentTicketEditorWorkspace'
import { removeLocalTicketDraft } from '../collaboration/draftRecovery'
import { ticketDraftStorageKey } from './model/ticketEditorModel'
import {
  article as knowledgeArticle,
  revision as knowledgeRevision,
} from '../../extensions/knowledge-workflow/fixtures'

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

const aiJobsHandler = http.get(
  '/api/v1/agent/tickets/:ticketNumber/ai/jobs',
  () => HttpResponse.json({ items: [] }),
)

const workspaceHandlers = (...overrides: HttpHandler[]) => [
  ...overrides,
  externalReferenceHandler,
  collaborationHandler,
  macroHandler,
  macroPreviewHandler,
  aiJobsHandler,
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

export const PreserveDraftBeforeNavigation: Story = {
  args: {
    detail: { ...detail, ticket: { ...detail.ticket, ticketNumber: 93011 } },
  },
  beforeEach: async () => {
    localStorage.removeItem(ticketDraftStorageKey(staffId, 93011))
    await Promise.all(
      ['PUBLIC_REPLY', 'INTERNAL_NOTE'].map((channel) =>
        removeLocalTicketDraft(
          staffId,
          93011,
          channel as 'PUBLIC_REPLY' | 'INTERNAL_NOTE',
        ),
      ),
    )
  },
  render: function DraftNavigation(args) {
    const location = useLocation()
    const navigate = useNavigate()
    if (location.pathname === '/draft-preview-next')
      return <p role="status">초안을 이 브라우저에 보관하고 이동했습니다.</p>
    return (
      <>
        <SeedButton onClick={() => navigate('/draft-preview-next')}>
          다른 티켓 열기
        </SeedButton>
        <AgentTicketEditorWorkspace {...args} />
      </>
    )
  },
  play: async ({ canvas, userEvent }) => {
    await userEvent.click(
      await canvas.findByRole('textbox', { name: '공개 답변 내용' }),
    )
    await userEvent.paste('전송하지 않고 보관할 답변')
    await userEvent.click(
      canvas.getByRole('button', { name: '다른 티켓 열기' }),
    )
    const guard = await canvas.findByRole('dialog', {
      name: '저장하지 않은 변경사항',
    })
    await expect(
      within(guard).getByRole('button', { name: '초안 유지하고 이동' }),
    ).toBeEnabled()
    await userEvent.click(
      within(guard).getByRole('button', { name: '계속 작성' }),
    )
    await expect(
      canvas.getByRole('textbox', { name: '공개 답변 내용' }),
    ).toHaveTextContent('전송하지 않고 보관할 답변')
    await userEvent.click(
      canvas.getByRole('button', { name: '다른 티켓 열기' }),
    )
    await userEvent.click(
      canvas.getByRole('button', { name: '초안 유지하고 이동' }),
    )
    await expect(await canvas.findByRole('status')).toHaveTextContent(
      '초안을 이 브라우저에 보관하고 이동했습니다.',
    )
  },
}

export const LatestActivity: Story = {
  args: {
    detail: {
      ...detail,
      history: [1, 6, 2, 5, 3, 4].map((hour) => ({
        id: `activity-${hour}`,
        eventType: 'TICKET_UPDATED',
        actor: { id: 'staff-3001', type: 'STAFF', displayName: '상담사 A' },
        occurredAt: `2026-08-15T0${hour}:00:00Z`,
      })),
    },
  },
  play: async ({ canvas, userEvent }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '티켓 컨텍스트 열기' }),
    )
    const panel = canvas.getByRole('tabpanel', { name: '고객' })
    const times = () =>
      Array.from(panel.querySelectorAll('time')).map((item) => item.dateTime)
    await expect(times()).toEqual(
      [6, 5, 4, 3].map((hour) => `2026-08-15T0${hour}:00:00Z`),
    )
    await userEvent.click(
      within(panel).getByRole('button', { name: '활동 6건 모두 보기' }),
    )
    await expect(times()).toHaveLength(6)
    await userEvent.click(
      within(panel).getByRole('button', { name: '최근 4건만 보기' }),
    )
    await expect(times()).toHaveLength(4)
  },
}

export const ManualDraftSaveFailure: Story = {
  args: {
    detail: { ...detail, ticket: { ...detail.ticket, ticketNumber: 93012 } },
  },
  beforeEach: async () => {
    localStorage.removeItem(ticketDraftStorageKey(staffId, 93012))
    await Promise.all(
      (['PUBLIC_REPLY', 'INTERNAL_NOTE'] as const).map((channel) =>
        removeLocalTicketDraft(staffId, 93012, channel),
      ),
    )
  },
  parameters: {
    msw: {
      handlers: workspaceHandlers(
        http.put('/api/v1/agent/tickets/93012/drafts/:channel', () =>
          HttpResponse.json(
            {
              type: '/problems/draft-unavailable',
              title: 'Draft unavailable',
              status: 500,
            },
            { status: 500 },
          ),
        ),
      ),
    },
  },
  play: async ({ canvas, userEvent }) => {
    await userEvent.click(
      await canvas.findByRole('textbox', { name: '공개 답변 내용' }),
    )
    await userEvent.paste('이 브라우저에 남길 답변')
    await userEvent.click(canvas.getByRole('button', { name: '초안 저장' }))
    await expect(
      await canvas.findByText('이 브라우저에만 저장됨', { exact: true }),
    ).toBeVisible()
    await expect(
      canvas.queryByText('복구 초안을 저장했습니다.'),
    ).not.toBeInTheDocument()
    await expect(
      canvas.getByRole('textbox', { name: '공개 답변 내용' }),
    ).toHaveTextContent('이 브라우저에 남길 답변')
  },
}

export const ContextPreservesWork: Story = {
  play: async ({ canvas, userEvent }) => {
    const trigger = canvas.getByRole('button', {
      name: '협업 작업',
    })
    await userEvent.click(trigger)
    const dialog = await canvas.findByRole('dialog', { name: '티켓 컨텍스트' })
    const context = within(dialog)
    await expect(context.getByRole('tab', { name: '협업' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    await userEvent.click(
      context.getByRole('button', { name: '협업 메모 작성' }),
    )
    await userEvent.type(
      context.getByRole('textbox', { name: '협업 메모' }),
      '확인 중인 내용은 유지합니다.',
    )
    const collaborationTab = context.getByRole('tab', {
      name: '협업',
    })
    collaborationTab.focus()
    await userEvent.keyboard('{ArrowRight}')
    await expect(context.getByRole('tab', { name: '자료' })).toHaveFocus()
    await expect(
      context.queryByRole('textbox', { name: '협업 메모' }),
    ).not.toBeInTheDocument()
    await userEvent.keyboard('{ArrowLeft}')
    await expect(
      context.getByRole('textbox', { name: '협업 메모' }),
    ).toHaveValue('확인 중인 내용은 유지합니다.')
    await userEvent.keyboard('{Escape}')
    await expect(trigger).toHaveFocus()
    await expect(canvas.queryByRole('dialog')).not.toBeInTheDocument()
    await userEvent.click(trigger)
    await expect(
      canvas.getByRole('textbox', { name: '협업 메모' }),
    ).toHaveValue('확인 중인 내용은 유지합니다.')
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

export const SaveTargetLabels: Story = {
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole('combobox', { name: '우선순위' }))
    await userEvent.keyboard('{End}{Enter}')
    await expect(
      canvas.getByRole('button', { name: '변경사항 저장' }),
    ).toBeEnabled()
    const editor = await canvas.findByRole('textbox', {
      name: '공개 답변 내용',
    })
    await userEvent.click(editor)
    await userEvent.paste('변경한 상태와 함께 보낼 답변')
    await expect(
      canvas.getByRole('button', { name: '답변과 변경사항 저장' }),
    ).toBeEnabled()
    await userEvent.click(
      canvas.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }),
    )
    await expect(
      canvas.getByRole('button', { name: '변경사항 저장' }),
    ).toBeEnabled()
    await userEvent.type(
      await canvas.findByRole('textbox', { name: '내부 메모 내용' }),
      '내부 확인 사항',
    )
    await expect(
      canvas.getByRole('button', { name: '메모와 변경사항 저장' }),
    ).toBeEnabled()
  },
}

export const ValidationFeedback: Story = {
  parameters: {
    msw: {
      handlers: workspaceHandlers(
        http.post('/api/v1/agent/tickets/3001/commands', () =>
          HttpResponse.json(
            {
              type: '/problems/validation',
              status: 400,
              detail: 'One or more command fields are invalid.',
              fieldErrors: [
                {
                  field: 'assigneeId',
                  message: '현재 그룹의 활성 상담사를 선택해 주세요.',
                },
              ],
              requestId: 'validation-story',
            },
            { status: 400 },
          ),
        ),
      ),
    },
  },
  play: async ({ canvas }) => {
    const editor = await canvas.findByRole('textbox', {
      name: '공개 답변 내용',
    })
    await userEvent.click(editor)
    await userEvent.paste('실패해도 보존하는 답변')
    await expect(editor).toHaveTextContent('실패해도 보존하는 답변')
    await userEvent.click(canvas.getByRole('button', { name: '답변 보내기' }))
    await expect(
      await canvas.findByText(
        '담당자: 현재 그룹의 활성 상담사를 선택해 주세요.',
      ),
    ).toBeVisible()
    await expect(editor).toHaveTextContent('실패해도 보존하는 답변')
    await expect(canvas.getByText('요청 ID: validation-story')).toBeVisible()
  },
}

export const BackgroundOwnershipUpdate: Story = {
  render: function ProjectionUpdate(args) {
    const [current, setCurrent] = useState(args.detail)
    return (
      <>
        <SeedButton
          onClick={() =>
            setCurrent({
              ...args.detail,
              ticket: {
                ...args.detail.ticket,
                version: 4,
                assignee: { id: 'staff-3002', displayName: '상담사 B' },
              },
              assignmentOptions: {
                groups: [
                  {
                    ...args.detail.assignmentOptions.groups[0]!,
                    members: [
                      ...args.detail.assignmentOptions.groups[0]!.members,
                      { id: 'staff-3002', displayName: '상담사 B' },
                    ],
                  },
                ],
              },
            })
          }
        >
          이관 완료 조회
        </SeedButton>
        <AgentTicketEditorWorkspace {...args} detail={current} />
      </>
    )
  },
  play: async ({ canvas }) => {
    const editor = await canvas.findByRole('textbox', {
      name: '공개 답변 내용',
    })
    await userEvent.click(editor)
    await userEvent.paste('이관 결과 조회 중에도 보존할 답변')
    await expect(editor).toHaveTextContent('이관 결과 조회 중에도 보존할 답변')
    await userEvent.click(
      canvas.getByRole('button', { name: '이관 완료 조회' }),
    )
    await expect(
      canvas.getByRole('combobox', { name: '담당자' }),
    ).toHaveTextContent('상담사 B')
    await expect(editor).toHaveTextContent('이관 결과 조회 중에도 보존할 답변')
    await expect(
      canvas.getByRole('button', { name: '답변 보내기' }),
    ).toBeEnabled()
  },
}

export const ConfirmedSaveDraftConflict: Story = {
  args: { staffId: '66666666-6666-4666-8666-666666666666' },
  parameters: {
    msw: {
      handlers: workspaceHandlers(
        http.get('/api/v1/agent/tickets/3001/drafts/PUBLIC_REPLY', () =>
          HttpResponse.json({
            ticketNumber: 3001,
            channel: 'PUBLIC_REPLY',
            body: '저장할 복구 답변',
            content: { format: 'PLAIN_TEXT', text: '저장할 복구 답변' },
            attachmentIds: [],
            clientDeviceId: '33333333-3333-4333-8333-333333333333',
            baseTicketVersion: 3,
            draftVersion: 2,
            updatedAt: '2026-08-24T11:00:00Z',
            expiresAt: '2099-08-31T11:00:00Z',
          }),
        ),
        http.post('/api/v1/agent/tickets/3001/commands', () =>
          HttpResponse.json({
            ticketNumber: 3001,
            version: 4,
            auditId: '22222222-2222-4222-8222-222222222222',
            warnings: [],
          }),
        ),
        http.delete('/api/v1/agent/tickets/3001/drafts/PUBLIC_REPLY', () =>
          HttpResponse.json(
            {
              type: '/problems/ticket-draft-conflict',
              status: 409,
              requestId: 'draft-clear-story',
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
    await waitFor(() => expect(editor).toHaveTextContent('저장할 복구 답변'))
    await userEvent.click(canvas.getByRole('button', { name: '답변 보내기' }))
    await expect(
      await canvas.findByText('공개 답변과 변경사항을 저장했습니다.'),
    ).toBeVisible()
    await expect(
      canvas.getByText(/다른 브라우저의 새 복구 초안은 삭제하지 않았습니다/),
    ).toBeVisible()
    await expect(editor).toHaveTextContent(/^$/)
    await expect(
      canvas.getByRole('button', { name: '답변 보내기' }),
    ).toBeDisabled()
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
            expiresAt: '2099-09-21T00:00:00Z',
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
            expiresAt: '2099-09-21T00:00:00Z',
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
  play: async ({ canvas, userEvent }) => {
    await userEvent.click(
      canvas.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }),
    )
    const editor = await canvas.findByRole('textbox', {
      name: '내부 메모 내용',
    })
    await userEvent.click(editor)
    await userEvent.paste('직원 전용 확인 사항입니다.')
    await expect(editor).toHaveTextContent('직원 전용 확인 사항입니다.')
    await userEvent.click(
      canvas.getByRole('tab', { name: '공개 답변 작성 모드로 전환' }),
    )
    await expect(
      await canvas.findByRole('textbox', { name: '공개 답변 내용' }),
    ).not.toHaveTextContent('직원 전용 확인 사항입니다.')
    await userEvent.click(
      canvas.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }),
    )
    await expect(
      await canvas.findByRole('textbox', { name: '내부 메모 내용' }),
    ).toHaveTextContent('직원 전용 확인 사항입니다.')
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

export const AllCollaborationRequests: Story = {
  args: {
    detail: {
      ...detail,
      ticket: { ...detail.ticket, openChildCount: 5 },
      context: {
        ...detail.context,
        children: Array.from({ length: 5 }, (_, index) => ({
          ...detail.ticket,
          ticketNumber: 4001 + index,
          subject: `결제 검토 ${index + 1}`,
          isChild: true,
        })),
      },
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '티켓 컨텍스트 열기' }),
    )
    const context = within(
      await canvas.findByRole('dialog', { name: '티켓 컨텍스트' }),
    )
    await expect(
      await context.findByText('진행 중인 내부 협업 요청 5건'),
    ).toBeVisible()
    await expect(
      context.queryByRole('link', { name: '#4005' }),
    ).not.toBeInTheDocument()
    await userEvent.click(
      context.getByRole('button', { name: /관련 티켓 .*건 모두 보기/ }),
    )
    await expect(context.getByRole('link', { name: '#4005' })).toBeVisible()
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
    await userEvent.click(
      canvas.getByRole('button', { name: '답변과 변경사항 저장' }),
    )
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
    await userEvent.click(within(drawer).getByRole('tab', { name: '협업' }))
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
    await userEvent.click(within(drawer).getByRole('tab', { name: '자료' }))
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
    await userEvent.click(within(drawer).getByRole('tab', { name: '자료' }))
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
    await userEvent.click(within(drawer).getByRole('tab', { name: '자료' }))
    await expect(await within(drawer).findByRole('alert')).toHaveTextContent(
      /외부 참조를 불러오지 못했습니다/,
    )
  },
}

export const InsertKnowledgeLinkPreservesReply: Story = {
  parameters: {
    msw: {
      handlers: workspaceHandlers(
        http.post('/api/v1/agent/knowledge/search', () =>
          HttpResponse.json({
            items: [
              {
                articleSlug: 'refund-guide',
                title: '환불 처리 안내',
                excerpt: '환불 절차',
                audience: 'PUBLIC',
                categoryTitle: '결제',
                sectionTitle: '환불',
              },
            ],
            nextCursor: null,
          }),
        ),
        http.get('/api/v1/agent/knowledge/articles/refund-guide', () =>
          HttpResponse.json({
            ...knowledgeArticle,
            lifecycle: 'PUBLISHED',
            currentPublishedRevision: knowledgeRevision,
          }),
        ),
      ),
    },
  },
  play: async ({ canvas, userEvent }) => {
    const reply = await canvas.findByRole('textbox', { name: '공개 답변 내용' })
    await userEvent.click(reply)
    await userEvent.paste('기존 답변을 이어서 작성합니다.')
    await expect(reply).toHaveTextContent('기존 답변을 이어서 작성합니다.')
    await userEvent.click(
      canvas.getByRole('button', { name: '티켓 컨텍스트 열기' }),
    )
    const context = await canvas.findByRole('dialog', { name: '티켓 컨텍스트' })
    await userEvent.click(within(context).getByRole('tab', { name: '자료' }))
    await userEvent.click(
      within(context).getByRole('button', { name: '문서 검색 열기' }),
    )
    const panel = within(
      await canvas.findByRole('dialog', { name: '지식 문서 검색' }),
    )
    await userEvent.type(panel.getByLabelText(/지식 검색어/), '환불')
    await userEvent.click(panel.getByRole('button', { name: '지식 검색' }))
    await userEvent.click(
      await panel.findByRole('button', { name: '읽기: 환불 처리 안내' }),
    )
    await userEvent.click(
      await panel.findByRole('button', { name: '현재 답변에 링크 삽입' }),
    )
    await panel.findByText('공개 답변 초안에 문서 링크를 넣었습니다.')
    await userEvent.keyboard('{Escape}')
    await userEvent.keyboard('{Escape}')
    await expect(reply).toHaveTextContent('기존 답변을 이어서 작성합니다.')
    await expect(
      within(reply).getByRole('link', { name: '환불 처리 안내' }),
    ).toHaveAttribute('href', `${window.location.origin}/articles/refund-guide`)
  },
}
