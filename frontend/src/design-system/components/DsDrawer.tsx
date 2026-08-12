import {
  useEffect,
  useId,
  useRef,
  type KeyboardEvent,
  type ReactNode,
  type RefObject,
} from 'react'
import { DeskseedIcon } from '../primitives/DeskseedIcon'

type DsDrawerProps = {
  children: ReactNode
  description?: string
  onClose: () => void
  open: boolean
  returnFocusRef?: RefObject<HTMLElement | null>
  title: string
}

function focusableElements(container: HTMLElement) {
  return Array.from(
    container.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ),
  )
}

export function DsDrawer({
  children,
  description,
  onClose,
  open,
  returnFocusRef,
  title,
}: DsDrawerProps) {
  const panelRef = useRef<HTMLElement | null>(null)
  const closeRef = useRef<HTMLButtonElement | null>(null)
  const previousFocusRef = useRef<HTMLElement | null>(null)
  const titleId = useId()
  const descriptionId = useId()

  useEffect(() => {
    if (!open) return
    previousFocusRef.current = document.activeElement as HTMLElement | null
    const focusTimer = window.setTimeout(() => closeRef.current?.focus())
    return () => {
      window.clearTimeout(focusTimer)
      const returnTarget = returnFocusRef?.current ?? previousFocusRef.current
      returnTarget?.focus()
      previousFocusRef.current = null
    }
  }, [open, returnFocusRef])

  if (!open) return null

  const handleKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      onClose()
      return
    }
    if (event.key !== 'Tab') return

    const elements = panelRef.current ? focusableElements(panelRef.current) : []
    if (!elements.length) {
      event.preventDefault()
      return
    }
    const first = elements[0]
    const last = elements.at(-1)
    if (!first || !last) return
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    }
    if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  return (
    <div className="ds-drawer-backdrop" onMouseDown={onClose}>
      <section
        aria-describedby={description ? descriptionId : undefined}
        aria-labelledby={titleId}
        aria-modal="true"
        className="ds-drawer"
        onKeyDown={handleKeyDown}
        onMouseDown={(event) => event.stopPropagation()}
        ref={panelRef}
        role="dialog"
      >
        <header className="ds-drawer-header">
          <div>
            <h2 id={titleId}>{title}</h2>
            {description ? <p id={descriptionId}>{description}</p> : null}
          </div>
          <button
            aria-label={`${title} 닫기`}
            className="ds-drawer-close"
            onClick={onClose}
            ref={closeRef}
            type="button"
          >
            <DeskseedIcon name="x" />
          </button>
        </header>
        <div className="ds-drawer-body">{children}</div>
      </section>
    </div>
  )
}
