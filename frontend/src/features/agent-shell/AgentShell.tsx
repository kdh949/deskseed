import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { NavLink, Outlet } from 'react-router'
import { listAgentViews } from '../../api/client'
import {
  AppShell,
  NavRail,
  WorkSidebar,
  type NavRailItem,
} from '../../shared/ui/system'
import { useStaffSession } from '../staff-auth/StaffSessionContext'

export function AgentShell() {
  const session = useStaffSession()
  const staffId = session.staff?.id
  const storageKey = `deskseed:agent:${staffId ?? 'unknown'}:work-nav-collapsed:v1`
  const [collapsed, setCollapsed] = useState(
    () => localStorage.getItem(storageKey) === 'true',
  )
  const views = useQuery({
    queryKey: ['agent-views', staffId],
    queryFn: listAgentViews,
    enabled: session.status === 'authenticated' && staffId !== undefined,
  })
  const navItems: NavRailItem[] = [
    { to: '/agent/views', label: 'Views', icon: 'V' },
    { to: '/agent/search', label: '검색', icon: 'S' },
    ...(session.staff?.role === 'ADMIN'
      ? [{ to: '/admin/staff', label: '관리자 설정', icon: 'A' }]
      : []),
  ]

  useEffect(() => {
    setCollapsed(localStorage.getItem(storageKey) === 'true')
  }, [storageKey])

  const toggleNavigation = () => {
    setCollapsed((current) => {
      localStorage.setItem(storageKey, String(!current))
      return !current
    })
  }

  return (
    <AppShell
      className={`agent-shell${collapsed ? ' work-nav-collapsed' : ''}`}
      contentId="agent-main"
      skipLabel="상담사 작업 내용으로 건너뛰기"
    >
      <NavRail
        brandDestination="/agent/views/my-open"
        brandLabel="Deskseed Views"
        items={navItems}
        accountName={session.staff?.displayName}
      />

      <WorkSidebar
        eyebrow="WORKSPACE"
        title="Views"
        collapsed={collapsed}
        onToggle={toggleNavigation}
        footer={
          <div className="agent-user-card">
            <span className="agent-user-avatar" aria-hidden="true">
              {session.staff?.displayName.slice(0, 1)}
            </span>
            <div>
              <strong>{session.staff?.displayName}</strong>
              <span>{session.staff?.role}</span>
            </div>
            <button
              className="text-button"
              type="button"
              onClick={session.signOut}
            >
              로그아웃
            </button>
          </div>
        }
      >
        <nav className="saved-views-nav" aria-label="기본 Views">
          {views.isPending ? <p role="status">Views 불러오는 중</p> : null}
          {views.isError ? (
            <div className="work-nav-error" role="alert">
              <p>Views를 불러오지 못했습니다.</p>
              <button
                className="text-button"
                type="button"
                onClick={() => views.refetch()}
              >
                다시 시도
              </button>
            </div>
          ) : null}
          {views.data?.map((view) => (
            <NavLink
              key={view.key}
              to={`/agent/views/${view.key}`}
              aria-label={`${view.name}, 티켓 ${view.ticketCount ?? '수 미확인'}개`}
            >
              <span className="view-nav-icon" aria-hidden="true">
                ≡
              </span>
              <span>{view.name}</span>
              {view.ticketCount !== null ? (
                <strong>{view.ticketCount}</strong>
              ) : null}
            </NavLink>
          ))}
        </nav>
      </WorkSidebar>

      <div className="agent-content-column" id="agent-main" tabIndex={-1}>
        <Outlet />
      </div>
    </AppShell>
  )
}
