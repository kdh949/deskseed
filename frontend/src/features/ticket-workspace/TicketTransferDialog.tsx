import { useRef, useState, type FormEvent, type RefObject } from 'react'
import { ApiError, transferAgentTicket } from '../../api/client'
import type { AgentTicketDetail } from '../../api/types'
import { Notification } from '../../shared/ui/system'
import {
  TicketActionDialogFrame,
  createTicketCommandId,
} from './TicketActionDialogFrame'

const MAX_TRANSFER_REASON_LENGTH = 2_000

export function TicketTransferDialog({
  detail,
  returnFocusRef,
  onClose,
  onCompleted,
}: {
  detail: AgentTicketDetail
  returnFocusRef: RefObject<HTMLElement | null>
  onClose: () => void
  onCompleted: () => Promise<unknown>
}) {
  const initialGroup =
    detail.assignmentOptions.groups.find(
      (group) => group.id === detail.ticket.group?.id,
    ) ?? detail.assignmentOptions.groups[0]
  const [groupId, setGroupId] = useState(initialGroup?.id ?? '')
  const [assigneeId, setAssigneeId] = useState(
    initialGroup?.members.some(
      (member) => member.id === detail.ticket.assignee?.id,
    )
      ? (detail.ticket.assignee?.id ?? '')
      : '',
  )
  const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<{
    message: string
    requestId?: string
  } | null>(null)
  const groupRef = useRef<HTMLSelectElement>(null)
  const selectedGroup = detail.assignmentOptions.groups.find(
    (group) => group.id === groupId,
  )
  const currentGroupId = detail.ticket.group?.id ?? ''
  const currentAssigneeId = detail.ticket.assignee?.id ?? ''
  const hasOwnershipChange =
    groupId !== currentGroupId || assigneeId !== currentAssigneeId
  const reasonTooLong = reason.length > MAX_TRANSFER_REASON_LENGTH
  const close = () => {
    if (!submitting) onClose()
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!groupId || !hasOwnershipChange || reasonTooLong || submitting) return
    setSubmitting(true)
    setError(null)
    try {
      await transferAgentTicket(detail.ticket.ticketNumber, {
        expectedVersion: detail.ticket.version,
        groupId,
        assigneeId: assigneeId || null,
        reason: reason.trim() || null,
        clientCommandId: createTicketCommandId(),
      })
      await onCompleted()
      onClose()
    } catch (cause) {
      const apiError = cause instanceof ApiError ? cause : null
      setError({
        message:
          apiError?.status === 412
            ? '티켓이 다른 곳에서 변경되었습니다. 최신 상태를 확인한 뒤 다시 이관해 주세요.'
            : apiError?.status === 403
              ? '현재 상담사는 이 티켓의 소유권을 변경할 수 없습니다.'
              : (apiError?.message ?? '티켓을 이관하지 못했습니다.'),
        requestId: apiError?.requestId,
      })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <TicketActionDialogFrame
      title="티켓 이관"
      description="현재 티켓의 그룹과 담당자를 변경합니다. 새 티켓은 생성되지 않으며 사유는 내부 메모로만 기록됩니다."
      initialFocusRef={groupRef}
      returnFocusRef={returnFocusRef}
      busy={submitting}
      onClose={close}
    >
      {error ? (
        <Notification tone="danger" title="이관하지 못했습니다.">
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
          <span>대상 그룹</span>
          <select
            ref={groupRef}
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
        <label>
          <span>이관 사유 (내부 메모)</span>
          <textarea
            value={reason}
            disabled={submitting}
            maxLength={MAX_TRANSFER_REASON_LENGTH}
            onChange={(event) => setReason(event.target.value)}
          />
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
            disabled={
              !groupId || !hasOwnershipChange || reasonTooLong || submitting
            }
          >
            {submitting ? '이관 중…' : '소유권 이관'}
          </button>
        </div>
      </form>
    </TicketActionDialogFrame>
  )
}
