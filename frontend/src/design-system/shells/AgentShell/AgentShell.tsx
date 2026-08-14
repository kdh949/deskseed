import { useState, type MouseEvent, type ReactNode } from 'react'
import { NavLink, useLocation, useNavigate, useOutlet } from 'react-router'
import { DeskseedIcon } from '../../primitives/DeskseedIcon'
import { DsButton } from '../../primitives/DeskseedControls'
import {
  DeskseedBrandMark,
  DsAvatar,
  DsIconButton,
} from '../../primitives/DeskseedPrimitives'
import agentAvatar from '../../../assets/deskseed/agent-mina-park-v1.png'

const navigationItems = [
  { id: 'views', icon: 'inbox' as const, label: 'Views', to: '/agent/views' },
]

type AgentNavigationItemId = (typeof navigationItems)[number]['id']

const initialOpenTickets = [
  { number: '1042', subject: '결제 버튼을 누르면 오류가 납니다' },
  { number: '1038', subject: '환불 처리 문의' },
]

type AgentShellProps = {
  activeNavigationItem?: AgentNavigationItemId
  children?: ReactNode
  displayName: string
  onSignOut?: () => void
}

export function AgentShell({
  activeNavigationItem,
  children,
  displayName,
  onSignOut,
}: AgentShellProps) {
  const location = useLocation()
  const navigate = useNavigate()
  const outlet = useOutlet()
  const isTicketRoute = location.pathname.startsWith('/agent/tickets/')
  const [openTickets, setOpenTickets] = useState(initialOpenTickets)

  const closeTicketTab = (
    event: MouseEvent<HTMLButtonElement>,
    number: string,
  ) => {
    event.preventDefault()
    event.stopPropagation()
    const remainingTickets = openTickets.filter(
      (ticket) => ticket.number !== number,
    )
    setOpenTickets(remainingTickets)
    if (location.pathname === `/agent/tickets/${number}`) {
      navigate(
        remainingTickets[0]
          ? `/agent/tickets/${remainingTickets[0].number}`
          : '/agent/views/my-open',
      )
    }
  }

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
            <>
              <NavLink className="agent-back-to-views" to="/agent/views">
                <DeskseedIcon name="arrowLeft" />
                Back to Views
              </NavLink>
              <nav aria-label="열린 티켓 탭" className="agent-ticket-tabs">
                {openTickets.map((ticket) => (
                  <div
                    className={
                      location.pathname === `/agent/tickets/${ticket.number}`
                        ? 'agent-ticket-tab-wrap agent-ticket-tab-wrap--active'
                        : 'agent-ticket-tab-wrap'
                    }
                    key={ticket.number}
                  >
                    <NavLink
                      aria-label={`티켓 #${ticket.number} ${ticket.subject}`}
                      className="agent-ticket-tab"
                      to={`/agent/tickets/${ticket.number}`}
                    >
                      <strong>#{ticket.number}</strong>
                      <span>{ticket.subject}</span>
                    </NavLink>
                    <button
                      aria-label={`티켓 #${ticket.number} 탭 닫기`}
                      className="agent-ticket-tab-close"
                      onClick={(event) => closeTicketTab(event, ticket.number)}
                      type="button"
                    >
                      <DeskseedIcon name="x" size="sm" />
                    </button>
                  </div>
                ))}
              </nav>
            </>
          ) : null}
          <DsButton
            className="agent-create-ticket-action"
            onClick={() => navigate('/agent/tickets/new')}
            tone="primary"
          >
            <DeskseedIcon name="plus" size="sm" />새 티켓
          </DsButton>
          <div className="agent-profile">
            <DsAvatar name={displayName} size="sm" src={agentAvatar} />
            <span>
              <strong>{displayName}</strong>
              <small>Available</small>
            </span>
            <DsIconButton
              icon="chevronDown"
              label="로그아웃"
              onClick={onSignOut}
            />
          </div>
        </header>
        {children ?? outlet}
      </div>
    </div>
  )
}
