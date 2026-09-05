import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type FocusEvent as ReactFocusEvent,
  type MouseEvent as ReactMouseEvent,
  type ReactNode,
} from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import {
  ApiError,
  createAgentSavedView,
  deleteAgentSavedView,
  listAgentViews,
  listTicketAssignmentOptions,
  listTicketsInView,
  previewAgentSavedView,
  reorderAgentSavedViews,
  updateAgentSavedView,
} from '../../api/client'
import type {
  AgentTicketFilters,
  AgentTicketStatus,
  FirstReplySlaState,
  FirstReplySlaBadge,
  SavedAgentView,
  TicketPriority,
} from '../../api/types'
import {
  SeedButton,
  SeedFeedbackState,
  SeedFilterBar,
  SeedIcon,
  SeedQueueTicketTable,
  SeedSavedViewNavigation,
  SeedSelectField,
  SeedSkeletonRows,
  SeedStatusBadge,
  SeedTextField,
  type SeedQueueColumn,
  type SeedQueueTicket,
  type SeedSavedViewItem,
} from '../../design-system/canonical'
import {
  ViewConfigurationDrawer,
  toCreateSavedViewInput,
  type SavedViewEditorSave,
  type ViewEditor,
} from './ViewConfigurationDrawer'
import { createOpaqueUuid } from '../../api/uuid'
import { BulkTicketActionPanel } from './BulkTicketActionPanel'

const VIEW_NAVIGATION_COPY = {
  create: '새 보기 만들기',
  reorderTip: '보기를 드래그하여 순서를 변경할 수 있어요.',
}

const VIEW_PRESENTATION: Record<
  string,
  {
    name: string
  }
> = {
  'my-open': {
    name: '내 티켓',
  },
  'unassigned-my-groups': {
    name: '미배정 티켓',
  },
  pending: {
    name: '고객 답변 대기',
  },
  urgent: {
    name: '긴급 티켓',
  },
  'today-updated': {
    name: '오늘 업데이트된 티켓',
  },
  'recently-solved': {
    name: '최근 해결',
  },
  'customer-reply-pending': {
    name: '고객 응답 대기',
  },
  'created-by-me': {
    name: '내가 생성한 티켓',
  },
  following: {
    name: '내가 팔로우 중인 티켓',
  },
  drafts: {
    name: '임시 보관함',
  },
  'my-child-tasks': {
    name: '내부 협업',
  },
}

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
const SERVER_FILTER_KEYS = [
  'status',
  'priority',
  'groupId',
  'assigneeId',
  'slaState',
]

