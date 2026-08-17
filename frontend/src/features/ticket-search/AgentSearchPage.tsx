import { useQuery } from '@tanstack/react-query'
import {
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type ReactNode,
} from 'react'
import { useNavigate } from 'react-router'
import {
  ApiError,
  listTicketAssignmentOptions,
  searchAgentTickets,
} from '../../api/client'
import type {
  AgentTicketSearchFilters,
  AgentTicketSearchInput,
  AgentTicketStatus,
  AgentTicketSummary,
  FirstReplySlaState,
  TicketPriority,
} from '../../api/types'
import { createOpaqueUuid } from '../../api/uuid'
import {
  DeskseedIcon,
  DsButton,
  DsSelect,
  QueueTicketTable,
  ScreenState,
  TableSkeleton,
  type QueueTicketTableItem,
} from '../../design-system'
import { BulkTicketActionPanel } from '../ticket-views/BulkTicketActionPanel'

const STATUSES: AgentTicketStatus[] = [
  'NEW',
  'OPEN',
  'PENDING',
  'ON_HOLD',
  'SOLVED',
  'CLOSED',
]
const PRIORITIES: TicketPriority[] = ['LOW', 'NORMAL', 'HIGH', 'URGENT']
const SLA_STATES: FirstReplySlaState[] = [
  'ACTIVE',
  'AT_RISK',
  'PAUSED',
  'ACHIEVED',
  'BREACHED',
  'CANCELLED',
  'NO_POLICY',
]

