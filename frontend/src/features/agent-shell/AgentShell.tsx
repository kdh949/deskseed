import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { NavLink, Outlet } from 'react-router'
import { listAgentViews } from '../../api/client'
import { useStaffSession } from '../staff-auth/StaffSessionContext'

export function AgentShell() {
  const session = useStaffSession()
  const staffId = session.staff?.id ?? 'unknown'
  const storageKey = `deskseed:agent:${staffId}:work-nav-collapsed:v1`
  const [collapsed, setCollapsed] = useState(
    () => localStorage.getItem(storageKey) === 'true',
  )
  const views = useQuery({ queryKey: ['agent-views'], queryFn: listAgentViews })

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
    <div className={`agent-shell${collapsed ? ' work-nav-collapsed' : ''}`}>
      <nav className="agent-global-nav" aria-label="상담사 전역 탐색">
        <NavLink
          className="agent-brand"
          to="/agent/views/my-open"
          aria-label="Deskseed Views"
        >
          <span aria-hidden="true">D</span>
        </NavLink>
        <div className="agent-nav-items">
          <NavLink
            className="agent-nav-link"
            to="/agent/views"
            aria-label="Views"
          >
            <span aria-hidden="true">V</span>
            <span className="agent-nav-tooltip">Views</span>
          </NavLink>
          {session.staff?.role === 'ADMIN' ? (
            <NavLink
              className="agent-nav-link"
              to="/admin/staff"
              aria-label="관리자 설정"
            >
              <span aria-hidden="true">A</span>
              <span className="agent-nav-tooltip">관리자 설정</span>
            </NavLink>
          ) : null}
        </div>
        <div className="agent-nav-account" aria-hidden="true">
          {session.staff?.displayName.slice(0, 1)}
        </div>
      </nav>

      {!collapsed ? (
        <aside className="agent-work-nav" aria-label="상담사 작업 탐색">
          <header className="work-nav-header">
            <div>
              <p className="agent-work-nav-eyebrow">WORKSPACE</p>
              <h1>Views</h1>
            </div>
            <button
              className="icon-button"
              type="button"
              onClick={toggleNavigation}
              aria-label="작업 탐색 접기"
            >
              ‹
            </button>
          </header>
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
        </aside>
      ) : (
        <button
          className="work-nav-expand"
          type="button"
          onClick={toggleNavigation}
          aria-label="작업 탐색 펼치기"
        >
          ›
        </button>
      )}

      <div className="agent-content-column">
        <Outlet />
      </div>
    </div>
  )
}
