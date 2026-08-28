import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { RetryButton, ScreenState } from '../../design-system'
import { CustomerRequestConversation } from '../customer-requests/CustomerRequestConversation'
import { useCustomerSession } from '../customer-auth/CustomerSessionContext'
import {
  addCustomerFollowUp,
  ApiError,
  downloadAuthenticatedCustomerAttachment,
  getCustomerRequest,
  uploadAuthenticatedCustomerAttachment,
} from './api/customerPortalClient'
import { customerRequestQueryKeys } from './customerRequestQueryKeys'

export function CustomerRequestDetailPage() {
  const { ticketNumber: ticketNumberParameter } = useParams()
  const ticketNumber = parseTicketNumber(ticketNumberParameter)
  const session = useCustomerSession()
  const customerId = session.customer?.id ?? 'anonymous'
  const query = useQuery({
    enabled: ticketNumber !== null && session.customer !== null,
    queryKey: customerRequestQueryKeys.detail(customerId, ticketNumber ?? 0),
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
  if (session.status === 'loading') {
    return (
      <CustomerDetailState
        kind="loading"
        title="문의 내용을 불러오고 있습니다."
      />
    )
  }
  if (session.customer === null) {
    return (
      <CustomerDetailState
        action={<Link to="/customer/sign-in">고객 로그인</Link>}
        description="로그인한 뒤 내 문의 목록에서 다시 열어 주세요."
        kind="denied"
        title="문의 내용을 열 수 없습니다."
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
      downloadAttachment={(attachmentId) =>
        downloadAuthenticatedCustomerAttachment(ticketNumber, attachmentId)
      }
      onFollowUpConflict={() => void query.refetch()}
      onFollowUpSubmitted={() => void query.refetch()}
      onSubmitFollowUp={(body, clientCommandId, attachmentIds) =>
        addCustomerFollowUp(ticketNumber, body, clientCommandId, attachmentIds)
      }
      request={query.data}
      uploadAttachment={(file) =>
        uploadAuthenticatedCustomerAttachment(ticketNumber, file)
      }
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
        description="로그인한 뒤 내 문의 목록에서 다시 열어 주세요."
        kind="denied"
        requestId={apiError.requestId}
        title="문의 내용을 열 수 없습니다."
      />
    )
  }
  if (apiError?.status === 404) {
    return (
      <CustomerDetailState
        action={<Link to="/account/requests">내 문의 목록</Link>}
        description="내 문의 목록으로 돌아가 문의를 다시 선택해 주세요."
        kind="not-found"
        requestId={apiError.requestId}
        title="문의 내용을 열 수 없습니다."
      />
    )
  }
  return (
    <CustomerDetailState
      action={<RetryButton onClick={onRetry} />}
      description="잠시 후 다시 시도해 주세요."
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
    <div className="customer-page">
      <ScreenState
        action={action}
        description={description}
        kind={kind}
        requestId={requestId}
        title={title}
      />
    </div>
  )
}

function parseTicketNumber(value: string | undefined) {
  if (!value || !/^\d+$/.test(value)) return null
  const ticketNumber = Number(value)
  return Number.isSafeInteger(ticketNumber) && ticketNumber > 0
    ? ticketNumber
    : null
}
