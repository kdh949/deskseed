import { Navigate, Outlet, useLocation } from 'react-router'
import { StaffSessionProvider, useStaffSession } from './StaffSessionContext'

export function StaffSessionLayout() {
  return (
    <StaffSessionProvider>
      <Outlet />
    </StaffSessionProvider>
  )
}

export function StaffRoute() {
  const session = useStaffSession()
  const location = useLocation()

  if (session.status === 'loading') {
    return <main className="staff-gate">직원 세션을 확인하고 있습니다.</main>
  }
  if (session.status === 'error') {
    return (
      <main className="staff-gate" role="alert">
        <h1>세션을 확인할 수 없습니다.</h1>
        <button
          className="button primary"
          type="button"
          onClick={session.retry}
        >
          다시 시도
        </button>
      </main>
    )
  }
  if (session.status === 'anonymous') {
    return (
      <Navigate to="/agent/login" replace state={{ from: location.pathname }} />
    )
  }
  return <Outlet />
}

export function AdminRoute() {
  const session = useStaffSession()
  if (session.staff?.role !== 'ADMIN') {
    return <Navigate to="/agent/home" replace />
  }
  return <Outlet />
}