export function AgentViewsPage() {
  const { viewKey = 'my-open' } = useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const [currentPageSearch, setCurrentPageSearch] = useState('')
  const [filtersOpen, setFiltersOpen] = useState(() =>
    SERVER_FILTER_KEYS.some((key) => searchParams.has(key)),
  )
  const [selectedTicketNumbers, setSelectedTicketNumbers] = useState<
    Set<number>
  >(() => new Set())
  const [editor, setEditor] = useState<ViewEditor | null>(null)
  const [pendingPersonalOrder, setPendingPersonalOrder] = useState<
    string[] | null
  >(null)
  const [actionsOpen, setActionsOpen] = useState(false)
  const [cursorHistory, setCursorHistory] = useState<Array<string | null>>([
    null,
  ])
  const [cursorIndex, setCursorIndex] = useState(0)
  const selectionAnchor = useRef<number | null>(null)
  const editorTriggerRef = useRef<HTMLElement | null>(null)
  const filters = {
    ...filtersFrom(searchParams),
    ...(cursorHistory[cursorIndex]
      ? { cursor: cursorHistory[cursorIndex]! }
      : {}),
  }
  const viewQuery = useQuery({
    queryKey: ['agent-views'],
    queryFn: listAgentViews,
  })
  const assignmentQuery = useQuery({
    queryKey: ['agent-assignment-options'],
    queryFn: listTicketAssignmentOptions,
  })
  const query = useQuery({
    queryKey: ['agent-view', viewKey, filters],
    queryFn: () => listTicketsInView(viewKey, filters),
  })
  const serverViews = viewQuery.data ?? []
  const personalItems = useMemo(
    () => personalViewItems(serverViews, pendingPersonalOrder),
    [pendingPersonalOrder, serverViews],
  )
  const openCreateEditor = (event: ReactMouseEvent<HTMLButtonElement>) => {
    editorTriggerRef.current = event.currentTarget
    setPendingPersonalOrder(null)
    setEditor({ mode: 'create' })
  }
  const openEditEditor = (
    item: SeedSavedViewItem,
    event: ReactMouseEvent<HTMLButtonElement>,
  ) => {
    editorTriggerRef.current = event.currentTarget
    const view = serverViews.find((candidate) => candidate.key === item.key)
    if (view && view.scope !== 'SYSTEM') {
      setPendingPersonalOrder(null)
      setEditor({ mode: 'edit', view })
    }
  }
  const closeEditor = () => {
    setPendingPersonalOrder(null)
    setEditor(null)
  }
  const currentMutableView = serverViews.find(
    (view) => view.key === viewKey && view.scope !== 'SYSTEM',
  )
  const actionItems = [
    {
      id: 'create-ticket',
      label: '새 티켓 생성',
      onClick: () => navigate('/agent/tickets/new'),
    },
    ...(currentMutableView
      ? [
          {
            id: 'edit-view',
            label: '보기 설정',
            onClick: (event: ReactMouseEvent<HTMLButtonElement>) =>
              openEditEditor(toNavigationItem(currentMutableView), event),
          },
        ]
      : []),
    {
      id: 'create-view',
      label: VIEW_NAVIGATION_COPY.create,
      onClick: openCreateEditor,
    },
  ]
  const closeActionsWhenFocusLeaves = (
    event: ReactFocusEvent<HTMLDivElement>,
  ) => {
    if (!event.currentTarget.contains(event.relatedTarget)) {
      setActionsOpen(false)
    }
  }
  const sidebarSections = [
    {
      id: 'shared',
      label: '공유 보기',
      items: serverViews
        .filter((view) => view.scope !== 'PERSONAL')
        .map((view) => toNavigationItem(view)),
    },
    {
      id: 'personal',
      label: '개인 보기',
      items: personalItems,
    },
  ]
  const currentServerView = serverViews.find((view) => view.key === viewKey)
  const currentPresentation = currentServerView
    ? presentationFor(currentServerView)
    : null
  const title =
    currentPresentation?.name ?? VIEW_PRESENTATION[viewKey]?.name ?? '티켓'
  const querySignature = `${viewKey}:${searchParams.toString()}`

  useEffect(() => {
    selectionAnchor.current = null
    setSelectedTicketNumbers(new Set())
  }, [querySignature])

  useEffect(() => {
    setCursorHistory([null])
    setCursorIndex(0)
  }, [
    viewKey,
    filters.status,
    filters.priority,
    filters.groupId,
    filters.assigneeId,
    filters.slaState,
  ])

  const groups = assignmentQuery.data?.groups ?? []
  const assignees = uniqueBy(
    groups.flatMap((group) => group.members),
    (assignee) => assignee.id,
  )
  const visibleTickets = useMemo(() => {
    const normalized = normalizeSearch(currentPageSearch)
    if (!normalized) return query.data?.items ?? []
    return (query.data?.items ?? []).filter((ticket) =>
      [
        ticket.ticketNumber,
        ticket.subject,
        ticket.requester.displayName,
        ticket.group?.name,
        ticket.assignee?.displayName,
      ]
        .filter(Boolean)
        .some((value) => normalizeSearch(String(value)).includes(normalized)),
    )
  }, [currentPageSearch, query.data?.items])
  const activeFilters = filterSummary(filters, groups, assignees)
  const hasActiveFilters =
    activeFilters.length > 0 || Boolean(currentPageSearch)
  const selectedCount = visibleTickets.filter((ticket) =>
    selectedTicketNumbers.has(ticket.ticketNumber),
  ).length
  const selectedTickets = visibleTickets.filter((ticket) =>
    selectedTicketNumbers.has(ticket.ticketNumber),
  )

  const updateFilter = (key: string, value: string) => {
    const next = new URLSearchParams(searchParams)
    if (value) next.set(key, value)
    else next.delete(key)
    next.delete('cursor')
    setCursorHistory([null])
    setCursorIndex(0)
    setSearchParams(next)
  }

  const clearFilters = () => {
    const next = new URLSearchParams(searchParams)
    SERVER_FILTER_KEYS.forEach((key) => next.delete(key))
    next.delete('cursor')
    setCurrentPageSearch('')
    setSearchParams(next)
  }

  const toggleTicketSelection = (
    ticketNumber: number,
    options: { orderedTicketNumbers: number[]; range: boolean },
  ) => {
    setSelectedTicketNumbers((current) => {
      const next = new Set(current)
      if (options.range && selectionAnchor.current !== null) {
        const anchorIndex = options.orderedTicketNumbers.indexOf(
          selectionAnchor.current,
        )
        const targetIndex = options.orderedTicketNumbers.indexOf(ticketNumber)
        if (anchorIndex >= 0 && targetIndex >= 0) {
          options.orderedTicketNumbers
            .slice(
              Math.min(anchorIndex, targetIndex),
              Math.max(anchorIndex, targetIndex) + 1,
            )
            .forEach((number) => next.add(number))
          return next
        }
      }
      if (next.has(ticketNumber)) next.delete(ticketNumber)
      else if (next.size < 100) next.add(ticketNumber)
      selectionAnchor.current = ticketNumber
      return next
    })
  }

  const toggleSelectAll = () => {
    setSelectedTicketNumbers((current) => {
      const next = new Set(current)
      const allSelected = visibleTickets.every((ticket) =>
        next.has(ticket.ticketNumber),
      )
      visibleTickets.forEach((ticket) => {
        if (allSelected) next.delete(ticket.ticketNumber)
        else if (next.size < 100) next.add(ticket.ticketNumber)
      })
      selectionAnchor.current = visibleTickets[0]?.ticketNumber ?? null
      return next
    })
  }

  const handleSaveView = async (values: SavedViewEditorSave) => {
    if (editor?.mode === 'create') {
      const created = await createAgentSavedView(toCreateSavedViewInput(values))
      await queryClient.invalidateQueries({ queryKey: ['agent-views'] })
      setEditor(null)
      navigate(`/agent/views/${created.key}`)
      return
    }
    if (editor?.mode === 'edit') {
      let savedView = editor.view
      if (!editor.pendingOrderOnly) {
        savedView = await updateAgentSavedView(editor.view.key, {
          expectedVersion:
            values.expectedVersion ?? editor.view.definitionVersion,
          ...values.definition,
        })
        queryClient.setQueryData<SavedAgentView[]>(['agent-views'], (current) =>
          current?.map((view) =>
            view.key === savedView.key ? savedView : view,
          ),
        )
        setEditor({ mode: 'edit', view: savedView })
        await queryClient.invalidateQueries({
          queryKey: ['agent-view', editor.view.key],
        })
      }
      if (pendingPersonalOrder) {
        try {
          await reorderAgentSavedViews({
            scope: 'PERSONAL',
            expectedOrderVersion: savedView.orderVersion,
            viewKeys: pendingPersonalOrder,
          })
        } catch (error) {
          const refreshed = await viewQuery.refetch()
          const latest = refreshed.data?.find(
            (view) => view.key === savedView.key,
          )
          setEditor({
            mode: 'edit',
            view: latest ?? savedView,
            pendingOrderOnly: true,
          })
          throw new SavedViewOrderSaveError(error)
        }
      }
      await queryClient.invalidateQueries({ queryKey: ['agent-views'] })
      await queryClient.invalidateQueries({
        queryKey: ['agent-view', editor.view.key],
      })
    }
    setPendingPersonalOrder(null)
    setEditor(null)
  }

  const editorPosition =
    editor?.mode === 'edit'
      ? {
          index: personalItems.findIndex(
            (item) => item.key === editor.view.key,
          ),
          total: personalItems.length,
        }
      : undefined

  const moveEditedView = (direction: 'down' | 'up') => {
    if (editor?.mode !== 'edit') return
    const currentOrder = personalItems.map((item) => item.key)
    const index = currentOrder.indexOf(editor.view.key)
    const destination = direction === 'up' ? index - 1 : index + 1
    if (index < 0 || destination < 0 || destination >= currentOrder.length)
      return
    const next = [...currentOrder]
    const [moved] = next.splice(index, 1)
    if (moved) next.splice(destination, 0, moved)
    setPendingPersonalOrder(next)
  }

  const deleteEditedView = async (view: SavedAgentView) => {
    await deleteAgentSavedView(view.key, view.definitionVersion)
    await queryClient.invalidateQueries({ queryKey: ['agent-views'] })
    setEditor(null)
    navigate('/agent/views/my-open')
  }

  const reloadEditedView = async () => {
    if (editor?.mode !== 'edit') return
    const refreshed = await viewQuery.refetch()
    const latest = refreshed.data?.find((view) => view.key === editor.view.key)
    if (!latest) throw new Error('Saved view no longer exists')
    setPendingPersonalOrder(null)
    setEditor({ mode: 'edit', view: latest })
  }

  return (
    <section className="seed-queue" aria-label="티켓 큐">
      <SeedSavedViewNavigation
        activeKey={viewKey}
        onCreate={openCreateEditor}
        onEdit={openEditEditor}
        sections={sidebarSections}
      />
      <section
        className="seed-queue__content"
        aria-labelledby="agent-view-title"
      >
        <header className="seed-queue__header">
          <div>
            <div>
              <h1 id="agent-view-title">{title}</h1>
              {query.data && (
                <b>{query.data.totalApproximate ?? query.data.items.length}</b>
              )}
            </div>
            {currentServerView?.description && (
              <p>{currentServerView.description}</p>
            )}
          </div>
          <div className="seed-queue__actions">
            <SeedButton
              aria-expanded={filtersOpen}
              aria-label="필터 열기"
              onClick={() => setFiltersOpen((current) => !current)}
            >
              <SeedIcon name="filter" /> 필터
            </SeedButton>
            <SeedButton onClick={() => query.refetch()} variant="quiet">
              <SeedIcon name="refresh" /> 새로고침
            </SeedButton>
            <div className="seed-menu" onBlur={closeActionsWhenFocusLeaves}>
              <SeedButton
                aria-expanded={actionsOpen}
                aria-haspopup="menu"
                onClick={() => setActionsOpen((current) => !current)}
              >
                작업 <SeedIcon name="chevron" />
              </SeedButton>
              {actionsOpen && (
                <div aria-label="보기 작업" role="menu">
                  {actionItems.map((item) => (
                    <button
                      key={item.id}
                      onClick={(event) => {
                        item.onClick(event)
                        setActionsOpen(false)
                      }}
                      role="menuitem"
                      type="button"
                    >
                      {item.label}
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>
        </header>
        {filtersOpen && (
          <section aria-label={`${title} 필터`} className="seed-queue__filters">
            <SeedFilterBar>
              <SeedTextField
                aria-label="현재 목록 검색"
                label="현재 목록 검색"
                leadingIcon="search"
                onChange={(event) => setCurrentPageSearch(event.target.value)}
                placeholder="제목, 요청자, 번호"
                type="search"
                value={currentPageSearch}
              />
              <FilterSelect
                label="상태"
                onChange={(value) => updateFilter('status', value)}
                value={filters.status ?? ''}
              >
                <option value="">전체</option>
                {STATUSES.map((status) => (
                  <option key={status} value={status}>
                    {STATUS_LABELS[status]}
                  </option>
                ))}
              </FilterSelect>
              <FilterSelect
                label="최초 답변 SLA"
                onChange={(value) => updateFilter('slaState', value)}
                value={filters.slaState ?? ''}
              >
                <option value="">전체</option>
                {SLA_STATES.map((state) => (
                  <option key={state} value={state}>
                    {state}
                  </option>
                ))}
              </FilterSelect>
              <FilterSelect
                label="우선순위"
                onChange={(value) => updateFilter('priority', value)}
                value={filters.priority ?? ''}
              >
                <option value="">전체</option>
                {PRIORITIES.map((priority) => (
                  <option key={priority} value={priority}>
                    {PRIORITY_LABELS[priority]}
                  </option>
                ))}
              </FilterSelect>
              <FilterSelect
                label="그룹"
                onChange={(value) => updateFilter('groupId', value)}
                value={filters.groupId ?? ''}
              >
                <option value="">전체</option>
                {groups.map((group) => (
                  <option key={group.id} value={group.id}>
                    {group.name}
                  </option>
                ))}
              </FilterSelect>
              <FilterSelect
                label="담당자"
                onChange={(value) => updateFilter('assigneeId', value)}
                value={filters.assigneeId ?? ''}
              >
                <option value="">전체</option>
                <option value="me">나</option>
                <option value="unassigned">미배정</option>
                {assignees.map((assignee) => (
                  <option key={assignee.id} value={assignee.id}>
                    {assignee.displayName}
                  </option>
                ))}
              </FilterSelect>
            </SeedFilterBar>
          </section>
        )}
        {hasActiveFilters && (
          <section aria-label="적용된 필터" className="seed-filter-summary">
            <div>
              {currentPageSearch && (
                <span>검색: {shortenSearch(currentPageSearch)}</span>
              )}
              {activeFilters.map((filter) => (
                <span key={filter.key}>{filter.label}</span>
              ))}
            </div>
            <SeedButton onClick={clearFilters} variant="quiet">
              모두 지우기
            </SeedButton>
          </section>
        )}
        <div className="seed-queue__table">
          {query.isLoading ? (
            <SeedSkeletonRows label={`${title} 불러오는 중`} rows={8} />
          ) : query.isError ? (
            <QueueError error={query.error} onRetry={() => query.refetch()} />
          ) : !visibleTickets.length ? (
            <SeedFeedbackState
              action={
                hasActiveFilters ? (
                  <SeedButton onClick={clearFilters}>필터 지우기</SeedButton>
                ) : undefined
              }
              description={
                hasActiveFilters
                  ? '다른 필터나 검색어를 사용해 보세요.'
                  : '새로운 티켓이 도착하면 여기에 표시됩니다.'
              }
              kind="empty"
              title={
                hasActiveFilters
                  ? '일치하는 티켓이 없습니다.'
                  : '처리할 티켓이 없습니다.'
              }
            />
          ) : (
            <>
              {selectedCount > 0 && (
                <section
                  aria-label="선택된 티켓"
                  className="seed-selection-bar"
                >
                  <strong>{selectedCount}개 선택됨</strong>
                  <SeedButton
                    onClick={() => setSelectedTicketNumbers(new Set())}
                    variant="quiet"
                  >
                    선택 해제
                  </SeedButton>
                </section>
              )}
              {assignmentQuery.data && (
                <BulkTicketActionPanel
                  onComplete={() => {
                    setSelectedTicketNumbers(new Set())
                    void queryClient.invalidateQueries({
                      queryKey: ['agent-view', viewKey],
                    })
                  }}
                  options={assignmentQuery.data}
                  tickets={selectedTickets}
                />
              )}
              <SeedQueueTicketTable
                items={visibleTickets.map(toSeedQueueTicket)}
                label={title}
                onOpenTicket={(ticketNumber) =>
                  navigate(`/agent/tickets/${ticketNumber}`)
                }
                onSelectAll={toggleSelectAll}
                onSelectionChange={toggleTicketSelection}
                selectedTicketNumbers={selectedTicketNumbers}
                visibleColumns={toSeedQueueColumns(currentServerView?.columns)}
              />
              <footer className="seed-pagination">
                <span>{visibleTickets.length}개 표시</span>
                <div>
                  <SeedButton
                    disabled={cursorIndex === 0}
                    onClick={() => {
                      setCursorIndex((current) => Math.max(0, current - 1))
                      setSelectedTicketNumbers(new Set())
                    }}
                  >
                    이전 페이지
                  </SeedButton>
                  {query.data?.nextCursor && (
                    <SeedButton
                      onClick={() => {
                        const nextCursor = query.data?.nextCursor
                        if (!nextCursor) return
                        setCursorHistory((current) => [
                          ...current.slice(0, cursorIndex + 1),
                          nextCursor,
                        ])
                        setCursorIndex((current) => current + 1)
                        setSelectedTicketNumbers(new Set())
                      }}
                    >
                      다음 페이지
                    </SeedButton>
                  )}
                </div>
              </footer>
            </>
          )}
        </div>
      </section>
      <ViewConfigurationDrawer
        editor={editor}
        onClose={closeEditor}
        onDelete={deleteEditedView}
        onMove={moveEditedView}
        onPreview={(definition) =>
          previewAgentSavedView(definition, createOpaqueUuid())
        }
        onReload={reloadEditedView}
        onSave={handleSaveView}
        position={
          editorPosition && editorPosition.index >= 0
            ? editorPosition
            : undefined
        }
        returnFocusRef={editorTriggerRef}
      />
    </section>
  )
}

class SavedViewOrderSaveError extends Error {
  cause: unknown

  constructor(cause: unknown) {
    super('Saved view definition committed but order save failed')
    this.name = 'SavedViewOrderSaveError'
    this.cause = cause
  }
}

const SAVED_VIEW_COLUMN_MAP = {
  TICKET_NUMBER: 'ticketNumber',
  SUBJECT: 'subject',
  STATUS: 'status',
  PRIORITY: 'priority',
  GROUP: 'group',
  ASSIGNEE: 'assignee',
  UPDATED_AT: 'updatedAt',
  FIRST_REPLY_SLA: 'sla',
} as const satisfies Record<string, SeedQueueColumn>

function toSeedQueueColumns(columns: SavedAgentView['columns'] | undefined) {
  return (columns ?? Object.keys(SAVED_VIEW_COLUMN_MAP)).map(
    (column) =>
      SAVED_VIEW_COLUMN_MAP[column as keyof typeof SAVED_VIEW_COLUMN_MAP],
  )
}

function FilterSelect({
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
      aria-label={`${label} 필터`}
      label={label}
      onChange={(event) => onChange(event.target.value)}
      value={value}
    >
      {children}
    </SeedSelectField>
  )
}

function QueueError({
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
        error instanceof ApiError ? `요청 ID: ${error.requestId}` : undefined
      }
      kind={denied ? 'denied' : 'error'}
      title={
        denied
          ? '이 티켓 목록에 접근할 수 없습니다.'
          : '티켓 목록을 불러오지 못했습니다.'
      }
    />
  )
}

function personalViewItems(
  serverViews: SavedAgentView[],
  order: string[] | null,
) {
  const items = serverViews
    .filter((view) => view.scope === 'PERSONAL')
    .map((view) => toNavigationItem(view))
  if (!order) return items
  return [...items].sort(
    (left, right) => order.indexOf(left.key) - order.indexOf(right.key),
  )
}

function toNavigationItem(view: SavedAgentView): SeedSavedViewItem {
  const presentation = presentationFor(view)
  return {
    key: view.key,
    label: presentation.name,
    count: view.ticketCountState === 'EXACT' ? view.ticketCount : null,
    countAsOf: view.ticketCountState === 'EXACT' ? view.ticketCountAsOf : null,
    to: `/agent/views/${view.key}`,
    editable: view.scope !== 'SYSTEM',
  }
}

function presentationFor(view: SavedAgentView) {
  const base = VIEW_PRESENTATION[view.key] ?? { name: view.name }
  return base
}

function toSeedQueueTicket(ticket: {
  ticketNumber: number
  subject: string
  status: AgentTicketStatus
  priority: TicketPriority
  requester: { displayName: string }
  group: { name: string } | null
  assignee: { displayName: string } | null
  updatedAt: string
  isChild: boolean
  sla: FirstReplySlaBadge | null
}): SeedQueueTicket {
  return {
    ticketNumber: ticket.ticketNumber,
    subject: ticket.subject,
    status: (
      <SeedStatusBadge
        tone={
          ticket.status === 'PENDING' || ticket.status === 'ON_HOLD'
            ? 'warning'
            : ticket.status === 'SOLVED' || ticket.status === 'CLOSED'
              ? 'positive'
              : 'info'
        }
      >
        {STATUS_LABELS[ticket.status]}
      </SeedStatusBadge>
    ),
    priority: PRIORITY_LABELS[ticket.priority],
    requester: ticket.requester.displayName,
    group: ticket.group?.name ?? '미지정',
    assignee: ticket.assignee?.displayName ?? '미배정',
    updatedLabel: formatUpdatedAt(ticket.updatedAt),
    sla: ticket.sla ? ticket.sla.state : '정책 없음',
  }
}

function filtersFrom(searchParams: URLSearchParams): AgentTicketFilters {
  const status = searchParams.get('status')
  const priority = searchParams.get('priority')
  return {
    ...(status && STATUSES.includes(status as AgentTicketStatus)
      ? { status: status as AgentTicketStatus }
      : {}),
    ...(priority && PRIORITIES.includes(priority as TicketPriority)
      ? { priority: priority as TicketPriority }
      : {}),
    ...(searchParams.get('groupId')
      ? { groupId: searchParams.get('groupId')! }
      : {}),
    ...(searchParams.get('assigneeId')
      ? { assigneeId: searchParams.get('assigneeId')! }
      : {}),
    ...(searchParams.get('slaState') &&
    SLA_STATES.includes(searchParams.get('slaState') as FirstReplySlaState)
      ? { slaState: searchParams.get('slaState') as FirstReplySlaState }
      : {}),
    limit: 50,
  }
}

function filterSummary(
  filters: AgentTicketFilters,
  groups: { id: string; name: string }[],
  assignees: { id: string; displayName: string }[],
) {
  const labels: Array<{ key: string; label: string }> = []
  if (filters.status)
    labels.push({
      key: 'status',
      label: `상태: ${STATUS_LABELS[filters.status]}`,
    })
  if (filters.priority)
    labels.push({
      key: 'priority',
      label: `우선순위: ${PRIORITY_LABELS[filters.priority]}`,
    })
  if (filters.groupId)
    labels.push({
      key: 'groupId',
      label: `그룹: ${groups.find((group) => group.id === filters.groupId)?.name ?? '선택됨'}`,
    })
  if (filters.assigneeId)
    labels.push({
      key: 'assigneeId',
      label: `담당자: ${filters.assigneeId === 'me' ? '나' : filters.assigneeId === 'unassigned' ? '미배정' : (assignees.find((assignee) => assignee.id === filters.assigneeId)?.displayName ?? '선택됨')}`,
    })
  if (filters.slaState)
    labels.push({
      key: 'slaState',
      label: `최초 답변 SLA: ${filters.slaState}`,
    })
  return labels
}

function uniqueBy<T>(items: T[], key: (item: T) => string) {
  const seen = new Set<string>()
  return items.filter((item) => {
    const value = key(item)
    if (seen.has(value)) return false
    seen.add(value)
    return true
  })
}

function normalizeSearch(value: string) {
  return value.trim().toLocaleLowerCase('ko-KR')
}
function shortenSearch(value: string) {
  const normalized = value.trim()
  return normalized.length > 22 ? `${normalized.slice(0, 22)}…` : normalized
}
function formatUpdatedAt(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(value))
}
