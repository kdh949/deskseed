import type { ReactNode } from 'react'

export type FeedbackTone =
  'info' | 'success' | 'warning' | 'danger' | 'conflict'

const ICONS: Record<FeedbackTone, string> = {
  info: 'i',
  success: '✓',
  warning: '!',
  danger: '×',
  conflict: '↔',
}

interface NotificationProps {
  tone: FeedbackTone
  title: string
  children?: ReactNode
  action?: ReactNode
  className?: string
}

export function Notification({
  tone,
  title,
  children,
  action,
  className = '',
}: NotificationProps) {
  const urgent = tone === 'danger' || tone === 'conflict'
  return (
    <div
      className={`ds-notification tone-${tone} ${className}`.trim()}
      role={urgent ? 'alert' : 'status'}
    >
      <span className="ds-notification-icon" aria-hidden="true">
        {ICONS[tone]}
      </span>
      <div className="ds-notification-content">
        <strong>{title}</strong>
        {children}
      </div>
      {action ? <div className="ds-notification-action">{action}</div> : null}
    </div>
  )
}

export type ScreenStateKind =
  'loading' | 'empty' | 'error' | 'denied' | 'not-found' | 'conflict' | 'stale'

const STATE_META: Record<
  ScreenStateKind,
  { icon: string; label: string; urgent: boolean }
> = {
  loading: { icon: '…', label: '불러오는 중', urgent: false },
  empty: { icon: '○', label: '표시할 내용 없음', urgent: false },
  error: { icon: '×', label: '오류', urgent: true },
  denied: { icon: '⊘', label: '접근 거부', urgent: true },
  'not-found': { icon: '?', label: '찾을 수 없음', urgent: false },
  conflict: { icon: '↔', label: '변경 충돌', urgent: true },
  stale: { icon: '↻', label: '최신 정보 필요', urgent: false },
}

interface ScreenStateProps {
  kind: ScreenStateKind
  title: string
  description?: ReactNode
  requestId?: string
  action?: ReactNode
  compact?: boolean
  className?: string
}

export function ScreenState({
  kind,
  title,
  description,
  requestId,
  action,
  compact = false,
  className = '',
}: ScreenStateProps) {
  const meta = STATE_META[kind]
  return (
    <section
      className={`ds-screen-state state-${kind}${compact ? ' is-compact' : ''} ${className}`.trim()}
      role={meta.urgent ? 'alert' : 'status'}
      aria-busy={kind === 'loading' ? true : undefined}
    >
      <span className="ds-screen-state-icon" aria-hidden="true">
        {meta.icon}
      </span>
      <p className="ds-screen-state-label">{meta.label}</p>
      <h2>{title}</h2>
      {description ? (
        <div className="ds-screen-state-description">{description}</div>
      ) : null}
      {requestId ? (
        <p className="ds-request-id">
          요청 ID <code>{requestId}</code>
        </p>
      ) : null}
      {action ? <div className="ds-screen-state-action">{action}</div> : null}
    </section>
  )
}

export function TableSkeleton({ label }: { label: string }) {
  return (
    <div
      className="ticket-table-skeleton"
      role="status"
      aria-label={label}
      aria-busy="true"
    >
      <span />
      <span />
      <span />
      <span />
    </div>
  )
}
