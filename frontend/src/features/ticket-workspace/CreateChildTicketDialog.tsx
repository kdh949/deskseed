import { useRef, useState, type FormEvent } from 'react'
import { ApiError, createChildTicket } from '../../api/client'
import type { AgentTicketDetail, TicketPriority } from '../../api/types'
import { Notification } from '../../shared/ui/system'
import {
  TicketActionDialogFrame,
  createTicketCommandId,
} from './TicketActionDialogFrame'

export function CreateChildTicketDialog({
  detail,
  onClose,
  onCompleted,
}: {
  detail: AgentTicketDetail
  onClose: () => void
  onCompleted: () => Promise<unknown>
}) {
  const initialGroup =
    detail.assignmentOptions.groups.find(
      (group) => group.id === detail.ticket.group?.id,
    ) ?? detail.assignmentOptions.groups[0]
  const [subject, setSubject] = useState('')
  const [body, setBody] = useState('')
  const [groupId, setGroupId] = useState(initialGroup?.id ?? '')
  const [assigneeId, setAssigneeId] = useState('')
  const [priority, setPriority] = useState<TicketPriority>('NORMAL')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<{
    message: string
    requestId?: string
  } | null>(null)
  const subjectRef = useRef<HTMLInputElement>(null)
  const selectedGroup = detail.assignmentOptions.groups.find(
    (group) => group.id === groupId,
  )
  const close = () => {
    if (!submitting) onClose()
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!subject.trim() || !body.trim() || !groupId || submitting) return
    setSubmitting(true)
    setError(null)
    try {
      await createChildTicket(detail.ticket.ticketNumber, {
        expectedVersion: detail.ticket.version,
        subject: subject.trim(),
        body: body.trim(),
        groupId,
        assigneeId: assigneeId || null,
        priority,
        clientCommandId: createTicketCommandId(),
      })
      await onCompleted()
      onClose()
    } catch (cause) {
      const apiError = cause instanceof ApiError ? cause : null
      setError({
        message:
          apiError?.status === 412
            ? 'Parent 티켓이 변경되었습니다. 최신 상태를 확인한 뒤 다시 생성해 주세요.'
            : apiError?.status === 403
              ? '현재 상담사는 이 parent에 child를 만들 수 없습니다.'
              : apiError?.status === 422
                ? 'Child 관계의 깊이·순환·소유자 조건을 만족하지 않습니다.'
                : (apiError?.message ??
                  '내부 child ticket을 만들지 못했습니다.'),
        requestId: apiError?.requestId,
      })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <TicketActionDialogFrame
      title="내부 child 만들기"
      description="별도 내부 ticket을 생성합니다. 첫 comment는 INTERNAL이며 고객에게 노출되지 않고 parent의 소유권은 그대로 유지됩니다."
      initialFocusRef={subjectRef}
      onClose={close}
    >
      {error ? (
        <Notification tone="danger" title="생성하지 못했습니다.">
          <p>{error.message}</p>
          {error.requestId ? (
            <p className="ds-request-id">요청 ID: {error.requestId}</p>
          ) : null}
        </Notification>
      ) : null}
      <form
        className="ticket-action-form"
        onSubmit={(event) => void submit(event)}
      >
        <label>
          <span>Child 제목</span>
          <input
            ref={subjectRef}
            value={subject}
            disabled={submitting}
            maxLength={200}
            required
            onChange={(event) => setSubject(event.target.value)}
          />
        </label>
        <label>
          <span>내부 작업 설명</span>
          <textarea
            value={body}
            disabled={submitting}
            maxLength={20_000}
            required
            onChange={(event) => setBody(event.target.value)}
          />
        </label>
        <div className="ticket-action-form-grid">
          <label>
            <span>대상 그룹</span>
            <select
              value={groupId}
              disabled={submitting}
              required
              onChange={(event) => {
                setGroupId(event.target.value)
                setAssigneeId('')
              }}
            >
              {detail.assignmentOptions.groups.map((group) => (
                <option key={group.id} value={group.id}>
                  {group.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span>대상 담당자</span>
            <select
              value={assigneeId}
              disabled={submitting}
              onChange={(event) => setAssigneeId(event.target.value)}
            >
              <option value="">지정하지 않음</option>
              {selectedGroup?.members.map((member) => (
                <option key={member.id} value={member.id}>
                  {member.displayName}
                </option>
              ))}
            </select>
          </label>
        </div>
        <label>
          <span>Child 우선순위</span>
          <select
            value={priority}
            disabled={submitting}
            onChange={(event) =>
              setPriority(event.target.value as TicketPriority)
            }
          >
            <option value="LOW">낮음</option>
            <option value="NORMAL">보통</option>
            <option value="HIGH">높음</option>
            <option value="URGENT">긴급</option>
          </select>
        </label>
        <div className="ticket-action-form-actions">
          <button
            className="button secondary"
            type="button"
            disabled={submitting}
            onClick={close}
          >
            취소
          </button>
          <button
            className="button primary"
            type="submit"
            aria-busy={submitting}
            disabled={!subject.trim() || !body.trim() || !groupId || submitting}
          >
            {submitting ? '생성 중…' : 'Child 생성'}
          </button>
        </div>
      </form>
    </TicketActionDialogFrame>
  )
}
