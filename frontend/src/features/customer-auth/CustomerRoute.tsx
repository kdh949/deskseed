import { Navigate, Outlet, useLocation } from 'react-router'
import { ScreenState } from '../../shared/ui/system'
import {
  CustomerSessionProvider,
  useCustomerSession,
} from './CustomerSessionContext'

export function CustomerSessionLayout() {
  return (
    <CustomerSessionProvider>
      <Outlet />
    </CustomerSessionProvider>
  )
}

export function CustomerRoute() {
  const session = useCustomerSession()
  const location = useLocation()
  if (session.status === 'loading') {
    return <ScreenState kind="loading" title="고객 세션을 확인하고 있습니다." />
  }
  if (session.status === 'error') {
    return (
      <ScreenState
        kind="error"
        title="고객 세션을 확인할 수 없습니다."
        action={
          <button
            className="button primary"
            type="button"
            onClick={session.retry}
          >
            다시 시도
          </button>
        }
      />
    )
  }
  if (session.status === 'anonymous') {
    return (
      <Navigate
        to="/customer/sign-in"
        replace
        state={{ from: location.pathname }}
      />
    )
  }
  return <Outlet />
}
