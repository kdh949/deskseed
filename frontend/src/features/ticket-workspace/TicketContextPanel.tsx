import { useRef, useState } from 'react'
import { Link } from 'react-router'
import type { AgentTicketDetail } from '../../api/types'
import { ContextPanel, type ContextPanelTab } from '../../shared/ui/system'
import { CreateChildTicketDialog } from './CreateChildTicketDialog'
import { TicketTransferDialog } from './TicketTransferDialog'

type ContextTab = 'customer' | 'history' | 'related'

export function TicketContextPanel({
  detail,
  canUpdate,
  onCommandCompleted,
}: {
  detail: AgentTicketDetail
  canUpdate: boolean
  onCommandCompleted: () => Promise<unknown>
}) {
  const [activeTab, setActiveTab] = useState<ContextTab>('customer')
  const [dialog, setDialog] = useState<'transfer' | 'child' | null>(null)
  const transferTriggerRef = useRef<HTMLButtonElement>(null)
  const childTriggerRef = useRef<HTMLButtonElement>(null)
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
          <h2>Parent ticket</h2>
          {detail.context.parent ? (
            <RelatedTicketLink ticket={detail.context.parent} />
          ) : (
            <p>연결된 parent ticket이 없습니다.</p>
          )}
          <h2>Child tickets</h2>
          {detail.context.children.length ? (
            <ul className="related-ticket-list">
              {detail.context.children.map((child) => (
                <li key={child.ticketNumber}>
                  <RelatedTicketLink ticket={child} />
                </li>
              ))}
            </ul>
          ) : (
            <p>연결된 child ticket이 없습니다.</p>
          )}
          {canUpdate ? (
            <div className="related-ticket-actions">
              <button
                ref={transferTriggerRef}
                className="button secondary"
                type="button"
                onClick={() => setDialog('transfer')}
              >
                티켓 이관
              </button>
              {!detail.ticket.isChild ? (
                <button
                  ref={childTriggerRef}
                  className="button primary"
                  type="button"
                  onClick={() => setDialog('child')}
                >
                  내부 child 만들기
                </button>
              ) : null}
            </div>
          ) : (
            <p className="related-write-note">
              관계에 의한 읽기는 parent 쓰기 권한을 부여하지 않습니다.
            </p>
          )}
          <h2>외부 참조</h2>
          <p>
            {detail.context.externalReferences.length
              ? `${detail.context.externalReferences.length}개`
              : '연결된 외부 참조가 없습니다.'}
          </p>
        </div>
      ) : null}
      {dialog === 'transfer' ? (
        <TicketTransferDialog
          detail={detail}
          returnFocusRef={transferTriggerRef}
          onClose={() => setDialog(null)}
          onCompleted={onCommandCompleted}
        />
      ) : null}
      {dialog === 'child' ? (
        <CreateChildTicketDialog
          detail={detail}
          returnFocusRef={childTriggerRef}
          onClose={() => setDialog(null)}
          onCompleted={onCommandCompleted}
        />
      ) : null}
    </ContextPanel>
  )
}

function RelatedTicketLink({
  ticket,
}: {
  ticket: AgentTicketDetail['ticket']
}) {
  return (
    <Link
      className="related-ticket-link"
      to={`/agent/tickets/${ticket.ticketNumber}`}
    >
      <span>
        #{ticket.ticketNumber} {ticket.subject}
      </span>
      <small>{ticket.status}</small>
    </Link>
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
