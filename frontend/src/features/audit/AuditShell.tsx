import { NavLink, Outlet } from 'react-router'
import { AppShell, BrandMark } from '../../shared/ui/system'
import { useStaffSession } from '../staff-auth/StaffSessionContext'

export function AuditShell() {
  const session = useStaffSession()
  return (
    <AppShell
      className="audit-shell"
      contentId="audit-main"
      skipLabel="감사 조사 내용으로 건너뛰기"
    >
      <header className="audit-header">
        <NavLink className="audit-brand" to="/audit/activity">
          <BrandMark compact />
          <span>
            <strong>Deskseed Audit</strong>
            <small>Security investigation</small>
          </span>
        </NavLink>
        <nav aria-label="감사 센터 메뉴">
          <NavLink to="/audit/activity">Activity Explorer</NavLink>
        </nav>
        <div className="audit-identity">
          <span>{session.staff?.displayName}</span>
          <strong>READ ONLY</strong>
          <button
            className="button secondary small"
            type="button"
            onClick={session.signOut}
          >
            로그아웃
          </button>
        </div>
      </header>
      <main className="audit-main" id="audit-main" tabIndex={-1}>
        <Outlet />
      </main>
    </AppShell>
  )
}
