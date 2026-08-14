import { Link, Navigate, Outlet, useLocation } from 'react-router'
import { DsButton, ScreenState } from '../../design-system'
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
            <DsButton onClick={session.retry} tone="primary">
              다시 시도
            </DsButton>
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

export function AgentRoute() {
  const session = useStaffSession()
  const allowed =
    (session.staff?.role === 'ADMIN' || session.staff?.role === 'AGENT') &&
    session.staff.capabilities.includes('AGENT_WORKSPACE')
  if (!allowed) {
    return (
      <main className="staff-gate">
        <ScreenState
          kind="denied"
          title="상담사 작업 공간 권한이 필요합니다."
          description="이 계정은 티켓 큐와 작업 공간을 열 수 없습니다."
          action={<Link to="/agent/login">다른 계정으로 로그인</Link>}
        />
      </main>
    )
  }
  return <Outlet />
}

export function AuditRoute() {
  const session = useStaffSession()
  const allowed = session.staff?.role === 'SECURITY_AUDITOR'
  if (!allowed) {
    return (
      <main className="staff-gate">
        <ScreenState
          kind="denied"
          title="감사 권한이 필요합니다."
          description="이 계정은 감사 탐색기와 내보내기 작업을 열 수 없습니다."
          action={<Link to="/agent/login">다른 계정으로 로그인</Link>}
        />
      </main>
    )
  }
  return <Outlet />
}
