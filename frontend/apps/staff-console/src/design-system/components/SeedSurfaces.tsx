import { useEffect, useId, useRef, type ReactNode, type RefObject } from 'react'
import {
  SeedButton,
  SeedIcon,
  SeedIconButton,
  type SeedIconName,
} from '../primitives/SeedCore'

export type SeedTone = 'neutral' | 'info' | 'positive' | 'warning' | 'danger'

export function SeedStatusBadge({
  children,
  tone = 'neutral',
}: {
  children: ReactNode
  tone?: SeedTone
}) {
  return <span className={`seed-status seed-status--${tone}`}>{children}</span>
}

export function SeedSlaMeter({
  label,
  detail,
  percent,
  tone = 'positive',
}: {
  label: string
  detail: string
  percent: number
  tone?: 'positive' | 'warning' | 'danger' | 'neutral'
}) {
  const safePercent = Math.max(0, Math.min(100, percent))
  return (
    <div className={`seed-sla seed-sla--${tone}`}>
      <span className="seed-sla__copy">
        <span>
          <SeedIcon name="clock" size="small" /> {label}
        </span>
        <strong>{detail}</strong>
      </span>
      <span
        aria-label={`${label} ${detail}`}
        aria-valuemax={100}
        aria-valuemin={0}
        aria-valuenow={safePercent}
        className="seed-sla__track"
        role="progressbar"
      >
        <span style={{ width: `${safePercent}%` }} />
      </span>
    </div>
  )
}

const noticeIcon: Record<SeedTone, SeedIconName> = {
  neutral: 'check',
  info: 'check',
  positive: 'check',
  warning: 'alert',
  danger: 'alert',
}

export function SeedNotice({
  title,
  children,
  tone = 'info',
  action,
  onDismiss,
}: {
  title: string
  children?: ReactNode
  tone?: SeedTone
  action?: ReactNode
  onDismiss?: () => void
}) {
  return (
    <section
      aria-live={tone === 'danger' ? 'assertive' : 'polite'}
      className={`seed-notice seed-notice--${tone}`}
      role={tone === 'danger' ? 'alert' : 'status'}
    >
      <SeedIcon name={noticeIcon[tone]} />
      <span className="seed-notice__copy">
        <strong>{title}</strong>
        {children && <span>{children}</span>}
      </span>
      {action && <span className="seed-notice__action">{action}</span>}
      {onDismiss && (
        <SeedButton aria-label="알림 닫기" onClick={onDismiss} variant="quiet">
          <SeedIcon name="x" />
        </SeedButton>
      )}
    </section>
  )
}

export function SeedFeedbackState({
  title,
  description,
  kind,
  action,
  compact = false,
}: {
  title: string
  description?: string
  kind:
    | 'loading'
    | 'empty'
    | 'error'
    | 'denied'
    | 'not-found'
    | 'conflict'
    | 'stale'
  action?: ReactNode
  compact?: boolean
}) {
  const icon: Record<typeof kind, SeedIconName> = {
    loading: 'refresh',
    empty: 'search',
    error: 'alert',
    denied: 'lock',
    'not-found': 'search',
    conflict: 'alert',
    stale: 'refresh',
  }
  return (
    <section
      aria-live={kind === 'loading' ? 'polite' : undefined}
      className={`seed-feedback${compact ? ' seed-feedback--compact' : ''}`}
      role={
        kind === 'error' || kind === 'denied' || kind === 'conflict'
          ? 'alert'
          : kind === 'loading'
            ? 'status'
            : undefined
      }
    >
      <span className={`seed-feedback__icon seed-feedback__icon--${kind}`}>
        <SeedIcon name={icon[kind]} size="large" />
      </span>
      {kind === 'loading' && (
        <span className="seed-spinner" aria-hidden="true" />
      )}
      <h2>{title}</h2>
      {description && <p>{description}</p>}
      {action && <div className="seed-feedback__actions">{action}</div>}
    </section>
  )
}

export function SeedContextCard({
  title,
  badge,
  action,
  children,
}: {
  title: string
  badge?: ReactNode
  action?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="seed-context-card">
      <header>
        <h2>{title}</h2>
        {badge}
        {action && <span className="seed-context-card__action">{action}</span>}
      </header>
      <div className="seed-context-card__body">{children}</div>
    </section>
  )
}

export function SeedDrawer({
  children,
  description,
  onClose,
  open,
  returnFocusRef,
  title,
}: {
  children: ReactNode
  description?: string
  onClose: () => void
  open: boolean
  returnFocusRef?: RefObject<HTMLElement>
  title: string
}) {
  const titleId = useId()
  const descriptionId = useId()
  const panelRef = useRef<HTMLElement>(null)
  const fallbackReturnFocusRef = useRef<HTMLElement | null>(null)
  useEffect(() => {
    if (!open) return
    if (document.activeElement instanceof HTMLElement) {
      fallbackReturnFocusRef.current = document.activeElement
    }
    panelRef.current?.focus()
    const closeFromKeyboard = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        onClose()
        return
      }
      if (event.key === 'Tab' && panelRef.current) {
        const focusable = Array.from(
          panelRef.current.querySelectorAll<HTMLElement>(
            'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])',
          ),
        )
        if (!focusable.length) {
          event.preventDefault()
          panelRef.current.focus()
          return
        }
        const first = focusable[0]
        const last = focusable.at(-1)
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault()
          last?.focus()
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault()
          first?.focus()
        }
      }
    }
    window.addEventListener('keydown', closeFromKeyboard)
    return () => {
      window.removeEventListener('keydown', closeFromKeyboard)
      const focusTarget =
        returnFocusRef?.current ?? fallbackReturnFocusRef.current
      focusTarget?.focus()
      fallbackReturnFocusRef.current = null
    }
  }, [onClose, open, returnFocusRef])
  if (!open) return null
  return (
    <div className="seed-drawer-layer">
      <button
        aria-label="패널 닫기"
        className="seed-drawer-layer__scrim"
        onClick={onClose}
        type="button"
      />
      <section
        aria-describedby={description ? descriptionId : undefined}
        aria-labelledby={titleId}
        aria-modal="true"
        className="seed-drawer"
        ref={panelRef}
        role="dialog"
        tabIndex={-1}
      >
        <header>
          <div>
            <h2 id={titleId}>{title}</h2>
            {description && <p id={descriptionId}>{description}</p>}
          </div>
          <SeedIconButton
            icon="x"
            label="닫기"
            onClick={onClose}
            variant="quiet"
          />
        </header>
        <div className="seed-drawer__body">{children}</div>
      </section>
    </div>
  )
}

export function SeedSkeletonRows({
  columns = 6,
  rows = 5,
  label = '콘텐츠 불러오는 중',
}: {
  columns?: number
  rows?: number
  label?: string
}) {
  return (
    <div aria-label={label} className="seed-skeleton" role="status">
      {Array.from({ length: rows }, (_, row) => (
        <span className="seed-skeleton__row" key={row}>
          {Array.from({ length: columns }, (_, column) => (
            <span key={column} />
          ))}
        </span>
      ))}
    </div>
  )
}
