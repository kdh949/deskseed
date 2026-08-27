import {
  forwardRef,
  type ButtonHTMLAttributes,
  type HTMLAttributes,
  type ReactNode,
} from 'react'
import brandMark from '../assets/deskseed/brand-mark-transparent-v2.png'
import { CustomerIcon, type CustomerIconName } from './CustomerIcon'

export type CustomerButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  tone?: 'primary' | 'secondary' | 'ghost' | 'danger'
  icon?: CustomerIconName
}

export function DsButton({
  children,
  className = '',
  icon,
  tone = 'secondary',
  ...props
}: CustomerButtonProps) {
  return (
    <button
      {...props}
      className={`customer-button customer-button--${tone} ${className}`.trim()}
      type={props.type ?? 'button'}
    >
      {icon ? <CustomerIcon name={icon} /> : null}
      {children}
    </button>
  )
}

export function CustomerBrand() {
  return (
    <span className="customer-brand">
      <img alt="" src={brandMark} />
      <strong>DeskSeed</strong>
    </span>
  )
}

export type ScreenStateKind =
  'loading' | 'empty' | 'error' | 'denied' | 'not-found' | 'conflict' | 'stale'

const stateIcons: Record<ScreenStateKind, CustomerIconName> = {
  loading: 'clock',
  empty: 'inbox',
  error: 'alert',
  denied: 'lock',
  'not-found': 'search',
  conflict: 'alert',
  stale: 'reload',
}

export function ScreenState({
  action,
  className = '',
  compact = false,
  description,
  kind,
  requestId,
  title,
}: {
  action?: ReactNode
  className?: string
  compact?: boolean
  description?: string
  kind: ScreenStateKind
  requestId?: string
  title: string
}) {
  return (
    <section
      aria-busy={kind === 'loading' || undefined}
      className={`customer-screen-state customer-screen-state--${kind} ${compact ? 'customer-screen-state--compact' : ''} ${className}`.trim()}
      role={kind === 'error' || kind === 'denied' ? 'alert' : 'status'}
    >
      <span className="customer-screen-state__icon">
        <CustomerIcon name={stateIcons[kind]} size="lg" />
      </span>
      <h2>{title}</h2>
      {description ? <p>{description}</p> : null}
      {requestId ? <small>요청 ID: {requestId}</small> : null}
      {action ? (
        <div className="customer-screen-state__action">{action}</div>
      ) : null}
    </section>
  )
}

export const Notification = forwardRef<
  HTMLElement,
  {
    children?: ReactNode
    className?: string
    title: string
    tone: 'info' | 'success' | 'warning' | 'danger' | 'conflict'
  } & Omit<HTMLAttributes<HTMLElement>, 'title'>
>(function Notification(
  { children, className = '', title, tone, ...props },
  ref,
) {
  return (
    <section
      {...props}
      className={`customer-notification customer-notification--${tone} ${className}`.trim()}
      ref={ref}
      role={tone === 'danger' || tone === 'conflict' ? 'alert' : 'status'}
    >
      <CustomerIcon name={tone === 'success' ? 'check' : 'alert'} />
      <div>
        <strong>{title}</strong>
        {children}
      </div>
    </section>
  )
})

export function RetryButton({ onClick }: { onClick: () => void }) {
  return (
    <DsButton icon="reload" onClick={onClick}>
      다시 시도
    </DsButton>
  )
}

export function DsStatusIndicator({
  children,
  tone,
}: {
  children: ReactNode
  tone: 'new' | 'open' | 'pending' | 'onHold' | 'solved' | 'high'
}) {
  return (
    <span className={`customer-status customer-status--${tone}`}>
      <span aria-hidden="true" />
      {children}
    </span>
  )
}
