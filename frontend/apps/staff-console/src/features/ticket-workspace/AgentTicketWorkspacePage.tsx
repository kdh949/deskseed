import { useQuery } from '@tanstack/react-query'
import { useCallback, useMemo, useRef } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { ApiError, getAgentTicket } from '../../api/client'
import { createOpaqueUuid } from '../../api/uuid'
import { SeedButton, SeedFeedbackState } from '../../design-system/canonical'
import { useStaffSession } from '../staff-auth/StaffSessionContext'
import { AgentTicketEditorWorkspace } from './AgentTicketEditorWorkspace'

export function AgentTicketWorkspacePage() {
  const { ticketNumber: ticketNumberParam = '' } = useParams()
  const ticketNumber = parseTicketNumber(ticketNumberParam)
  const session = useStaffSession()
  const location = useLocation()
  const originSearchEventId = readOriginSearchEventId(location.state)
  const interactionId = useMemo(createOpaqueUuid, [ticketNumber])
  const successfulInteractionId = useRef<string | null>(null)
  const query = useQuery({
    queryKey: [
      'agent-ticket',
      ticketNumber,
      interactionId,
      originSearchEventId,
    ],
    queryFn: async () => {
      const readIntent =
        successfulInteractionId.current === interactionId
          ? 'BACKGROUND'
          : 'NAVIGATION'
      const detail = await getAgentTicket(
        ticketNumber ?? 0,
        interactionId,
        readIntent,
        originSearchEventId,
      )
      successfulInteractionId.current = interactionId
      return detail
    },
    enabled: ticketNumber !== null && session.staff !== null,
    retry: false,
  })
  const refreshLatest = useCallback(async () => {
    const result = await query.refetch()
    if (result.error) throw result.error
    if (!result.data) throw new Error('최신 티켓 정보를 받지 못했습니다.')
    return result.data
  }, [query.refetch])

  if (ticketNumber === null) return <InvalidTicketRoute />
  if (session.staff === null) return <MissingStaffSession />
  if (query.isPending) return <WorkspaceLoading />
  if (query.isError && !query.data) {
    return <WorkspaceError error={query.error} retry={() => query.refetch()} />
  }

  return (
    <AgentTicketEditorWorkspace
      detail={query.data}
      extensionAccess={{
        role: session.staff.role,
        capabilities: session.staff.capabilities,
      }}
      key={`${session.staff.id}:${ticketNumber}`}
      refreshLatest={refreshLatest}
      staffId={session.staff.id}
    />
  )
}

function readOriginSearchEventId(state: unknown) {
  if (!state || typeof state !== 'object' || !('originSearchEventId' in state))
    return undefined
  const value = (state as { originSearchEventId?: unknown }).originSearchEventId
  return typeof value === 'string' ? value : undefined
}

function WorkspaceLoading() {
  return (
    <section className="seed-route-feedback">
      <SeedFeedbackState
        compact
        kind="loading"
        title="티켓을 불러오는 중입니다."
      />
    </section>
  )
}

function MissingStaffSession() {
  return (
    <section className="seed-route-feedback">
      <SeedFeedbackState
        kind="denied"
        title="직원 세션을 확인할 수 없습니다."
      />
    </section>
  )
}

function InvalidTicketRoute() {
  return (
    <section className="seed-route-feedback">
      <SeedFeedbackState
        action={<Link to="/agent/views/my-open">내 티켓으로 돌아가기</Link>}
        description="올바른 티켓 번호로 다시 시도해 주세요."
        kind="not-found"
        title="티켓 번호를 확인할 수 없습니다."
      />
    </section>
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
    <section className="seed-route-feedback">
      <SeedFeedbackState
        action={
          <div className="seed-state-actions">
            <SeedButton onClick={retry}>다시 시도</SeedButton>
            <Link to="/agent/views/my-open">내 티켓으로 돌아가기</Link>
          </div>
        }
        description={`${
          apiError?.status === 403
            ? '현재 계정으로 이 티켓을 볼 수 없습니다.'
            : apiError?.status === 404
              ? '티켓이 없거나 더 이상 열 수 없습니다.'
              : '잠시 후 다시 시도해 주세요.'
        }${apiError?.requestId ? ` 요청 ID: ${apiError.requestId}` : ''}`}
        kind={kind}
        title="티켓을 열 수 없습니다."
      />
    </section>
  )
}

function parseTicketNumber(value: string): number | null {
  if (!/^[1-9]\d*$/.test(value)) return null
  const ticketNumber = Number(value)
  return Number.isSafeInteger(ticketNumber) ? ticketNumber : null
}
