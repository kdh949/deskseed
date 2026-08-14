import type { ReactNode } from 'react'
import type {
  AuditActivity,
  AuditActivityFilters,
  AuditLedgerType,
  AuditOutcome,
  AuditProjectionStatus,
} from '../../api/types'
import {
  DsButton,
  DsPropertyField,
  DsSelect,
  DsTabs,
  Notification,
  ScreenState,
  TableSkeleton,
} from '../../design-system'
import { AuditActivityTable } from './AuditActivityTable'
import type { AuditFilterKey } from './model/useAuditActivityFilters'

const LEDGER_TABS: { id: AuditLedgerType | 'ALL'; label: string }[] = [
  { id: 'ALL', label: '전체' },
  { id: 'TICKET_CHANGE', label: '티켓 변경' },
  { id: 'ACCESS_SEARCH', label: '접근 및 검색' },
  { id: 'ADMIN_SECURITY', label: '관리 및 보안' },
]

const OUTCOME_LABELS: Record<AuditOutcome, string> = {
  SUCCEEDED: '성공',
  DENIED: '거부',
  FAILED: '실패',
}

export type AuditActivitiesState =
  | { status: 'loading' }
  | { status: 'error'; denied: boolean; requestId?: string }
  | {
      status: 'ready'
      items: AuditActivity[]
      nextCursor: string | null
      projection: AuditProjectionStatus
    }

export interface AuditExplorerProps {
  activities: AuditActivitiesState
  filters: AuditActivityFilters
  hasActiveFilters: boolean
  onClearFilters: () => void
  onExport: () => void
  onNextPage: () => void
  onOpenActivity: (activityId: string) => void
  onRetryActivities: () => void
  onUpdateFilter: (key: AuditFilterKey, value: string) => void
}

