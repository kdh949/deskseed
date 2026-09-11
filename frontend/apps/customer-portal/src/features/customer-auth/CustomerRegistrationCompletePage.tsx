import { Link, Navigate, useLocation } from 'react-router'
import { ScreenState } from '../../design-system'
import { useCustomerSession } from './CustomerSessionContext'
import { CustomerRegisterPage } from './CustomerRegisterPage'
import { customerAuthDestination } from './customerAuthDestination'
export function CustomerRegistrationCompletePage() {
  const session = useCustomerSession()
  const location = useLocation()
  if (session.status === 'loading')
    return (
      <div className="customer-page">
        <ScreenState kind="loading" title="로그인 상태를 확인하고 있습니다." />
      </div>
    )
  if (session.status === 'error')
    return (
      <div className="customer-page">
        <ScreenState
          kind="error"
          title="로그인 상태를 확인할 수 없습니다."
          action={
            <Link to="/customer/sign-in" state={location.state}>
              다시 로그인
            </Link>
          }
        />
      </div>
    )
  if (!session.customer)
    return <Navigate replace to="/customer/sign-in" state={location.state} />
  if (session.customer.registrationState === 'COMPLETE')
    return (
      <Navigate
        replace
        to={customerAuthDestination(
          session.customer,
          (location.state as { from?: unknown } | null)?.from,
        )}
      />
    )
  return (
    <CustomerRegisterPage
      key={session.customer.id}
      customer={session.customer}
    />
  )
}
