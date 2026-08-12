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
  { id: 'home', icon: 'home' as const, label: '홈', to: '/agent/home' },
  { id: 'views', icon: 'inbox' as const, label: 'Views', to: '/agent/views' },
  {
    id: 'customers',
    icon: 'userGroup' as const,
    label: '고객',
    to: '/agent/customers',
  },
  {
    id: 'knowledge',
    icon: 'bookClosed' as const,
    label: '지식',
    to: '/agent/knowledge',
  },
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
  role?: 'ADMIN' | 'AGENT'
}

export function AgentShell({
  activeNavigationItem,
  children,
  displayName,
  onSignOut,
  role = 'AGENT',
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
          : '/agent/home',
      )
    }
  }

  return (
    <div className="agent-shell">
      <nav className="agent-global-nav" aria-label="상담사 전역 탐색">
        <NavLink
          className="agent-brand"
          to="/agent/home"
          aria-label="Deskseed 상담사 홈"
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
          {role === 'ADMIN' ? (
            <NavLink
              className="agent-nav-link"
              to="/admin/staff"
              aria-label="관리자 설정"
            >
              <DeskseedIcon name="gear" />
              <span className="agent-nav-tooltip">관리자 설정</span>
            </NavLink>
          ) : null}
        </div>
        <div className="agent-nav-bottom">
          <DsIconButton icon="notification" label="알림" />
          <DsIconButton icon="info" label="도움말" />
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
                <DsIconButton icon="plus" label="새 티켓 탭 열기" />
              </nav>
            </>
          ) : null}
          {!isTicketRoute ? (
            <DsButton className="agent-create-ticket" tone="ghost">
              <DeskseedIcon name="plus" size="sm" />
              생성
            </DsButton>
          ) : null}
          <label className="agent-search">
            <DeskseedIcon name="search" />
            <span className="sr-only">Deskseed 검색</span>
            <input placeholder="Search Deskseed" type="search" />
            <kbd>⌘ K</kbd>
          </label>
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
        {children ?? outlet ?? <AgentHomePage />}
      </div>
    </div>
  )
}

export function AgentHomePage() {
  return (
    <main className="agent-workspace" aria-label="상담사 작업 공간">
      <section
        className="agent-empty-state"
        aria-labelledby="agent-empty-title"
      >
        <span className="agent-empty-state-icon">
          <DeskseedIcon name="inbox" size="lg" />
        </span>
        <h1 id="agent-empty-title">처리할 티켓을 선택하세요</h1>
        <p>
          Views에서 티켓을 열면 고객 대화, 티켓 속성, 고객 맥락을 한 화면에서
          이어서 처리할 수 있습니다.
        </p>
        <NavLink className="ticket-secondary-button" to="/agent/tickets/1042">
          예시 티켓 열기
        </NavLink>
      </section>
    </main>
  )
}
