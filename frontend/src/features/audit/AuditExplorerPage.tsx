import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import {
  ApiError,
  listAuditActivities,
  rebuildAuditProjection,
} from '../../api/client'
import type {
  AuditActivity,
  AuditActivityFilters,
  AuditLedgerType,
  AuditOutcome,
} from '../../api/types'
import {
  Notification,
  ScreenState,
  TableSkeleton,
} from '../../shared/ui/system'
import { useStaffSession } from '../staff-auth/StaffSessionContext'
import { AuditDetailDrawer } from './AuditDetailDrawer'
import { AuditExportDialog } from './AuditExportDialog'
import { createAuditInteractionId } from './auditInteraction'

const LEDGER_TABS: Array<[AuditLedgerType | '', string]> = [
  ['', '전체 활동'],
  ['TICKET_CHANGE', 'Ticket changes'],
  ['ACCESS_SEARCH', 'Access & searches'],
  ['ADMIN_SECURITY', 'Admin & security'],
]

export function AuditExplorerPage() {
  const session = useStaffSession()
  const staffId = session.staff?.id
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const [exportOpen, setExportOpen] = useState(false)
  const [refreshSequence, setRefreshSequence] = useState(0)
  const filters = filtersFrom(searchParams)
  const filterKey = filterKeyFrom(searchParams)
  const [pagination, setPagination] = useState(() =>
    initialPagination(filterKey),
  )
  const activePagination =
    pagination.filterKey === filterKey
      ? pagination
      : initialPagination(filterKey)
  const { cursor, cursorHistory } = activePagination
  const listInteractionId = useMemo(
    () => createAuditInteractionId(),
    [filterKey, refreshSequence, staffId],
  )
  const query = useQuery({
    queryKey: [
      'audit-activities',
      staffId,
      listInteractionId,
      filterKey,
      cursor,
    ],
    queryFn: () =>
      listAuditActivities({ ...filters, limit: 50 }, cursor, listInteractionId),
    enabled: session.status === 'authenticated' && staffId !== undefined,
  })
  const rebuild = useMutation({
    mutationFn: () => rebuildAuditProjection(createAuditInteractionId()),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['audit-activities'] })
    },
  })
  const selectedActivity = searchParams.get('activity')

  const updateFilter = (key: string, value: string) => {
    const next = new URLSearchParams(searchParams)
    if (value) next.set(key, value)
    else next.delete(key)
    next.delete('activity')
    setPagination(initialPagination(filterKeyFrom(next)))
    setSearchParams(next)
  }
  const selectActivity = (activityId: string | null) => {
    const next = new URLSearchParams(searchParams)
    if (activityId) next.set('activity', activityId)
    else next.delete('activity')
    setSearchParams(next, { replace: true })
  }
  const refresh = () => {
    setRefreshSequence((current) => current + 1)
  }

  return (
    <div className="audit-explorer-page" aria-labelledby="audit-explorer-title">
      <header className="audit-title-row">
        <div>
          <p className="agent-page-eyebrow">
            UNIFIED AUDIT EXPLORER · READ ONLY
          </p>
          <h1 id="audit-explorer-title">활동 조사</h1>
          <p>
            Ticket Change, Access/Search, Admin/Security canonical 원장을 한
            흐름에서 조사합니다.
          </p>
        </div>
        <div className="audit-title-actions">
          <button className="compact-button" type="button" onClick={refresh}>
            새 interaction으로 새로고침
          </button>
          {session.staff?.capabilities.includes('audit:projection:rebuild') ? (
            <button
              className="compact-button"
              type="button"
              aria-busy={rebuild.isPending}
              disabled={rebuild.isPending}
              onClick={() => rebuild.mutate()}
            >
              {rebuild.isPending ? '재생성 중…' : 'Projection 재생성'}
            </button>
          ) : null}
          {session.staff?.capabilities.includes('audit:export') ? (
            <button
              className="button primary small"
              type="button"
              onClick={() => setExportOpen(true)}
            >
              Export 요청
            </button>
          ) : null}
        </div>
      </header>

      {rebuild.isError ? (
        <Notification tone="danger" title="Projection을 재생성하지 못했습니다.">
          <p>{rebuild.error.message}</p>
        </Notification>
      ) : null}
      {rebuild.data ? (
        <Notification
          tone="success"
          title="Projection 재생성이 완료되었습니다."
        >
          <p>
            {rebuild.data.totalCount.toLocaleString()}건 · Ticket{' '}
            {rebuild.data.ticketChangeCount.toLocaleString()} · Access{' '}
            {rebuild.data.accessSearchCount.toLocaleString()} · Admin{' '}
            {rebuild.data.adminSecurityCount.toLocaleString()}
          </p>
        </Notification>
      ) : null}

      <nav className="audit-ledger-tabs" aria-label="감사 원장 필터">
        {LEDGER_TABS.map(([value, label]) => (
          <button
            key={value || 'all'}
            type="button"
            aria-pressed={(filters.ledger ?? '') === value}
            onClick={() => updateFilter('ledger', value)}
          >
            {label}
          </button>
        ))}
      </nav>

      <AuditFilters
        params={searchParams}
        update={updateFilter}
        clear={() => {
          setPagination(initialPagination(''))
          setSearchParams(new URLSearchParams())
        }}
      />

      {query.data?.projection.state !== 'CURRENT' ? (
        <Notification
          tone="warning"
          title={`Projection 상태: ${query.data?.projection.state}`}
        >
          <p>
            Canonical 원장은 유지됩니다. 결과가 stale할 수 있으므로 재생성
            상태를 확인하세요.
          </p>
        </Notification>
      ) : null}

      <section className="audit-results" aria-labelledby="audit-results-title">
        <header>
          <div>
            <h2 id="audit-results-title">조사 결과</h2>
            <span>
              {query.data
                ? `${query.data.projection.projectedCount.toLocaleString()} projected rows · snapshot ${formatTime(query.data.snapshotAt)}`
                : 'Snapshot 준비 중'}
            </span>
          </div>
          {query.isFetching && !query.isPending ? (
            <span role="status">최신 결과 확인 중</span>
          ) : null}
        </header>
        {query.isPending ? (
          <TableSkeleton label="감사 활동 불러오는 중" />
        ) : null}
        {query.isError ? (
          <AuditListError error={query.error} retry={() => query.refetch()} />
        ) : null}
        {query.data?.items.length === 0 ? (
          <ScreenState
            kind="empty"
            title="조건에 맞는 감사 활동이 없습니다."
            description="기간이나 필터를 조정해 보세요."
            className="queue-state"
          />
        ) : null}
        {query.data?.items.length ? (
          <AuditActivityTable
            items={query.data.items}
            selectedId={selectedActivity}
            onSelect={(activityId) => selectActivity(activityId)}
          />
        ) : null}
        {query.data && (cursorHistory.length || query.data.nextCursor) ? (
          <footer className="audit-pagination">
            <button
              className="compact-button"
              type="button"
              disabled={cursorHistory.length === 0}
              onClick={() => {
                const previous = cursorHistory.at(-1) ?? null
                setPagination({
                  filterKey,
                  cursor: previous,
                  cursorHistory: cursorHistory.slice(0, -1),
                })
              }}
            >
              이전 페이지
            </button>
            <button
              className="compact-button"
              type="button"
              disabled={!query.data.nextCursor}
              onClick={() => {
                setPagination({
                  filterKey,
                  cursor: query.data.nextCursor,
                  cursorHistory: [...cursorHistory, cursor],
                })
              }}
            >
              다음 페이지
            </button>
          </footer>
        ) : null}
      </section>

      {selectedActivity ? (
        <AuditDetailDrawer
          key={selectedActivity}
          activityId={selectedActivity}
          onClose={() => selectActivity(null)}
          onSelectActivity={selectActivity}
        />
      ) : null}
      {exportOpen ? (
        <AuditExportDialog
          filters={filters}
          onClose={() => setExportOpen(false)}
        />
      ) : null}
    </div>
  )
}

