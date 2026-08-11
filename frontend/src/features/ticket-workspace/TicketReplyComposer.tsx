import type { TicketCommandWarning, TicketVisibility } from '../../api/types'
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
  warnings,
  internalOnly = false,
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
  warnings: TicketCommandWarning[]
  internalOnly?: boolean
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
      {warnings.map((warning) => (
        <Notification
          key={`${warning.code}:${warning.relatedTicketNumbers.join(',')}`}
          tone="warning"
          urgent
          title="열린 child ticket 경고 — 저장 완료"
        >
          <p>{warning.message}</p>
          {warning.relatedTicketNumbers.length ? (
            <p>
              영향받는 child:{' '}
              {warning.relatedTicketNumbers
                .map((ticketNumber) => `#${ticketNumber}`)
                .join(', ')}
            </p>
          ) : null}
        </Notification>
      ))}
      <ComposerModeSeam
        mode={mode}
        availableModes={internalOnly ? ['INTERNAL'] : undefined}
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
