import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn, userEvent, waitFor, within } from 'storybook/test'
import { AiAssistantPanel, type AiAssistantClient } from './AiAssistantPanel'
import { plainTextDocument } from '../../api/types'
import type { AiFeature, AiJobReceipt, AiResult } from './api'

const JOB_IDS: Record<AiFeature, string> = {
  'ticket.summary': '11111111-1111-4111-8111-111111111111',
  'ticket.triage': '22222222-2222-4222-8222-222222222222',
  'ticket.reply_draft': '33333333-3333-4333-8333-333333333333',
}

const results: Record<AiFeature, AiResult> = {
  'ticket.summary': {
    type: 'ticket.summary',
    problem:
      '고객이 결제 승인 여부를 확인하고 있으며 상담사가 승인 기록을 조회 중입니다.',
    attemptedActions: ['결제 승인 기록 확인을 시작함'],
    unresolvedItems: ['최종 승인 상태 확인'],
    nextChecks: ['PG 승인 응답과 주문 상태 비교'],
  },
  'ticket.triage': {
    type: 'ticket.triage',
    topicCode: 'BILLING',
    suggestedTagIds: [],
    suggestedPriority: 'HIGH',
    reasons: ['결제 승인 결과에 따라 고객의 주문 진행 여부가 달라집니다.'],
  },
  'ticket.reply_draft': {
    type: 'ticket.reply_draft',
    answer:
      '안녕하세요. 결제 승인 기록을 확인하고 있습니다.\n\n확인이 끝나는 대로 결과를 안내드리겠습니다.',
    citations: [
      {
        articleId: '44444444-4444-4444-8444-444444444444',
        revisionId: '55555555-5555-4555-8555-555555555555',
        chunkId: '66666666-6666-4666-8666-666666666666',
        title: '결제 승인 상태 확인 안내',
        url: '/help/articles/payment-approval-status',
      },
    ],
  },
}

function receipt(
  feature: AiFeature,
  overrides: Partial<AiJobReceipt> = {},
): AiJobReceipt {
  return {
    jobId: JOB_IDS[feature],
    feature,
    status: 'SUCCEEDED',
    phase: 'COMPLETE',
    requestRevision: 7,
    createdAt: '2026-09-17T00:00:00Z',
    deadlineAt: '2026-09-17T00:05:00Z',
    completedAt: '2026-09-17T00:00:04Z',
    resultExpiresAt: '2026-09-24T00:00:04Z',
    pollAfterMs: 10_000,
    cancelRequested: false,
    contextRevision: 'a'.repeat(64),
    contextPolicyVersion: 'public-comments-v1',
    inputScope: 'PUBLIC_ONLY',
    stale: false,
    canInsert: feature === 'ticket.reply_draft',
    errorCode: null,
    result: results[feature],
    ...overrides,
  }
}

function clientFor(items: AiJobReceipt[]): AiAssistantClient {
  return {
    list: fn(async () => ({ items })),
    get: fn(async (_ticketNumber, jobId) => {
      const job = items.find((item) => item.jobId === jobId)
      if (!job) throw new Error('missing story job')
      return job
    }),
    create: fn(async (_ticketNumber, _version, feature) => receipt(feature)),
    cancel: fn(async (_ticketNumber, jobId) => {
      const job = items.find((item) => item.jobId === jobId)
      if (!job) throw new Error('missing story job')
      return { ...job, status: 'CANCELLED' as const }
    }),
    feedback: fn(async (_ticketNumber, jobId, type) => ({
      jobId,
      type,
      replayed: false,
      recordedAt: '2026-09-17T00:00:05Z',
    })),
  }
}

const readyJobs = [
  receipt('ticket.summary'),
  receipt('ticket.triage'),
  receipt('ticket.reply_draft'),
]

const emptyDraft = {
  body: '',
  document: plainTextDocument(''),
  attachmentIds: [],
}

const meta = {
  title: '06 Domain/AI assistance/AiAssistantPanel',
  component: AiAssistantPanel,
  parameters: { layout: 'centered' },
  decorators: [
    (Story) => (
      <div style={{ width: 360 }}>
        <Story />
      </div>
    ),
  ],
  args: {
    client: clientFor(readyJobs),
    composerMode: 'PUBLIC',
    publicDraft: emptyDraft,
    ticketNumber: 3001,
    ticketVersion: 7,
    onInsertReply: fn(),
  },
} satisfies Meta<typeof AiAssistantPanel>

