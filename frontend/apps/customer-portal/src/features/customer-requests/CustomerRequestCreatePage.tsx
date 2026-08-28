import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router'
import type { ReactNode } from 'react'
import { submitRequest, submitRequestWithAttachments } from '../../api/client'
import { RetryButton, ScreenState } from '../../design-system'
import { useCustomerSession } from '../customer-auth/CustomerSessionContext'
import { getCustomerAccessMode } from '../customer-auth/api/customerAuthClient'
import { CustomerRequestForm } from './CustomerRequestForm'
import { storeRequestAccessToken } from '../customer-portal/customerAccessToken'

export function CustomerRequestCreatePage() {
  const session = useCustomerSession()
  const navigate = useNavigate()
  const accessModeQuery = useQuery({
    enabled:
      session.status === 'anonymous' || session.status === 'authenticated',
    queryKey: ['customer-access-mode'],
    queryFn: getCustomerAccessMode,
  })

  if (session.status === 'loading') {
    return (
      <CustomerRouteState
        kind="loading"
        title="로그인 상태를 확인하고 있습니다."
      />
    )
  }

  if (session.status === 'error') {
    return (
      <CustomerRouteState
        action={<RetryButton onClick={() => void session.retry()} />}
        description="로그인 상태를 확인한 뒤 다시 시도해 주세요."
        kind="error"
        title="로그인 상태를 확인할 수 없습니다."
      />
    )
  }

  if (accessModeQuery.isPending) {
    return (
      <CustomerRouteState
        kind="loading"
        title="문의 접수 가능 여부를 확인하고 있습니다."
      />
    )
  }

  if (accessModeQuery.isError) {
    return (
      <CustomerRouteState
        action={<RetryButton onClick={() => void accessModeQuery.refetch()} />}
        description="접수 가능 여부를 확인한 뒤 다시 시도해 주세요."
        kind="error"
        title="문의 접수 가능 여부를 확인할 수 없습니다."
      />
    )
  }

  if (
    accessModeQuery.data === 'REGISTRATION_REQUIRED' &&
    session.status !== 'authenticated'
  ) {
    return (
      <CustomerRouteState
        action={<Link to="/customer/sign-in">고객 로그인</Link>}
        description="로그인용 이메일 링크를 받은 뒤 문의를 접수해 주세요."
        kind="denied"
        title="로그인이 필요한 문의 접수입니다."
      />
    )
  }

  return (
    <CustomerRequestForm
      onSubmitted={(submitted) => {
        storeRequestAccessToken(
          window.sessionStorage,
          submitted.ticketNumber,
          submitted.accessToken,
        )
        navigate(`/requests/submitted/${submitted.ticketNumber}`, {
          state: { submitted },
        })
      }}
      submit={(input, files = []) =>
        files.length
          ? submitRequestWithAttachments(
              input,
              files,
              session.status === 'authenticated',
            )
          : submitRequest(input, session.status === 'authenticated')
      }
    />
  )
}

function CustomerRouteState({
  action,
  description,
  kind,
  title,
}: {
  action?: ReactNode
  description?: string
  kind: 'denied' | 'error' | 'loading'
  title: string
}) {
  return (
    <div className="customer-page">
      <ScreenState
        action={action}
        description={description}
        kind={kind}
        title={title}
      />
    </div>
  )
}
