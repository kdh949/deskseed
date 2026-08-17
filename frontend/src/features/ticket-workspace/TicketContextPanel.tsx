import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState, type FormEvent } from 'react'
import {
  ApiError,
  createTicketExternalReference,
  deleteTicketExternalReference,
  listTicketExternalReferences,
} from '../../api/client'
import type { AgentTicketDetail, ExternalObjectType } from '../../api/types'
import { createOpaqueUuid } from '../../api/uuid'
import { DeskseedIcon } from '../../design-system/primitives/DeskseedIcon'
import {
  DsInitialAvatar,
  DsStatusIndicator,
} from '../../design-system/primitives/DeskseedPrimitives'
import { DsTabs } from '../../design-system/primitives/DeskseedControls'
import {
  DsButton,
  DsSelect,
  Notification,
  ScreenState,
} from '../../design-system'

export type ContextTab = 'customer' | 'related' | 'external' | 'activity'

type TicketContextPanelProps = {
  activeTab: ContextTab
  detail?: AgentTicketDetail
  onTabChange: (tab: ContextTab) => void
}

const contextTabs: { id: ContextTab; label: string }[] = [
  { id: 'customer', label: 'Customer' },
  { id: 'related', label: 'Related' },
  { id: 'external', label: 'External references' },
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
        {activeTab === 'external' ? (
          <ExternalReferencesContext detail={detail} />
        ) : null}
        {activeTab === 'activity' ? <ActivityContext detail={detail} /> : null}
      </div>
    </aside>
  )
}

const OBJECT_TYPES: ExternalObjectType[] = [
  'ORDER',
  'PAYMENT',
  'REFUND',
  'USER',
  'STORE',
  'OPS_CASE',
  'CUSTOM',
]

