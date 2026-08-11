import {
  useEffect,
  useId,
  useRef,
  type KeyboardEvent,
  type ReactNode,
  type RefObject,
} from 'react'

export function TicketActionDialogFrame({
  title,
  description,
  initialFocusRef,
  onClose,
  children,
}: {
  title: string
  description: string
  initialFocusRef: RefObject<HTMLElement | null>
  onClose: () => void
  children: ReactNode
}) {
  const titleId = useId()
  const descriptionId = useId()
  const dialogRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const restoreFocus =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null
    initialFocusRef.current?.focus()
    return () => restoreFocus?.focus()
  }, [initialFocusRef])

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      onClose()
      return
    }
    if (event.key !== 'Tab') return
    const focusable = Array.from(
      dialogRef.current?.querySelectorAll<HTMLElement>(
        'button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), a[href]',
      ) ?? [],
    )
    if (!focusable.length) return
    const first = focusable[0]!
    const last = focusable[focusable.length - 1]!
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  return (
    <div
      className="ticket-action-dialog-backdrop"
      onMouseDown={(event) => {
        if (event.currentTarget === event.target) onClose()
      }}
    >
      <div
        ref={dialogRef}
        className="ticket-action-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
        onKeyDown={handleKeyDown}
      >
        <header>
          <div>
            <p className="agent-page-eyebrow">OWNERSHIP &amp; COLLABORATION</p>
            <h2 id={titleId}>{title}</h2>
          </div>
          <button
            className="compact-button"
            type="button"
            aria-label={`${title} 닫기`}
            onClick={onClose}
          >
            닫기
          </button>
        </header>
        <p id={descriptionId} className="ticket-action-dialog-description">
          {description}
        </p>
        {children}
      </div>
    </div>
  )
}

export function createTicketCommandId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  const bytes = new Uint8Array(16)
  globalThis.crypto?.getRandomValues?.(bytes)
  bytes[6] = (bytes[6]! & 0x0f) | 0x40
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) =>
    byte.toString(16).padStart(2, '0'),
  ).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
