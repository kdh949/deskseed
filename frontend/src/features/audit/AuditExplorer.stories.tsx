import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn, userEvent } from 'storybook/test'
import { AuditExplorer, type AuditActivitiesState } from './AuditExplorer'
import type { AuditActivity, AuditActivityFilters } from '../../api/types'

const meta = {
  title: '06 Domain & Workspace/AuditExplorer',
  component: AuditExplorer,
  parameters: {
    docs: {
      description: {
        component:
          'AUD-001 감사 탐색기. 데이터 패칭은 상위 AuditExplorerPage 컨테이너가 소유하고, 이 컴포넌트는 레저 탭/필터/목록/페이지네이션 상태를 props로 받아 렌더링만 한다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof AuditExplorer>

export default meta
type Story = StoryObj<typeof meta>

const baseFilters: AuditActivityFilters = {
  from: '2026-08-07T00:00:00Z',
  limit: 50,
}

const activities: AuditActivity[] = [
  {
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
  },
  {
    id: '22222222-2222-4222-8222-222222222222',
    ledger: 'ACCESS_SEARCH',
    action: 'SEARCH_EXECUTED',
    actor: { id: 'staff-2', type: 'STAFF', displayName: '박도윤' },
    occurredAt: '2026-08-14T08:40:00Z',
    ticketNumber: null,
    groupId: null,
    field: null,
    resourceType: 'SEARCH',
    resourceId: null,
    summary: '고객 문의 검색 실행',
    source: 'AGENT_UI',
    outcome: 'SUCCEEDED',
    requestId: 'req-c3d4',
    correlationId: 'corr-c3d4',
    protectedContentAvailable: true,
    searchFingerprint: 'fp-1',
  },
  {
    id: '33333333-3333-4333-8333-333333333333',
    ledger: 'ADMIN_SECURITY',
    action: 'STAFF_LOGIN_DENIED',
    actor: { id: null, type: 'SYSTEM', displayName: '시스템' },
    occurredAt: '2026-08-14T08:00:00Z',
    ticketNumber: null,
    groupId: null,
    field: null,
    resourceType: 'STAFF_SESSION',
    resourceId: null,
    summary: '잘못된 비밀번호로 로그인 거부',
    source: 'AGENT_UI',
    outcome: 'DENIED',
    requestId: 'req-e5f6',
    correlationId: 'corr-e5f6',
    protectedContentAvailable: false,
    searchFingerprint: null,
  },
]

const baseArgs = {
  filters: baseFilters,
  hasActiveFilters: false,
  onClearFilters: fn(),
  onExport: fn(),
  onNextPage: fn(),
  onOpenActivity: fn(),
  onRetryActivities: fn(),
  onUpdateFilter: fn(),
}

export const Loading: Story = {
  args: { ...baseArgs, activities: { status: 'loading' } },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('감사 활동 불러오는 중')).toBeVisible()
  },
}

export const Denied: Story = {
  args: {
    ...baseArgs,
    activities: { status: 'error', denied: true, requestId: 'req-denied' },
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByText('감사 활동을 조회할 권한이 없습니다.'),
    ).toBeVisible()
  },
}

export const LoadError: Story = {
  args: {
    ...baseArgs,
    activities: { status: 'error', denied: false, requestId: 'req-500' },
  },
  play: async ({ args, canvas }) => {
    await userEvent.click(canvas.getByRole('button', { name: '다시 시도' }))
    await expect(args.onRetryActivities).toHaveBeenCalled()
  },
}

export const Empty: Story = {
  args: {
    ...baseArgs,
    activities: {
      status: 'ready',
      items: [],
      nextCursor: null,
      projection: {
        state: 'CURRENT',
        projectedCount: 0,
        lastRebuiltAt: null,
      },
    },
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByText('일치하는 감사 활동이 없습니다.'),
    ).toBeVisible()
  },
}

const readyState: AuditActivitiesState = {
  status: 'ready',
  items: activities,
  nextCursor: 'opaque-cursor-2',
  projection: { state: 'CURRENT', projectedCount: 3, lastRebuiltAt: null },
}

export const WithResults: Story = {
  args: { ...baseArgs, activities: readyState },
  play: async ({ args, canvas }) => {
    await expect(canvas.getByText('박도윤')).toBeVisible()
    await userEvent.click(
      canvas.getByRole('link', { name: 'TICKET_PRIORITY_CHANGED 상세 보기' }),
    )
    await expect(args.onOpenActivity).toHaveBeenCalledWith(
      '11111111-1111-4111-8111-111111111111',
    )
    await userEvent.click(canvas.getByRole('button', { name: '다음 페이지' }))
    await expect(args.onNextPage).toHaveBeenCalled()
  },
}

export const FiltersApplied: Story = {
  args: {
    ...baseArgs,
    filters: { ...baseFilters, outcome: 'DENIED' },
    hasActiveFilters: true,
    activities: readyState,
  },
  play: async ({ args, canvas }) => {
    const clearButtons = canvas.getAllByRole('button', { name: '필터 지우기' })
    await userEvent.click(clearButtons[0]!)
    await expect(args.onClearFilters).toHaveBeenCalled()
  },
}

export const ProjectionDegraded: Story = {
  args: {
    ...baseArgs,
    activities: {
      status: 'ready',
      items: activities,
      nextCursor: null,
      projection: {
        state: 'DEGRADED',
        projectedCount: 3,
        lastRebuiltAt: '2026-08-13T00:00:00Z',
      },
    },
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByText(/감사 조회 프로젝션이 저하 상태입니다/),
    ).toBeVisible()
  },
}

export const ExportAction: Story = {
  args: { ...baseArgs, activities: readyState },
  play: async ({ args, canvas }) => {
    await userEvent.click(canvas.getByRole('button', { name: '내보내기' }))
    await expect(args.onExport).toHaveBeenCalled()
  },
}
