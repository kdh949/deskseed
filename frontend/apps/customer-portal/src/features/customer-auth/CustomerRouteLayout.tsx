import { useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router'
import { CustomerSiteLayout, Notification } from '../../design-system'
import { CustomerAuthApiError } from './api/customerAuthClient'
import {
  CustomerSessionProvider,
  useCustomerSession,
} from './CustomerSessionContext'

export function CustomerRouteLayout() {
  return (
    <CustomerSessionProvider>
      <CustomerRouteContent />
    </CustomerSessionProvider>
  )
}

function CustomerRouteContent() {
  const navigate = useNavigate()
  const location = useLocation()
  const session = useCustomerSession()
  const [logoutError, setLogoutError] = useState<LogoutError | null>(null)

  const signOut = async () => {
    setLogoutError(null)
    navigate('/', { replace: true })
    try {
      await session.signOut()
    } catch (error) {
      setLogoutError(toLogoutError(error))
    }
  }

  return (
    <CustomerSiteLayout
      onSignOut={() => void signOut()}
      presentation={location.pathname === '/' ? 'workspace' : 'site'}
      session={{
        customer: session.customer,
        signingOut: session.signingOut,
        status: session.status,
      }}
    >
      {logoutError ? (
        <div className="customer-route-notification">
          <Notification title="로그아웃을 완료하지 못했습니다." tone="danger">
            <p>
              {logoutError.requestId
                ? `요청 ID: ${logoutError.requestId}`
                : '현재 세션을 확인한 뒤 다시 시도해 주세요.'}
            </p>
          </Notification>
        </div>
      ) : null}
      <Outlet />
    </CustomerSiteLayout>
  )
}

interface LogoutError {
  requestId?: string
}

function toLogoutError(error: unknown): LogoutError {
  if (error instanceof CustomerAuthApiError)
    return { requestId: error.requestId }
  return {}
}
