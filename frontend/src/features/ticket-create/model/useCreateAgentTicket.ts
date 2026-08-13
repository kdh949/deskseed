import { useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { ApiError, createAgentTicket } from '../../../api/client'
import type {
  CreateAgentTicketRequester,
  TicketCommandWarning,
  TicketPriority,
  TicketVisibility,
} from '../../../api/types'

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
  const clientCommandIdRef = useRef<string>(createClientCommandId())

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
      clientCommandIdRef.current = createClientCommandId()
      setWarnings(result.warnings)
      navigate(`/agent/tickets/${result.ticketNumber}`)
    } catch (cause) {
      const apiError = cause instanceof ApiError ? cause : null
      if (!isAmbiguousCommandFailure(cause)) {
        clientCommandIdRef.current = createClientCommandId()
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

function createClientCommandId(): string {
  const webCrypto = globalThis.crypto
  if (webCrypto?.randomUUID) return webCrypto.randomUUID()

  const bytes = new Uint8Array(16)
  if (webCrypto?.getRandomValues) webCrypto.getRandomValues(bytes)
  else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256)
    }
  }
  bytes[6] = (bytes[6]! & 0x0f) | 0x40
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) =>
    byte.toString(16).padStart(2, '0'),
  ).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