export function AgentSearchPage() {
  const navigate = useNavigate()
  const [queryText, setQueryText] = useState('')
  const [filters, setFilters] = useState<AgentTicketSearchFilters>({})
  const [submitted, setSubmitted] = useState<AgentTicketSearchInput | null>(
    null,
  )
  const [cursorHistory, setCursorHistory] = useState<Array<string | null>>([
    null,
  ])
  const [cursorIndex, setCursorIndex] = useState(0)
  const [selectedTicketNumbers, setSelectedTicketNumbers] = useState(
    new Set<number>(),
  )
  const interactionId = useRef(createOpaqueUuid())

  const assignmentQuery = useQuery({
    queryKey: ['agent-assignment-options'],
    queryFn: listTicketAssignmentOptions,
  })
  const searchQuery = useQuery({
    enabled: submitted !== null,
    queryKey: [
      'agent-search',
      submitted,
      cursorHistory[cursorIndex],
      interactionId.current,
    ],
    queryFn: () =>
      searchAgentTickets(
        { ...submitted!, cursor: cursorHistory[cursorIndex] ?? null },
        interactionId.current,
      ),
    retry: false,
  })

  const items = searchQuery.data?.items ?? []
  const selectedTickets = useMemo(
    () =>
      items.filter((ticket) => selectedTicketNumbers.has(ticket.ticketNumber)),
    [items, selectedTicketNumbers],
  )
  const resetPaging = () => {
    setCursorHistory([null])
    setCursorIndex(0)
    setSelectedTicketNumbers(new Set())
  }
  const updateFilter = <K extends keyof AgentTicketSearchFilters>(
    key: K,
    value: AgentTicketSearchFilters[K] | undefined,
  ) => {
    setFilters((current) => ({ ...current, [key]: value || undefined }))
    resetPaging()
  }
  const submitSearch = (event: FormEvent) => {
    event.preventDefault()
    const normalized = queryText.trim()
    if (!normalized) return
    interactionId.current = createOpaqueUuid()
    resetPaging()
    setSubmitted({
      query: normalized,
      filters,
      sort: 'score:desc,ticketNumber:desc',
      limit: 25,
    })
  }
  const nextPage = () => {
    const cursor = searchQuery.data?.nextCursor
    if (!cursor) return
    setCursorHistory((current) => [
      ...current.slice(0, cursorIndex + 1),
      cursor,
    ])
    setCursorIndex((current) => current + 1)
    setSelectedTicketNumbers(new Set())
  }

  return (
    <main
      className="agent-search-workspace"
      aria-labelledby="agent-search-title"
    >
      <header className="agent-search-header">
        <div>
          <h1 id="agent-search-title">티켓 전체 검색</h1>
          <p>
            현재 페이지 검색이 아니라 권한 범위 안의 서버 전체 티켓을
            검색합니다.
          </p>
        </div>
      </header>
      <form className="agent-search-form" onSubmit={submitSearch} role="search">
        <label className="agent-search-query">
          <span>검색어</span>
          <span className="agent-search-query__control">
            <DeskseedIcon name="search" />
            <input
              aria-label="서버 전체 티켓 검색어"
              autoComplete="off"
              maxLength={500}
              onChange={(event) => setQueryText(event.target.value)}
              placeholder="제목, 요청자, 티켓 내용"
              type="search"
              value={queryText}
            />
          </span>
          <small>검색어는 URL, 일반 로그, analytics에 기록하지 않습니다.</small>
        </label>
        <SearchFilter
          label="상태"
          value={filters.status ?? ''}
          onChange={(value) =>
            updateFilter('status', value as AgentTicketStatus)
          }
        >
          <option value="">전체</option>
          {STATUSES.map((status) => (
            <option key={status} value={status}>
              {status}
            </option>
          ))}
        </SearchFilter>
        <SearchFilter
          label="우선순위"
          value={filters.priority ?? ''}
          onChange={(value) =>
            updateFilter('priority', value as TicketPriority)
          }
        >
          <option value="">전체</option>
          {PRIORITIES.map((priority) => (
            <option key={priority} value={priority}>
              {priority}
            </option>
          ))}
        </SearchFilter>
        <SearchFilter
          label="그룹"
          value={filters.groupId ?? ''}
          onChange={(value) => updateFilter('groupId', value)}
        >
          <option value="">전체</option>
          {assignmentQuery.data?.groups.map((group) => (
            <option key={group.id} value={group.id}>
              {group.name}
            </option>
          ))}
        </SearchFilter>
        <SearchFilter
          label="담당자"
          value={filters.assigneeId ?? ''}
          onChange={(value) => updateFilter('assigneeId', value)}
        >
          <option value="">전체</option>
          <option value="me">나</option>
          <option value="unassigned">미배정</option>
          {uniqueMembers(assignmentQuery.data?.groups ?? []).map((member) => (
            <option key={member.id} value={member.id}>
              {member.displayName}
            </option>
          ))}
        </SearchFilter>
        <SearchFilter
          label="최초 답변 SLA"
          value={filters.slaState ?? ''}
          onChange={(value) =>
            updateFilter('slaState', value as FirstReplySlaState)
          }
        >
          <option value="">전체</option>
          {SLA_STATES.map((state) => (
            <option key={state} value={state}>
              {state}
            </option>
          ))}
        </SearchFilter>
        <DsButton
          disabled={!queryText.trim() || searchQuery.isFetching}
          tone="primary"
          type="submit"
        >
          {searchQuery.isFetching ? '검색 중…' : '서버 전체 검색'}
        </DsButton>
      </form>

      {!submitted ? (
        <ScreenState
          kind="empty"
          title="검색어와 필터를 입력하세요."
          description="원문 검색어는 화면 메모리와 POST 요청 본문에만 유지됩니다."
        />
      ) : searchQuery.isPending ? (
        <TableSkeleton label="서버 전체 검색 결과 불러오는 중" />
      ) : searchQuery.isError ? (
        <SearchError
          error={searchQuery.error}
          onRetry={() => searchQuery.refetch()}
        />
      ) : !items.length ? (
        <ScreenState
          kind="empty"
          title="일치하는 티켓이 없습니다."
          description="다른 검색어나 필터를 사용해 보세요."
        />
      ) : (
        <section
          aria-label="서버 전체 검색 결과"
          className="agent-search-results"
        >
          <header>
            <h2>검색 결과</h2>
            <p>
              정확한 전체 결과{' '}
              {searchQuery.data.resultCount.toLocaleString('ko-KR')}개
            </p>
          </header>
          <BulkTicketActionPanel
            onComplete={() => void searchQuery.refetch()}
            options={assignmentQuery.data ?? { groups: [] }}
            tickets={selectedTickets}
          />
          <QueueTicketTable
            items={items.map(toQueueItem)}
            label="서버 전체 검색 결과"
            onOpenTicket={(ticketNumber) =>
              navigate(`/agent/tickets/${ticketNumber}`, {
                state: { originSearchEventId: searchQuery.data.searchEventId },
              })
            }
            onSelectAll={() =>
              setSelectedTicketNumbers((current) => {
                const allSelected = items.every((ticket) =>
                  current.has(ticket.ticketNumber),
                )
                return allSelected
                  ? new Set()
                  : new Set(
                      items.slice(0, 100).map((ticket) => ticket.ticketNumber),
                    )
              })
            }
            onSelectionChange={(ticketNumber) =>
              setSelectedTicketNumbers((current) => {
                const next = new Set(current)
                if (next.has(ticketNumber)) next.delete(ticketNumber)
                else if (next.size < 100) next.add(ticketNumber)
                return next
              })
            }
            selectedTicketNumbers={selectedTicketNumbers}
            ticketHref={(ticketNumber) => `/agent/tickets/${ticketNumber}`}
          />
          <footer className="agent-queue-pagination">
            <p>현재 페이지 {items.length}개</p>
            <div>
              <DsButton
                disabled={cursorIndex === 0}
                onClick={() => {
                  setCursorIndex((current) => Math.max(0, current - 1))
                  setSelectedTicketNumbers(new Set())
                }}
              >
                이전 페이지
              </DsButton>
              <DsButton
                disabled={!searchQuery.data.nextCursor}
                onClick={nextPage}
              >
                다음 페이지
              </DsButton>
            </div>
          </footer>
        </section>
      )}
    </main>
  )
}

