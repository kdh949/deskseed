import { type ReactNode } from 'react'
import { NavLink, useLocation, useOutlet } from 'react-router'
import { DeskseedIcon } from '../../primitives/DeskseedIcon'
import {
  DeskseedBrandMark,
  DsIconButton,
  DsInitialAvatar,
} from '../../primitives/DeskseedPrimitives'

const navigationItems = [
  { id: 'views', icon: 'inbox' as const, label: 'Views', to: '/agent/views' },
]

type AgentNavigationItemId = (typeof navigationItems)[number]['id']

type AgentShellProps = {
  activeNavigationItem?: AgentNavigationItemId
  canCreateTicket?: boolean
  children?: ReactNode
  displayName: string
  onSignOut?: () => void
}

function toInitials(displayName: string) {
  const words = displayName.trim().split(/\s+/).filter(Boolean)
  if (words.length > 1) {
    return words
      .slice(0, 2)
      .map((word) => Array.from(word)[0])
      .join('')
      .toLocaleUpperCase()
  }
  return Array.from(words[0] ?? '')
    .slice(0, 2)
    .join('')
}

export function AgentShell({
  activeNavigationItem,
  canCreateTicket = false,
  children,
  displayName,
  onSignOut,
}: AgentShellProps) {
  const location = useLocation()
  const outlet = useOutlet()
  const isTicketRoute = location.pathname.startsWith('/agent/tickets/')

  return (
    <div className="agent-shell">
      <nav className="agent-global-nav" aria-label="상담사 전역 탐색">
        <NavLink
          className="agent-brand"
          to="/agent/views/my-open"
          aria-label="Deskseed 티켓 큐"
        >
          <DeskseedBrandMark transparent />
        </NavLink>
        <div className="agent-nav-items">
          {navigationItems.map((item) => (
            <NavLink
              className={({ isActive }) =>
                isActive || activeNavigationItem === item.id
                  ? 'agent-nav-link is-active'
                  : 'agent-nav-link'
              }
              key={item.label}
              to={
                activeNavigationItem === item.id ? location.pathname : item.to
              }
              aria-label={item.label}
            >
              <DeskseedIcon name={item.icon} />
              <span className="agent-nav-tooltip">{item.label}</span>
            </NavLink>
          ))}
        </div>
      </nav>
      <div className="agent-main-column">
        <header className="agent-top-chrome">
          {isTicketRoute ? (
            <NavLink className="agent-back-to-views" to="/agent/views">
              <DeskseedIcon name="arrowLeft" />
              Back to Views
            </NavLink>
          ) : null}
          {canCreateTicket ? (
            <NavLink
              className="agent-create-ticket-action"
              to="/agent/tickets/new"
            >
              <DeskseedIcon name="plus" size="sm" />새 티켓
            </NavLink>
          ) : null}
          <div className="agent-profile">
            <DsInitialAvatar
              initials={toInitials(displayName)}
              label={displayName}
            />
            <span>
              <strong>{displayName}</strong>
            </span>
            {onSignOut ? (
              <DsIconButton
                icon="chevronDown"
                label="로그아웃"
                onClick={onSignOut}
              />
            ) : null}
          </div>
        </header>
        {children ?? outlet}
      </div>
    </div>
  )
}
