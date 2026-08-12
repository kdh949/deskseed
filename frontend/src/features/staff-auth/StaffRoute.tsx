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
            <Link
              className="button primary"
              to={
                session.staff?.role === 'SECURITY_AUDITOR'
                  ? '/audit/activity'
                  : '/agent/home'
              }
            >
              {session.staff?.role === 'SECURITY_AUDITOR'
                ? '감사 조사 화면으로 이동'
                : '상담사 작업 공간으로 이동'}
            </Link>
          }
        />
      </main>
    )
  }
  return <Outlet />
}

export function IntegrationAdminRoute() {
  return (
    <IntegrationCapabilityRoute
      capability="integration:clients:manage"
      title="연동 클라이언트 관리 권한이 필요합니다."
      description="보안 감사자와 상담사 계정은 API key를 발급하거나 변경할 수 없습니다."
    />
  )
}

export function ExternalSystemAdminRoute() {
  return (
    <IntegrationCapabilityRoute
      capability="integration:systems:manage"
      title="외부 시스템 관리 권한이 필요합니다."
      description="보안 감사자와 상담사 계정은 외부 시스템과 허용 hostname을 변경할 수 없습니다."
    />
  )
}

function IntegrationCapabilityRoute({
  capability,
  title,
  description,
}: {
  capability: string
  title: string
  description: string
}) {
  const session = useStaffSession()
  const allowed =
    session.staff?.role === 'ADMIN' &&
    session.staff.capabilities.includes(capability)
  if (!allowed) {
    return (
      <main className="staff-gate">
        <ScreenState
          kind="denied"
          title={title}
          description={description}
          action={
            <Link
              className="button primary"
              to={
                session.staff?.role === 'SECURITY_AUDITOR'
                  ? '/audit/activity'
                  : '/agent/home'
              }
            >
              허용된 작업 공간으로 이동
            </Link>
          }
        />
      </main>
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
          description="보안 감사자 계정은 티켓 화면과 변경 작업을 사용할 수 없습니다."
          action={
            <Link className="button primary" to="/audit/activity">
              감사 조사 화면으로 이동
            </Link>
          }
        />
      </main>
    )
  }
  return <Outlet />
}

export function AuditRoute() {
  const session = useStaffSession()
  const allowed =
    session.staff?.role === 'SECURITY_AUDITOR' &&
    session.staff.capabilities.includes('audit:activity:read')
  if (!allowed) {
    return (
      <main className="staff-gate">
        <ScreenState
          kind="denied"
          title="보안 감사자 권한이 필요합니다."
          description="이 계정은 통합 감사 원장을 조사할 수 없습니다."
          action={
            <Link
              className="button primary"
              to={
                session.staff?.role === 'ADMIN' ? '/admin/staff' : '/agent/home'
              }
            >
              허용된 작업 공간으로 이동
            </Link>
          }
        />
      </main>
    )
  }
  return <Outlet />
}
