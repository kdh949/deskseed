import { useEffect, useRef } from 'react'

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

  useEffect(() => {
    if (blocker.state === 'blocked') continueRef.current?.focus()
  }, [blocker.state])

  if (blocker.state !== 'blocked') return null
  return (
    <div className="navigation-dialog-backdrop">
      <section
        className="navigation-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="unsaved-navigation-title"
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
            onClick={blocker.reset}
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
