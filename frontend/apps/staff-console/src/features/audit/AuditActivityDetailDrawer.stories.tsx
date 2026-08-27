import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn, userEvent } from 'storybook/test'
import { AuditActivityDetailDrawer } from './AuditActivityDetailDrawer'
import type { AuditActivityDetail } from '../../api/types'

const meta = {
  title: '06 Domain & Workspace/AuditActivityDetailDrawer',
  component: AuditActivityDetailDrawer,
  parameters: {
    docs: {
      description: {
        component:
          'AUD-001 감사 활동 상세 drawer. 보호된 검색어 원문 공개(reveal)는 재인증 흐름이 아직 없어 이 슬라이스에서 제외되며, 보호된 내용은 항상 마스킹 상태로만 표시된다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof AuditActivityDetailDrawer>

export default meta
type Story = StoryObj<typeof meta>

const changeDetail: AuditActivityDetail = {
  id: '11111111-1111-4111-8111-111111111111',
  ledger: 'TICKET_CHANGE',
  action: 'TICKET_PRIORITY_CHANGED',
  actor: { id: 'staff-1', type: 'STAFF', displayName: '이서연' },
  occurredAt: '2026-08-14T09:12:00Z',
  ticketNumber: 1050,
  groupId: null,
  field: 'priority',
  resourceType: null,
  resourceId: null,
  summary: '우선순위를 보통에서 높음으로 변경',
  source: 'AGENT_UI',
  outcome: 'SUCCEEDED',
  requestId: 'req-a1b2',
  correlationId: 'corr-a1b2',
  protectedContentAvailable: false,
  searchFingerprint: null,
  canonicalEventId: 'evt-9001',
  canonicalParentId: null,
  fieldChange: { field: 'priority', before: 'NORMAL', after: 'HIGH' },
  interactionId: 'int-1',
  sessionFingerprint: 'v1:sess-fp',
  authType: 'PASSWORD',
  ipAddress: '203.0.113.10',
  userAgent: 'Mozilla/5.0',
  search: null,
  metadata: {},
}

const searchDetail: AuditActivityDetail = {
  ...changeDetail,
  id: '22222222-2222-4222-8222-222222222222',
  ledger: 'ACCESS_SEARCH',
  action: 'SEARCH_EXECUTED',
  ticketNumber: null,
  field: null,
  summary: '고객 문의 검색 실행',
  protectedContentAvailable: true,
  fieldChange: null,
  canonicalEventId: 'evt-9002',
  search: {
    queryRedacted: 'mi***@example.test',
    queryFingerprint: 'fp-1',
    filters: { status: 'OPEN' },
    sort: null,
    resultCount: 4,
    originSearchActivityId: null,
    openedActivityCount: 2,
    openedActivitiesTruncated: false,
    openedActivities: [
      {
        activityId: '33333333-3333-4333-8333-333333333333',
        ticketNumber: 1042,
        occurredAt: '2026-08-14T08:41:00Z',
      },
      {
        activityId: '44444444-4444-4444-8444-444444444444',
        ticketNumber: 1050,
        occurredAt: '2026-08-14T08:42:00Z',
      },
    ],
  },
}

const baseArgs = {
  onClose: fn(),
  onOpenActivity: fn(),
  onRetry: fn(),
}

export const Loading: Story = {
  args: { ...baseArgs, open: true, state: { status: 'loading' } },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByText('상세 정보를 불러오고 있습니다.'),
    ).toBeVisible()
  },
}

export const LoadError: Story = {
  args: {
    ...baseArgs,
    open: true,
    state: { status: 'error', requestId: 'req-detail-500' },
  },
  play: async ({ args, canvas }) => {
    await userEvent.click(canvas.getByRole('button', { name: '다시 시도' }))
    await expect(args.onRetry).toHaveBeenCalled()
  },
}

export const FieldChange: Story = {
  args: {
    ...baseArgs,
    open: true,
    state: { status: 'ready', detail: changeDetail },
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('evt-9001')).toBeVisible()
    await expect(canvas.getByText('NORMAL')).toBeVisible()
    await expect(canvas.getByText('HIGH')).toBeVisible()
  },
}

export const ProtectedSearchWithOpenedLinks: Story = {
  args: {
    ...baseArgs,
    open: true,
    state: { status: 'ready', detail: searchDetail },
  },
  play: async ({ args, canvas }) => {
    await expect(
      canvas.getByText(
        '보호된 내용이 있습니다. 이 화면에서는 원문을 공개할 수 없습니다.',
      ),
    ).toBeVisible()
    await expect(canvas.getByText('mi***@example.test')).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '티켓 #1042' }))
    await expect(args.onOpenActivity).toHaveBeenCalledWith(
      '33333333-3333-4333-8333-333333333333',
    )
  },
}

export const Closed: Story = {
  args: { ...baseArgs, open: false, state: null },
}
