import { useState } from 'react'
import { Navigate, useNavigate, useParams } from 'react-router'
import {
  AgentShell,
  CustomerPortalShell,
  DeskseedIcon,
  DsButton,
  QueueTicketTable,
  ScreenState,
  TableSkeleton,
  ViewNavigation,
  type QueueTicketTableItem,
} from '../../design-system'
import { AgentHomePage } from '../../design-system/shells/AgentShell/AgentShell'
import { TicketWorkspace } from '../ticket-workspace/TicketWorkspace'

const fixtureTickets: QueueTicketTableItem[] = [
  {
    ticketNumber: 1042,
    subject: '결제 버튼을 누르면 오류가 납니다',
    requester: '김지연',
    status: 'OPEN',
    priority: 'HIGH',
    group: 'Billing',
    assignee: 'Mina Park',
    updatedAt: '2026-08-11T09:33:00Z',
    updatedLabel: '08.11. 09:33',
  },
  {
    ticketNumber: 1038,
    subject: '환불 처리 문의',
    requester: '서민서',
    status: 'PENDING',
    priority: 'NORMAL',
    group: 'Billing',
    assignee: 'Mina Park',
    updatedAt: '2026-08-10T17:02:00Z',
    updatedLabel: '08.10. 17:02',
  },
  {
    ticketNumber: 1034,
    subject: '계정 로그인 확인 요청',
    requester: '최도윤',
    status: 'NEW',
    priority: 'URGENT',
    group: 'Support',
    assignee: '미배정',
    updatedAt: '2026-08-10T16:22:00Z',
    updatedLabel: '08.10. 16:22',
  },
]

export function FrontendSystemFixturePage() {
  const { fixtureName } = useParams()
  if (fixtureName?.startsWith('view-queue')) {
    return (
      <AgentShell activeNavigationItem="views" displayName="Mina Park">
        <QueueFixture state={fixtureName.replace('view-queue-', '')} />
      </AgentShell>
    )
  }
  if (fixtureName === 'workspace' || fixtureName === 'workspace-internal') {
    return (
      <AgentShell displayName="Mina Park">
        <TicketWorkspace />
      </AgentShell>
    )
  }
  if (fixtureName === 'workspace-conflict') {
    return (
      <AgentShell displayName="Mina Park">
        <TicketWorkspace initialState="conflict" />
      </AgentShell>
    )
  }
  if (fixtureName === 'agent-home') {
    return (
      <AgentShell displayName="Mina Park">
        <AgentHomePage />
      </AgentShell>
    )
  }
  if (fixtureName === 'public-form' || fixtureName === 'public-detail') {
    return (
      <CustomerPortalShell>
        <ScreenState
          description="제품 경로와 같은 고객 포털 셸에서 상태만 고정해 확인합니다."
          kind="empty"
          title="고객 포털 시각 점검"
        />
      </CustomerPortalShell>
    )
  }
  if (fixtureName === 'admin' || fixtureName === 'states') {
    return (
      <ScreenState
        description="이 화면은 현재 디자인 시스템 프리미티브만 사용합니다."
        kind="empty"
        title="화면 준비 중"
      />
    )
  }
  return <Navigate to="/__fixtures__/frontend-system/agent-home" replace />
}

