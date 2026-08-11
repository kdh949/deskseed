import { useQuery } from '@tanstack/react-query'
import { useMemo, useRef } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError, getAgentTicket } from '../../api/client'
import type { AgentTicketStatus } from '../../api/types'
import {
  PropertyPanel,
  ScreenState,
  SplitPanel,
  TicketTabs,
  type PropertyPanelItem,
} from '../../shared/ui/system'
import { useStaffSession } from '../staff-auth/StaffSessionContext'
import { TicketContextPanel } from './TicketContextPanel'
import { TicketConversation } from './TicketConversation'
import { usePanelPreferences } from './usePanelPreferences'

export function AgentTicketWorkspacePage() {
  const { ticketNumber: ticketNumberParam = '' } = useParams()
  const ticketNumber = parseTicketNumber(ticketNumberParam)
  const session = useStaffSession()
  const interactionId = useMemo(createInteractionId, [ticketNumber])
  const contextToggleRef = useRef<HTMLButtonElement>(null)
  const { preferences, setPropertyWidth, setContextWidth, toggleContext } =
    usePanelPreferences(session.staff?.id ?? 'unknown')
  const query = useQuery({
    queryKey: ['agent-ticket', ticketNumber, interactionId],
    queryFn: () =>
      getAgentTicket(ticketNumber ?? 0, interactionId, 'NAVIGATION'),
    enabled: ticketNumber !== null,
  })

  if (ticketNumber === null) {
    return <InvalidTicketRoute />
  }

  if (query.isPending) {
    return <WorkspaceLoading ticketNumber={ticketNumberParam} />
  }
  if (query.isError) {
    return <WorkspaceError error={query.error} retry={() => query.refetch()} />
  }

  const detail = query.data
  const ticket = detail.ticket
  const properties: PropertyPanelItem[] = [
    { label: '상태', value: statusLabel(ticket.status) },
    { label: '우선순위', value: ticket.priority },
    { label: '요청자', value: ticket.requester.displayName },
    { label: '그룹', value: ticket.group?.name ?? '미배정' },
    { label: '담당자', value: ticket.assignee?.displayName ?? '미배정' },
    { label: '업데이트', value: formatDate(ticket.updatedAt) },
    { label: '티켓 유형', value: ticket.isChild ? 'Child task' : '일반 티켓' },
  ]

  return (
    <main
      className="ticket-workspace-page"
      aria-labelledby="ticket-workspace-title"
    >
      <TicketTabs
        backTo="/agent/views/my-open"
        backLabel="Views로 돌아가기"
        ticketNumber={ticket.ticketNumber}
        subject={ticket.subject}
        status={ticket.status}
        onRefresh={() => query.refetch()}
        refreshing={query.isFetching}
      />
      <header className="ticket-titlebar">
        <div>
          <p className="agent-page-eyebrow">
            TICKET #{ticket.ticketNumber} · ALL_TICKETS READ
          </p>
          <h1 id="ticket-workspace-title">{ticket.subject}</h1>
        </div>
        {preferences.contextCollapsed ? (
          <button
            ref={contextToggleRef}
            className="compact-button"
            type="button"
            onClick={toggleContext}
          >
            컨텍스트 패널 펼치기
          </button>
        ) : (
          <button
            ref={contextToggleRef}
            className="compact-button"
            type="button"
            onClick={toggleContext}
          >
            컨텍스트 패널 접기
          </button>
        )}
      </header>
      <SplitPanel
        propertyWidth={preferences.propertyWidth}
        contextWidth={preferences.contextWidth}
        onPropertyWidthChange={setPropertyWidth}
        onContextWidthChange={setContextWidth}
        propertyPanel={
          <PropertyPanel
            title="속성"
            meta={`v${ticket.version}`}
            items={properties}
            footer={
              <div className="read-boundary-note">
                <strong>읽기 범위: ALL_TICKETS</strong>
                <p>
                  다른 그룹 티켓의 쓰기 권한은 현재 그룹·담당자 정책을 따릅니다.
                </p>
              </div>
            }
          />
        }
        conversationPanel={<TicketConversation comments={detail.comments} />}
        contextPanel={
          preferences.contextCollapsed ? undefined : (
            <TicketContextPanel detail={detail} />
          )
        }
      />
    </main>
  )
}

function WorkspaceLoading({ ticketNumber }: { ticketNumber: string }) {
  return (
    <main className="ticket-workspace-loading" aria-busy="true">
      <span className="sr-only" role="status">
        티켓 #{ticketNumber} 불러오는 중
      </span>
      <div />
      <div />
      <div />
    </main>
  )
}

function InvalidTicketRoute() {
  return (
    <main className="workspace-error-state" role="alert">
      <p className="agent-page-eyebrow">INVALID TICKET URL</p>
      <h1>티켓 번호를 확인할 수 없습니다.</h1>
      <p>양의 정수 티켓 번호로 다시 시도해 주세요.</p>
      <Link to="/agent/views/my-open">Views로 돌아가기</Link>
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
        kind={kind}
        title="티켓을 열 수 없습니다."
        description={
          apiError?.status === 404
            ? '티켓이 없거나 접근 가능한 범위에 없습니다.'
            : '읽기 감사 기록을 포함한 요청을 완료하지 못했습니다.'
        }
        requestId={apiError?.requestId}
        action={
          <div className="state-action-row">
            <button className="compact-button" type="button" onClick={retry}>
              다시 시도
            </button>
            <Link to="/agent/views/my-open">Views로 돌아가기</Link>
          </div>
        }
      />
    </main>
  )
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

function statusLabel(status: AgentTicketStatus) {
  const labels: Record<AgentTicketStatus, string> = {
    NEW: '신규',
    OPEN: '처리 중',
    PENDING: '고객 답변 대기',
    ON_HOLD: '보류',
    SOLVED: '해결됨',
    CLOSED: '종료',
  }
  return labels[status]
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}
