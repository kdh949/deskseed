import { useRef, type KeyboardEvent } from 'react'
import type { AuditActivity, AuditOutcome } from '../../api/types'
import { DeskseedIcon, DsStatusIndicator } from '../../design-system'

const outcomeLabels: Record<AuditOutcome, string> = {
  SUCCEEDED: '성공',
  DENIED: '거부',
  FAILED: '실패',
}

function outcomeTone(outcome: AuditOutcome) {
  if (outcome === 'SUCCEEDED') return 'solved' as const
  if (outcome === 'DENIED') return 'onHold' as const
  return 'high' as const
}

const actorTypeLabels: Record<AuditActivity['actor']['type'], string> = {
  CUSTOMER: '고객',
  STAFF: '직원',
  INTEGRATION_CLIENT: '연동 클라이언트',
  TRIGGER: '트리거',
  AUTOMATION: '자동화',
  SYSTEM: '시스템',
}

type AuditActivityTableProps = {
  items: AuditActivity[]
  label: string
  onOpenActivity: (activityId: string) => void
}

export function AuditActivityTable({
  items,
  label,
  onOpenActivity,
}: AuditActivityTableProps) {
  const links = useRef<Array<HTMLAnchorElement | null>>([])

  const handleKeyDown = (
    event: KeyboardEvent<HTMLAnchorElement>,
    index: number,
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
    }
  }

  return (
    <div className="ds-audit-activity-table-wrap">
      <table
        aria-label={`${label} 감사 활동`}
        className="ds-audit-activity-table"
      >
        <caption className="sr-only">{label} 감사 활동 목록</caption>
        <thead>
          <tr>
            <th scope="col">시각</th>
            <th scope="col">액터</th>
            <th scope="col">액션</th>
            <th scope="col">리소스</th>
            <th scope="col">결과</th>
            <th scope="col">출처 · 요청 ID</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item, index) => (
            <tr key={item.id}>
              <td>{formatOccurredAt(item.occurredAt)}</td>
              <td>
                <span className="ds-audit-activity-actor">
                  {item.actor.displayName}
                  <small>{actorTypeLabels[item.actor.type]}</small>
                </span>
              </td>
              <td>
                <a
                  aria-label={`${item.action} 상세 보기`}
                  href={`#${item.id}`}
                  onClick={(event) => {
                    event.preventDefault()
                    onOpenActivity(item.id)
                  }}
                  onKeyDown={(event) => handleKeyDown(event, index)}
                  ref={(element) => {
                    links.current[index] = element
                  }}
                  tabIndex={index === 0 ? 0 : -1}
                >
                  {item.action}
                </a>
              </td>
              <td>{resourceLabel(item)}</td>
              <td>
                <DsStatusIndicator tone={outcomeTone(item.outcome)}>
                  {outcomeLabels[item.outcome]}
                </DsStatusIndicator>
                {item.protectedContentAvailable ? (
                  <span
                    className="ds-audit-activity-protected"
                    title="보호된 내용 포함"
                  >
                    <DeskseedIcon name="lock" size="sm" />
                  </span>
                ) : null}
              </td>
              <td>
                <small>
                  {item.source}
                  {item.requestId ? ` · ${item.requestId}` : ''}
                </small>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function resourceLabel(item: AuditActivity) {
  if (item.ticketNumber !== null) return `티켓 #${item.ticketNumber}`
  if (item.resourceType) return item.resourceType
  return '—'
}

function formatOccurredAt(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}