function QueueFixture({ state }: { state: string }) {
  const navigate = useNavigate()
  const [selected, setSelected] = useState<Set<number>>(
    () => new Set(state === 'bulk' ? [1042, 1038] : []),
  )
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [createdPersonalView, setCreatedPersonalView] = useState(false)
  const items =
    state === 'long-content'
      ? fixtureTickets.map((ticket) =>
          ticket.ticketNumber === 1042
            ? {
                ...ticket,
                subject:
                  '결제 승인 오류 이후 주문 생성과 영수증 발행이 함께 지연되는 현상에 대한 긴 상담 제목',
              }
            : ticket,
        )
      : fixtureTickets

  return (
    <main className="agent-queue-workspace" aria-label="티켓 큐">
      <ViewNavigation
        footer={
          <>
            <strong>Tip</strong>: 보기를 드래그하여 순서를 변경할 수 있어요.
          </>
        }
        label="티켓 보기"
        sections={[
          {
            id: 'shared',
            label: '공유 보기',
            items: [
              {
                key: 'my-open',
                label: '내 티켓',
                count: 3,
                icon: 'inbox',
                to: '/__fixtures__/frontend-system/view-queue',
              },
              {
                key: 'unassigned',
                label: '미배정 티켓',
                count: 16,
                icon: 'userGroup',
                to: '/__fixtures__/frontend-system/view-queue-unassigned',
              },
              {
                key: 'all-open',
                label: '모든 미해결 티켓',
                count: 142,
                icon: 'inbox',
                to: '/__fixtures__/frontend-system/view-queue-all-open',
              },
              {
                key: 'urgent',
                label: '긴급 티켓',
                count: 12,
                icon: 'alertWarning',
                iconTone: 'danger',
                to: '/__fixtures__/frontend-system/view-queue-urgent',
              },
              {
                key: 'updated',
                label: '오늘 업데이트된 티켓',
                count: 36,
                icon: 'history',
                to: '/__fixtures__/frontend-system/view-queue-updated',
              },
              {
                key: 'solved',
                label: '최근 해결된 티켓',
                count: 24,
                icon: 'checkCircle',
                iconTone: 'success',
                to: '/__fixtures__/frontend-system/view-queue-solved',
              },
              {
                key: 'pending',
                label: '고객 답변 대기',
                count: 18,
                icon: 'speechBubble',
                iconTone: 'warning',
                to: '/__fixtures__/frontend-system/view-queue-pending',
              },
            ],
          },
          {
            id: 'personal',
            label: '개인 보기',
            footerAction: (
              <button
                className="ds-view-navigation-create"
                onClick={() => setCreatedPersonalView(true)}
                type="button"
              >
                <DeskseedIcon name="plus" size="sm" />새 보기 만들기
              </button>
            ),
            items: [
              {
                key: 'created',
                label: '내가 생성한 티켓',
                count: 7,
                icon: 'circle',
                to: '/__fixtures__/frontend-system/view-queue-created',
              },
              {
                key: 'follow-up',
                label: '내가 팔로우 중인 티켓',
                count: 5,
                icon: 'userGroup',
                to: '/__fixtures__/frontend-system/view-queue-follow-up',
              },
              {
                key: 'drafts',
                label: '임시 보관함',
                count: 3,
                icon: 'inbox',
                to: '/__fixtures__/frontend-system/view-queue-drafts',
              },
              ...(createdPersonalView
                ? [
                    {
                      key: 'fixture-created',
                      label: '새 개인 보기',
                      count: null,
                      icon: 'bookmark' as const,
                      to: '/__fixtures__/frontend-system/view-queue-fixture-created',
                    },
                  ]
                : []),
            ],
          },
        ]}
        title="보기"
      />
      <section className="agent-queue" aria-labelledby="fixture-queue-title">
        <header className="agent-queue-header">
          <div>
            <div className="agent-queue-title-row">
              <h1 id="fixture-queue-title">내 티켓</h1>
              <span>3개</span>
            </div>
            <DsButton
              aria-expanded={filtersOpen}
              aria-label="필터 열기"
              className="agent-queue-filter-trigger"
              onClick={() => setFiltersOpen((current) => !current)}
            >
              <DeskseedIcon name="adjust" size="sm" />
              필터
            </DsButton>
          </div>
          <div className="agent-queue-header-actions">
            <DsButton className="agent-queue-toolbar-action" tone="ghost">
              <DeskseedIcon name="reload" size="sm" />
              새로고침
            </DsButton>
            <span aria-hidden="true" className="agent-queue-toolbar-divider" />
            <DsButton className="agent-queue-toolbar-action" tone="ghost">
              작업
              <DeskseedIcon name="chevronDown" size="sm" />
            </DsButton>
          </div>
        </header>
        {filtersOpen ? (
          <section
            aria-label="내 티켓 필터"
            className="agent-queue-fixture-filter-state"
          >
            현재 목록 필터가 열렸습니다.
          </section>
        ) : null}
        {state === 'loading' ? (
          <TableSkeleton label="내 티켓 불러오는 중" />
        ) : null}
        {state === 'empty' ? (
          <ScreenState
            description="새로운 티켓이 도착하면 여기에 표시됩니다."
            kind="empty"
            title="처리할 티켓이 없습니다."
          />
        ) : null}
        {state === 'error' ? (
          <ScreenState kind="error" title="티켓 목록을 불러오지 못했습니다." />
        ) : null}
        {state === 'denied' ? (
          <ScreenState
            kind="denied"
            title="이 티켓 목록에 접근할 수 없습니다."
          />
        ) : null}
        {state === 'no-results' ? (
          <ScreenState kind="empty" title="일치하는 티켓이 없습니다." />
        ) : null}
        {!['loading', 'empty', 'error', 'denied', 'no-results'].includes(
          state,
        ) ? (
          <>
            {selected.size ? (
              <section
                aria-label="선택된 티켓"
                className="agent-queue-bulk-action"
              >
                <strong>{selected.size}개 선택됨</strong>
                <DsButton onClick={() => setSelected(new Set())} tone="ghost">
                  선택 해제
                </DsButton>
              </section>
            ) : null}
            <QueueTicketTable
              items={items}
              label="내 티켓"
              onOpenTicket={(number) => navigate(`/agent/tickets/${number}`)}
              onSelectAll={() =>
                setSelected((current) =>
                  current.size === items.length
                    ? new Set()
                    : new Set(items.map((item) => item.ticketNumber)),
                )
              }
              onSelectionChange={(number) =>
                setSelected((current) => {
                  const next = new Set(current)
                  if (next.has(number)) next.delete(number)
                  else next.add(number)
                  return next
                })
              }
              selectedTicketNumbers={selected}
            />
          </>
        ) : null}
      </section>
    </main>
  )
}