function SearchFilter({
  children,
  label,
  onChange,
  value,
}: {
  children: ReactNode
  label: string
  onChange: (value: string) => void
  value: string
}) {
  return (
    <label>
      <span>{label}</span>
      <DsSelect
        aria-label={`${label} 검색 필터`}
        onChange={(event) => onChange(event.target.value)}
        value={value}
      >
        {children}
      </DsSelect>
    </label>
  )
}

function SearchError({
  error,
  onRetry,
}: {
  error: unknown
  onRetry: () => void
}) {
  const denied = error instanceof ApiError && error.status === 403
  return (
    <ScreenState
      action={<DsButton onClick={onRetry}>다시 시도</DsButton>}
      kind={denied ? 'denied' : 'error'}
      requestId={error instanceof ApiError ? error.requestId : undefined}
      title={
        denied ? '검색 권한이 없습니다.' : '티켓 검색을 완료하지 못했습니다.'
      }
    />
  )
}

function uniqueMembers(
  groups: Array<{ members: Array<{ id: string; displayName: string }> }>,
) {
  return [
    ...new Map(
      groups
        .flatMap((group) => group.members)
        .map((member) => [member.id, member]),
    ).values(),
  ]
}

function toQueueItem(ticket: AgentTicketSummary): QueueTicketTableItem {
  return {
    ticketNumber: ticket.ticketNumber,
    subject: ticket.subject,
    status: ticket.status,
    priority: ticket.priority,
    requester: ticket.requester.displayName,
    group: ticket.group?.name ?? '미지정',
    assignee: ticket.assignee?.displayName ?? '미배정',
    updatedAt: ticket.updatedAt,
    updatedLabel: new Intl.DateTimeFormat('ko-KR', {
      dateStyle: 'short',
      timeStyle: 'short',
    }).format(new Date(ticket.updatedAt)),
    isChild: ticket.isChild,
    sla: ticket.sla,
  }
}
