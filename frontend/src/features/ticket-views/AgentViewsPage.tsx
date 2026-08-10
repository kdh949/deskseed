import { useQuery } from '@tanstack/react-query'
import { Link, useParams, useSearchParams } from 'react-router'
import { ApiError, listTicketsInView } from '../../api/client'
import type {
  AgentTicketFilters,
  TicketPriority,
  TicketStatus,
} from '../../api/types'
import { StatusBadge } from '../../components/StatusBadge'

const VIEW_NAMES: Record<string, string> = {
  'my-open': '내 open',
  'unassigned-my-groups': '내 그룹 미배정',
  pending: 'Pending',
  'recently-solved': '최근 solved',
  'my-child-tasks': '내 child tasks',
}

const STATUSES: TicketStatus[] = ['NEW', 'OPEN', 'PENDING', 'SOLVED']
const PRIORITIES: TicketPriority[] = ['LOW', 'NORMAL', 'HIGH', 'URGENT']

export function AgentViewsPage() {
  const { viewKey = 'my-open' } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = filtersFrom(searchParams)
  const query = useQuery({
    queryKey: ['agent-view', viewKey, filters],
    queryFn: () => listTicketsInView(viewKey, filters),
  })
  const viewName = VIEW_NAMES[viewKey] ?? viewKey

  const updateFilter = (key: string, value: string) => {
    const next = new URLSearchParams(searchParams)
    if (value) next.set(key, value)
    else next.delete(key)
    if (key !== 'cursor') next.delete('cursor')
    setSearchParams(next)
  }

  const groups = uniqueBy(
    query.data?.items.flatMap((ticket) =>
      ticket.group ? [ticket.group] : [],
    ) ?? [],
    (group) => group.id,
  )
  const assignees = uniqueBy(
    query.data?.items.flatMap((ticket) =>
      ticket.assignee ? [ticket.assignee] : [],
    ) ?? [],
    (assignee) => assignee.id,
  )

  return (
    <main
      className="agent-page agent-views-page"
      aria-labelledby="agent-view-title"
    >
      <header className="agent-page-header">
        <div>
          <p className="agent-page-eyebrow">VIEWS · ALL_TICKETS</p>
          <h1 id="agent-view-title">{viewName}</h1>
          <p>모든 활성 상담사가 읽을 수 있는 staff-visible 티켓입니다.</p>
        </div>
        <button
          className="compact-button"
          type="button"
          onClick={() => query.refetch()}
        >
          새로고침
        </button>
      </header>

      <section
        className="ticket-filter-bar"
        aria-label={`${viewName} 임시 필터`}
      >
        <label>
          <span>상태</span>
          <select
            aria-label="상태 필터"
            value={filters.status ?? ''}
            onChange={(event) => updateFilter('status', event.target.value)}
          >
            <option value="">전체</option>
            {STATUSES.map((status) => (
              <option key={status} value={status}>
                {status}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span>우선순위</span>
          <select
            aria-label="우선순위 필터"
            value={filters.priority ?? ''}
            onChange={(event) => updateFilter('priority', event.target.value)}
          >
            <option value="">전체</option>
            {PRIORITIES.map((priority) => (
              <option key={priority} value={priority}>
                {priority}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span>그룹</span>
          <select
            aria-label="그룹 필터"
            value={filters.groupId ?? ''}
            onChange={(event) => updateFilter('groupId', event.target.value)}
          >
            <option value="">전체</option>
            {groups.map((group) => (
              <option key={group.id} value={group.id}>
                {group.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span>담당자</span>
          <select
            aria-label="담당자 필터"
            value={filters.assigneeId ?? ''}
            onChange={(event) => updateFilter('assigneeId', event.target.value)}
          >
            <option value="">전체</option>
            <option value="me">나</option>
            <option value="unassigned">미배정</option>
            {assignees.map((assignee) => (
              <option key={assignee.id} value={assignee.id}>
                {assignee.displayName}
              </option>
            ))}
          </select>
        </label>
        {query.isFetching && !query.isPending ? (
          <span className="queue-refresh-indicator" role="status">
            최신 목록 확인 중
          </span>
        ) : null}
      </section>

      {query.isPending ? <TicketTableSkeleton viewName={viewName} /> : null}
      {query.isError ? (
        <QueueError error={query.error} retry={() => query.refetch()} />
      ) : null}
      {query.data && query.data.items.length === 0 ? (
        <section className="queue-state" role="status">
          <h2>이 View에 표시할 티켓이 없습니다.</h2>
          <p>필터를 해제하거나 새로고침해 보세요.</p>
        </section>
      ) : null}
      {query.data && query.data.items.length > 0 ? (
        <div className="ticket-table-scroll">
          <table className="ticket-table" aria-label={`${viewName} 티켓`}>
            <thead>
              <tr>
                <th scope="col">상태</th>
                <th scope="col">번호</th>
                <th scope="col">제목</th>
                <th scope="col">요청자</th>
                <th scope="col">우선순위</th>
                <th scope="col">그룹</th>
                <th scope="col">담당자</th>
                <th scope="col">업데이트</th>
              </tr>
            </thead>
            <tbody>
              {query.data.items.map((ticket) => (
                <tr key={ticket.ticketNumber}>
                  <td>
                    <StatusBadge status={ticket.status} />
                  </td>
                  <td className="ticket-number">#{ticket.ticketNumber}</td>
                  <td className="ticket-subject-cell">
                    <Link
                      to={`/agent/tickets/${ticket.ticketNumber}`}
                      aria-label={`#${ticket.ticketNumber} ${ticket.subject} 열기`}
                    >
                      {ticket.subject}
                    </Link>
                    {ticket.isChild ? (
                      <span className="row-kind">Child task</span>
                    ) : null}
                  </td>
                  <td>{ticket.requester.displayName}</td>
                  <td>
                    <span
                      className={`priority-label priority-${ticket.priority.toLowerCase()}`}
                    >
                      {ticket.priority}
                    </span>
                  </td>
                  <td>{ticket.group?.name ?? '미배정'}</td>
                  <td>{ticket.assignee?.displayName ?? '미배정'}</td>
                  <td>
                    <time dateTime={ticket.updatedAt}>
                      {formatDate(ticket.updatedAt)}
                    </time>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {query.data?.nextCursor ? (
        <footer className="queue-pagination">
          <button
            className="compact-button"
            type="button"
            onClick={() => updateFilter('cursor', query.data.nextCursor ?? '')}
          >
            다음 페이지
          </button>
        </footer>
      ) : null}
    </main>
  )
}

function filtersFrom(searchParams: URLSearchParams): AgentTicketFilters {
  const status = searchParams.get('status') as TicketStatus | null
  const priority = searchParams.get('priority') as TicketPriority | null
  return {
    ...(status ? { status } : {}),
    ...(priority ? { priority } : {}),
    ...(searchParams.get('groupId')
      ? { groupId: searchParams.get('groupId')! }
      : {}),
    ...(searchParams.get('assigneeId')
      ? { assigneeId: searchParams.get('assigneeId')! }
      : {}),
    ...(searchParams.get('cursor')
      ? { cursor: searchParams.get('cursor')! }
      : {}),
    limit: 50,
  }
}

function TicketTableSkeleton({ viewName }: { viewName: string }) {
  return (
    <div
      className="ticket-table-skeleton"
      role="status"
      aria-label={`${viewName} 티켓 불러오는 중`}
      aria-busy="true"
    >
      <span />
      <span />
      <span />
      <span />
    </div>
  )
}

function QueueError({ error, retry }: { error: Error; retry: () => void }) {
  const requestId = error instanceof ApiError ? error.requestId : undefined
  return (
    <section className="queue-state queue-error" role="alert">
      <h2>티켓 목록을 불러오지 못했습니다.</h2>
      <p>
        {requestId ? `요청 ID: ${requestId}` : '잠시 후 다시 시도해 주세요.'}
      </p>
      <button className="compact-button" type="button" onClick={retry}>
        다시 시도
      </button>
    </section>
  )
}

function uniqueBy<T>(items: T[], key: (item: T) => string): T[] {
  const seen = new Set<string>()
  return items.filter((item) => {
    const value = key(item)
    if (seen.has(value)) return false
    seen.add(value)
    return true
  })
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
