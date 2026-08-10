import { NavLink } from 'react-router'
import { DeskseedButton } from '../../shared/ui/DeskseedButton'

const navigationItems = [
  { label: '홈', symbol: 'H', to: '/agent/home' },
  { label: 'Views', symbol: 'V', to: '/agent/views' },
]

export function AgentShell() {
  return (
    <div className="agent-shell">
      <nav className="agent-global-nav" aria-label="상담사 전역 탐색">
        <NavLink
          className="agent-brand"
          to="/agent/home"
          aria-label="Deskseed 상담사 홈"
        >
          <span aria-hidden="true">D</span>
        </NavLink>
        <div className="agent-nav-items">
          {navigationItems.map((item) => (
            <NavLink
              className="agent-nav-link"
              key={item.to}
              to={item.to}
              aria-label={item.label}
            >
              <span aria-hidden="true">{item.symbol}</span>
              <span className="agent-nav-tooltip">{item.label}</span>
            </NavLink>
          ))}
        </div>
      </nav>

      <aside className="agent-work-nav" aria-label="상담사 작업 탐색">
        <p className="agent-work-nav-eyebrow">DESKSEED</p>
        <h1>상담사 작업 공간</h1>
        <p>Views와 티켓 업무 흐름은 다음 세로 슬라이스에서 연결됩니다.</p>
      </aside>

      <section className="agent-content-column">
        <header className="agent-topbar">
          <div>
            <p className="agent-topbar-eyebrow">AGENT HOME</p>
            <h2>비어 있는 작업 공간</h2>
          </div>
          <DeskseedButton
            type="button"
            disabled
            aria-describedby="agent-shell-notice"
          >
            새 티켓
          </DeskseedButton>
        </header>
        <main className="agent-workspace" aria-label="상담사 작업 공간">
          <section
            className="agent-empty-state"
            aria-labelledby="agent-empty-title"
          >
            <p className="agent-empty-state-mark" aria-hidden="true">
              +
            </p>
            <h3 id="agent-empty-title">아직 열려 있는 티켓이 없습니다.</h3>
            <p id="agent-shell-notice">
              이 화면은 Deskseed의 독립 브랜드 Shell입니다. 업무 데이터와 API는
              아직 연결하지 않았습니다.
            </p>
          </section>
        </main>
      </section>
    </div>
  )
}
