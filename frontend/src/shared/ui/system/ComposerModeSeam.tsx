import { useId, useState } from 'react'

export type ComposerMode = 'PUBLIC' | 'INTERNAL'

interface ComposerModeSeamProps {
  initialMode?: ComposerMode
  disabledReason?: string
}

/**
 * Local-only seam for the later command composer. It deliberately has no
 * network submit behavior in this read-only PR.
 */
export function ComposerModeSeam({
  initialMode = 'PUBLIC',
  disabledReason,
}: ComposerModeSeamProps) {
  const [mode, setMode] = useState<ComposerMode>(initialMode)
  const [drafts, setDrafts] = useState({ PUBLIC: '', INTERNAL: '' })
  const statusId = useId()
  const isInternal = mode === 'INTERNAL'

  return (
    <section
      className={`composer-mode-seam mode-${mode.toLowerCase()}`}
      aria-labelledby={`${statusId}-title`}
    >
      <header>
        <div>
          <p className="agent-page-eyebrow">COMPOSER MODE</p>
          <h2 id={`${statusId}-title`}>답변 작성 모드</h2>
        </div>
        <div
          className="composer-mode-tabs"
          role="tablist"
          aria-label="답변 공개 범위"
        >
          <button
            type="button"
            role="tab"
            aria-selected={!isInternal}
            aria-controls={`${statusId}-draft`}
            onClick={() => setMode('PUBLIC')}
          >
            <span aria-hidden="true">↗</span> 공개 답변
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={isInternal}
            aria-controls={`${statusId}-draft`}
            onClick={() => setMode('INTERNAL')}
          >
            <span aria-hidden="true">◆</span> 내부 메모
          </button>
        </div>
      </header>
      <p
        id={statusId}
        className="composer-mode-announcement"
        role="status"
        aria-live="polite"
      >
        {isInternal
          ? '내부 메모 모드입니다. 이 내용은 고객에게 공개되지 않습니다.'
          : '공개 답변 모드입니다. 이 내용은 고객에게 표시됩니다.'}
      </p>
      <label htmlFor={`${statusId}-draft`}>
        {isInternal ? '내부 메모' : '공개 답변'}
      </label>
      <textarea
        id={`${statusId}-draft`}
        aria-describedby={statusId}
        value={drafts[mode]}
        onChange={(event) =>
          setDrafts((current) => ({ ...current, [mode]: event.target.value }))
        }
        disabled={Boolean(disabledReason)}
      />
      {disabledReason ? (
        <p className="composer-disabled-reason">{disabledReason}</p>
      ) : null}
    </section>
  )
}
