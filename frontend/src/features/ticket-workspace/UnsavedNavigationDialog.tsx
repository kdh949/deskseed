import { useEffect, useRef } from 'react'

const FOCUSABLE_SELECTOR =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

export function UnsavedNavigationDialog({
  blocker,
  submitting,
}: {
  blocker: {
    state: string
    proceed?: () => void
    reset?: () => void
  }
  submitting: boolean
}) {
  const continueRef = useRef<HTMLButtonElement>(null)
  const dialogRef = useRef<HTMLElement>(null)
  const originRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
    if (blocker.state !== 'blocked') return
    originRef.current =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null
    continueRef.current?.focus()
  }, [blocker.state])

  const resetAndRestoreFocus = () => {
    const origin = originRef.current
    blocker.reset?.()
    queueMicrotask(() => origin?.focus())
  }

  const onKeyDown = (event: React.KeyboardEvent<HTMLElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      resetAndRestoreFocus()
      return
    }
    if (event.key !== 'Tab') return
    const focusable = Array.from(
      dialogRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR) ??
        [],
    )
    if (focusable.length === 0) {
      event.preventDefault()
      return
    }
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

  if (blocker.state !== 'blocked') return null
  return (
    <div className="navigation-dialog-backdrop">
      <section
        ref={dialogRef}
        className="navigation-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="unsaved-navigation-title"
        onKeyDown={onKeyDown}
      >
        <h2 id="unsaved-navigation-title">저장하지 않은 변경사항</h2>
        <p>
          {submitting
            ? '저장이 끝날 때까지 이 티켓을 떠날 수 없습니다.'
            : '초안은 이 브라우저에 유지됩니다. 지금 티켓을 떠날까요?'}
        </p>
        <div>
          <button
            ref={continueRef}
            className="compact-button"
            type="button"
            onClick={resetAndRestoreFocus}
          >
            계속 편집
          </button>
          {!submitting ? (
            <button
              className="button primary"
              type="button"
              onClick={blocker.proceed}
            >
              초안 유지하고 나가기
            </button>
          ) : null}
        </div>
      </section>
    </div>
  )
}
