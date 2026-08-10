import { NavLink, Outlet } from 'react-router'
import { useStaffSession } from '../staff-auth/StaffSessionContext'

export function AdminShell() {
  const session = useStaffSession()
  return (
    <div className="admin-shell">
      <header className="admin-header">
        <NavLink className="admin-brand" to="/admin/staff">
          <span aria-hidden="true">D</span>
          <strong>Deskseed 설정</strong>
        </NavLink>
        <nav aria-label="관리자 설정 메뉴">
          <NavLink to="/admin/staff">직원</NavLink>
          <NavLink to="/admin/groups">그룹</NavLink>
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
      <main className="admin-main">
        <Outlet />
      </main>
    </div>
  )
}
