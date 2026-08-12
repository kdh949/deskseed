import { useEffect, useId, useState, type RefObject } from 'react'
import {
  DeskseedIcon,
  DsButton,
  DsDrawer,
  type IconName,
} from '../../design-system'

export type ConfigurableView = {
  icon: IconName
  key: string
  label: string
}

export type ViewEditor =
  { mode: 'create' } | { mode: 'edit'; view: ConfigurableView }

const ICON_OPTIONS: Array<{ icon: IconName; label: string }> = [
  { icon: 'inbox', label: '받은 편지함' },
  { icon: 'clock', label: '대기 시간' },
  { icon: 'alertWarning', label: '주의' },
  { icon: 'bookmark', label: '북마크' },
  { icon: 'star', label: '중요' },
  { icon: 'userGroup', label: '협업' },
  { icon: 'history', label: '최근 활동' },
  { icon: 'checkCircle', label: '완료' },
]

type ViewConfigurationDrawerProps = {
  editor: ViewEditor | null
  onClose: () => void
  onMove?: (direction: 'down' | 'up') => void
  onSave: (values: { icon: IconName; label: string }) => void
  position?: { index: number; total: number }
  returnFocusRef?: RefObject<HTMLElement | null>
}

export function ViewConfigurationDrawer({
  editor,
  onClose,
  onMove,
  onSave,
  position,
  returnFocusRef,
}: ViewConfigurationDrawerProps) {
  const [label, setLabel] = useState('')
  const [icon, setIcon] = useState<IconName>('inbox')
  const [error, setError] = useState('')
  const labelId = useId()
  const iconId = useId()
  const isEditing = editor?.mode === 'edit'

  useEffect(() => {
    if (!editor) return
    setLabel(editor.mode === 'edit' ? editor.view.label : '')
    setIcon(editor.mode === 'edit' ? editor.view.icon : 'inbox')
    setError('')
  }, [editor])

  const handleSubmit = () => {
    const nextLabel = label.trim()
    if (!nextLabel) {
      setError('보기 이름을 입력하세요.')
      return
    }
    onSave({ icon, label: nextLabel })
  }

  return (
    <DsDrawer
      description="이 설정은 현재 브라우저 화면에서만 유지됩니다."
      onClose={onClose}
      open={editor !== null}
      returnFocusRef={returnFocusRef}
      title={isEditing ? '개인 보기 편집' : '새 개인 보기 만들기'}
    >
      <form
        className="view-configuration-form"
        onSubmit={(event) => {
          event.preventDefault()
          handleSubmit()
        }}
      >
        <label className="view-configuration-field" htmlFor={labelId}>
          <span id={`${labelId}-label`}>보기 이름</span>
          <input
            aria-describedby={error ? `${labelId}-error` : undefined}
            aria-invalid={Boolean(error)}
            aria-labelledby={`${labelId}-label`}
            autoFocus
            id={labelId}
            maxLength={40}
            onChange={(event) => {
              setLabel(event.target.value)
              if (error) setError('')
            }}
            value={label}
          />
          {error ? (
            <small id={`${labelId}-error`} role="alert">
              {error}
            </small>
          ) : null}
        </label>

        <fieldset className="view-configuration-field">
          <legend id={iconId}>사이드바 아이콘</legend>
          <div
            aria-labelledby={iconId}
            className="view-icon-picker"
            role="group"
          >
            {ICON_OPTIONS.map((option) => (
              <button
                aria-label={`${option.label} 아이콘 선택`}
                aria-pressed={icon === option.icon}
                className={icon === option.icon ? 'is-selected' : ''}
                key={option.icon}
                onClick={() => setIcon(option.icon)}
                type="button"
              >
                <DeskseedIcon name={option.icon} />
                <span>{option.label}</span>
              </button>
            ))}
          </div>
        </fieldset>

        {isEditing && position && onMove ? (
          <section aria-label="보기 순서" className="view-configuration-order">
            <div>
              <strong>사이드바 순서</strong>
              <p>
                개인 보기 {position.index + 1} / {position.total}
              </p>
            </div>
            <div>
              <DsButton
                disabled={position.index === 0}
                onClick={() => onMove('up')}
                type="button"
              >
                위로
              </DsButton>
              <DsButton
                disabled={position.index === position.total - 1}
                onClick={() => onMove('down')}
                type="button"
              >
                아래로
              </DsButton>
            </div>
          </section>
        ) : null}

        <footer className="view-configuration-actions">
          <DsButton onClick={onClose} type="button">
            취소
          </DsButton>
          <DsButton tone="primary" type="submit">
            {isEditing ? '변경 저장' : '보기 만들기'}
          </DsButton>
        </footer>
      </form>
    </DsDrawer>
  )
}
