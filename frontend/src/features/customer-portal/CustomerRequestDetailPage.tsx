import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { RetryButton, ScreenState } from '../../design-system'
import { CustomerRequestConversation } from '../customer-requests/CustomerRequestConversation'
import {
  addCustomerFollowUp,
  ApiError,
  getCustomerRequest,
} from './api/customerPortalClient'

export function CustomerRequestDetailPage() {
  const { ticketNumber: ticketNumberParameter } = useParams()
  const ticketNumber = parseTicketNumber(ticketNumberParameter)
  const query = useQuery({
    enabled: ticketNumber !== null,
    queryKey: ['customer-request-detail', ticketNumber],
    queryFn: () => {
      if (ticketNumber === null) throw new Error('invalid-ticket-number')
      return getCustomerRequest(ticketNumber)
    },
    retry: false,
  })

  if (ticketNumber === null) {
    return (
      <CustomerDetailState
        description="문의 번호를 확인한 뒤 내 문의 목록으로 돌아가 주세요."
        kind="not-found"
        title="올바른 문의 번호가 아닙니다."
      />
    )
  }
  if (query.isPending) {
    return (
      <CustomerDetailState
        kind="loading"
        title="내 문의를 불러오고 있습니다."
      />
    )
  }
  if (query.isError) {
    return (
      <CustomerAccountDetailError
        error={query.error}
        onRetry={() => void query.refetch()}
      />
    )
  }

  return (
    <CustomerRequestConversation
      onFollowUpConflict={() => void query.refetch()}
      onFollowUpSubmitted={() => void query.refetch()}
      onSubmitFollowUp={(body, clientCommandId) =>
        addCustomerFollowUp(ticketNumber, body, clientCommandId)
      }
      request={query.data}
    />
  )
}

function CustomerAccountDetailError({
  error,
  onRetry,
}: {
  error: unknown
  onRetry: () => void
}) {
  const apiError = error instanceof ApiError ? error : null
  if (apiError?.status === 401 || apiError?.status === 403) {
    return (
      <CustomerDetailState
        action={<Link to="/customer/sign-in">고객 로그인</Link>}
        description="내 문의는 로그인한 고객 계정에서만 볼 수 있습니다."
        kind="denied"
        requestId={apiError.requestId}
        title="내 문의 접근이 허용되지 않았습니다."
      />
    )
  }
  if (apiError?.status === 404) {
    return (
      <CustomerDetailState
        action={<Link to="/account/requests">내 문의 목록</Link>}
        description="문의가 없거나 현재 계정에 연결되어 있지 않습니다."
        kind="not-found"
        requestId={apiError.requestId}
        title="문의 내용을 찾을 수 없습니다."
      />
    )
  }
  return (
    <CustomerDetailState
      action={<RetryButton onClick={onRetry} />}
      description="고객 문의 내용을 불러오지 못했습니다."
      kind="error"
      requestId={apiError?.requestId}
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
