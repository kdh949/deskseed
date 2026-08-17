import { useMemo, useRef, useState, type KeyboardEvent } from 'react'
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

type QueueTicketSort = {
  direction: 'ascending' | 'descending'
  key: QueueTicketSortKey
}

const sortableColumns: Array<{ key: QueueTicketSortKey; label: string }> = [
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

type QueueTicketTableProps = {
  items: QueueTicketTableItem[]
  label: string
  onOpenTicket?: (ticketNumber: number) => void
  onSelectAll?: () => void
  onSelectionChange?: (
    ticketNumber: number,
    options: { orderedTicketNumbers: number[]; range: boolean },
  ) => void
  selectedTicketNumbers?: Set<number>
  ticketHref?: (ticketNumber: number) => string
}

export function QueueTicketTable({
  items,
  label,
  onOpenTicket,
  onSelectAll,
  onSelectionChange,
  selectedTicketNumbers = new Set<number>(),
  ticketHref,
}: QueueTicketTableProps) {
  const links = useRef<Array<HTMLAnchorElement | null>>([])
  const [sort, setSort] = useState<QueueTicketSort>({
    direction: 'descending',
    key: 'ticketNumber',
  })
  const sortedItems = useMemo(
    () => sortQueueTicketItems(items, sort),
    [items, sort],
  )
  const orderedTicketNumbers = sortedItems.map((item) => item.ticketNumber)

  const toggleSort = (key: QueueTicketSortKey) => {
    setSort((current) => ({
      key,
      direction:
        current.key === key && current.direction === 'descending'
          ? 'ascending'
          : 'descending',
    }))
  }

  const handleKeyDown = (
    event: KeyboardEvent<HTMLAnchorElement>,
    index: number,
    ticketNumber: number,
  ) => {
    let nextIndex: number | null = null
    if (event.key === 'ArrowDown')
      nextIndex = Math.min(index + 1, sortedItems.length - 1)
    if (event.key === 'ArrowUp') nextIndex = Math.max(index - 1, 0)
    if (event.key === 'Home') nextIndex = 0
    if (event.key === 'End') nextIndex = sortedItems.length - 1
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
                    sortedItems.length > 0 &&
                    sortedItems.every((item) =>
                      selectedTicketNumbers.has(item.ticketNumber),
                    )
                  }
                  onChange={onSelectAll}
                  type="checkbox"
                />
              ) : null}
            </th>
            {sortableColumns.map((column) => {
              const active = sort.key === column.key
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
                  aria-sort={active ? sort.direction : 'none'}
                  className={columnClassName}
                  key={column.key}
                  scope="col"
                >
                  <button
                    aria-label={`${column.label} ${active ? (sort.direction === 'ascending' ? '오름차순' : '내림차순') : '정렬'}`}
                    className="ds-queue-ticket-sort-button"
                    onClick={() => toggleSort(column.key)}
                    type="button"
                  >
                    <span>{column.label}</span>
                    <DeskseedIcon name="sort" size="sm" />
                  </button>
                </th>
              )
            })}
          </tr>
        </thead>
        <tbody>
          {sortedItems.map((item, index) => {
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
                <td>
                  <strong>#{item.ticketNumber}</strong>
                </td>
                <td>{item.requester}</td>
                <td>
                  <DsStatusIndicator tone={statusTone(item.status)}>
                    {statusLabels[item.status]}
                  </DsStatusIndicator>
                </td>
                <td>
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
                </td>
                <td className="ds-queue-ticket-group-column">{item.group}</td>
                <td className="ds-queue-ticket-assignee-column">
                  {item.assignee}
                </td>
                <td className="ds-queue-ticket-sla-column">
                  <FirstReplySlaIndicator sla={item.sla ?? null} />
                </td>
                <td>{item.updatedLabel}</td>
                <td className="ds-queue-ticket-subject-column">
                  <a
                    aria-label={`티켓 #${item.ticketNumber} ${item.subject}`}
                    href={href}
                    onClick={(event) => {
                      if (!onOpenTicket) return
                      event.preventDefault()
                      onOpenTicket(item.ticketNumber)
                    }}
                    onKeyDown={(event) =>
                      handleKeyDown(event, index, item.ticketNumber)
                    }
                    ref={(element) => {
                      links.current[index] = element
                    }}
                    tabIndex={index === 0 ? 0 : -1}
                  >
                    <span>{item.subject}</span>
                    {item.isChild ? <small>내부 작업</small> : null}
                  </a>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function sortQueueTicketItems(
  items: QueueTicketTableItem[],
  sort: QueueTicketSort,
) {
  const getValue = (item: QueueTicketTableItem) =>
    sort.key === 'updatedAt'
      ? (item.updatedAt ?? item.updatedLabel)
      : sort.key === 'sla'
        ? `${item.sla?.state ?? ''}:${item.sla?.dueAt ?? ''}`
        : item[sort.key]

  return [...items].sort((left, right) => {
    const leftValue = getValue(left)
    const rightValue = getValue(right)
    const comparison =
      typeof leftValue === 'number' && typeof rightValue === 'number'
        ? leftValue - rightValue
        : String(leftValue).localeCompare(String(rightValue), 'ko-KR', {
            numeric: true,
          })
    return sort.direction === 'ascending' ? comparison : -comparison
  })
}
