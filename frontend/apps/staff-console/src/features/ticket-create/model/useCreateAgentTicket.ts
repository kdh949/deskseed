import { useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { ApiError, createAgentTicket } from '../../../api/client'
import type {
  CreateAgentTicketRequester,
  TicketCommandWarning,
  TicketPriority,
  TicketVisibility,
} from '../../../api/types'
import { createOpaqueUuid } from '../../../api/uuid'

export interface CreateAgentTicketInput {
  requester: CreateAgentTicketRequester
  subject: string
  visibility: TicketVisibility
  body: string
  priority: TicketPriority
  groupId: string | null
  assigneeId: string | null
}

interface CreateAgentTicketErrorState {
  message: string
  requestId?: string
}

export function useCreateAgentTicket() {
  const navigate = useNavigate()
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<CreateAgentTicketErrorState | null>(null)
  const [warnings, setWarnings] = useState<TicketCommandWarning[]>([])
  const clientCommandIdRef = useRef<string>(createOpaqueUuid())

  const submit = async (input: CreateAgentTicketInput) => {
    if (submitting) return
    setSubmitting(true)
    setError(null)
    setWarnings([])
    try {
      const result = await createAgentTicket({
        requester: input.requester,
        subject: input.subject,
        firstComment: { visibility: input.visibility, body: input.body },
        priority: input.priority,
        groupId: input.groupId,
        assigneeId: input.assigneeId,
        clientCommandId: clientCommandIdRef.current,
      })
      clientCommandIdRef.current = createOpaqueUuid()
      setWarnings(result.warnings)
      navigate(`/agent/tickets/${result.ticketNumber}`)
    } catch (cause) {
      const apiError = cause instanceof ApiError ? cause : null
      if (!isAmbiguousCommandFailure(cause)) {
        clientCommandIdRef.current = createOpaqueUuid()
      }
      setError({
        message:
          apiError?.message ??
          '티켓을 생성하지 못했습니다. 입력은 그대로 보존되었습니다.',
        requestId: apiError?.requestId,
      })
    } finally {
      setSubmitting(false)
    }
  }

  return { submit, submitting, error, warnings }
}

function isAmbiguousCommandFailure(cause: unknown) {
  if (!(cause instanceof ApiError)) return true
  return cause.status >= 500 || (cause.status >= 200 && cause.status < 300)
}
