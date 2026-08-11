import { Link } from 'react-router'
import type { AgentTicketStatus } from '../../../api/types'
import { StatusBadge } from './StatusBadge'

interface TicketTabsProps {
  backTo: string
  backLabel: string
  ticketNumber: number
  subject: string
  status: AgentTicketStatus
  onRefresh: () => void
  refreshing?: boolean
  unsaved?: boolean
}

export function TicketTabs({
  backTo,
  backLabel,
  ticketNumber,
  subject,
  status,
  onRefresh,
  refreshing = false,
  unsaved = false,
}: TicketTabsProps) {
  return (
    <header className="ticket-tabbar">
      <Link className="ticket-back-link" to={backTo} aria-label={backLabel}>
        <span aria-hidden="true">←</span>
      </Link>
      <div className="ticket-tabs-list" role="tablist" aria-label="열린 티켓">
        <div
          className="active-ticket-tab"
          role="tab"
          aria-selected="true"
          aria-label={`티켓 #${ticketNumber} ${subject}${unsaved ? ' 저장하지 않은 변경사항' : ''}`}
          tabIndex={0}
        >
          <StatusBadge status={status} />
          <span>#{ticketNumber}</span>
          <strong>{subject}</strong>
          {unsaved ? (
            <span className="ticket-unsaved-indicator">
              <span aria-hidden="true">●</span> 미저장
            </span>
          ) : null}
        </div>
      </div>
      <button className="compact-button" type="button" onClick={onRefresh}>
        티켓 새로고침
      </button>
      {refreshing ? (
        <span className="sr-only" role="status">
          티켓 최신 정보 확인 중
        </span>
      ) : null}
    </header>
  )
}
