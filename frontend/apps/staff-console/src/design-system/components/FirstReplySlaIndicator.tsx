import type { FirstReplySlaBadge } from '../../api/types'
import { DeskseedIcon, type IconName } from '../primitives/DeskseedIcon'

export type FirstReplySlaIndicatorProps = {
  detail?: boolean
  sla: FirstReplySlaBadge | null
}

const SLA_PRESENTATION: Record<
  FirstReplySlaBadge['state'],
  { icon: IconName; label: string; tone: string }
> = {
  ACTIVE: { icon: 'clock', label: '진행 중', tone: 'active' },
  AT_RISK: { icon: 'alertWarning', label: '위험', tone: 'warning' },
  PAUSED: { icon: 'pause', label: '일시 정지', tone: 'paused' },
  ACHIEVED: { icon: 'checkCircle', label: '달성', tone: 'success' },
  BREACHED: { icon: 'alertWarning', label: '위반', tone: 'danger' },
  CANCELLED: { icon: 'x', label: '취소', tone: 'muted' },
  NO_POLICY: { icon: 'info', label: '정책 없음', tone: 'muted' },
}

export function FirstReplySlaIndicator({
  detail = false,
  sla,
}: FirstReplySlaIndicatorProps) {
  if (!sla) {
    return (
      <span className="ds-sla-indicator ds-sla-indicator--muted">
        <DeskseedIcon name="info" size="sm" />
        <span>정보 없음</span>
      </span>
    )
  }

  const presentation = SLA_PRESENTATION[sla.state]
  return (
    <span
      aria-label={`최초 답변 SLA ${presentation.label}`}
      className={`ds-sla-indicator ds-sla-indicator--${presentation.tone}`}
    >
      <span className="ds-sla-indicator__state">
        <DeskseedIcon name={presentation.icon} size="sm" />
        <span>{presentation.label}</span>
      </span>
      <span className="ds-sla-indicator__due">
        {sla.dueAt ? `기한 ${formatDueAt(sla.dueAt)}` : '기한 없음'}
      </span>
      {detail ? (
        <span className="ds-sla-indicator__detail">
          <span>목표 {formatTarget(sla.targetMinutes)}</span>
          <span>정책 v{sla.policyVersion ?? '-'}</span>
          <span>일정 v{sla.scheduleVersion ?? '-'}</span>
        </span>
      ) : null}
    </span>
  )
}

function formatDueAt(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(value))
}

function formatTarget(minutes: number | null) {
  if (minutes === null) return '-'
  if (minutes < 60) return `${minutes}분`
  if (minutes % 60 === 0) return `${minutes / 60}시간`
  return `${Math.floor(minutes / 60)}시간 ${minutes % 60}분`
}
