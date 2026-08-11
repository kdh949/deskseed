import { Link } from 'react-router'
import { ScreenState, TicketTable } from '../../shared/ui/system'
import { FixtureAgentFrame } from './FixtureAgentFrame'
import { fixtureTickets } from './fixtureData'

export function AgentHomeFixture() {
  return (
    <FixtureAgentFrame>
      <main
        className="agent-page fixture-agent-home"
        aria-labelledby="fixture-home-title"
      >
        <header className="agent-page-header">
          <div>
            <p className="agent-page-eyebrow">2026. 8. 11. 화요일</p>
            <h1 id="fixture-home-title">좋은 오후예요, 서연님</h1>
            <p>우선 확인할 티켓과 내 작업 범위를 한곳에서 살펴보세요.</p>
          </div>
          <Link
            className="compact-button"
            to="/__fixtures__/frontend-system/view-queue"
          >
            전체 Views 열기
          </Link>
        </header>
        <section
          className="home-work-summary"
          aria-labelledby="work-summary-title"
        >
          <header>
            <div>
              <p className="agent-page-eyebrow">MY WORK</p>
              <h2 id="work-summary-title">내 작업 요약</h2>
            </div>
            <p>ALL_TICKETS read · GROUP_OR_ASSIGNEE write</p>
          </header>
          <dl>
            <div>
              <dt>처리 중</dt>
              <dd>4</dd>
            </div>
            <div>
              <dt>고객 대기</dt>
              <dd>12</dd>
            </div>
            <div>
              <dt>긴급</dt>
              <dd>1</dd>
            </div>
            <div>
              <dt>내 child task</dt>
              <dd>2</dd>
            </div>
          </dl>
        </section>
        <section
          className="home-priority-section"
          aria-labelledby="priority-title"
        >
          <header>
            <div>
              <p className="agent-page-eyebrow">PRIORITY</p>
              <h2 id="priority-title">먼저 확인할 티켓</h2>
            </div>
            <span>업데이트 순</span>
          </header>
          <TicketTable
            label="먼저 확인할 티켓"
            items={fixtureTickets.slice(0, 3)}
            ticketHref={() => '/__fixtures__/frontend-system/workspace'}
          />
        </section>
        <ScreenState
          kind="empty"
          compact
          title="오늘 놓친 티켓이 없습니다."
          description="새로운 배정이나 긴급 티켓이 생기면 여기에 표시됩니다."
          className="home-calm-state"
        />
      </main>
    </FixtureAgentFrame>
  )
}
