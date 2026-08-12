import { useEffect, useState } from 'react'
import { useParams } from 'react-router'
import { DeskseedIcon } from '../../design-system/primitives/DeskseedIcon'
import { DsIconButton } from '../../design-system/primitives/DeskseedPrimitives'
import { DsSelect } from '../../design-system/primitives/DeskseedControls'
import { ConversationTimeline } from './ConversationTimeline'
import { ReplyComposer } from './ReplyComposer'
import { TicketContextPanel } from './TicketContextPanel'
import { TicketPropertiesPanel } from './TicketPropertiesPanel'
import {
  ticketFixtures,
  type ComposerMode,
  type WorkspaceState,
} from './ticketWorkspaceFixture'
import type { AgentTicketDetail } from '../../api/types'

type DraftsByTicket = Record<string, Record<ComposerMode, string>>
type ContextTab = 'customer' | 'related' | 'activity'

type TicketWorkspaceProps = {
  initialState?: WorkspaceState
  ticket?: (typeof ticketFixtures)[number]
  detail?: AgentTicketDetail
  onRefresh?: () => void
  refreshing?: boolean
  submitDisabledReason?: string
}

const initialDrafts: DraftsByTicket = Object.fromEntries(
  ticketFixtures.map((ticket) => [
    ticket.number,
    {
      public: '',
      internal:
        ticket.number === '1042'
          ? '카드사 승인 로그와 게이트웨이 응답 코드 확인 필요.\n현재 PG사(BluePay) 측 간헐적 오류 이력 있음.\n지연님께는 진행 상황 공유 예정.'
          : '',
    },
  ]),
)

