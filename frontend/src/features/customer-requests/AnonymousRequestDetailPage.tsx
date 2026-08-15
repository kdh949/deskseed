import { useQuery } from '@tanstack/react-query'
import { useLayoutEffect, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import {
  addAnonymousRequestComment,
  ApiError,
  getPublicRequest,
} from '../../api/client'
import { RetryButton, ScreenState } from '../../design-system'
import {
  consumeRequestAccessTokenFragment,
  readRequestAccessToken,
} from '../customer-portal/customerAccessToken'
import { CustomerRequestConversation } from './CustomerRequestConversation'

export function AnonymousRequestDetailPage() {
  const { ticketNumber: ticketNumberParameter } = useParams()
  const ticketNumber = parseTicketNumber(ticketNumberParameter)
  const access = useRequestAccessCapability(ticketNumber)
  const query = useQuery({
    enabled: ticketNumber !== null && access.ready && access.hasAccess,
    queryKey: ['anonymous-customer-request', ticketNumber],
    queryFn: async () => {
      if (ticketNumber === null) throw new Error('invalid-ticket-number')
      const token = readRequestAccessToken(window.sessionStorage, ticketNumber)
      if (!token) throw new ApiError('request-access-token-unavailable', 404)
      return getPublicRequest(ticketNumber, token)
    },
    retry: false,
  })

  if (ticketNumber === null) {
    return (
      <CustomerDetailState
        description="문의 번호를 확인한 뒤 이메일의 문의 링크를 다시 열어 주세요."
        kind="not-found"
        title="올바른 문의 번호가 아닙니다."
      />
    )
  }

  if (!access.ready) {
    return (
      <CustomerDetailState
        kind="loading"
        title="안전한 문의 링크를 확인하고 있습니다."
      />
    )
  }

  if (!access.hasAccess) {
    return (
      <CustomerDetailState
        action={<Link to="/requests/lookup">문의 조회 안내</Link>}
        description="접근 토큰은 주소창의 fragment에서만 읽고 이 브라우저의 해당 문의 세션에만 보관합니다."
        kind="denied"
        title="이메일 문의 링크가 필요합니다."
      />
    )
  }

  if (query.isPending) {
    return (
      <CustomerDetailState
        kind="loading"
        title="공개 문의 대화를 불러오고 있습니다."
      />
    )
  }

  if (query.isError) {
    return (
      <AnonymousDetailError
        error={query.error}
        onRetry={() => void query.refetch()}
      />
    )
  }

  return (
    <CustomerRequestConversation
      onFollowUpConflict={() => void query.refetch()}
      onFollowUpSubmitted={() => void query.refetch()}
      onSubmitFollowUp={async (body, clientCommandId) => {
        const token = readRequestAccessToken(
          window.sessionStorage,
          ticketNumber,
        )
        if (!token) throw new ApiError('request-access-token-unavailable', 404)
        return addAnonymousRequestComment(
          ticketNumber,
          token,
          body,
          clientCommandId,
        )
      }}
      request={query.data}
    />
  )
}

function useRequestAccessCapability(ticketNumber: number | null) {
  const [state, setState] = useState({
    hasAccess: false,
    ready: false,
    ticketNumber: null as number | null,
  })

  useLayoutEffect(() => {
    if (ticketNumber === null) {
      setState({ hasAccess: false, ready: true, ticketNumber: null })
      return
    }
    const token = consumeRequestAccessTokenFragment({
      history: window.history,
      location: window.location,
      sessionStorage: window.sessionStorage,
      ticketNumber,
    })
    setState({ hasAccess: token !== null, ready: true, ticketNumber })
  }, [ticketNumber])

  return {
    hasAccess: state.ticketNumber === ticketNumber && state.hasAccess,
    ready: state.ticketNumber === ticketNumber && state.ready,
  }
}

function AnonymousDetailError({
  error,
  onRetry,
}: {
  error: unknown
  onRetry: () => void
}) {
  const status = statusOf(error)
  const requestId = requestIdOf(error)
  if (status === 404) {
    return (
      <CustomerDetailState
        action={<Link to="/requests/lookup">문의 조회 안내</Link>}
        description="문의 링크가 만료되었거나 현재 브라우저에서 더 이상 사용할 수 없습니다."
        kind="not-found"
        requestId={requestId}
        title="문의 내용을 찾을 수 없습니다."
      />
    )
  }
  if (status === 403) {
    return (
      <CustomerDetailState
        action={<Link to="/requests/lookup">문의 조회 안내</Link>}
        description="이 문의를 볼 수 있는 권한을 확인할 수 없습니다."
        kind="denied"
        requestId={requestId}
        title="문의 조회가 허용되지 않았습니다."
      />
    )
  }
  if (status === 429) {
    return (
      <CustomerDetailState
        action={<RetryButton onClick={onRetry} />}
        description={`${formatRetryAfter(retryAfterOf(error))} 후 다시 시도해 주세요.`}
        kind="error"
        requestId={requestId}
        title="문의 조회가 잠시 제한되었습니다."
      />
    )
  }
  return (
    <CustomerDetailState
      action={<RetryButton onClick={onRetry} />}
      description="공개 문의 대화를 불러오지 못했습니다. 입력한 후속 답변 초안은 이 화면에 표시되지 않았습니다."
      kind="error"
      requestId={requestId}
      title="문의 내용을 불러올 수 없습니다."
    />
  )
}

function CustomerDetailState({
  action,
  description,
  kind,
  requestId,
  title,
}: {
  action?: ReactNode
  description?: string
  kind: 'denied' | 'error' | 'loading' | 'not-found'
  requestId?: string
  title: string
}) {
  return (
    <main className="customer-page">
      <ScreenState
        action={action}
        description={description}
        kind={kind}
        requestId={requestId}
        title={title}
      />
    </main>
  )
}

function parseTicketNumber(value: string | undefined) {
  if (!value || !/^\d+$/.test(value)) return null
  const ticketNumber = Number(value)
  return Number.isSafeInteger(ticketNumber) && ticketNumber > 0
    ? ticketNumber
    : null
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
