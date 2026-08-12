import {
  useId,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactNode,
} from 'react'

export type ComposerMode = 'PUBLIC' | 'INTERNAL'

interface ComposerModeSeamProps {
  initialMode?: ComposerMode
  disabledReason?: string
  mode?: ComposerMode
  drafts?: Record<ComposerMode, string>
  onModeChange?: (mode: ComposerMode) => void
  onDraftChange?: (mode: ComposerMode, value: string) => void
  footer?: ReactNode
  busy?: boolean
  availableModes?: ComposerMode[]
}

const MODES = [
  { value: 'PUBLIC', label: '공개 답변', icon: '↗' },
  { value: 'INTERNAL', label: '내부 메모', icon: '◆' },
] as const

export function ComposerModeSeam({
  initialMode = 'PUBLIC',
  disabledReason,
  mode: controlledMode,
  drafts: controlledDrafts,
  onModeChange,
  onDraftChange,
  footer,
  busy = false,
  availableModes = ['PUBLIC', 'INTERNAL'],
}: ComposerModeSeamProps) {
  const [localMode, setLocalMode] = useState<ComposerMode>(initialMode)
  const [localDrafts, setLocalDrafts] = useState({ PUBLIC: '', INTERNAL: '' })
  const configuredModes = MODES.filter((option) =>
    availableModes.includes(option.value),
  )
  const modes = configuredModes.length ? configuredModes : MODES
  const requestedMode = controlledMode ?? localMode
  const mode = modes.some((option) => option.value === requestedMode)
    ? requestedMode
    : modes[0]!.value
  const drafts = controlledDrafts ?? localDrafts
  const baseId = useId()
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([])
  const statusId = `${baseId}-status`
  const isInternal = mode === 'INTERNAL'

  const tabId = (nextMode: ComposerMode) => `${baseId}-tab-${nextMode}`
  const panelId = (nextMode: ComposerMode) => `${baseId}-panel-${nextMode}`
  const draftId = (nextMode: ComposerMode) => `${baseId}-draft-${nextMode}`

  const selectMode = (nextMode: ComposerMode) => {
    if (busy) return
    if (controlledMode === undefined) setLocalMode(nextMode)
    onModeChange?.(nextMode)
  }

  const updateDraft = (nextMode: ComposerMode, value: string) => {
    if (controlledDrafts === undefined) {
      setLocalDrafts((current) => ({ ...current, [nextMode]: value }))
    }
    onDraftChange?.(nextMode, value)
  }

  const moveTab = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    if (busy) return
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    const last = modes.length - 1
    const nextIndex =
      event.key === 'Home'
        ? 0
        : event.key === 'End'
          ? last
          : event.key === 'ArrowRight'
            ? (index + 1) % modes.length
            : (index - 1 + modes.length) % modes.length
    const nextMode = modes[nextIndex]
    if (!nextMode) return
    selectMode(nextMode.value)
    tabRefs.current[nextIndex]?.focus()
  }

  return (
    <section
      className={`composer-mode-seam mode-${mode.toLowerCase()}`}
      aria-labelledby={`${baseId}-title`}
    >
      <header>
        <div>
          <p className="agent-page-eyebrow">COMPOSER MODE</p>
          <h2 id={`${baseId}-title`}>답변 작성 모드</h2>
        </div>
        <div
          className="composer-mode-tabs"
          role="tablist"
          aria-label="답변 공개 범위"
          aria-orientation="horizontal"
        >
          {modes.map((option, index) => {
            const selected = mode === option.value
            return (
              <button
                key={option.value}
                ref={(element) => {
                  tabRefs.current[index] = element
                }}
                id={tabId(option.value)}
                type="button"
                role="tab"
                aria-selected={selected}
                aria-controls={panelId(option.value)}
                tabIndex={selected ? 0 : -1}
                disabled={busy}
                onClick={() => selectMode(option.value)}
                onKeyDown={(event) => moveTab(event, index)}
              >
                <span aria-hidden="true">{option.icon}</span> {option.label}
              </button>
            )
          })}
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
      {modes.map((option) => {
        const selected = mode === option.value
        return (
          <div
            key={option.value}
            id={panelId(option.value)}
            role="tabpanel"
            aria-labelledby={tabId(option.value)}
            hidden={!selected}
          >
            <label htmlFor={draftId(option.value)}>{option.label}</label>
            <textarea
              id={draftId(option.value)}
              aria-describedby={statusId}
              value={drafts[option.value]}
              onChange={(event) =>
                updateDraft(option.value, event.target.value)
              }
              disabled={Boolean(disabledReason) || busy}
              maxLength={20_000}
            />
            {disabledReason ? (
              <p className="composer-disabled-reason">{disabledReason}</p>
            ) : null}
          </div>
        )
      })}
      {footer}
    </section>
  )
}
