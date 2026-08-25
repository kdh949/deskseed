import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect, userEvent, waitFor } from 'storybook/test'
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
      body: '결제가 완료되었는지 확인하고 싶습니다.',
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
  },
  tags: ['autodocs'],
} satisfies Meta<typeof AgentTicketEditorWorkspace>

export default meta
type Story = StoryObj<typeof meta>

export const Writable: Story = {
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('textbox', { name: '공개 답변 내용' }),
    ).toBeVisible()
    await expect(canvas.getByLabelText('그룹')).toHaveValue('group-payments')
  },
}

export const RecoversRemoteDrafts: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/tickets/3001/drafts/PUBLIC_REPLY', () =>
          HttpResponse.json({
            ticketNumber: 3001,
            channel: 'PUBLIC_REPLY',
            body: '저장된 공개 답변 초안입니다.',
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
            attachmentIds: [],
            clientDeviceId: '33333333-3333-4333-8333-333333333333',
            baseTicketVersion: 3,
            draftVersion: 3,
            updatedAt: '2026-08-22T00:00:00Z',
            expiresAt: '2026-09-21T00:00:00Z',
          }),
        ),
      ],
    },
  },
  play: async ({ canvas, userEvent }) => {
    await waitFor(() => {
      expect(
        canvas.getByRole('textbox', { name: '공개 답변 내용' }),
      ).toHaveValue('저장된 공개 답변 초안입니다.')
    })

    await userEvent.click(
      canvas.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }),
    )

    await waitFor(() => {
      expect(
        canvas.getByRole('textbox', { name: '내부 메모 내용' }),
      ).toHaveValue('저장된 내부 메모 초안입니다.')
    })
  },
}

export const SavingLocksInputs: Story = {
  parameters: {
    msw: {
      handlers: [
        ...mswHandlers,
        http.get('/api/v1/agent/csrf', () =>
          HttpResponse.json({
            token: 'a'.repeat(32),
            headerName: 'X-CSRF-TOKEN',
          }),
        ),
        http.post('/api/v1/agent/tickets/3001/commands', async () => {
          await new Promise((resolve) => window.setTimeout(resolve, 1_000))
          return HttpResponse.json({
            ticketNumber: 3001,
            version: 4,
            auditId: '22222222-2222-4222-8222-222222222222',
            warnings: [],
          })
        }),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(
      canvas.getByRole('textbox', { name: '공개 답변 내용' }),
      '저장 중 입력 잠금 확인',
    )
    await userEvent.click(
      canvas.getByRole('button', { name: '공개 답변 저장' }),
    )
    await expect(
      canvas.getByRole('textbox', { name: '공개 답변 내용' }),
    ).toBeDisabled()
    await expect(canvas.getByRole('combobox', { name: '상태' })).toBeDisabled()
    await expect(
      canvas.getByRole('combobox', { name: '우선순위' }),
    ).toBeDisabled()
    await expect(canvas.getByRole('combobox', { name: '그룹' })).toBeDisabled()
    await expect(
      canvas.getByRole('combobox', { name: '담당자' }),
    ).toBeDisabled()
  },
}

export const InternalDraft: Story = {
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }),
    )
    await userEvent.type(
      canvas.getByRole('textbox', { name: '내부 메모 내용' }),
      '직원 전용 확인 사항입니다.',
    )
    await expect(
      canvas.getByText('내부 메모는 고객에게 공개되지 않습니다.'),
    ).toBeVisible()
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
      canvas.getByRole('textbox', { name: '내부 메모 내용' }),
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
