import { useCallback, useId, useRef, useState, type FormEvent } from 'react'
import type { AttachmentUpload } from '../../api/types'
import { DsButton, Notification } from '../../design-system'
import { createOpaqueUuid } from '../../api/uuid'
import {
  AttachmentUploadField,
  type AttachmentDraftState,
} from '../attachments/AttachmentUploadField'

export function CustomerFollowUpForm({
  onConflict,
  onSubmitted,
  onSubmit,
  uploadAttachment,
}: {
  onConflict?: () => void
  onSubmitted?: () => void
  onSubmit: (
    body: string,
    clientCommandId: string,
    attachmentIds: string[],
  ) => Promise<unknown>
  uploadAttachment?: (file: File) => Promise<AttachmentUpload>
}) {
  const [body, setBody] = useState('')
  const [attempted, setAttempted] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [notice, setNotice] = useState<FollowUpNotice | null>(null)
  const [attachments, setAttachments] = useState<AttachmentDraftState>({
    blocked: false,
    ids: [],
  })
  const [attachmentResetVersion, setAttachmentResetVersion] = useState(0)
  const commandIdRef = useRef<string | null>(null)
  const submittedBodyRef = useRef<string | null>(null)
  const bodyId = useId()
  const errorId = `${bodyId}-error`
  const bodyError =
    attempted && !body.trim() ? '답변 내용을 입력해 주세요.' : null
  const updateAttachments = useCallback(
    (state: AttachmentDraftState) => setAttachments(state),
    [],
  )

  const updateBody = (nextBody: string) => {
    if (
      commandIdRef.current !== null &&
      submittedBodyRef.current !== nextBody.trim()
    ) {
      commandIdRef.current = null
      submittedBodyRef.current = null
    }
    setBody(nextBody)
    setNotice(null)
  }

  const submitFollowUp = async () => {
    setAttempted(true)
    const submittedBody = body.trim()
    if (!submittedBody || submitting || attachments.blocked) return

    const clientCommandId = commandIdRef.current ?? createOpaqueUuid()
    commandIdRef.current = clientCommandId
    submittedBodyRef.current = submittedBody
    setSubmitting(true)
    setNotice(null)
    try {
      await onSubmit(submittedBody, clientCommandId, attachments.ids)
      commandIdRef.current = null
      submittedBodyRef.current = null
      setBody('')
      setAttempted(false)
      setNotice({ kind: 'success' })
      setAttachmentResetVersion((current) => current + 1)
      onSubmitted?.()
    } catch (error) {
      const nextNotice = toFollowUpNotice(error)
      if (nextNotice.kind !== 'unavailable') {
        commandIdRef.current = null
        submittedBodyRef.current = null
      }
      setNotice(nextNotice)
      if (nextNotice.kind === 'conflict') onConflict?.()
    } finally {
      setSubmitting(false)
    }
  }

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    void submitFollowUp()
  }

  return (
    <section aria-label="고객 후속 답변" className="customer-follow-up">
      <h2 id="customer-follow-up-title">추가 답변</h2>
      <p>답변은 담당자와 공유되는 공개 대화에만 추가됩니다.</p>
      {notice ? <FollowUpNoticeView notice={notice} /> : null}
      <form onSubmit={handleSubmit}>
        <label htmlFor={bodyId}>
          <span className="sr-only">추가 답변</span>
          <textarea
            aria-describedby={bodyError ? errorId : undefined}
            aria-invalid={bodyError ? 'true' : undefined}
            id={bodyId}
            maxLength={20_000}
            onChange={(event) => updateBody(event.target.value)}
            placeholder="추가로 전달할 내용을 입력해 주세요."
            rows={5}
            value={body}
          />
        </label>
        {bodyError ? (
          <small id={errorId} role="alert">
            {bodyError}
          </small>
        ) : null}
        {uploadAttachment ? (
          <AttachmentUploadField
            disabled={submitting}
            label="PUBLIC 첨부 파일"
            onStateChange={updateAttachments}
            resetVersion={attachmentResetVersion}
            upload={uploadAttachment}
          />
        ) : null}
        <div className="customer-follow-up-actions">
          <DsButton
            disabled={submitting || attachments.blocked}
            onClick={() => void submitFollowUp()}
            tone="primary"
          >
            {submitting ? '답변 전송 중…' : '답변 보내기'}
          </DsButton>
          <p>
            전송 결과를 확인할 수 없으면 같은 답변을 다시 보내도 중복되지
            않습니다.
          </p>
        </div>
      </form>
    </section>
  )
}

