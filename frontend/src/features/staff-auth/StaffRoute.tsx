import { Link, Navigate, Outlet, useLocation } from 'react-router'
import { ScreenState } from '../../shared/ui/system'
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
    return (
      <main className="staff-gate">
        <ScreenState
          kind="loading"
          compact
          title="직원 세션을 확인하고 있습니다."
        />
      </main>
    )
  }
  if (session.status === 'error') {
    return (
      <main className="staff-gate">
        <ScreenState
          kind="error"
          title="세션을 확인할 수 없습니다."
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
    return (
      <main className="staff-gate">
        <ScreenState
          kind="denied"
          title="관리자 권한이 필요합니다."
          description="이 계정은 관리자 설정을 열 수 없습니다."
          action={
            <Link className="button primary" to="/agent/home">
              상담사 작업 공간으로 이동
            </Link>
          }
        />
      </main>
    )
  }
  return <Outlet />
}