export default meta
type Story = StoryObj<typeof meta>

export const ReadyRecommendations: Story = {}

export const Generating: Story = {
  args: {
    client: clientFor([
      receipt('ticket.summary', {
        status: 'RUNNING',
        phase: 'GENERATE',
        completedAt: null,
        resultExpiresAt: null,
        canInsert: false,
        result: null,
      }),
    ]),
  },
}

export const StaleReply: Story = {
  args: {
    client: clientFor([
      receipt('ticket.reply_draft', { stale: true, canInsert: false }),
    ]),
  },
}

export const BudgetExhausted: Story = {
  args: {
    client: clientFor([
      receipt('ticket.reply_draft', {
        status: 'FAILED',
        phase: 'COMPLETE',
        canInsert: false,
        errorCode: 'BUDGET_EXCEEDED',
        result: null,
      }),
    ]),
  },
}

export const ExistingDraftChoice: Story = {
  args: {
    client: clientFor([receipt('ticket.reply_draft')]),
    publicDraft: {
      body: '기존에 작성하던 고객 답변입니다.',
      document: plainTextDocument('기존에 작성하던 고객 답변입니다.'),
      attachmentIds: [],
    },
    onInsertReply: fn(),
  },
  play: async ({ canvasElement, args }) => {
    const canvas = within(canvasElement)
    await canvas.findByText('안녕하세요. 결제 승인 기록을 확인하고 있습니다.')
    await userEvent.click(
      canvas.getByRole('button', { name: 'PUBLIC 작성기에 사용' }),
    )
    await canvas.findByText('PUBLIC 작성기에 기존 초안이 있습니다.')
    await userEvent.click(canvas.getByRole('button', { name: '뒤에 추가' }))
    await waitFor(() =>
      expect(args.onInsertReply).toHaveBeenCalledWith(
        expect.stringContaining('결제 승인 기록'),
        'append',
        expect.objectContaining({
          body: '기존에 작성하던 고객 답변입니다.',
        }),
      ),
    )
    await expect(
      canvas.getByText('기존 PUBLIC 초안 뒤에 AI 초안을 추가했습니다.'),
    ).toBeVisible()
    await userEvent.click(
      canvas.getByRole('button', { name: 'PUBLIC 작성기에 사용' }),
    )
    await expect(args.onInsertReply).toHaveBeenCalledTimes(1)
    await expect(
      canvas.getByText('이 AI 초안은 이미 작성기에 넣었습니다.'),
    ).toBeVisible()
  },
}

export const AttachmentOnlyDraftChoice: Story = {
  args: {
    client: clientFor([receipt('ticket.reply_draft')]),
    publicDraft: {
      ...emptyDraft,
      attachmentIds: ['77777777-7777-4777-8777-777777777777'],
    },
    onInsertReply: fn(),
  },
  play: async ({ canvasElement, args }) => {
    const canvas = within(canvasElement)
    await canvas.findByText('안녕하세요. 결제 승인 기록을 확인하고 있습니다.')
    await userEvent.click(
      canvas.getByRole('button', { name: 'PUBLIC 작성기에 사용' }),
    )
    await expect(
      canvas.getByText('PUBLIC 작성기에 기존 초안이 있습니다.'),
    ).toBeVisible()
    await expect(args.onInsertReply).not.toHaveBeenCalled()
  },
}

export const MissingSucceededResult: Story = {
  args: {
    client: clientFor([
      receipt('ticket.summary', {
        result: null,
      }),
    ]),
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement)
    await expect(
      await canvas.findByText(
        '완료된 AI 결과를 불러오지 못했습니다. 새로 생성해 주세요.',
      ),
    ).toBeVisible()
  },
}

export const InternalComposerGuard: Story = {
  args: {
    client: clientFor([receipt('ticket.reply_draft')]),
    composerMode: 'INTERNAL',
    onInsertReply: fn(),
  },
  play: async ({ canvasElement, args }) => {
    const canvas = within(canvasElement)
    await canvas.findByText('안녕하세요. 결제 승인 기록을 확인하고 있습니다.')
    await userEvent.click(
      canvas.getByRole('button', { name: 'PUBLIC 작성기에 사용' }),
    )
    await expect(
      canvas.getByText('PUBLIC 답변 작성기를 선택한 뒤 다시 시도해 주세요.'),
    ).toBeVisible()
    await expect(args.onInsertReply).not.toHaveBeenCalled()
  },
}