function ExternalReferencesContext({ detail }: { detail?: AgentTicketDetail }) {
  const queryClient = useQueryClient()
  const interactionId = useMemo(createOpaqueUuid, [detail?.ticket.ticketNumber])
  const [systemId, setSystemId] = useState('')
  const [objectType, setObjectType] = useState<ExternalObjectType>('ORDER')
  const [externalId, setExternalId] = useState('')
  const [label, setLabel] = useState('')
  const [deepLink, setDeepLink] = useState('')
  const ticketNumber = detail?.ticket.ticketNumber
  const query = useQuery({
    enabled: ticketNumber !== undefined,
    queryKey: ['ticket-external-references', ticketNumber, interactionId],
    queryFn: () => listTicketExternalReferences(ticketNumber!, interactionId),
    retry: false,
  })
  const invalidate = async () => {
    await queryClient.invalidateQueries({
      queryKey: ['ticket-external-references', ticketNumber],
    })
  }
  const createMutation = useMutation({
    mutationFn: () =>
      createTicketExternalReference(ticketNumber!, {
        externalSystemId: systemId,
        objectType,
        externalId: externalId.trim(),
        displayLabel: label.trim(),
        safeDeepLink: deepLink.trim(),
        metadata: {},
        metadataObservedAt: new Date().toISOString(),
        expectedVersion:
          query.data?.ticketVersion ?? detail?.ticket.version ?? 0,
      }),
    onSuccess: async () => {
      setExternalId('')
      setLabel('')
      setDeepLink('')
      await invalidate()
    },
  })
  const deleteMutation = useMutation({
    mutationFn: (referenceId: string) =>
      deleteTicketExternalReference(
        ticketNumber!,
        referenceId,
        query.data?.ticketVersion ?? detail?.ticket.version ?? 0,
      ),
    onSuccess: invalidate,
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!systemId || !externalId.trim() || !label.trim() || !deepLink.trim())
      return
    createMutation.mutate()
  }

  if (query.isPending)
    return (
      <ScreenState
        compact
        kind="loading"
        title="외부 참조를 불러오는 중입니다."
      />
    )
  if (query.isError)
    return (
      <ScreenState
        action={<DsButton onClick={() => query.refetch()}>다시 시도</DsButton>}
        compact
        kind={
          query.error instanceof ApiError && query.error.status === 403
            ? 'denied'
            : 'error'
        }
        requestId={
          query.error instanceof ApiError ? query.error.requestId : undefined
        }
        title={
          query.error instanceof ApiError && query.error.status === 403
            ? '외부 참조를 볼 권한이 없습니다.'
            : '외부 참조를 불러오지 못했습니다.'
        }
      />
    )

  return (
    <div className="ticket-context-content external-reference-context">
      <section className="context-section">
        <h2>외부 참조</h2>
        {query.data.items.length ? (
          <ul className="external-reference-list">
            {query.data.items.map((reference) => (
              <li key={reference.id}>
                <div>
                  <strong>{reference.displayLabel}</strong>
                  <span>
                    {reference.system.displayName} · {reference.objectType} ·{' '}
                    {reference.externalId}
                  </span>
                  <small>
                    {reference.system.status} · {reference.linkState}
                  </small>
                </div>
                <div>
                  <DsButton
                    disabled={
                      !reference.safeDeepLink ||
                      reference.linkState !== 'AVAILABLE'
                    }
                    onClick={() => openSafeDeepLink(reference.safeDeepLink)}
                  >
                    새 탭에서 열기
                  </DsButton>
                  {query.data.canManage ? (
                    <DsButton
                      disabled={deleteMutation.isPending}
                      onClick={() => deleteMutation.mutate(reference.id)}
                    >
                      연결 해제
                    </DsButton>
                  ) : null}
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <p>연결된 외부 참조가 없습니다.</p>
        )}
      </section>
      {createMutation.isError || deleteMutation.isError ? (
        <Notification
          title="외부 참조 변경을 완료하지 못했습니다."
          tone="danger"
        >
          버전 충돌 또는 권한을 확인하고 최신 목록을 다시 불러오세요.
        </Notification>
      ) : null}
      {query.data.canManage ? (
        <form className="external-reference-form" onSubmit={submit}>
          <h2>외부 참조 추가</h2>
          <label>
            <span>외부 시스템</span>
            <DsSelect
              aria-label="외부 시스템"
              onChange={(event) => setSystemId(event.target.value)}
              value={systemId}
            >
              <option value="">선택</option>
              {query.data.availableSystems.map((system) => (
                <option key={system.id} value={system.id}>
                  {system.displayName} · {system.status}
                </option>
              ))}
            </DsSelect>
          </label>
          <label>
            <span>리소스 유형</span>
            <DsSelect
              aria-label="외부 리소스 유형"
              onChange={(event) =>
                setObjectType(event.target.value as ExternalObjectType)
              }
              value={objectType}
            >
              {OBJECT_TYPES.map((type) => (
                <option key={type} value={type}>
                  {type}
                </option>
              ))}
            </DsSelect>
          </label>
          <label>
            <span>External ID</span>
            <input
              aria-label="External ID"
              maxLength={200}
              onChange={(event) => setExternalId(event.target.value)}
              value={externalId}
            />
          </label>
          <label>
            <span>표시 이름</span>
            <input
              aria-label="외부 참조 표시 이름"
              maxLength={200}
              onChange={(event) => setLabel(event.target.value)}
              value={label}
            />
          </label>
          <label>
            <span>검증할 HTTPS deep link</span>
            <input
              aria-label="외부 참조 deep link"
              onChange={(event) => setDeepLink(event.target.value)}
              type="url"
              value={deepLink}
            />
          </label>
          <DsButton
            disabled={
              createMutation.isPending ||
              !systemId ||
              !externalId.trim() ||
              !label.trim() ||
              !deepLink.trim()
            }
            tone="primary"
            type="submit"
          >
            외부 참조 추가
          </DsButton>
        </form>
      ) : null}
    </div>
  )
}

function openSafeDeepLink(url: string | null) {
  if (!url) return
  const opened = window.open(url, '_blank', 'noopener,noreferrer')
  if (opened) opened.opener = null
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