type FollowUpNotice =
  | { kind: 'success' }
  | { kind: 'validation' }
  | { kind: 'denied'; requestId?: string }
  | { kind: 'conflict'; requestId?: string }
  | { kind: 'rate-limited'; requestId?: string; retryAfter?: string }
  | { kind: 'unavailable'; requestId?: string }

function toFollowUpNotice(error: unknown): FollowUpNotice {
  const status = statusOf(error)
  const requestId = requestIdOf(error)
  if (status === 400) return { kind: 'validation' }
  if (status === 403 || status === 404) return { kind: 'denied', requestId }
  if (status === 409) return { kind: 'conflict', requestId }
  if (status === 429) {
    return { kind: 'rate-limited', requestId, retryAfter: retryAfterOf(error) }
  }
  return { kind: 'unavailable', requestId }
}

function FollowUpNoticeView({ notice }: { notice: FollowUpNotice }) {
  const content =
    notice.kind === 'success'
      ? {
          description: '최신 공개 대화를 불러오고 있습니다.',
          title: '답변이 저장되었습니다.',
          tone: 'success' as const,
        }
      : notice.kind === 'validation'
        ? {
            description: '답변 내용을 확인한 뒤 새 명령으로 다시 보내 주세요.',
            title: '답변을 저장할 수 없습니다.',
            tone: 'danger' as const,
          }
        : notice.kind === 'denied'
          ? {
              description:
                '이 문의에 더 이상 답변할 수 없습니다. 이메일의 최신 문의 링크를 확인해 주세요.',
              title: '답변 권한이 만료되었거나 허용되지 않았습니다.',
              tone: 'danger' as const,
            }
          : notice.kind === 'conflict'
            ? {
                description:
                  '최신 문의 상태를 확인한 뒤 초안을 검토하고 새 명령으로 다시 보내 주세요.',
                title: '문의 상태가 변경되었습니다.',
                tone: 'conflict' as const,
              }
            : notice.kind === 'rate-limited'
              ? {
                  description: `${formatRetryAfter(notice.retryAfter)} 후 다시 시도해 주세요.`,
                  title: '답변 전송이 잠시 제한되었습니다.',
                  tone: 'warning' as const,
                }
              : {
                  description:
                    '초안과 명령 식별자는 유지됩니다. 같은 답변을 다시 보내 결과를 확인해 주세요.',
                  title: '답변 전송 결과를 확인할 수 없습니다.',
                  tone: 'danger' as const,
                }
  const requestId =
    notice.kind === 'success' || notice.kind === 'validation'
      ? undefined
      : notice.requestId

  return (
    <Notification title={content.title} tone={content.tone}>
      <p>{content.description}</p>
      {requestId ? <p>요청 ID: {requestId}</p> : null}
    </Notification>
  )
}

function statusOf(error: unknown) {
  if (typeof error !== 'object' || error === null) return undefined
  const status = (error as { status?: unknown }).status
  return typeof status === 'number' ? status : undefined
}

function requestIdOf(error: unknown) {
  if (typeof error !== 'object' || error === null) return undefined
  const requestId = (error as { requestId?: unknown }).requestId
  return typeof requestId === 'string' ? requestId : undefined
}

function retryAfterOf(error: unknown) {
  if (typeof error !== 'object' || error === null) return undefined
  const retryAfter = (error as { retryAfter?: unknown }).retryAfter
  return typeof retryAfter === 'string' ? retryAfter : undefined
}

function formatRetryAfter(retryAfter: string | undefined) {
  const seconds = Number(retryAfter)
  if (!Number.isSafeInteger(seconds) || seconds < 1) return '잠시'
  return `${seconds}초`
}
