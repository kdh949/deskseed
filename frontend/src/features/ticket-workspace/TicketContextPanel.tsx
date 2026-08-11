import { useState } from 'react'
import type { AgentTicketDetail } from '../../api/types'
import { ContextPanel, type ContextPanelTab } from '../../shared/ui/system'

type ContextTab = 'customer' | 'history' | 'related'

export function TicketContextPanel({ detail }: { detail: AgentTicketDetail }) {
  const [activeTab, setActiveTab] = useState<ContextTab>('customer')
  const tabs: ContextPanelTab[] = [
    { id: 'customer', label: '고객' },
    { id: 'history', label: '기록' },
    { id: 'related', label: '관련' },
  ]

  return (
    <ContextPanel
      label="티켓 컨텍스트"
      tabs={tabs}
      activeTab={activeTab}
      onTabChange={(id) => setActiveTab(id as ContextTab)}
    >
      {activeTab === 'customer' ? (
        <div className="customer-context-card">
          <span className="customer-avatar" aria-hidden="true">
            {detail.context.customer.displayName.slice(0, 1)}
          </span>
          <h2>{detail.context.customer.displayName}</h2>
          <a href={`mailto:${detail.context.customer.email}`}>
            {detail.context.customer.email}
          </a>
          <dl>
            <div>
              <dt>고객 ID</dt>
              <dd>{detail.context.customer.id}</dd>
            </div>
            <div>
              <dt>최근 티켓</dt>
              <dd>현재 projection에서 제공하지 않음</dd>
            </div>
          </dl>
        </div>
      ) : null}
      {activeTab === 'history' ? (
        detail.history.length ? (
          <ol className="history-list">
            {detail.history.map((item) => (
              <li key={item.id}>
                <strong>{historyLabel(item.eventType)}</strong>
                <span>{item.actor.displayName}</span>
                <time dateTime={item.occurredAt}>
                  {formatDate(item.occurredAt)}
                </time>
              </li>
            ))}
          </ol>
        ) : (
          <p className="context-empty">표시할 로컬 기록이 없습니다.</p>
        )
      ) : null}
      {activeTab === 'related' ? (
        <div className="related-context">
          <h2>연결된 티켓</h2>
          <p>
            {detail.context.children.length
              ? `Child task ${detail.context.children.length}개`
              : '연결된 child task가 없습니다.'}
          </p>
          <h2>외부 참조</h2>
          <p>
            {detail.context.externalReferences.length
              ? `${detail.context.externalReferences.length}개`
              : '연결된 외부 참조가 없습니다.'}
          </p>
        </div>
      ) : null}
    </ContextPanel>
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
