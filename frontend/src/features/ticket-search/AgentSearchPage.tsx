import { useMutation } from '@tanstack/react-query'
import { type FormEvent, useState } from 'react'
import { ApiError, searchAgentTickets } from '../../api/client'
import type {
  AgentTicketSearchFilters,
  AgentTicketStatus,
  TicketPriority,
} from '../../api/types'
import { ScreenState, TableSkeleton, TicketTable } from '../../shared/ui/system'

const STATUSES: AgentTicketStatus[] = [
  'NEW',
  'OPEN',
  'PENDING',
  'ON_HOLD',
  'SOLVED',
  'CLOSED',
]
const PRIORITIES: TicketPriority[] = ['LOW', 'NORMAL', 'HIGH', 'URGENT']
const SEARCH_SORT = 'updatedAt:desc,ticketNumber:desc' as const
const TICKET_DATE_FORMATTER = new Intl.DateTimeFormat('ko-KR', {
  month: 'short',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
})

export function AgentSearchPage() {
  const [query, setQuery] = useState('')
  const [filters, setFilters] = useState<AgentTicketSearchFilters>({})
  const search = useMutation({
    mutationFn: () =>
      searchAgentTickets(
        { query: query.trim(), filters, sort: SEARCH_SORT, limit: 25 },
        createInteractionId(),
      ),
  })

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!query.trim() || query.length > 500 || search.isPending) return
    search.mutate()
  }

  return (
    <main
      className="agent-page agent-search-page"
      aria-labelledby="search-title"
    >
      <header className="agent-page-header">
        <div>
          <p className="agent-page-eyebrow">SEARCH · ALL_TICKETS</p>
          <h1 id="search-title">티켓 검색</h1>
          <p>
            권한이 있는 티켓만 검색하며, 검색과 결과 열람은 감사 기록에
            연결됩니다.
          </p>
        </div>
      </header>

      <form className="agent-search-form" role="search" onSubmit={submit}>
        <label className="agent-search-query">
          <span>검색어</span>
          <input
            type="search"
            aria-label="티켓 검색어"
            autoComplete="off"
            maxLength={500}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="티켓 번호, 제목, 요청자 또는 대화 내용"
          />
        </label>
        <label>
          <span>상태</span>
          <select
            aria-label="검색 상태 필터"
            value={filters.status ?? ''}
            onChange={(event) =>
              setFilters((current) => ({
                ...current,
                ...(event.target.value
                  ? { status: event.target.value as AgentTicketStatus }
                  : { status: undefined }),
              }))
            }
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
            aria-label="검색 우선순위 필터"
            value={filters.priority ?? ''}
            onChange={(event) =>
              setFilters((current) => ({
                ...current,
                ...(event.target.value
                  ? { priority: event.target.value as TicketPriority }
                  : { priority: undefined }),
              }))
            }
          >
            <option value="">전체</option>
            {PRIORITIES.map((priority) => (
              <option key={priority} value={priority}>
                {priority}
              </option>
            ))}
          </select>
        </label>
        <button
          className="compact-button agent-search-submit"
          type="submit"
          disabled={!query.trim() || query.length > 500 || search.isPending}
        >
          {search.isPending ? '검색 중' : '티켓 검색'}
        </button>
      </form>

      {search.isPending ? <TableSkeleton label="티켓 검색 중" /> : null}
      {search.isError ? <SearchError error={search.error} /> : null}
      {search.isSuccess && search.data.items.length === 0 ? (
        <ScreenState
          kind="empty"
          title="검색 결과가 없습니다."
          description="검색어나 필터를 바꾸어 다시 시도해 보세요."
          className="queue-state"
        />
      ) : null}
      {search.isSuccess && search.data.items.length > 0 ? (
        <section aria-labelledby="search-results-title">
          <div className="search-results-summary">
            <h2 id="search-results-title">
              검색 결과 {search.data.resultCount}개
            </h2>
            {search.data.resultCount > search.data.items.length ? (
              <span>상위 {search.data.items.length}개 표시</span>
            ) : null}
          </div>
          <TicketTable
            label="티켓 검색 결과"
            items={search.data.items.map((ticket) => ({
              ticketNumber: ticket.ticketNumber,
              subject: ticket.subject,
              status: ticket.status,
              priority: ticket.priority,
              requester: ticket.requester.displayName,
              group: ticket.group?.name ?? '미배정',
              assignee: ticket.assignee?.displayName ?? '미배정',
              updatedLabel: formatDate(ticket.updatedAt),
              isChild: ticket.isChild,
            }))}
            ticketHref={(ticketNumber) =>
              `/agent/tickets/${ticketNumber}?originSearchEventId=${search.data.searchEventId}`
            }
          />
        </section>
      ) : null}
    </main>
  )
}

function SearchError({ error }: { error: Error }) {
  const apiError = error instanceof ApiError ? error : undefined
  const isAuditUnavailable = apiError?.status === 503
  const isDenied = apiError?.status === 403
  return (
    <ScreenState
      kind={isDenied ? 'denied' : 'error'}
      title={
        isDenied
          ? '티켓 검색 권한이 없습니다.'
          : isAuditUnavailable
            ? '감사 기록을 안전하게 저장할 수 없어 검색 결과를 표시하지 않았습니다.'
            : '티켓 검색에 실패했습니다.'
      }
      description={
        isAuditUnavailable
          ? '잠시 후 다시 검색해 주세요.'
          : '검색 조건을 확인하고 다시 시도해 주세요.'
      }
      requestId={apiError?.requestId}
      className="queue-state queue-error"
    />
  )
}

function createInteractionId(): string {
  return globalThis.crypto.randomUUID()
}

function formatDate(value: string): string {
  return TICKET_DATE_FORMATTER.format(new Date(value))
}
