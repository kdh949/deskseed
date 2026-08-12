import { useQuery } from '@tanstack/react-query'
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
import { ApiError, listAgentViews, listTicketsInView } from '../../api/client'
import type {
  AgentTicketFilters,
  AgentTicketStatus,
  SavedAgentView,
  TicketPriority,
} from '../../api/types'
import {
  DeskseedIcon,
  DsButton,
  DsSelect,
  QueueTicketTable,
  ScreenState,
  TableSkeleton,
  ViewNavigation,
  type IconName,
  type QueueTicketTableItem,
  type ViewNavigationItem,
  type ViewNavigationSection,
} from '../../design-system'
import {
  ViewConfigurationDrawer,
  type ConfigurableView,
  type ViewEditor,
} from './ViewConfigurationDrawer'

const LOCAL_VIEW_PREFIX = 'local-'

const VIEW_NAVIGATION_COPY = {
  create: '새 보기 만들기',
  reorderTip: '보기를 드래그하여 순서를 변경할 수 있어요.',
}

const VIEW_PRESENTATION: Record<
  string,
  {
    icon: IconName
    iconTone?: ViewNavigationItem['iconTone']
    name: string
  }
> = {
  'my-open': {
    name: '내 티켓',
    icon: 'inbox',
  },
  'unassigned-my-groups': {
    name: '미배정 티켓',
    icon: 'userGroup',
  },
  pending: {
    name: '고객 답변 대기',
    icon: 'clock',
    iconTone: 'warning',
  },
  urgent: {
    name: '긴급 티켓',
    icon: 'alertWarning',
    iconTone: 'danger',
  },
  'today-updated': {
    name: '오늘 업데이트된 티켓',
    icon: 'history',
  },
  'recently-solved': {
    name: '최근 해결',
    icon: 'checkCircle',
    iconTone: 'success',
  },
  'customer-reply-pending': {
    name: '고객 응답 대기',
    icon: 'speechBubble',
    iconTone: 'warning',
  },
  'created-by-me': {
    name: '내가 생성한 티켓',
    icon: 'circle',
  },
  following: {
    name: '내가 팔로우 중인 티켓',
    icon: 'userGroup',
  },
  drafts: {
    name: '임시 보관함',
    icon: 'inbox',
  },
  'my-child-tasks': {
    name: '내부 협업',
    icon: 'userGroup',
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
const SERVER_FILTER_KEYS = ['status', 'priority', 'groupId', 'assigneeId']

type LocalView = ConfigurableView & {
  count: null
}

type PersonalViewOverride = Pick<ConfigurableView, 'icon' | 'label'>

export function AgentViewsPage() {
  const { viewKey = 'my-open' } = useParams()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const [currentPageSearch, setCurrentPageSearch] = useState('')
  const [filtersOpen, setFiltersOpen] = useState(() =>
    SERVER_FILTER_KEYS.some((key) => searchParams.has(key)),
  )
  const [selectedTicketNumbers, setSelectedTicketNumbers] = useState<
    Set<number>
  >(() => new Set())
  const [localViews, setLocalViews] = useState<LocalView[]>([])
  const [personalOverrides, setPersonalOverrides] = useState<
    Record<string, PersonalViewOverride>
  >({})
  const [personalOrder, setPersonalOrder] = useState<string[]>([])
  const [personalOrderSnapshot, setPersonalOrderSnapshot] = useState<
    string[] | null
  >(null)
  const [editor, setEditor] = useState<ViewEditor | null>(null)
  const [actionsOpen, setActionsOpen] = useState(false)
  const nextLocalViewId = useRef(0)
  const selectionAnchor = useRef<number | null>(null)
  const editorTriggerRef = useRef<HTMLElement | null>(null)
  const filters = filtersFrom(searchParams)
  const isLocalRoute = viewKey.startsWith(LOCAL_VIEW_PREFIX)
  const viewQuery = useQuery({
    queryKey: ['agent-views'],
    queryFn: listAgentViews,
  })
  const query = useQuery({
    enabled: !isLocalRoute,
    queryKey: ['agent-view', viewKey, filters],
    queryFn: () => listTicketsInView(viewKey, filters),
  })
  const currentLocalView = localViews.find((view) => view.key === viewKey)
  const serverViews = viewQuery.data ?? []
  const personalItems = useMemo(
    () =>
      personalViewItems(
        serverViews,
        localViews,
        personalOverrides,
        personalOrder,
      ),
    [localViews, personalOrder, personalOverrides, serverViews],
  )
  const openCreateEditor = (event: ReactMouseEvent<HTMLButtonElement>) => {
    editorTriggerRef.current = event.currentTarget
    setPersonalOrderSnapshot(null)
    setEditor({ mode: 'create' })
  }
  const openEditEditor = (
    item: ViewNavigationItem,
    event: ReactMouseEvent<HTMLButtonElement>,
  ) => {
    editorTriggerRef.current = event.currentTarget
    setPersonalOrderSnapshot(
      personalItems.map((personalItem) => personalItem.key),
    )
    setEditor({
      mode: 'edit',
      view: { icon: item.icon, key: item.key, label: item.label },
    })
  }
  const closeEditor = () => {
    if (personalOrderSnapshot) setPersonalOrder(personalOrderSnapshot)
    setPersonalOrderSnapshot(null)
    setEditor(null)
  }
  const currentPersonalItem = personalItems.find((item) => item.key === viewKey)
  const actionItems = [
    ...(currentPersonalItem
      ? [
          {
            id: 'edit-view',
            label: '보기 설정',
            onClick: (event: ReactMouseEvent<HTMLButtonElement>) =>
              openEditEditor(currentPersonalItem, event),
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
  const sidebarSections: ViewNavigationSection[] = [
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
      footerAction: (
        <button
          className="ds-view-navigation-create"
          onClick={openCreateEditor}
          type="button"
        >
          <DeskseedIcon name="plus" size="sm" />
          {VIEW_NAVIGATION_COPY.create}
        </button>
      ),
      items: personalItems,
    },
  ]
  const currentServerView = serverViews.find((view) => view.key === viewKey)
  const currentPresentation = currentServerView
    ? presentationFor(currentServerView, personalOverrides)
    : null
  const title =
    currentLocalView?.label ??
    currentPresentation?.name ??
    VIEW_PRESENTATION[viewKey]?.name ??
    '티켓'
  const querySignature = `${viewKey}:${searchParams.toString()}`

  useEffect(() => {
    selectionAnchor.current = null
    setSelectedTicketNumbers(new Set())
  }, [querySignature])

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

  const updateFilter = (key: string, value: string) => {
    const next = new URLSearchParams(searchParams)
    if (value) next.set(key, value)
    else next.delete(key)
    if (key !== 'cursor') next.delete('cursor')
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
      else next.add(ticketNumber)
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
        else next.add(ticket.ticketNumber)
      })
      selectionAnchor.current = visibleTickets[0]?.ticketNumber ?? null
      return next
    })
  }

  const handleSaveView = (values: { icon: IconName; label: string }) => {
    if (editor?.mode === 'create') {
      const key = `${LOCAL_VIEW_PREFIX}${++nextLocalViewId.current}`
      setLocalViews((current) => [...current, { ...values, key, count: null }])
      setPersonalOrder((current) => [...current, key])
      setPersonalOrderSnapshot(null)
      setEditor(null)
      navigate(`/agent/views/${key}`)
      return
    }
    if (editor?.mode === 'edit') {
      const { key } = editor.view
      if (key.startsWith(LOCAL_VIEW_PREFIX)) {
        setLocalViews((current) =>
          current.map((view) =>
            view.key === key ? { ...view, ...values } : view,
          ),
        )
      } else {
        setPersonalOverrides((current) => ({ ...current, [key]: values }))
      }
    }
    setPersonalOrderSnapshot(null)
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
    setPersonalOrder(next)
  }

  return (
    <main className="agent-queue-workspace" aria-label="티켓 큐">
      <ViewNavigation
        footer={
          <>
            <strong>Tip</strong>: {VIEW_NAVIGATION_COPY.reorderTip}
          </>
        }
        label="티켓 보기"
        onEditItem={openEditEditor}
        sections={sidebarSections}
        title="보기"
      />
      <section className="agent-queue" aria-labelledby="agent-view-title">
        <header className="agent-queue-header">
          <div>
            <div className="agent-queue-title-row">
              <h1 id="agent-view-title">{title}</h1>
              {!isLocalRoute && query.data ? (
                <span>
                  {query.data.totalApproximate ?? query.data.items.length}개
                </span>
              ) : null}
            </div>
            <DsButton
              aria-expanded={filtersOpen}
              aria-label="필터 열기"
              className="agent-queue-filter-trigger"
              onClick={() => setFiltersOpen((current) => !current)}
            >
              <DeskseedIcon name="adjust" size="sm" />
              필터
            </DsButton>
          </div>
          <div className="agent-queue-header-actions">
            {!isLocalRoute ? (
              <DsButton
                className="agent-queue-toolbar-action"
                onClick={() => query.refetch()}
                tone="ghost"
              >
                <DeskseedIcon name="reload" size="sm" />
                새로고침
              </DsButton>
            ) : null}
            {!isLocalRoute ? (
              <span
                aria-hidden="true"
                className="agent-queue-toolbar-divider"
              />
            ) : null}
            <div
              className="agent-queue-actions-menu"
              onBlur={closeActionsWhenFocusLeaves}
            >
              <DsButton
                aria-expanded={actionsOpen}
                aria-haspopup="menu"
                className="agent-queue-toolbar-action"
                onClick={() => setActionsOpen((current) => !current)}
                tone="ghost"
              >
                작업
                <DeskseedIcon name="chevronDown" size="sm" />
              </DsButton>
              {actionsOpen ? (
                <div
                  aria-label="보기 작업"
                  className="agent-queue-actions-popover"
                  role="menu"
                >
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
              ) : null}
            </div>
          </div>
        </header>

        {filtersOpen && !isLocalRoute ? (
          <section aria-label={`${title} 필터`} className="agent-queue-filters">
            <label className="agent-queue-filter agent-queue-search">
              <span>현재 목록 검색</span>
              <span className="agent-queue-search-control">
                <DeskseedIcon name="search" size="sm" />
                <input
                  aria-label="현재 목록 검색"
                  onChange={(event) => setCurrentPageSearch(event.target.value)}
                  placeholder="제목, 요청자, 번호"
                  type="search"
                  value={currentPageSearch}
                />
              </span>
            </label>
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
          </section>
        ) : null}

        {hasActiveFilters && !isLocalRoute ? (
          <section
            aria-label="적용된 필터"
            className="agent-queue-filter-summary"
          >
            <div>
              {currentPageSearch ? (
                <span>검색: {shortenSearch(currentPageSearch)}</span>
              ) : null}
              {activeFilters.map((filter) => (
                <span key={filter.key}>{filter.label}</span>
              ))}
            </div>
            <DsButton onClick={clearFilters} tone="ghost">
              모두 지우기
            </DsButton>
          </section>
        ) : null}

        {isLocalRoute ? (
          currentLocalView ? (
            <ScreenState
              description="이 프로토타입에서는 이름·아이콘·순서만 설정합니다. 티켓 조건을 연결하면 이 보기에 결과가 표시됩니다."
              kind="empty"
              title="아직 연결된 티켓 조건이 없습니다."
            />
          ) : (
            <ScreenState
              description="개인 보기 설정은 새로고침 후 초기 상태로 돌아갑니다."
              kind="not-found"
              title="개인 보기를 찾을 수 없습니다."
            />
          )
        ) : query.isLoading ? (
          <TableSkeleton label={`${title} 불러오는 중`} />
        ) : query.isError ? (
          <QueueError error={query.error} onRetry={() => query.refetch()} />
        ) : !visibleTickets.length ? (
          <ScreenState
            action={
              hasActiveFilters ? (
                <DsButton onClick={clearFilters}>필터 지우기</DsButton>
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
            {selectedCount ? (
              <section
                aria-label="선택된 티켓"
                className="agent-queue-bulk-action"
              >
                <div>
                  <strong>{selectedCount}개 선택됨</strong>
                  <p>선택한 티켓은 같은 보기 안에서 계속 비교할 수 있습니다.</p>
                </div>
                <DsButton
                  onClick={() => setSelectedTicketNumbers(new Set())}
                  tone="ghost"
                >
                  선택 해제
                </DsButton>
              </section>
            ) : null}
            <QueueTicketTable
              items={visibleTickets.map(toQueueTicketItem)}
              label={title}
              onOpenTicket={(ticketNumber) =>
                navigate(`/agent/tickets/${ticketNumber}`)
              }
              onSelectAll={toggleSelectAll}
              onSelectionChange={toggleTicketSelection}
              selectedTicketNumbers={selectedTicketNumbers}
              ticketHref={(ticketNumber) => `/agent/tickets/${ticketNumber}`}
            />
            <footer className="agent-queue-pagination">
              <p>{visibleTickets.length}개 표시</p>
              {query.data?.nextCursor ? (
                <DsButton
                  onClick={() =>
                    updateFilter('cursor', query.data?.nextCursor ?? '')
                  }
                >
                  다음 페이지
                </DsButton>
              ) : null}
            </footer>
          </>
        )}
      </section>
      <ViewConfigurationDrawer
        editor={editor}
        onClose={closeEditor}
        onMove={moveEditedView}
        onSave={handleSaveView}
        position={
          editorPosition && editorPosition.index >= 0
            ? editorPosition
            : undefined
        }
        returnFocusRef={editorTriggerRef}
      />
    </main>
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
    <label className="agent-queue-filter">
      <span>{label}</span>
      <DsSelect
        aria-label={`${label} 필터`}
        onChange={(event) => onChange(event.target.value)}
        value={value}
      >
        {children}
      </DsSelect>
    </label>
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
    <ScreenState
      action={<DsButton onClick={onRetry}>다시 시도</DsButton>}
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
  localViews: LocalView[],
  overrides: Record<string, PersonalViewOverride>,
  order: string[],
) {
  const items = [
    ...serverViews
      .filter((view) => view.scope === 'PERSONAL')
      .map((view) => toNavigationItem(view, overrides)),
    ...localViews.map((view) => ({
      key: view.key,
      label: view.label,
      icon: view.icon,
      count: view.count,
      to: `/agent/views/${view.key}`,
      editable: true,
    })),
  ]
  return [...items].sort((left, right) => {
    const leftIndex = order.indexOf(left.key)
    const rightIndex = order.indexOf(right.key)
    if (leftIndex < 0 && rightIndex < 0) return 0
    if (leftIndex < 0) return 1
    if (rightIndex < 0) return -1
    return leftIndex - rightIndex
  })
}

function toNavigationItem(
  view: SavedAgentView,
  overrides: Record<string, PersonalViewOverride> = {},
): ViewNavigationItem {
  const presentation = presentationFor(view, overrides)
  return {
    key: view.key,
    label: presentation.name,
    icon: presentation.icon,
    iconTone: presentation.iconTone,
    count: view.ticketCount,
    to: `/agent/views/${view.key}`,
    editable: view.scope === 'PERSONAL',
  }
}

function presentationFor(
  view: SavedAgentView,
  overrides: Record<string, PersonalViewOverride>,
) {
  const base = VIEW_PRESENTATION[view.key] ?? {
    name: view.name,
    icon: (view.scope === 'PERSONAL' ? 'bookmark' : 'inbox') as IconName,
  }
  const override = overrides[view.key]
  return {
    ...base,
    name: override?.label ?? base.name,
    icon: override?.icon ?? base.icon,
  }
}

function toQueueTicketItem(ticket: {
  ticketNumber: number
  subject: string
  status: AgentTicketStatus
  priority: TicketPriority
  requester: { displayName: string }
  group: { name: string } | null
  assignee: { displayName: string } | null
  updatedAt: string
  isChild: boolean
}): QueueTicketTableItem {
  return {
    ticketNumber: ticket.ticketNumber,
    subject: ticket.subject,
    status: ticket.status,
    priority: ticket.priority,
    requester: ticket.requester.displayName,
    group: ticket.group?.name ?? '미지정',
    assignee: ticket.assignee?.displayName ?? '미배정',
    updatedAt: ticket.updatedAt,
    updatedLabel: formatUpdatedAt(ticket.updatedAt),
    isChild: ticket.isChild,
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
    ...(searchParams.get('cursor')
      ? { cursor: searchParams.get('cursor')! }
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
