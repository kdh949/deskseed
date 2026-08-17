import { useRef, type KeyboardEvent, type ReactNode } from 'react'
import { DeskseedIcon } from '../primitives/DeskseedIcon'
import { DsStatusIndicator } from '../primitives/DeskseedPrimitives'
import { FirstReplySlaIndicator } from '../components/FirstReplySlaIndicator'
import type { FirstReplySlaBadge } from '../../api/types'

export type QueueTicketTableItem = {
  assignee: string
  group: string
  isChild?: boolean
  priority: 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
  requester: string
  sla?: FirstReplySlaBadge | null
  status: 'NEW' | 'OPEN' | 'PENDING' | 'ON_HOLD' | 'SOLVED' | 'CLOSED'
  subject: string
  ticketNumber: number
  updatedAt?: string
  updatedLabel: string
}

export type QueueTicketSortKey =
  | 'assignee'
  | 'group'
  | 'priority'
  | 'requester'
  | 'sla'
  | 'status'
  | 'subject'
  | 'ticketNumber'
  | 'updatedAt'

export type QueueTicketSort = {
  direction: 'ascending' | 'descending'
  key: QueueTicketSortKey
}

export type QueueTicketColumn = QueueTicketSortKey

const columns: Array<{ key: QueueTicketColumn; label: string }> = [
  { key: 'ticketNumber', label: '티켓 ID' },
  { key: 'requester', label: '요청자' },
  { key: 'status', label: '상태' },
  { key: 'priority', label: '우선순위' },
  { key: 'group', label: '그룹' },
  { key: 'assignee', label: '담당자' },
  { key: 'sla', label: '최초 답변 SLA' },
  { key: 'updatedAt', label: '최근 업데이트' },
  { key: 'subject', label: '제목' },
]

const statusLabels: Record<QueueTicketTableItem['status'], string> = {
  NEW: '신규',
  OPEN: '처리 중',
  PENDING: '고객 답변 대기',
  ON_HOLD: '보류',
  SOLVED: '해결',
  CLOSED: '종료',
}

const priorityLabels: Record<QueueTicketTableItem['priority'], string> = {
  LOW: '낮음',
  NORMAL: '보통',
  HIGH: '높음',
  URGENT: '긴급',
}

function statusTone(status: QueueTicketTableItem['status']) {
  if (status === 'NEW') return 'new' as const
  if (status === 'OPEN') return 'open' as const
  if (status === 'PENDING') return 'pending' as const
  if (status === 'ON_HOLD') return 'onHold' as const
  return 'solved' as const
}

export type QueueTicketTableProps = {
  items: QueueTicketTableItem[]
  label: string
  onOpenTicket?: (ticketNumber: number) => void
  onSelectAll?: () => void
  onSelectionChange?: (
    ticketNumber: number,
    options: { orderedTicketNumbers: number[]; range: boolean },
  ) => void
  selectedTicketNumbers?: Set<number>
  sort?: QueueTicketSort
  ticketHref?: (ticketNumber: number) => string
  visibleColumns?: QueueTicketColumn[]
  onSortChange?: (sort: QueueTicketSort) => void
}

