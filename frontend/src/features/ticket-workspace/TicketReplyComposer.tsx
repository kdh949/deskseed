import type { TicketVisibility } from '../../api/types'
import {
  ComposerModeSeam,
  Notification,
  type ComposerMode,
} from '../../shared/ui/system'
import type { TicketCommentDrafts } from './ticketEditorModel'

export function TicketReplyComposer({
  mode,
  drafts,
  submitting,
  canSubmit,
  error,
  success,
  onModeChange,
  onDraftChange,
  onSubmit,
}: {
  mode: TicketVisibility
  drafts: TicketCommentDrafts
  submitting: boolean
  canSubmit: boolean
  error: { message: string; requestId?: string; saved?: boolean } | null
  success: string | null
  onModeChange: (mode: ComposerMode) => void
  onDraftChange: (mode: ComposerMode, value: string) => void
  onSubmit: () => void
}) {
  return (
    <div className="ticket-reply-composer">
      {error ? (
        <Notification
          tone={error.saved ? 'warning' : 'danger'}
          title={
            error.saved ? '저장은 완료되었습니다.' : '저장하지 못했습니다.'
          }
        >
          <p>{error.message}</p>
          {error.requestId ? (
            <p className="ds-request-id">
              요청 ID: <code>{error.requestId}</code>
            </p>
          ) : null}
        </Notification>
      ) : null}
      {success ? <Notification tone="success" title={success} /> : null}
      <ComposerModeSeam
        mode={mode}
        drafts={drafts}
        onModeChange={onModeChange}
        onDraftChange={onDraftChange}
        busy={submitting}
        footer={
          <div className="ticket-composer-actions">
            <span>
              {mode === 'PUBLIC' ? '↗ 고객에게 공개' : '◆ 고객에게 비공개'}
            </span>
            <button
              className="button primary"
              type="button"
              aria-busy={submitting}
              disabled={!canSubmit}
              onClick={onSubmit}
            >
              {submitting ? '저장 중…' : '변경사항 저장'}
            </button>
          </div>
        }
      />
    </div>
  )
}
