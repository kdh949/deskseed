import type { PropsWithChildren } from 'react'
import { NavLink } from 'react-router'
import {
  AppShell,
  NavRail,
  WorkSidebar,
  type NavRailItem,
} from '../../shared/ui/system'

const navItems: NavRailItem[] = [
  { to: '/__fixtures__/frontend-system/agent-home', label: '홈', icon: 'H' },
  { to: '/__fixtures__/frontend-system/view-queue', label: 'Views', icon: 'V' },
  {
    to: '/__fixtures__/frontend-system/admin',
    label: '관리자 설정',
    icon: 'A',
  },
]

const views = [
  ['내 open', 4],
  ['내 그룹 미배정', 7],
  ['Pending', 12],
  ['최근 solved', 31],
  ['내 child tasks', 2],
] as const

export function FixtureAgentFrame({ children }: PropsWithChildren) {
  return (
    <AppShell
      className="agent-shell fixture-agent-shell"
      contentId="fixture-agent-main"
      skipLabel="상담사 작업 내용으로 건너뛰기"
    >
      <NavRail
        brandDestination="/__fixtures__/frontend-system/agent-home"
        brandLabel="Deskseed 상담사 홈"
        items={navItems}
        accountName="박서연"
      />
      <WorkSidebar
        eyebrow="WORKSPACE"
        title="Views"
        collapsed={false}
        onToggle={() => undefined}
        footer={
          <div className="agent-user-card">
            <span className="agent-user-avatar" aria-hidden="true">
              박
            </span>
            <div>
              <strong>박서연</strong>
              <span>AGENT</span>
            </div>
            <span className="fixture-online-label">접속 중</span>
          </div>
        }
      >
        <nav className="saved-views-nav" aria-label="기본 Views">
          {views.map(([label, count], index) => (
            <NavLink
              key={label}
              to={
                index === 0
                  ? '/__fixtures__/frontend-system/view-queue'
                  : `/__fixtures__/frontend-system/view-queue?view=${index}`
              }
              aria-label={`${label}, 티켓 ${count}개`}
            >
              <span className="view-nav-icon" aria-hidden="true">
                ≡
              </span>
              <span>{label}</span>
              <strong>{count}</strong>
            </NavLink>
          ))}
        </nav>
      </WorkSidebar>
      <div
        className="agent-content-column"
        id="fixture-agent-main"
        tabIndex={-1}
      >
        {children}
      </div>
    </AppShell>
  )
}