function AuditFilters({
  params,
  update,
  clear,
}: {
  params: URLSearchParams
  update: (key: string, value: string) => void
  clear: () => void
}) {
  return (
    <section className="audit-filter-panel" aria-label="감사 활동 필터">
      <div className="audit-filter-primary">
        <FilterInput
          label="시작일"
          type="date"
          value={params.get('from') ?? ''}
          onChange={(value) => update('from', value)}
        />
        <FilterInput
          label="종료일"
          type="date"
          value={params.get('to') ?? ''}
          onChange={(value) => update('to', value)}
        />
        <FilterInput
          label="활동"
          value={params.get('action') ?? ''}
          placeholder="예: SEARCH_EXECUTED"
          onChange={(value) => update('action', value)}
        />
        <FilterInput
          label="행위자 ID"
          value={params.get('actorId') ?? ''}
          placeholder="UUID"
          onChange={(value) => update('actorId', value)}
        />
        <FilterInput
          label="티켓"
          type="number"
          value={params.get('ticketNumber') ?? ''}
          placeholder="1042"
          onChange={(value) => update('ticketNumber', value)}
        />
        <label>
          <span>결과</span>
          <select
            value={params.get('outcome') ?? ''}
            onChange={(event) => update('outcome', event.target.value)}
          >
            <option value="">전체</option>
            <option value="SUCCEEDED">SUCCEEDED</option>
            <option value="DENIED">DENIED</option>
            <option value="FAILED">FAILED</option>
          </select>
        </label>
        <button className="compact-button" type="button" onClick={clear}>
          필터 초기화
        </button>
      </div>
      <details className="audit-filter-advanced">
        <summary>고급 식별자 필터</summary>
        <div>
          <label>
            <span>행위자 유형</span>
            <select
              value={params.get('actorType') ?? ''}
              onChange={(event) => update('actorType', event.target.value)}
            >
              <option value="">전체</option>
              {[
                'CUSTOMER',
                'STAFF',
                'INTEGRATION_CLIENT',
                'TRIGGER',
                'AUTOMATION',
                'SYSTEM',
              ].map((type) => (
                <option key={type} value={type}>
                  {type}
                </option>
              ))}
            </select>
          </label>
          <FilterInput
            label="그룹 ID"
            value={params.get('groupId') ?? ''}
            placeholder="UUID"
            onChange={(value) => update('groupId', value)}
          />
          <FilterInput
            label="필드"
            value={params.get('field') ?? ''}
            placeholder="status"
            onChange={(value) => update('field', value)}
          />
          <label>
            <span>소스</span>
            <select
              value={params.get('source') ?? ''}
              onChange={(event) => update('source', event.target.value)}
            >
              <option value="">전체</option>
              {[
                'CUSTOMER_PORTAL',
                'AGENT_UI',
                'ADMIN_UI',
                'PLATFORM_API',
                'TRIGGER',
                'AUTOMATION',
                'SYSTEM_JOB',
              ].map((source) => (
                <option key={source} value={source}>
                  {source}
                </option>
              ))}
            </select>
          </label>
          <FilterInput
            label="요청 ID"
            value={params.get('requestId') ?? ''}
            onChange={(value) => update('requestId', value)}
          />
          <FilterInput
            label="상관 ID"
            value={params.get('correlationId') ?? ''}
            onChange={(value) => update('correlationId', value)}
          />
          <FilterInput
            label="검색 fingerprint"
            value={params.get('searchFingerprint') ?? ''}
            onChange={(value) => update('searchFingerprint', value)}
          />
        </div>
      </details>
    </section>
  )
}

