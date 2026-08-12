import { NavLink, Outlet } from 'react-router'
import { AppShell, BrandMark } from '../../shared/ui/system'
import { useStaffSession } from '../staff-auth/StaffSessionContext'

export function AdminShell() {
  const session = useStaffSession()
  return (
    <AppShell
      className="admin-shell"
      contentId="admin-main"
      skipLabel="관리자 설정 내용으로 건너뛰기"
    >
      <header className="admin-header">
        <NavLink className="admin-brand" to="/admin/staff">
          <BrandMark compact />
          <strong>Deskseed 설정</strong>
        </NavLink>
        <nav aria-label="관리자 설정 메뉴">
          <NavLink to="/admin/staff">직원</NavLink>
          <NavLink to="/admin/groups">그룹</NavLink>
          {session.staff?.capabilities.includes(
            'integration:clients:manage',
          ) ? (
            <NavLink to="/integrations/clients">API 클라이언트</NavLink>
          ) : null}
          {session.staff?.capabilities.includes(
            'integration:systems:manage',
          ) ? (
            <NavLink to="/integrations/systems">외부 시스템</NavLink>
          ) : null}
          <NavLink to="/admin/access/customer-mode">고객 접근</NavLink>
          <NavLink to="/agent/home">상담사 화면</NavLink>
        </nav>
        <div className="admin-identity">
          <span>{session.staff?.displayName}</span>
          <button
            className="button secondary small"
            type="button"
            onClick={session.signOut}
          >
            로그아웃
          </button>
        </div>
      </header>
      <main className="admin-main" id="admin-main" tabIndex={-1}>
        <Outlet />
      </main>
    </AppShell>
  )
}
