import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError, getAgentTicket } from '../../api/client'
import type {
  AgentComment,
  AgentTicketDetail,
  AgentTicketStatus,
  TicketPriority,
} from '../../api/types'
import { DsButton, ScreenState } from '../../design-system'
import { TicketWorkspace } from './TicketWorkspace'
import type {
  ConversationEntry,
  WorkspaceTicket,
} from './ticketWorkspaceFixture'

export function AgentTicketWorkspacePage() {
  const { ticketNumber: ticketNumberParam = '' } = useParams()
  const ticketNumber = parseTicketNumber(ticketNumberParam)
  const interactionId = useMemo(createInteractionId, [ticketNumber])
  const query = useQuery({
    queryKey: ['agent-ticket', ticketNumber, interactionId],
    queryFn: () =>
      getAgentTicket(ticketNumber ?? 0, interactionId, 'NAVIGATION'),
    enabled: ticketNumber !== null,
  })

  if (ticketNumber === null) return <InvalidTicketRoute />
  if (query.isPending) return <TicketWorkspace initialState="loading" />
  if (query.isError) {
    return <WorkspaceError error={query.error} retry={() => query.refetch()} />
  }

  return (
    <TicketWorkspace
      detail={query.data}
      onRefresh={() => query.refetch()}
      refreshing={query.isFetching}
      submitDisabledReason="현재 티켓은 읽기 전용입니다."
      ticket={toWorkspaceTicket(query.data)}
    />
  )
}

function InvalidTicketRoute() {
  return (
    <main className="workspace-error-state">
      <ScreenState
        action={<Link to="/agent/views/my-open">내 티켓으로 돌아가기</Link>}
        description="올바른 티켓 번호로 다시 시도해 주세요."
        kind="not-found"
        title="티켓 번호를 확인할 수 없습니다."
      />
    </main>
  )
}

function WorkspaceError({ error, retry }: { error: Error; retry: () => void }) {
  const apiError = error instanceof ApiError ? error : null
  const kind =
    apiError?.status === 403
      ? 'denied'
      : apiError?.status === 404
        ? 'not-found'
        : 'error'
  return (
    <main className="workspace-error-state">
      <ScreenState
        action={
          <div className="state-action-row">
            <DsButton onClick={retry}>다시 시도</DsButton>
            <Link to="/agent/views/my-open">내 티켓으로 돌아가기</Link>
          </div>
        }
        description={
          apiError?.status === 403
            ? '현재 계정으로 이 티켓을 볼 수 없습니다.'
            : apiError?.status === 404
              ? '티켓이 없거나 더 이상 열 수 없습니다.'
              : '잠시 후 다시 시도해 주세요.'
        }
        kind={kind}
        requestId={apiError?.requestId}
        title="티켓을 열 수 없습니다."
      />
    </main>
  )
}

function toWorkspaceTicket(detail: AgentTicketDetail): WorkspaceTicket {
  const ticket = detail.ticket
  return {
    number: String(ticket.ticketNumber),
    subject: ticket.subject,
    createdAt: `업데이트 ${formatDate(ticket.updatedAt)}`,
    status: statusLabel(ticket.status),
    priority: priorityLabel(ticket.priority),
    group: ticket.group?.name ?? '미배정',
    assignee: ticket.assignee?.displayName ?? '미배정',
    requester: ticket.requester.displayName,
    conversation: detail.comments.map(toConversationEntry),
  }
}

function toConversationEntry(comment: AgentComment): ConversationEntry {
  if (comment.actor.type === 'SYSTEM') {
    return {
      kind: 'system',
      timestamp: formatDate(comment.createdAt),
      body: comment.body,
    }
  }
  return {
    kind: 'message',
    visibility: comment.visibility === 'INTERNAL' ? 'internal' : 'public',
    author: comment.actor.type === 'CUSTOMER' ? 'customer' : 'agent',
    name: comment.actor.displayName,
    timestamp: formatDate(comment.createdAt),
    body: comment.body.split(/\n{2,}/),
  }
}

function statusLabel(status: AgentTicketStatus): WorkspaceTicket['status'] {
  const labels: Record<AgentTicketStatus, WorkspaceTicket['status']> = {
    NEW: 'New',
    OPEN: 'Open',
    PENDING: 'Pending',
    ON_HOLD: 'Pending',
    SOLVED: 'Solved',
    CLOSED: 'Solved',
  }
  return labels[status]
}

function priorityLabel(priority: TicketPriority): WorkspaceTicket['priority'] {
  const labels: Record<TicketPriority, WorkspaceTicket['priority']> = {
    LOW: 'Low',
    NORMAL: 'Normal',
    HIGH: 'High',
    URGENT: 'Urgent',
  }
  return labels[priority]
}

function parseTicketNumber(value: string): number | null {
  if (!/^[1-9]\d*$/.test(value)) return null
  const ticketNumber = Number(value)
  return Number.isSafeInteger(ticketNumber) ? ticketNumber : null
}

function createInteractionId(): string {
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

function formatDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}
