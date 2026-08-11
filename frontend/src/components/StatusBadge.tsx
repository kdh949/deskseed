import type { AgentTicketStatus } from '../api/types'

const LABELS: Record<AgentTicketStatus, string> = {
  NEW: '신규',
  OPEN: '처리 중',
  PENDING: '고객 답변 대기',
  ON_HOLD: '보류',
  SOLVED: '해결',
  CLOSED: '종료',
}

export function StatusBadge({ status }: { status: AgentTicketStatus }) {
  return (
    <span className={`status-badge status-${status.toLowerCase()}`}>
      {LABELS[status]}
    </span>
  )
}