function FilterInput({
  label,
  value,
  onChange,
  type = 'text',
  placeholder,
}: {
  label: string
  value: string
  onChange: (value: string) => void
  type?: 'text' | 'date' | 'number'
  placeholder?: string
}) {
  return (
    <label>
      <span>{label}</span>
      <input
        type={type}
        min={type === 'number' ? 1 : undefined}
        value={value}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  )
}

function AuditActivityTable({
  items,
  selectedId,
  onSelect,
}: {
  items: AuditActivity[]
  selectedId: string | null
  onSelect: (id: string) => void
}) {
  return (
    <div className="audit-table-scroll">
      <table className="audit-table">
        <caption className="sr-only">통합 감사 활동</caption>
        <thead>
          <tr>
            <th>시각</th>
            <th>원장</th>
            <th>행위자</th>
            <th>활동</th>
            <th>대상</th>
            <th>결과</th>
            <th>요청 / 상관</th>
          </tr>
        </thead>
        <tbody>
          {items.map((activity) => (
            <tr
              key={activity.id}
              className={selectedId === activity.id ? 'is-selected' : undefined}
            >
              <td>
                <time dateTime={activity.occurredAt}>
                  {formatTime(activity.occurredAt)}
                </time>
              </td>
              <td>
                <span
                  className={`audit-ledger ledger-${activity.ledger.toLowerCase()}`}
                >
                  {shortLedger(activity.ledger)}
                </span>
              </td>
              <td>
                <strong>{activity.actor.displayName}</strong>
                <small>{activity.actor.type}</small>
              </td>
              <td>
                <button
                  className="audit-activity-button"
                  type="button"
                  onClick={() => onSelect(activity.id)}
                >
                  {activity.action}
                </button>
                <small>{activity.source}</small>
              </td>
              <td>
                {activity.ticketNumber
                  ? `#${activity.ticketNumber}`
                  : (activity.resourceType ?? '—')}
                {activity.field ? <small>field: {activity.field}</small> : null}
              </td>
              <td>
                <span
                  className={`audit-outcome outcome-${activity.outcome.toLowerCase()}`}
                >
                  {activity.outcome}
                </span>
              </td>
              <td className="audit-id-cell">
                <code>{compactId(activity.requestId)}</code>
                <small>{compactId(activity.correlationId)}</small>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function AuditListError({ error, retry }: { error: Error; retry: () => void }) {
  const apiError = error instanceof ApiError ? error : null
  return (
    <ScreenState
      kind={apiError?.status === 403 ? 'denied' : 'error'}
      title={
        apiError?.status === 403
          ? '감사 활동을 조회할 권한이 없습니다.'
          : '감사 활동을 불러오지 못했습니다.'
      }
      description="Canonical self-audit 저장 또는 projection 상태를 확인한 뒤 다시 시도하세요."
      requestId={apiError?.requestId}
      className="queue-state"
      action={
        <button className="compact-button" type="button" onClick={retry}>
          다시 시도
        </button>
      }
    />
  )
}

export function filtersFrom(params: URLSearchParams): AuditActivityFilters {
  const from = params.get('from')
  const to = params.get('to')
  const ticketNumber = Number(params.get('ticketNumber'))
  return {
    ...(from ? { from: localDayBoundary(from, 0) } : {}),
    ...(to ? { to: localDayBoundary(to, 1) } : {}),
    ...(params.get('ledger')
      ? { ledger: params.get('ledger') as AuditLedgerType }
      : {}),
    ...(params.get('action') ? { action: params.get('action')! } : {}),
    ...(params.get('actorType')
      ? { actorType: params.get('actorType') as AuditActivity['actor']['type'] }
      : {}),
    ...(params.get('actorId') ? { actorId: params.get('actorId')! } : {}),
    ...(Number.isSafeInteger(ticketNumber) && ticketNumber > 0
      ? { ticketNumber }
      : {}),
    ...(params.get('groupId') ? { groupId: params.get('groupId')! } : {}),
    ...(params.get('field') ? { field: params.get('field')! } : {}),
    ...(params.get('source') ? { source: params.get('source')! } : {}),
    ...(params.get('outcome')
      ? { outcome: params.get('outcome') as AuditOutcome }
      : {}),
    ...(params.get('requestId') ? { requestId: params.get('requestId')! } : {}),
    ...(params.get('correlationId')
      ? { correlationId: params.get('correlationId')! }
      : {}),
    ...(params.get('searchFingerprint')
      ? { searchFingerprint: params.get('searchFingerprint')! }
      : {}),
  }
}

function localDayBoundary(value: string, daysToAdd: number): string {
  const [yearText, monthText, dayText] = value.split('-')
  if (!yearText || !monthText || !dayText) {
    throw new Error('Invalid local calendar date')
  }
  const year = Number(yearText)
  const month = Number(monthText)
  const day = Number(dayText)
  return new Date(year, month - 1, day + daysToAdd).toISOString()
}

function initialPagination(filterKey: string) {
  return {
    filterKey,
    cursor: null as string | null,
    cursorHistory: [] as Array<string | null>,
  }
}

function filterKeyFrom(params: URLSearchParams): string {
  const normalized = new URLSearchParams(params)
  normalized.delete('activity')
  normalized.sort()
  return normalized.toString()
}

function shortLedger(ledger: AuditLedgerType) {
  if (ledger === 'TICKET_CHANGE') return 'CHANGE'
  if (ledger === 'ACCESS_SEARCH') return 'ACCESS'
  return 'SECURITY'
}

function compactId(value: string | null) {
  if (!value) return '—'
  return value.length > 20 ? `${value.slice(0, 8)}…${value.slice(-6)}` : value
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(value))
}
