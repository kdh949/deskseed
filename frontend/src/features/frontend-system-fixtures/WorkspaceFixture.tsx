import { useState } from 'react'
import {
  ComposerModeSeam,
  ContextPanel,
  ConversationTimeline,
  Notification,
  PropertyPanel,
  SplitPanel,
  TicketTabs,
  type ContextPanelTab,
} from '../../shared/ui/system'
import { FixtureAgentFrame } from './FixtureAgentFrame'
import { fixtureConversation, fixtureProperties } from './fixtureData'

const tabs: ContextPanelTab[] = [
  { id: 'customer', label: '고객' },
  { id: 'history', label: '기록' },
  { id: 'related', label: '관련' },
]

interface WorkspaceFixtureProps {
  mode?: 'PUBLIC' | 'INTERNAL'
  conflict?: boolean
}

export function WorkspaceFixture({
  mode = 'PUBLIC',
  conflict = false,
}: WorkspaceFixtureProps) {
  const [propertyWidth, setPropertyWidth] = useState(300)
  const [contextWidth, setContextWidth] = useState(320)
  const [activeTab, setActiveTab] = useState('customer')

  return (
    <FixtureAgentFrame>
      <main
        className="ticket-workspace-page"
        aria-labelledby="fixture-workspace-title"
      >
        <TicketTabs
          backTo="/__fixtures__/frontend-system/view-queue"
          backLabel="Views로 돌아가기"
          ticketNumber={1042}
          subject="결제 승인 오류 — 카드 인증 후 주문이 생성되지 않음"
          status="OPEN"
          onRefresh={() => undefined}
        />
        <header className="ticket-titlebar">
          <div>
            <p className="agent-page-eyebrow">
              TICKET #1042 · ALL_TICKETS READ
            </p>
            <h1 id="fixture-workspace-title">
              결제 승인 오류 — 카드 인증 후 주문이 생성되지 않음
            </h1>
          </div>
          <button className="compact-button" type="button">
            컨텍스트 패널 접기
          </button>
        </header>
        <SplitPanel
          propertyWidth={propertyWidth}
          contextWidth={contextWidth}
          onPropertyWidthChange={setPropertyWidth}
          onContextWidthChange={setContextWidth}
          propertyPanel={
            <PropertyPanel
              title="속성"
              meta="v7"
              items={fixtureProperties}
              footer={
                <div className="fixture-property-footer">
                  {conflict ? (
                    <Notification
                      tone="conflict"
                      title="담당자 변경이 충돌했습니다."
                    >
                      <p>서버 값 박서연 · 내 변경 정유나</p>
                    </Notification>
                  ) : null}
                  <div className="read-boundary-note">
                    <strong>쓰기 범위: GROUP_OR_ASSIGNEE</strong>
                    <p>현재 티켓의 그룹 또는 담당자만 변경할 수 있습니다.</p>
                  </div>
                </div>
              }
            />
          }
          conversationPanel={
            <ConversationTimeline
              items={fixtureConversation}
              footer={<ComposerModeSeam initialMode={mode} />}
            />
          }
          contextPanel={
            <ContextPanel
              label="티켓 컨텍스트"
              tabs={tabs}
              activeTab={activeTab}
              onTabChange={setActiveTab}
            >
              <FixtureContext activeTab={activeTab} />
            </ContextPanel>
          }
        />
      </main>
    </FixtureAgentFrame>
  )
}

function FixtureContext({ activeTab }: { activeTab: string }) {
  if (activeTab === 'history') {
    return (
      <ol className="history-list">
        <li>
          <strong>TICKET CREATED</strong>
          <span>김민수</span>
          <time>오전 11:00</time>
        </li>
        <li>
          <strong>ASSIGNEE CHANGED</strong>
          <span>박서연</span>
          <time>오전 11:24</time>
        </li>
      </ol>
    )
  }
  if (activeTab === 'related') {
    return (
      <div className="related-context">
        <h2>연결된 티켓</h2>
        <p>열린 child task 2개</p>
        <h2>외부 참조</h2>
        <p>연결된 주문 참조 1개</p>
      </div>
    )
  }
  return (
    <div className="customer-context-card">
      <span className="customer-avatar" aria-hidden="true">
        김
      </span>
      <h2>김민수</h2>
      <a href="mailto:minsu.fixture@example.com">minsu.fixture@example.com</a>
      <dl>
        <div>
          <dt>고객 ID</dt>
          <dd>customer-fixture-1</dd>
        </div>
        <div>
          <dt>최근 티켓</dt>
          <dd>3개 · 최근 30일</dd>
        </div>
      </dl>
    </div>
  )
}
