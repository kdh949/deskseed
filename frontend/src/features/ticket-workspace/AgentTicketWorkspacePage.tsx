import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useRef, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { ApiError, getAgentTicket } from '../../api/client'
import { ScreenState, SplitPanel, TicketTabs } from '../../shared/ui/system'
import { useStaffSession } from '../staff-auth/StaffSessionContext'
import { TicketContextPanel } from './TicketContextPanel'
import { TicketConversation } from './TicketConversation'
import { TicketPropertiesEditor } from './TicketPropertiesEditor'
import { TicketReplyComposer } from './TicketReplyComposer'
import { UnsavedNavigationDialog } from './UnsavedNavigationDialog'
import { usePanelPreferences } from './usePanelPreferences'
import { useTicketEditor } from './useTicketEditor'

export function AgentTicketWorkspacePage() {
  const { ticketNumber: ticketNumberParam = '' } = useParams()
  const ticketNumber = parseTicketNumber(ticketNumberParam)
  const [searchParams] = useSearchParams()
  const originSearchEventId = validUuid(searchParams.get('originSearchEventId'))
  const session = useStaffSession()
  const staffId = session.staff?.id
  const interactionId = useMemo(createInteractionId, [staffId, ticketNumber])
  const queryClient = useQueryClient()
  const queryKey = [
    'agent-ticket',
    staffId,
    ticketNumber,
    interactionId,
  ] as const
  const query = useQuery({
    queryKey,
    queryFn: () =>
      getAgentTicket(
        ticketNumber ?? 0,
        interactionId,
        'NAVIGATION',
        originSearchEventId,
      ),
    enabled:
      session.status === 'authenticated' &&
      staffId !== undefined &&
      ticketNumber !== null,
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
  const refreshLatest = async () => {
    const latest = await getAgentTicket(
      ticketNumber,
      interactionId,
      'BACKGROUND',
    )
    queryClient.setQueryData(queryKey, latest)
    return latest
  }

  return (
    <TicketWorkspaceContent
      key={`${session.staff?.id ?? 'unknown'}:${ticketNumber}`}
      detail={detail}
      staffId={staffId ?? 'unknown'}
      refreshLatest={refreshLatest}
    />
  )
}

function TicketWorkspaceContent({
  detail,
  staffId,
  refreshLatest,
}: {
  detail: Awaited<ReturnType<typeof getAgentTicket>>
  staffId: string
  refreshLatest: () => Promise<Awaited<ReturnType<typeof getAgentTicket>>>
}) {
  const contextToggleRef = useRef<HTMLButtonElement>(null)
  const [refreshing, setRefreshing] = useState(false)
  const { preferences, setPropertyWidth, setContextWidth, toggleContext } =
    usePanelPreferences(staffId)
  const refreshWithStatus = async () => {
    setRefreshing(true)
    try {
      return await refreshLatest()
    } finally {
      setRefreshing(false)
    }
  }
  const editor = useTicketEditor({
    detail,
    staffId,
    refreshLatest: refreshWithStatus,
  })
  const ticket = detail.ticket
  const canUpdate = detail.capabilities.includes('UPDATE')

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
        onRefresh={() => void editor.refreshEditor()}
        refreshing={refreshing}
        unsaved={editor.isUnsaved}
      />
      <header className="ticket-titlebar">
        <div>
          <p className="agent-page-eyebrow">
            TICKET #{ticket.ticketNumber} · ALL_TICKETS READ
          </p>
          <h1 id="ticket-workspace-title">{ticket.subject}</h1>
          {ticket.sla ? (
            <p
              className={`workspace-sla sla-${ticket.sla.state.toLowerCase()}`}
            >
              <strong>First Reply · {ticket.sla.state}</strong>
              {ticket.sla.dueAt ? (
                <span>
                  {' · 기한 '}
                  {new Intl.DateTimeFormat('ko-KR', {
                    dateStyle: 'short',
                    timeStyle: 'short',
                  }).format(new Date(ticket.sla.dueAt))}
                </span>
              ) : null}
              {ticket.sla.policyVersion ? (
                <small>
                  {' · 정책 v'}
                  {ticket.sla.policyVersion} / 일정 v
                  {ticket.sla.scheduleVersion}
                </small>
              ) : null}
            </p>
          ) : null}
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
          <TicketPropertiesEditor
            detail={detail}
            localFields={editor.localFields}
            conflict={editor.conflict}
            conflictRef={editor.conflictRef}
            disabled={!canUpdate || editor.submitting}
            onFieldChange={editor.updateField}
            onResolveConflict={editor.resolveField}
            onReloadConflict={() => void editor.loadLatestForConflict()}
          />
        }
        conversationPanel={
          <TicketConversation
            comments={detail.comments}
            footer={
              canUpdate ? (
                <TicketReplyComposer
                  mode={editor.mode}
                  drafts={editor.comments}
                  submitting={editor.submitting}
                  canSubmit={editor.canSubmit}
                  error={editor.error}
                  success={editor.success}
                  warnings={editor.warnings}
                  internalOnly={ticket.isChild}
                  onModeChange={editor.setMode}
                  onDraftChange={editor.updateDraft}
                  onSubmit={() => void editor.submit()}
                />
              ) : undefined
            }
          />
        }
        contextPanel={
          preferences.contextCollapsed ? undefined : (
            <TicketContextPanel
              detail={detail}
              canUpdate={canUpdate}
              onCommandCompleted={editor.refreshEditor}
            />
          )
        }
      />
      <UnsavedNavigationDialog
        blocker={editor.blocker}
        submitting={editor.submitting}
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

function validUuid(value: string | null): string | undefined {
  return value &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
      value,
    )
    ? value
    : undefined
}
