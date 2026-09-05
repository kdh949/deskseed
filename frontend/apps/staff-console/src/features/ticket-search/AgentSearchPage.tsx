import { useQuery } from '@tanstack/react-query'
import { useRef, useState, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router'
import {
  ApiError,
  listTicketAssignmentOptions,
  searchAgentTickets,
} from '../../api/client'
import type {
  AgentTicketSearchFilters,
  AgentTicketSearchInput,
  AgentTicketSearchSort,
  AgentTicketStatus,
  AgentTicketSummary,
  FirstReplySlaState,
  TicketPriority,
} from '../../api/types'
import { createOpaqueUuid } from '../../api/uuid'
import {
  SeedButton,
  SeedDataTable,
  SeedFeedbackState,
  SeedFilterBar,
  SeedIcon,
  SeedSelectField,
  SeedSkeletonRows,
  SeedStatusBadge,
  SeedTextField,
  type SeedTableColumn,
} from '../../design-system/canonical'

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
const STATUS_LABELS: Record<AgentTicketStatus, string> = {
  NEW: '신규',
  OPEN: '처리 중',
  PENDING: '고객 답변 대기',
  ON_HOLD: '보류',
  SOLVED: '해결',
  CLOSED: '종료',
}
const PRIORITY_LABELS: Record<TicketPriority, string> = {
  LOW: '낮음',
  NORMAL: '보통',
  HIGH: '높음',
  URGENT: '긴급',
}

export function AgentSearchPage() {
  const navigate = useNavigate()
  const [queryText, setQueryText] = useState('')
  const [draftFilters, setDraftFilters] = useState<AgentTicketSearchFilters>({})
  const [sort, setSort] = useState<AgentTicketSearchSort>(
    'score:desc,ticketNumber:desc',
  )
  const [submitted, setSubmitted] = useState<AgentTicketSearchInput | null>(
    null,
  )
  const [cursorHistory, setCursorHistory] = useState<Array<string | null>>([
    null,
  ])
  const [cursorIndex, setCursorIndex] = useState(0)
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
  const updateFilter = <K extends keyof AgentTicketSearchFilters>(
    key: K,
    value: AgentTicketSearchFilters[K] | undefined,
  ) => setDraftFilters((current) => ({ ...current, [key]: value || undefined }))
  const resetPaging = () => {
    setCursorHistory([null])
    setCursorIndex(0)
  }
  const submitSearch = (event: FormEvent) => {
    event.preventDefault()
    const normalized = queryText.trim()
    if (!normalized) return
    interactionId.current = createOpaqueUuid()
    resetPaging()
    setSubmitted({ query: normalized, filters: draftFilters, sort, limit: 25 })
  }
  const nextPage = () => {
    const cursor = searchQuery.data?.nextCursor
    if (!cursor) return
    setCursorHistory((current) => [
      ...current.slice(0, cursorIndex + 1),
      cursor,
    ])
    setCursorIndex((current) => current + 1)
  }

  return (
    <section className="seed-route" aria-labelledby="agent-search-title">
      <header className="seed-route__header">
        <div>
          <h1 id="agent-search-title">티켓 검색</h1>
          <p>권한 범위 안의 서버 전체 티켓을 검색합니다.</p>
        </div>
      </header>
      <form className="seed-search-panel" onSubmit={submitSearch} role="search">
        <div className="seed-search-panel__query">
          <SeedTextField
            aria-label="서버 전체 티켓 검색어"
            autoComplete="off"
            hint="검색어는 URL, 일반 로그, analytics에 기록하지 않습니다."
            label="검색어"
            leadingIcon="search"
            maxLength={500}
            onChange={(event) => setQueryText(event.target.value)}
            placeholder="제목, 요청자, 티켓 내용"
            type="search"
            value={queryText}
          />
          <SeedButton
            aria-label="서버 전체 검색"
            disabled={!queryText.trim() || searchQuery.isFetching}
            type="submit"
            variant="primary"
          >
            <SeedIcon name="search" />
            {searchQuery.isFetching ? '검색 중…' : '검색'}
          </SeedButton>
        </div>
        <SeedFilterBar>
          <SearchFilter
            label="상태"
            value={draftFilters.status ?? ''}
            onChange={(value) =>
              updateFilter('status', value as AgentTicketStatus)
            }
          >
            <option value="">전체 상태</option>
            {STATUSES.map((status) => (
              <option key={status} value={status}>
                {STATUS_LABELS[status]}
              </option>
            ))}
          </SearchFilter>
          <SearchFilter
            label="우선순위"
            value={draftFilters.priority ?? ''}
            onChange={(value) =>
              updateFilter('priority', value as TicketPriority)
            }
          >
            <option value="">전체 우선순위</option>
            {PRIORITIES.map((priority) => (
              <option key={priority} value={priority}>
                {PRIORITY_LABELS[priority]}
              </option>
            ))}
          </SearchFilter>
          <SearchFilter
            label="그룹"
            value={draftFilters.groupId ?? ''}
            onChange={(value) => updateFilter('groupId', value)}
          >
            <option value="">전체 그룹</option>
            {assignmentQuery.data?.groups.map((group) => (
              <option key={group.id} value={group.id}>
                {group.name}
              </option>
            ))}
          </SearchFilter>
          <SearchFilter
            label="담당자"
            value={draftFilters.assigneeId ?? ''}
            onChange={(value) => updateFilter('assigneeId', value)}
          >
            <option value="">전체 담당자</option>
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
            value={draftFilters.slaState ?? ''}
            onChange={(value) =>
              updateFilter('slaState', value as FirstReplySlaState)
            }
          >
            <option value="">전체 SLA</option>
            {SLA_STATES.map((state) => (
              <option key={state} value={state}>
                {state}
              </option>
            ))}
          </SearchFilter>
          <SearchFilter
            label="정렬"
            value={sort}
            onChange={(value) => {
              const next = value as AgentTicketSearchSort
              setSort(next)
              if (submitted) {
                interactionId.current = createOpaqueUuid()
                resetPaging()
                setSubmitted((current) =>
                  current ? { ...current, sort: next, cursor: null } : current,
                )
              }
            }}
          >
            <option value="score:desc,ticketNumber:desc">관련도 높은 순</option>
            <option value="updatedAt:desc,ticketNumber:desc">
              최근 업데이트 순
            </option>
          </SearchFilter>
        </SeedFilterBar>
      </form>
      <div className="seed-route__content">
        {!submitted ? (
          <SeedFeedbackState
            kind="empty"
            title="검색을 시작해 보세요"
            description="검색어와 필요한 필터를 입력하면 권한 범위 내 결과가 표시됩니다."
          />
        ) : searchQuery.isPending ? (
          <SeedSkeletonRows label="서버 전체 검색 결과 불러오는 중" rows={7} />
        ) : searchQuery.isError ? (
          <SearchError
            error={searchQuery.error}
            onRetry={() => searchQuery.refetch()}
          />
        ) : !items.length ? (
          <SeedFeedbackState
            kind="empty"
            title="일치하는 티켓이 없습니다"
            description="검색어나 필터 조건을 바꿔 다시 검색해 보세요."
          />
        ) : (
          <section className="seed-results" aria-label="서버 전체 검색 결과">
            <header>
              <div>
                <h2>검색 결과</h2>
                <p>
                  정확한 전체 결과{' '}
                  {searchQuery.data.resultCount.toLocaleString('ko-KR')}개
                </p>
              </div>
            </header>
            <SeedDataTable
              ariaLabel="서버 전체 검색 결과"
              columns={searchColumns(searchQuery.data.searchEventId)}
              onActivate={(ticket) =>
                navigate(`/agent/tickets/${ticket.ticketNumber}`, {
                  state: {
                    originSearchEventId: searchQuery.data.searchEventId,
                  },
                })
              }
              rowKey={(ticket) => ticket.ticketNumber}
              rows={items}
            />
            <footer className="seed-pagination">
              <span>현재 페이지 {items.length}개</span>
              <div>
                <SeedButton
                  aria-label="이전 페이지"
                  disabled={cursorIndex === 0}
                  onClick={() =>
                    setCursorIndex((current) => Math.max(0, current - 1))
                  }
                >
                  이전
                </SeedButton>
                <SeedButton
                  aria-label="다음 페이지"
                  disabled={!searchQuery.data.nextCursor}
                  onClick={nextPage}
                >
                  다음
                </SeedButton>
              </div>
            </footer>
          </section>
        )}
      </div>
    </section>
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
    <SeedSelectField
      aria-label={`${label} 검색 필터`}
      label={label}
      onChange={(event) => onChange(event.target.value)}
      value={value}
    >
      {children}
    </SeedSelectField>
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
    <SeedFeedbackState
      action={<SeedButton onClick={onRetry}>다시 시도</SeedButton>}
      description={
        error instanceof ApiError && error.requestId
          ? `요청 ID: ${error.requestId}`
          : undefined
      }
      kind={denied ? 'denied' : 'error'}
      title={
        denied ? '검색 권한이 없습니다' : '티켓 검색을 완료하지 못했습니다'
      }
    />
  )
}

const searchColumns = (
  originSearchEventId: string,
): SeedTableColumn<AgentTicketSummary>[] => [
  {
    id: 'number',
    label: '티켓',
    width: '6rem',
    render: (ticket) => (
      <Link
        aria-label={`티켓 #${ticket.ticketNumber} ${ticket.subject}`}
        onClick={(event) => event.stopPropagation()}
        state={{ originSearchEventId }}
        to={`/agent/tickets/${ticket.ticketNumber}`}
      >
        <strong>#{ticket.ticketNumber}</strong>
      </Link>
    ),
  },
  {
    id: 'subject',
    label: '제목',
    width: '36%',
    render: (ticket) => (
      <span className="seed-table__subject">
        {ticket.subject}
        <small>{ticket.requester.displayName}</small>
      </span>
    ),
  },
  {
    id: 'status',
    label: '상태',
    render: (ticket) => (
      <SeedStatusBadge tone={statusTone(ticket.status)}>
        {STATUS_LABELS[ticket.status]}
      </SeedStatusBadge>
    ),
  },
  {
    id: 'priority',
    label: '우선순위',
    render: (ticket) => PRIORITY_LABELS[ticket.priority],
  },
  {
    id: 'group',
    label: '그룹',
    render: (ticket) => ticket.group?.name ?? '미지정',
  },
  {
    id: 'assignee',
    label: '담당자',
    render: (ticket) => ticket.assignee?.displayName ?? '미배정',
  },
  {
    id: 'sla',
    label: 'SLA',
    render: (ticket) =>
      ticket.sla ? (
        <span aria-label={`최초 답변 SLA ${slaLabel(ticket.sla.state)}`}>
          {slaLabel(ticket.sla.state)}
        </span>
      ) : (
        '정책 없음'
      ),
  },
  {
    id: 'updated',
    label: '업데이트',
    render: (ticket) =>
      new Intl.DateTimeFormat('ko-KR', {
        dateStyle: 'short',
        timeStyle: 'short',
      }).format(new Date(ticket.updatedAt)),
  },
]

function statusTone(
  status: AgentTicketStatus,
): 'neutral' | 'info' | 'positive' | 'warning' | 'danger' {
  if (status === 'SOLVED' || status === 'CLOSED') return 'positive'
  if (status === 'PENDING' || status === 'ON_HOLD') return 'warning'
  if (status === 'NEW') return 'info'
  return 'neutral'
}

function slaLabel(state: FirstReplySlaState) {
  return {
    ACTIVE: '진행 중',
    AT_RISK: '위험',
    PAUSED: '일시 정지',
    ACHIEVED: '달성',
    BREACHED: '위반',
    CANCELLED: '취소',
    NO_POLICY: '정책 없음',
  }[state]
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
