import { Link } from 'react-router'
import type { TicketPriority, TicketStatus } from '../../../api/types'
import { StatusBadge } from './StatusBadge'

export interface TicketTableItem {
  ticketNumber: number
  subject: string
  status: TicketStatus
  priority: TicketPriority
  requester: string
  group: string
  assignee: string
  updatedLabel: string
  isChild?: boolean
}

interface TicketTableProps {
  label: string
  items: TicketTableItem[]
  ticketHref?: (ticketNumber: number) => string
}

export function TicketTable({
  label,
  items,
  ticketHref = (ticketNumber) => `/agent/tickets/${ticketNumber}`,
}: TicketTableProps) {
  return (
    <div className="ticket-table-scroll">
      <table className="ticket-table" aria-label={label}>
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
          {items.map((ticket) => (
            <tr key={ticket.ticketNumber}>
              <td>
                <StatusBadge status={ticket.status} />
              </td>
              <td className="ticket-number">#{ticket.ticketNumber}</td>
              <td className="ticket-subject-cell">
                <Link
                  to={ticketHref(ticket.ticketNumber)}
                  aria-label={`#${ticket.ticketNumber} ${ticket.subject} 열기`}
                >
                  {ticket.subject}
                </Link>
                {ticket.isChild ? (
                  <span className="row-kind">
                    <span aria-hidden="true">↳</span> Child task
                  </span>
                ) : null}
              </td>
              <td>{ticket.requester}</td>
              <td>
                <span
                  className={`priority-label priority-${ticket.priority.toLowerCase()}`}
                >
                  <span className="priority-symbol" aria-hidden="true">
                    {ticket.priority === 'URGENT' ? '!' : '•'}
                  </span>{' '}
                  {ticket.priority}
                </span>
              </td>
              <td>{ticket.group}</td>
              <td>{ticket.assignee}</td>
              <td>{ticket.updatedLabel}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
