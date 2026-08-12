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
  eyebrow = 'OWNERSHIP & COLLABORATION',
  initialFocusRef,
  returnFocusRef,
  busy = false,
  onClose,
  children,
}: {
  title: string
  description: string
  eyebrow?: string
  initialFocusRef: RefObject<HTMLElement | null>
  returnFocusRef?: RefObject<HTMLElement | null>
  busy?: boolean
  onClose: () => void
  children: ReactNode
}) {
  const titleId = useId()
  const descriptionId = useId()
  const dialogRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const restoreFocus =
      returnFocusRef?.current ??
      (document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null)
    initialFocusRef.current?.focus()
    return () => restoreFocus?.focus()
  }, [initialFocusRef, returnFocusRef])

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    const containFocus = (event: FocusEvent) => {
      if (!(event.target instanceof Node) || dialog.contains(event.target)) {
        return
      }
      if (busy) {
        dialog.focus()
        return
      }
      const first = focusableElements(dialog)[0]
      if (first) first.focus()
      else dialog.focus()
    }
    document.addEventListener('focusin', containFocus)

    const activeElement = document.activeElement
    if (
      busy &&
      (!dialog.contains(activeElement) ||
        (activeElement instanceof HTMLElement &&
          activeElement.matches(':disabled')))
    ) {
      dialog.focus()
    }
    return () => document.removeEventListener('focusin', containFocus)
  }, [busy])

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      if (!busy) onClose()
      return
    }
    if (event.key !== 'Tab') return
    const dialog = dialogRef.current
    if (!dialog) return
    const focusable = focusableElements(dialog)
    if (!focusable.length) {
      event.preventDefault()
      dialog.focus()
      return
    }
    const first = focusable[0]!
    const last = focusable[focusable.length - 1]!
    if (!focusable.includes(document.activeElement as HTMLElement)) {
      event.preventDefault()
      ;(event.shiftKey ? last : first).focus()
    } else if (event.shiftKey && document.activeElement === first) {
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
        if (!busy && event.currentTarget === event.target) onClose()
      }}
    >
      <div
        ref={dialogRef}
        className="ticket-action-dialog"
        role="dialog"
        tabIndex={-1}
        aria-modal="true"
        aria-busy={busy || undefined}
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
        onKeyDown={handleKeyDown}
      >
        <header>
          <div>
            <p className="agent-page-eyebrow">{eyebrow}</p>
            <h2 id={titleId}>{title}</h2>
          </div>
          <button
            className="compact-button"
            type="button"
            aria-label={`${title} 닫기`}
            disabled={busy}
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

function focusableElements(dialog: HTMLElement) {
  return Array.from(
    dialog.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), a[href]',
    ),
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