export function AuditExplorer({
  activities,
  filters,
  hasActiveFilters,
  onClearFilters,
  onExport,
  onNextPage,
  onOpenActivity,
  onRetryActivities,
  onUpdateFilter,
}: AuditExplorerProps) {
  const activeLedger = filters.ledger ?? 'ALL'

  return (
    <main aria-label="감사 탐색기" className="audit-explorer">
      <header className="audit-explorer-header">
        <div>
          <h1>감사 탐색기</h1>
          <p>티켓 변경, 접근·검색, 관리·보안 활동을 조회합니다.</p>
        </div>
        <DsButton onClick={onExport} tone="primary">
          내보내기
        </DsButton>
      </header>

      <DsTabs
        activeId={activeLedger}
        ariaLabel="감사 레저"
        items={LEDGER_TABS}
        onChange={(id) => onUpdateFilter('ledger', id === 'ALL' ? '' : id)}
      />

      <section aria-label="필터" className="audit-explorer-filters">
        <FilterField label="시작일">
          <input
            onChange={(event) =>
              onUpdateFilter('from', toIsoInstant(event.target.value))
            }
            type="datetime-local"
            value={toLocalInputValue(filters.from)}
          />
        </FilterField>
        <FilterField label="종료일">
          <input
            onChange={(event) =>
              onUpdateFilter('to', toIsoInstant(event.target.value))
            }
            type="datetime-local"
            value={toLocalInputValue(filters.to)}
          />
        </FilterField>
        <FilterField label="액션">
          <input
            onChange={(event) => onUpdateFilter('action', event.target.value)}
            value={filters.action ?? ''}
          />
        </FilterField>
        <FilterField label="액터 유형">
          <DsSelect
            onChange={(event) =>
              onUpdateFilter('actorType', event.target.value)
            }
            value={filters.actorType ?? ''}
          >
            <option value="">전체</option>
            <option value="STAFF">직원</option>
            <option value="CUSTOMER">고객</option>
            <option value="INTEGRATION_CLIENT">연동 클라이언트</option>
            <option value="TRIGGER">트리거</option>
            <option value="AUTOMATION">자동화</option>
            <option value="SYSTEM">시스템</option>
          </DsSelect>
        </FilterField>
        <FilterField label="액터 ID">
          <input
            onChange={(event) => onUpdateFilter('actorId', event.target.value)}
            value={filters.actorId ?? ''}
          />
        </FilterField>
        <FilterField label="티켓 번호">
          <input
            inputMode="numeric"
            onChange={(event) =>
              onUpdateFilter('ticketNumber', event.target.value)
            }
            value={filters.ticketNumber ?? ''}
          />
        </FilterField>
        <FilterField label="그룹 ID">
          <input
            onChange={(event) => onUpdateFilter('groupId', event.target.value)}
            value={filters.groupId ?? ''}
          />
        </FilterField>
        <FilterField label="필드">
          <input
            onChange={(event) => onUpdateFilter('field', event.target.value)}
            value={filters.field ?? ''}
          />
        </FilterField>
        <FilterField label="출처">
          <input
            onChange={(event) => onUpdateFilter('source', event.target.value)}
            value={filters.source ?? ''}
          />
        </FilterField>
        <FilterField label="결과">
          <DsSelect
            onChange={(event) => onUpdateFilter('outcome', event.target.value)}
            value={filters.outcome ?? ''}
          >
            <option value="">전체</option>
            {(Object.keys(OUTCOME_LABELS) as AuditOutcome[]).map((outcome) => (
              <option key={outcome} value={outcome}>
                {OUTCOME_LABELS[outcome]}
              </option>
            ))}
          </DsSelect>
        </FilterField>
        <FilterField label="요청 ID">
          <input
            onChange={(event) =>
              onUpdateFilter('requestId', event.target.value)
            }
            value={filters.requestId ?? ''}
          />
        </FilterField>
        <FilterField label="상관관계 ID">
          <input
            onChange={(event) =>
              onUpdateFilter('correlationId', event.target.value)
            }
            value={filters.correlationId ?? ''}
          />
        </FilterField>
        <FilterField label="검색 지문">
          <input
            onChange={(event) =>
              onUpdateFilter('searchFingerprint', event.target.value)
            }
            value={filters.searchFingerprint ?? ''}
          />
        </FilterField>
        {hasActiveFilters ? (
          <DsButton onClick={onClearFilters} tone="ghost">
            필터 지우기
          </DsButton>
        ) : null}
      </section>

      {activities.status === 'ready' &&
      activities.projection.state !== 'CURRENT' ? (
        <Notification
          title={
            activities.projection.state === 'REBUILDING'
              ? '감사 조회 프로젝션이 재구축 중입니다. 결과가 일시적으로 지연될 수 있습니다.'
              : '감사 조회 프로젝션이 저하 상태입니다. 결과가 최신이 아닐 수 있습니다.'
          }
          tone="warning"
        />
      ) : null}

      {activities.status === 'loading' ? (
        <TableSkeleton label="감사 활동 불러오는 중" />
      ) : activities.status === 'error' ? (
        <ScreenState
          action={<DsButton onClick={onRetryActivities}>다시 시도</DsButton>}
          description={
            activities.requestId
              ? `요청 ID: ${activities.requestId}`
              : undefined
          }
          kind={activities.denied ? 'denied' : 'error'}
          title={
            activities.denied
              ? '감사 활동을 조회할 권한이 없습니다.'
              : '감사 활동을 불러오지 못했습니다.'
          }
        />
      ) : activities.items.length === 0 ? (
        <ScreenState
          action={
            hasActiveFilters ? (
              <DsButton onClick={onClearFilters}>필터 지우기</DsButton>
            ) : undefined
          }
          description={
            hasActiveFilters
              ? '다른 필터를 사용해 보세요.'
              : '조건에 맞는 감사 활동이 아직 없습니다.'
          }
          kind="empty"
          title="일치하는 감사 활동이 없습니다."
        />
      ) : (
        <>
          <AuditActivityTable
            items={activities.items}
            label="감사 탐색기"
            onOpenActivity={onOpenActivity}
          />
          <footer className="audit-explorer-pagination">
            <p>{activities.items.length}개 표시</p>
            {activities.nextCursor ? (
              <DsButton onClick={onNextPage}>다음 페이지</DsButton>
            ) : null}
          </footer>
        </>
      )}
    </main>
  )
}

function FilterField({
  children,
  label,
}: {
  children: ReactNode
  label: string
}) {
  return <DsPropertyField label={label}>{children}</DsPropertyField>
}

function toLocalInputValue(value: string | undefined) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const offset = date.getTimezoneOffset()
  return new Date(date.getTime() - offset * 60000).toISOString().slice(0, 16)
}

function toIsoInstant(localValue: string) {
  if (!localValue) return ''
  const date = new Date(localValue)
  return Number.isNaN(date.getTime()) ? '' : date.toISOString()
}
