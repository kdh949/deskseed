import { TicketTable } from '../../shared/ui/system'
import { FixtureAgentFrame } from './FixtureAgentFrame'
import { fixtureTickets } from './fixtureData'

export function ViewQueueFixture() {
  return (
    <FixtureAgentFrame>
      <main
        className="agent-page agent-views-page"
        aria-labelledby="fixture-view-title"
      >
        <header className="agent-page-header">
          <div>
            <p className="agent-page-eyebrow">VIEWS · ALL_TICKETS</p>
            <h1 id="fixture-view-title">내 open</h1>
            <p>내가 담당하고 현재 처리가 필요한 staff-visible 티켓입니다.</p>
          </div>
          <button className="compact-button" type="button">
            새로고침
          </button>
        </header>
        <section className="ticket-filter-bar" aria-label="내 open 임시 필터">
          {['상태', '우선순위', '그룹', '담당자'].map((label) => (
            <label key={label}>
              <span>{label}</span>
              <select defaultValue="">
                <option value="">전체</option>
              </select>
            </label>
          ))}
          <span className="queue-fixture-count">
            티켓 {fixtureTickets.length}개
          </span>
        </section>
        <TicketTable
          label="내 open 티켓"
          items={fixtureTickets}
          ticketHref={() => '/__fixtures__/frontend-system/workspace'}
        />
        <footer className="queue-pagination">
          <p>최근 업데이트 기준 · 티켓 번호 보조 정렬</p>
          <button className="compact-button" type="button">
            다음 페이지
          </button>
        </footer>
      </main>
    </FixtureAgentFrame>
  )
}
