import { Link, Navigate, Outlet, useLocation, useNavigate } from 'react-router'
import { useState, type ReactNode } from 'react'
import {
  canUseAgentWorkspace,
  canReadAudit,
  canManageStaff,
  staffHome,
} from './staffNavigation'
import { SeedButton, SeedFeedbackState } from '../../design-system/canonical'
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
      <StaffGate title="직원 세션을 확인하고 있습니다.">
        <SeedFeedbackState
          kind="loading"
          compact
          title="직원 세션을 확인하고 있습니다."
        />
      </StaffGate>
    )
  }
  if (session.status === 'error') {
    return (
      <StaffGate title="세션을 확인할 수 없습니다.">
        <SeedFeedbackState
          kind="error"
          title="세션을 확인할 수 없습니다."
          action={
            <SeedButton onClick={session.retry} variant="primary">
              다시 시도
            </SeedButton>
          }
        />
      </StaffGate>
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
  const allowed = canUseAgentWorkspace(session.staff)
  if (!allowed) {
    return (
      <StaffGate title="상담사 작업 공간 권한이 필요합니다.">
        <SeedFeedbackState
          kind="denied"
          title="상담사 작업 공간 권한이 필요합니다."
          description="이 계정은 티켓 큐와 작업 공간을 열 수 없습니다."
          action={<DeniedActions />}
        />
      </StaffGate>
    )
  }
  return <Outlet />
}

export function AuditRoute() {
  const session = useStaffSession()
  const allowed = canReadAudit(session.staff)
  if (!allowed) {
    return (
      <StaffGate title="감사 권한이 필요합니다.">
        <SeedFeedbackState
          kind="denied"
          title="감사 권한이 필요합니다."
          description="이 계정은 감사 탐색기와 내보내기 작업을 열 수 없습니다."
          action={<DeniedActions />}
        />
      </StaffGate>
    )
  }
  return <Outlet />
}

export function AdminRoute() {
  const session = useStaffSession()
  const allowed = canManageStaff(session.staff)
  if (!allowed) {
    return (
      <StaffGate title="관리자 운영 권한이 필요합니다.">
        <SeedFeedbackState
          kind="denied"
          title="관리자 운영 권한이 필요합니다."
          description="이 계정은 운영 설정과 관리자 작업을 열 수 없습니다."
          action={<DeniedActions />}
        />
      </StaffGate>
    )
  }
  return <Outlet />
}

function StaffGate({
  children,
  title,
}: {
  children: ReactNode
  title: string
}) {
  return (
    <main className="seed-route-feedback">
      <h1 className="seed-visually-hidden">{title}</h1>
      {children}
    </main>
  )
}

function DeniedActions() {
  const session = useStaffSession()
  const navigate = useNavigate()
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState(false)
  async function switchAccount() {
    if (pending) return
    setPending(true)
    setFailure(false)
    try {
      await session.signOut()
      navigate('/agent/login', { replace: true })
    } catch {
      setFailure(true)
    } finally {
      setPending(false)
    }
  }
  return (
    <div>
      {session.staff && (
        <p>
          <Link to={staffHome(session.staff)}>내 작업 화면으로 이동</Link>
        </p>
      )}
      <SeedButton onClick={() => void switchAccount()} disabled={pending}>
        다른 계정으로 로그인
      </SeedButton>
      {failure && <p>로그아웃하지 못했습니다. 다시 시도해 주세요.</p>}
    </div>
  )
}
