import { forwardRef, type HTMLAttributes, type ReactNode } from 'react'
import { DsButton } from '../primitives/DeskseedControls'
import { DeskseedIcon, type IconName } from '../primitives/DeskseedIcon'

export type ScreenStateKind =
  'loading' | 'empty' | 'error' | 'denied' | 'not-found' | 'conflict' | 'stale'

type ScreenStateProps = {
  action?: ReactNode
  ariaLabel?: string
  className?: string
  compact?: boolean
  description?: string
  kind: ScreenStateKind
  requestId?: string
  title: string
}

const stateIcons: Record<ScreenStateKind, IconName> = {
  loading: 'clock',
  empty: 'inbox',
  error: 'alertWarning',
  denied: 'lock',
  'not-found': 'search',
  conflict: 'alertWarning',
  stale: 'history',
}

export function ScreenState({
  action,
  ariaLabel,
  className = '',
  compact = false,
  description,
  kind,
  requestId,
  title,
}: ScreenStateProps) {
  return (
    <section
      aria-busy={kind === 'loading' || undefined}
      aria-label={ariaLabel ?? title}
      className={`ds-screen-state ds-screen-state--${kind} ${compact ? 'ds-screen-state--compact' : ''} ${className}`.trim()}
      role={kind === 'error' || kind === 'denied' ? 'alert' : 'status'}
    >
      <span className="ds-screen-state-icon">
        <DeskseedIcon name={stateIcons[kind]} size="lg" />
      </span>
      <h2>{title}</h2>
      {description ? <p>{description}</p> : null}
      {requestId ? <small>요청 ID: {requestId}</small> : null}
      {action ? <div className="ds-screen-state-action">{action}</div> : null}
    </section>
  )
}

type NotificationProps = Omit<HTMLAttributes<HTMLElement>, 'title'> & {
  children?: ReactNode
  className?: string
  title: string
  tone: 'info' | 'success' | 'warning' | 'danger' | 'conflict'
}

export const Notification = forwardRef<HTMLElement, NotificationProps>(
  function Notification(
    { children, className = '', title, tone, ...props },
    ref,
  ) {
    return (
      <section
        {...props}
        aria-label={props['aria-label'] ?? title}
        className={`ds-notification ds-notification--${tone} ${className}`.trim()}
        ref={ref}
        role={tone === 'danger' || tone === 'conflict' ? 'alert' : 'status'}
      >
        <DeskseedIcon
          name={tone === 'success' ? 'checkCircle' : 'alertWarning'}
        />
        <div>
          <strong>{title}</strong>
          {children}
        </div>
      </section>
    )
  },
)

export function TableSkeleton({ label }: { label: string }) {
  return (
    <div aria-busy="true" aria-label={label} className="ds-table-skeleton">
      <span className="sr-only" role="status">
        {label}
      </span>
      {Array.from({ length: 6 }, (_, index) => (
        <div key={index} />
      ))}
    </div>
  )
}

export function RetryButton({ onClick }: { onClick: () => void }) {
  return <DsButton onClick={onClick}>다시 시도</DsButton>
}