export function QueueTicketTable({
  items,
  label,
  onOpenTicket,
  onSelectAll,
  onSelectionChange,
  onSortChange,
  selectedTicketNumbers = new Set<number>(),
  sort,
  ticketHref,
  visibleColumns = columns.map((column) => column.key),
}: QueueTicketTableProps) {
  const links = useRef<Array<HTMLAnchorElement | null>>([])
  const renderedColumns = visibleColumns
    .map((key) => columns.find((column) => column.key === key))
    .filter((column): column is (typeof columns)[number] => Boolean(column))
  const orderedTicketNumbers = items.map((item) => item.ticketNumber)

  const toggleSort = (key: QueueTicketSortKey) => {
    onSortChange?.({
      key,
      direction:
        sort?.key === key && sort.direction === 'descending'
          ? 'ascending'
          : 'descending',
    })
  }

  const handleKeyDown = (
    event: KeyboardEvent<HTMLAnchorElement>,
    index: number,
    ticketNumber: number,
  ) => {
    let nextIndex: number | null = null
    if (event.key === 'ArrowDown')
      nextIndex = Math.min(index + 1, items.length - 1)
    if (event.key === 'ArrowUp') nextIndex = Math.max(index - 1, 0)
    if (event.key === 'Home') nextIndex = 0
    if (event.key === 'End') nextIndex = items.length - 1
    if (nextIndex !== null) {
      event.preventDefault()
      links.current[nextIndex]?.focus()
      return
    }
    if (event.key === ' ') {
      event.preventDefault()
      onSelectionChange?.(ticketNumber, {
        orderedTicketNumbers,
        range: event.shiftKey,
      })
    }
  }

  return (
    <div className="ds-queue-ticket-table-wrap">
      <table aria-label={`${label} 티켓`} className="ds-queue-ticket-table">
        <caption className="sr-only">{label} 티켓 목록</caption>
        <thead>
          <tr>
            <th className="ds-queue-ticket-select-column" scope="col">
              <span className="sr-only">티켓 선택</span>
              {onSelectAll ? (
                <input
                  aria-label="현재 페이지 티켓 전체 선택"
                  checked={
                    items.length > 0 &&
                    items.every((item) =>
                      selectedTicketNumbers.has(item.ticketNumber),
                    )
                  }
                  onChange={onSelectAll}
                  type="checkbox"
                />
              ) : null}
            </th>
            {renderedColumns.map((column) => {
              const active = sort?.key === column.key
              const columnClassName =
                column.key === 'group'
                  ? 'ds-queue-ticket-group-column'
                  : column.key === 'assignee'
                    ? 'ds-queue-ticket-assignee-column'
                    : column.key === 'subject'
                      ? 'ds-queue-ticket-subject-column'
                      : column.key === 'sla'
                        ? 'ds-queue-ticket-sla-column'
                        : undefined
              return (
                <th
                  aria-sort={
                    onSortChange
                      ? active
                        ? sort.direction
                        : 'none'
                      : undefined
                  }
                  className={columnClassName}
                  key={column.key}
                  scope="col"
                >
                  {onSortChange ? (
                    <button
                      aria-label={`${column.label} ${active ? (sort?.direction === 'ascending' ? '오름차순' : '내림차순') : '정렬'}`}
                      className="ds-queue-ticket-sort-button"
                      onClick={() => toggleSort(column.key)}
                      type="button"
                    >
                      <span>{column.label}</span>
                      <DeskseedIcon name="sort" size="sm" />
                    </button>
                  ) : (
                    column.label
                  )}
                </th>
              )
            })}
          </tr>
        </thead>
        <tbody>
          {items.map((item, index) => {
            const selected = selectedTicketNumbers.has(item.ticketNumber)
            const href =
              ticketHref?.(item.ticketNumber) ??
              `/agent/tickets/${item.ticketNumber}`
            return (
              <tr
                className={selected ? 'is-selected' : ''}
                key={item.ticketNumber}
              >
                <td className="ds-queue-ticket-select-column">
                  {onSelectionChange ? (
                    <input
                      aria-label={`티켓 #${item.ticketNumber} 선택`}
                      checked={selected}
                      onChange={(event) =>
                        onSelectionChange(item.ticketNumber, {
                          orderedTicketNumbers,
                          range:
                            event.nativeEvent instanceof MouseEvent &&
                            event.nativeEvent.shiftKey,
                        })
                      }
                      type="checkbox"
                    />
                  ) : null}
                </td>
                {renderedColumns.map((column) => (
                  <TicketCell
                    column={column.key}
                    item={item}
                    key={column.key}
                    index={index}
                    href={href}
                    links={links}
                    onOpenTicket={onOpenTicket}
                    onKeyDown={handleKeyDown}
                  />
                ))}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function TicketCell({
  column,
  item,
  index,
  href,
  links,
  onOpenTicket,
  onKeyDown,
}: {
  column: QueueTicketColumn
  item: QueueTicketTableItem
  index: number
  href: string
  links: React.MutableRefObject<Array<HTMLAnchorElement | null>>
  onOpenTicket?: (ticketNumber: number) => void
  onKeyDown: (
    event: KeyboardEvent<HTMLAnchorElement>,
    index: number,
    ticketNumber: number,
  ) => void
}) {
  let content: ReactNode
  if (column === 'ticketNumber') content = <strong>#{item.ticketNumber}</strong>
  else if (column === 'requester') content = item.requester
  else if (column === 'status')
    content = (
      <DsStatusIndicator tone={statusTone(item.status)}>
        {statusLabels[item.status]}
      </DsStatusIndicator>
    )
  else if (column === 'priority')
    content = (
      <span
        className={
          item.priority === 'HIGH' || item.priority === 'URGENT'
            ? 'ds-queue-ticket-priority--attention'
            : 'ds-queue-ticket-priority'
        }
      >
        {item.priority === 'HIGH' || item.priority === 'URGENT' ? (
          <DeskseedIcon name="alertWarning" size="sm" />
        ) : null}
        {priorityLabels[item.priority]}
      </span>
    )
  else if (column === 'group') content = item.group
  else if (column === 'assignee') content = item.assignee
  else if (column === 'sla')
    content = <FirstReplySlaIndicator sla={item.sla ?? null} />
  else if (column === 'updatedAt') content = item.updatedLabel
  else
    content = (
      <a
        aria-label={`티켓 #${item.ticketNumber} ${item.subject}`}
        href={href}
        onClick={(event) => {
          if (!onOpenTicket) return
          event.preventDefault()
          onOpenTicket(item.ticketNumber)
        }}
        onKeyDown={(event) => onKeyDown(event, index, item.ticketNumber)}
        ref={(element) => {
          links.current[index] = element
        }}
        tabIndex={index === 0 ? 0 : -1}
      >
        <span>{item.subject}</span>
        {item.isChild ? <small>내부 작업</small> : null}
      </a>
    )
  return (
    <td
      className={
        column === 'group'
          ? 'ds-queue-ticket-group-column'
          : column === 'assignee'
            ? 'ds-queue-ticket-assignee-column'
            : column === 'subject'
              ? 'ds-queue-ticket-subject-column'
              : column === 'sla'
                ? 'ds-queue-ticket-sla-column'
                : undefined
      }
    >
      {content}
    </td>
  )
}
