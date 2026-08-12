import { DsStatusIndicator } from '../primitives/DeskseedPrimitives'

type Status = 'NEW' | 'OPEN' | 'PENDING' | 'ON_HOLD' | 'SOLVED' | 'CLOSED'

const labels: Record<Status, string> = {
  NEW: '신규',
  OPEN: '처리 중',
  PENDING: '고객 답변 대기',
  ON_HOLD: '보류',
  SOLVED: '해결',
  CLOSED: '종료',
}

export function StatusBadge({ status }: { status: Status }) {
  const tone =
    status === 'NEW'
      ? 'new'
      : status === 'OPEN'
        ? 'open'
        : status === 'PENDING' || status === 'ON_HOLD'
          ? 'pending'
          : 'solved'
  return <DsStatusIndicator tone={tone}>{labels[status]}</DsStatusIndicator>
}
