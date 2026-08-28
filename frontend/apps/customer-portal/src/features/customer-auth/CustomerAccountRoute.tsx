import { Navigate, Outlet, useLocation } from 'react-router'
import type { ReactNode } from 'react'
import { RetryButton, ScreenState } from '../../design-system'
import { useCustomerSession } from './CustomerSessionContext'

export function CustomerAccountRoute() {
  const location = useLocation()
  const session = useCustomerSession()

  if (session.status === 'loading') {
    return (
      <CustomerAccountState
        kind="loading"
        title="로그인 상태를 확인하고 있습니다."
      />
    )
  }

  if (session.status === 'error') {
    return (
      <CustomerAccountState
        action={<RetryButton onClick={() => void session.retry()} />}
        description="로그인 상태를 다시 확인해 주세요."
        kind="error"
        title="로그인 상태를 확인할 수 없습니다."
      />
    )
  }

  if (session.status !== 'authenticated' || !session.customer) {
    return (
      <Navigate
        replace
        state={{ from: accountDestination(location.pathname) }}
        to="/customer/sign-in"
      />
    )
  }

  return <Outlet />
}

function CustomerAccountState({
  action,
  description,
  kind,
  title,
}: {
  action?: ReactNode
  description?: string
  kind: 'error' | 'loading'
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

function accountDestination(pathname: string) {
  return pathname.startsWith('/account/') ? pathname : '/account/requests'
}
