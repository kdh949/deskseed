import { useQuery } from '@tanstack/react-query'
import { useRef, useState, type CSSProperties } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError, getAgentTicket } from '../../api/client'
import { StatusBadge } from '../../components/StatusBadge'
import { useStaffSession } from '../staff-auth/StaffSessionContext'
import { PanelResizer } from './PanelResizer'
import { TicketContextPanel } from './TicketContextPanel'
import { TicketConversation } from './TicketConversation'
import { usePanelPreferences } from './usePanelPreferences'

export function AgentTicketWorkspacePage() {
  const { ticketNumber: ticketNumberParam = '' } = useParams()
  const ticketNumber = Number(ticketNumberParam)
  const session = useStaffSession()
  const [interactionId] = useState(createInteractionId)
  const contextToggleRef = useRef<HTMLButtonElement>(null)
  const { preferences, setPropertyWidth, setContextWidth, toggleContext } =
    usePanelPreferences(session.staff?.id ?? 'unknown')
  const query = useQuery({
    queryKey: ['agent-ticket', ticketNumber, interactionId],
    queryFn: () => getAgentTicket(ticketNumber, interactionId, 'NAVIGATION'),
    enabled: Number.isSafeInteger(ticketNumber) && ticketNumber > 0,
  })

  if (query.isPending) {
    return <WorkspaceLoading ticketNumber={ticketNumberParam} />
  }
  if (query.isError) {
    return <WorkspaceError error={query.error} retry={() => query.refetch()} />
  }

  const detail = query.data
  const ticket = detail.ticket
  const gridStyle = {
    '--property-panel-width': `${preferences.propertyWidth}px`,
    '--context-panel-width': `${preferences.contextWidth}px`,
  } as CSSProperties

  return (
    <main
      className="ticket-workspace-page"
      aria-labelledby="ticket-workspace-title"
    >
      <header className="ticket-tabbar">
        <Link
          className="ticket-back-link"
          to="/agent/views/my-open"
          aria-label="Views로 돌아가기"
        >
          ←
        </Link>
        <div className="active-ticket-tab">
          <StatusBadge status={ticket.status} />
          <span>#{ticket.ticketNumber}</span>
          <strong>{ticket.subject}</strong>
        </div>
        <button
          className="compact-button"
          type="button"
          onClick={() => query.refetch()}
        >
          티켓 새로고침
        </button>
        {query.isFetching ? (
          <span className="sr-only" role="status">
            티켓 최신 정보 확인 중
          </span>
        ) : null}
      </header>
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
      <div
        className={`ticket-workspace-grid${preferences.contextCollapsed ? ' context-collapsed' : ''}`}
        style={gridStyle}
      >
        <section className="ticket-properties-panel" aria-label="티켓 속성">
          <header className="workspace-panel-header">
            <h2>속성</h2>
            <span>v{ticket.version}</span>
          </header>
          <dl className="ticket-properties">
            <Property label="상태" value={statusLabel(ticket.status)} />
            <Property label="우선순위" value={ticket.priority} />
            <Property label="요청자" value={ticket.requester.displayName} />
            <Property label="그룹" value={ticket.group?.name ?? '미배정'} />
            <Property
              label="담당자"
              value={ticket.assignee?.displayName ?? '미배정'}
            />
            <Property label="업데이트" value={formatDate(ticket.updatedAt)} />
            <Property
              label="티켓 유형"
              value={ticket.isChild ? 'Child task' : '일반 티켓'}
            />
          </dl>
          <div className="read-boundary-note">
            <strong>읽기 범위: ALL_TICKETS</strong>
            <p>
              다른 그룹 티켓의 쓰기 권한은 현재 그룹·담당자 정책을 따릅니다.
            </p>
          </div>
        </section>
        <PanelResizer
          label="속성 패널 너비 조절"
          value={preferences.propertyWidth}
          minimum={240}
          maximum={420}
          direction={1}
          onChange={setPropertyWidth}
        />
        <TicketConversation comments={detail.comments} />
        {!preferences.contextCollapsed ? (
          <>
            <PanelResizer
              label="컨텍스트 패널 너비 조절"
              value={preferences.contextWidth}
              minimum={240}
              maximum={520}
              direction={-1}
              onChange={setContextWidth}
            />
            <TicketContextPanel detail={detail} />
          </>
        ) : null}
      </div>
    </main>
  )
}

function Property({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
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

function WorkspaceError({ error, retry }: { error: Error; retry: () => void }) {
  const apiError = error instanceof ApiError ? error : null
  return (
    <main className="workspace-error-state" role="alert">
      <p className="agent-page-eyebrow">
        {apiError?.status === 403
          ? '403 · ACCESS DENIED'
          : 'TICKET READ FAILED'}
      </p>
      <h1>티켓을 열 수 없습니다.</h1>
      <p>
        {apiError?.status === 404
          ? '티켓이 없거나 접근 가능한 범위에 없습니다.'
          : '읽기 감사 기록을 포함한 요청을 완료하지 못했습니다.'}
      </p>
      {apiError?.requestId ? <p>요청 ID: {apiError.requestId}</p> : null}
      <button className="compact-button" type="button" onClick={retry}>
        다시 시도
      </button>
      <Link to="/agent/views/my-open">Views로 돌아가기</Link>
    </main>
  )
}

function createInteractionId() {
  return (
    globalThis.crypto?.randomUUID?.() ??
    `interaction-${Date.now()}-${Math.random().toString(16).slice(2)}`
  )
}

function statusLabel(status: string) {
  return (
    {
      NEW: '신규',
      OPEN: '처리 중',
      PENDING: '고객 답변 대기',
      SOLVED: '해결됨',
    }[status] ?? status
  )
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}
