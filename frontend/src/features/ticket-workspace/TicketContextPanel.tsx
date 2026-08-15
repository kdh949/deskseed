import type { AgentTicketDetail } from '../../api/types'
import { DeskseedIcon } from '../../design-system/primitives/DeskseedIcon'
import {
  DsInitialAvatar,
  DsStatusIndicator,
} from '../../design-system/primitives/DeskseedPrimitives'
import { DsTabs } from '../../design-system/primitives/DeskseedControls'

type ContextTab = 'customer' | 'related' | 'activity'

type TicketContextPanelProps = {
  activeTab: ContextTab
  detail?: AgentTicketDetail
  onTabChange: (tab: ContextTab) => void
}

const contextTabs: { id: ContextTab; label: string }[] = [
  { id: 'customer', label: 'Customer' },
  { id: 'related', label: 'Related' },
  { id: 'activity', label: 'Activity' },
]

export function TicketContextPanel({
  activeTab,
  detail,
  onTabChange,
}: TicketContextPanelProps) {
  return (
    <aside aria-label="고객 맥락" className="ticket-context">
      <DsTabs
        activeId={activeTab}
        ariaLabel="고객 맥락 보기"
        className="ticket-context-tabs"
        items={contextTabs.map((tab) => ({
          ...tab,
          panelId: `ticket-context-panel-${tab.id}`,
        }))}
        onChange={onTabChange}
      />
      <div
        aria-labelledby={`ticket-context-panel-${activeTab}-tab`}
        id={`ticket-context-panel-${activeTab}`}
        role="tabpanel"
        tabIndex={0}
      >
        {activeTab === 'customer' ? <CustomerContext detail={detail} /> : null}
        {activeTab === 'related' ? <RelatedContext detail={detail} /> : null}
        {activeTab === 'activity' ? <ActivityContext detail={detail} /> : null}
      </div>
    </aside>
  )
}

function CustomerContext({ detail }: { detail?: AgentTicketDetail }) {
  const customer = detail?.context.customer
  const name = customer?.displayName ?? '김지연'
  const email = customer?.email ?? 'jiyeon.kim@example.com'
  const initials = name.slice(0, 2)

  return (
    <div className="ticket-context-content">
      <section className="context-section">
        <div className="context-person">
          <DsInitialAvatar initials={initials} label={name} size="xl" />
          <span>
            <strong>{name}</strong>
            <small>{email}</small>
          </span>
        </div>
        <a className="ticket-link-button" href={`mailto:${email}`}>
          이메일 보내기 <DeskseedIcon name="link" size="sm" />
        </a>
      </section>
      <section className="context-section">
        <h2>고객 정보</h2>
        <p>고객과의 공개 대화와 연락처를 확인합니다.</p>
        <button className="ticket-link-button" type="button">
          고객 프로필 보기 <DeskseedIcon name="link" size="sm" />
        </button>
      </section>
      <section className="context-section">
        <h2>관련 티켓</h2>
        <dl className="context-definition-list">
          <div>
            <dt>열린 내부 작업</dt>
            <dd>{detail?.context.children.length ?? 0}</dd>
          </div>
          <div>
            <dt>외부 참조</dt>
            <dd>{detail?.context.externalReferenceCount ?? 0}</dd>
          </div>
        </dl>
      </section>
    </div>
  )
}

function RelatedContext({ detail }: { detail?: AgentTicketDetail }) {
  const children = detail?.context.children ?? []
  return (
    <div className="ticket-context-content">
      <section className="context-section">
        <h2>내부 협업</h2>
        {children.length ? (
          children.map((ticket) => (
            <a
              className="context-ticket-row"
              href={`/agent/tickets/${ticket.ticketNumber}`}
              key={ticket.ticketNumber}
            >
              <strong>#{ticket.ticketNumber}</strong>
              <span>{ticket.subject}</span>
              <DsStatusIndicator
                tone={ticket.status === 'OPEN' ? 'open' : 'pending'}
              >
                {ticket.status === 'OPEN' ? '처리 중' : '대기'}
              </DsStatusIndicator>
            </a>
          ))
        ) : (
          <p>연결된 내부 작업이 없습니다.</p>
        )}
      </section>
      <section className="context-section">
        <h2>외부 참조</h2>
        <p>
          {detail?.context.externalReferenceCount
            ? `${detail.context.externalReferenceCount}개의 참조가 연결되어 있습니다.`
            : '연결된 외부 참조가 없습니다.'}
        </p>
      </section>
    </div>
  )
}

function ActivityContext({ detail }: { detail?: AgentTicketDetail }) {
  const history = detail?.history ?? []
  return (
    <div className="ticket-context-content">
      <section className="context-section">
        <h2>최근 활동</h2>
        {history.length ? (
          <ol className="context-activity-list">
            {history.map((item) => (
              <li key={item.id}>
                <DeskseedIcon name="history" />
                <span>
                  <strong>{historyLabel(item.eventType)}</strong>
                  <small>
                    {item.actor.displayName} · {formatDate(item.occurredAt)}
                  </small>
                </span>
              </li>
            ))}
          </ol>
        ) : (
          <p>표시할 최근 활동이 없습니다.</p>
        )}
      </section>
    </div>
  )
}

function historyLabel(value: string) {
  return value.split('_').join(' ')
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}
