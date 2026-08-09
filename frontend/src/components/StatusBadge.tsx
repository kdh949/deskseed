import type { TicketStatus } from '../api/types'

const LABELS: Record<TicketStatus, string> = {
  NEW: '신규',
  OPEN: '처리 중',
  PENDING: '고객 답변 대기',
  ON_HOLD: '내부 처리 대기',
  SOLVED: '해결',
  CLOSED: '종료',
}

export function StatusBadge({ status }: { status: TicketStatus }) {
  return <span className={`status-badge status-${status.toLowerCase()}`}>{LABELS[status]}</span>
}
