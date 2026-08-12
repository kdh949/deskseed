import { DeskseedIcon } from '../../design-system/primitives/DeskseedIcon'
import { DsIconButton } from '../../design-system/primitives/DeskseedPrimitives'
import {
  DsSplitButton,
  DsTabs,
} from '../../design-system/primitives/DeskseedControls'
import type { ComposerMode } from './ticketWorkspaceFixture'

type ReplyComposerProps = {
  draft: string
  mode: ComposerMode
  onDraftChange: (draft: string) => void
  onModeChange: (mode: ComposerMode) => void
  onSubmit: () => void
  savedMessage: string
  submitDisabledReason?: string
}

export function ReplyComposer({
  draft,
  mode,
  onDraftChange,
  onModeChange,
  onSubmit,
  savedMessage,
  submitDisabledReason,
}: ReplyComposerProps) {
  const isInternal = mode === 'internal'
  const label = isInternal ? '내부 메모 내용' : '공개 답변 내용'
  const actionLabel = isInternal ? '내부 메모 추가' : '공개 답변 보내기'

  return (
    <section
      aria-label="답변 작성"
      className={`reply-composer reply-composer--${mode}`}
    >
      <div className="reply-composer-topbar">
        <DsTabs
          activeId={mode}
          ariaLabel="답변 공개 범위"
          className="reply-composer-tabs"
          items={[
            {
              id: 'internal',
              ariaLabel: '내부 메모 작성 모드로 전환',
              panelId: 'reply-composer-panel-internal',
              label: (
                <span className="reply-composer-mode-label">
                  <DeskseedIcon name="lock" /> Internal note
                  <DeskseedIcon name="chevronDown" size="sm" />
                </span>
              ),
            },
            {
              id: 'public',
              ariaLabel: '공개 답변 작성 모드로 전환',
              panelId: 'reply-composer-panel-public',
              label: (
                <span className="reply-composer-mode-label">
                  <DeskseedIcon name="speechBubble" /> Public reply
                </span>
              ),
            },
          ]}
          onChange={onModeChange}
        />
        <div className="reply-composer-toolbar" aria-label="답변 도구">
          <DsIconButton icon="paperclip" label="파일 첨부" />
          <DsIconButton icon="bookmark" label="매크로 저장" />
          <DsIconButton icon="overflow" label="추가 답변 도구" />
        </div>
      </div>
      <div
        aria-labelledby={`reply-composer-panel-${mode}-tab`}
        id={`reply-composer-panel-${mode}`}
        role="tabpanel"
      >
        <p aria-live="polite" className="sr-only">
          {isInternal
            ? '내부 메모 모드입니다. 고객에게 공개되지 않습니다.'
            : '공개 답변 모드입니다. 고객에게 전송됩니다.'}
        </p>
        <label className="reply-composer-input">
          <span className="sr-only">{label}</span>
          <textarea
            aria-label={label}
            onChange={(event) => onDraftChange(event.target.value)}
            placeholder={
              isInternal
                ? '팀에 공유할 확인 사항을 작성하세요.'
                : '고객에게 보낼 답변을 작성하세요.'
            }
            value={draft}
          />
        </label>
        <div className="reply-composer-footer">
          <p aria-live="polite" className="reply-composer-draft-status">
            <span aria-hidden="true" className="reply-composer-draft-dot" />
            {savedMessage ||
              submitDisabledReason ||
              '초안이 이 브라우저에 저장됩니다.'}
          </p>
          <div className="reply-composer-actions">
            <kbd className="reply-composer-shortcut">
              Press ⌘ ⇧ R to switch to Public reply or Internal note
            </kbd>
            <DsSplitButton
              actionLabel={actionLabel}
              disabled={Boolean(submitDisabledReason)}
              onAction={onSubmit}
            >
              {actionLabel}
            </DsSplitButton>
          </div>
        </div>
      </div>
    </section>
  )
}