export function TicketWorkspace({
  initialState = 'ready',
  ticket: ticketOverride,
  detail,
  onRefresh,
  refreshing = false,
  submitDisabledReason,
}: TicketWorkspaceProps) {
  const { ticketNumber: routeTicketNumber } = useParams()
  const initialTicketNumber = ticketFixtures.some(
    ({ number }) => number === routeTicketNumber,
  )
    ? routeTicketNumber!
    : '1042'
  const [activeTicketNumber, setActiveTicketNumber] =
    useState(initialTicketNumber)
  const [composerMode, setComposerMode] = useState<ComposerMode>('internal')
  const [drafts, setDrafts] = useState<DraftsByTicket>(initialDrafts)
  const [contextTab, setContextTab] = useState<ContextTab>('customer')
  const [propertiesCollapsed, setPropertiesCollapsed] = useState(false)
  const [contextOpen, setContextOpen] = useState(false)
  const [conflictVisible, setConflictVisible] = useState(
    initialState === 'conflict',
  )
  const [savedMessage, setSavedMessage] = useState('')
  const ticket =
    ticketOverride ??
    ticketFixtures.find(({ number }) => number === activeTicketNumber)

  useEffect(() => {
    if (
      !ticketOverride &&
      ticketFixtures.some(({ number }) => number === routeTicketNumber)
    ) {
      setActiveTicketNumber(routeTicketNumber!)
      setSavedMessage('')
    }
  }, [routeTicketNumber, ticketOverride])

  if (!ticket) return null
  if (initialState !== 'ready' && initialState !== 'conflict') {
    return <WorkspaceStateView state={initialState} />
  }

  const activeDrafts = drafts[ticket.number] ?? { public: '', internal: '' }

  const updateDraft = (value: string) => {
    setDrafts((current) => {
      const ticketDrafts = current[ticket.number] ?? {
        public: '',
        internal: '',
      }
      return {
        ...current,
        [ticket.number]: { ...ticketDrafts, [composerMode]: value },
      }
    })
    setSavedMessage('초안이 이 브라우저에 저장되었습니다.')
  }

  return (
    <main
      aria-label={`티켓 #${ticket.number} 작업 공간`}
      className="ticket-workspace"
    >
      <TicketPropertiesPanel
        collapsed={propertiesCollapsed}
        onCollapse={() => setPropertiesCollapsed((current) => !current)}
        onResolveConflict={() => setConflictVisible(false)}
        readOnly={Boolean(submitDisabledReason)}
        showConflict={conflictVisible}
        ticket={ticket}
      />
      <section className="ticket-conversation" aria-label="티켓 대화 및 답변">
        <header className="ticket-workspace-heading">
          <div className="ticket-heading-title">
            <DsIconButton
              disabled={Boolean(submitDisabledReason)}
              icon="star"
              label="티켓 즐겨찾기"
            />
            <div>
              <h1>
                <span>#{ticket.number}</span>
                {ticket.subject}
              </h1>
              <p>
                {ticket.createdAt}
                <span aria-hidden="true"> · </span>
                {ticket.channel ?? '상담 기록'}
              </p>
            </div>
          </div>
          <div className="ticket-heading-actions">
            {onRefresh ? (
              <DsIconButton
                disabled={refreshing}
                icon="history"
                label="티켓 새로고침"
                onClick={onRefresh}
              />
            ) : null}
            <label className="sr-only" htmlFor="ticket-status">
              티켓 상태
            </label>
            <DsSelect
              defaultValue={ticket.status}
              disabled={Boolean(submitDisabledReason)}
              id="ticket-status"
            >
              <option>New</option>
              <option>Open</option>
              <option>Pending</option>
              <option>Solved</option>
            </DsSelect>
            <label className="sr-only" htmlFor="ticket-priority">
              티켓 우선순위
            </label>
            <span className="ticket-select-shell ticket-select-shell--priority">
              <DeskseedIcon name="arrowLeft" />
              <DsSelect
                defaultValue={ticket.priority}
                disabled={Boolean(submitDisabledReason)}
                id="ticket-priority"
              >
                <option>Low</option>
                <option>Normal</option>
                <option>High</option>
                <option>Urgent</option>
              </DsSelect>
            </span>
            <DsIconButton
              disabled={Boolean(submitDisabledReason)}
              icon="overflow"
              label="티켓 추가 작업"
              onClick={() => setConflictVisible(true)}
            />
          </div>
        </header>
        <ConversationTimeline entries={ticket.conversation} />
        <ReplyComposer
          draft={activeDrafts[composerMode]}
          mode={composerMode}
          onDraftChange={updateDraft}
          onModeChange={(mode) => {
            setComposerMode(mode)
            setSavedMessage('')
          }}
          onSubmit={() => setSavedMessage('작성한 내용이 준비되었습니다.')}
          savedMessage={savedMessage}
          submitDisabledReason={submitDisabledReason}
        />
      </section>
      <div
        className={`ticket-context-wrap ${contextOpen ? 'ticket-context-wrap--open' : ''}`}
      >
        <TicketContextPanel
          activeTab={contextTab}
          detail={detail}
          onTabChange={setContextTab}
        />
      </div>
      <button
        aria-expanded={contextOpen}
        className="ticket-context-toggle"
        onClick={() => setContextOpen((current) => !current)}
        type="button"
      >
        <DeskseedIcon name="userGroup" />
        고객 맥락 {contextOpen ? '닫기' : '열기'}
      </button>
    </main>
  )
}

function WorkspaceStateView({
  state,
}: {
  state: Exclude<WorkspaceState, 'ready' | 'conflict'>
}) {
  const content = {
    loading: {
      title: '티켓을 불러오는 중',
      description: '대화와 속성 정보를 준비하고 있습니다.',
      icon: 'clock' as const,
    },
    empty: {
      title: '열린 티켓이 없습니다',
      description: 'Views에서 처리할 티켓을 선택하면 이곳에 표시됩니다.',
      icon: 'inbox' as const,
    },
    error: {
      title: '티켓을 불러오지 못했습니다',
      description: '일시적인 연결 문제일 수 있습니다. 다시 시도해 주세요.',
      icon: 'alertWarning' as const,
    },
    denied: {
      title: '이 티켓에 접근할 수 없습니다',
      description:
        '현재 역할에는 이 티켓을 볼 권한이 없습니다. 관리자에게 문의해 주세요.',
      icon: 'lock' as const,
    },
  }[state]

  return (
    <main aria-label="티켓 작업 공간 상태" className="ticket-workspace-state">
      <DeskseedIcon name={content.icon} size="lg" />
      <h1>{content.title}</h1>
      <p>{content.description}</p>
      {state === 'error' ? (
        <button className="ticket-secondary-button" type="button">
          다시 시도
        </button>
      ) : null}
    </main>
  )
}
