import { useState } from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, userEvent } from 'storybook/test'
import '../foundations/seed-story-helpers.css'
import { SeedButton } from '../primitives/SeedCore'
import { SeedContextCard, SeedStatusBadge } from '../components/SeedSurfaces'
import {
  SeedComposer,
  SeedConflictBar,
  SeedConversationItem,
  SeedConversationTimeline,
  SeedDataTable,
  SeedFilterBar,
  SeedNavigationRail,
  SeedPageShell,
  SeedSavedViews,
  SeedPropertyRow,
  SeedPropertyStack,
  SeedTicketWorkspaceShell,
  SeedTopBar,
  SeedWorkspaceHeader,
  type SeedTableColumn,
} from './SeedWorkspace'

type TicketRow = {
  id: string
  requester: string
  status: string
  subject: string
}
const rows: TicketRow[] = [
  {
    id: 'DS-48219',
    requester: 'Jennifer Ward',
    status: '처리 중',
    subject: '비밀번호 재설정 후 로그인할 수 없습니다',
  },
  {
    id: 'DS-45876',
    requester: 'Rebecca Wells',
    status: '고객 답변 대기',
    subject: '이중 인증 앱이 작동하지 않습니다',
  },
]
const columns: SeedTableColumn<TicketRow>[] = [
  {
    id: 'id',
    label: '티켓',
    width: '8rem',
    render: (row) => <strong>{row.id}</strong>,
  },
  { id: 'subject', label: '제목', render: (row) => row.subject },
  { id: 'requester', label: '요청자', render: (row) => row.requester },
  {
    id: 'status',
    label: '상태',
    width: '9rem',
    render: (row) => (
      <SeedStatusBadge tone={row.status === '처리 중' ? 'positive' : 'warning'}>
        {row.status}
      </SeedStatusBadge>
    ),
  },
]

function QueuePattern() {
  return (
    <SeedPageShell
      rail={
        <SeedNavigationRail
          items={[
            { id: 'tickets', label: '티켓', icon: 'ticket', active: true },
          ]}
          footerItems={[{ id: 'settings', label: '설정', icon: 'settings' }]}
          onNavigate={() => undefined}
        />
      }
      sidebar={
        <SeedSavedViews
          activeId="unassigned"
          onSelect={() => undefined}
          sections={[
            {
              id: 'shared',
              label: '공유 보기',
              items: [
                { id: 'unassigned', label: '미배정', count: 32 },
                { id: 'mine', label: '내 티켓', count: 14 },
              ],
            },
          ]}
        />
      }
      topbar={
        <SeedTopBar
          breadcrumb="큐 / 고객지원 / 미배정"
          onCreate={() => undefined}
          onSearch={() => undefined}
          profileInitials="AR"
          profileName="Alex Rivera"
        />
      }
    >
      <div className="seed-story-page">
        <SeedFilterBar>
          <SeedButton>필터</SeedButton>
          <SeedButton>상태</SeedButton>
          <SeedButton>우선순위</SeedButton>
        </SeedFilterBar>
        <SeedDataTable
          ariaLabel="미배정 티켓"
          columns={columns}
          onActivate={() => undefined}
          rowKey={(row) => row.id}
          rows={rows}
        />
      </div>
    </SeedPageShell>
  )
}

function ComposerPattern() {
  const [mode, setMode] = useState<'PUBLIC' | 'INTERNAL'>('PUBLIC')
  const [publicDraft, setPublicDraft] = useState('로그인 문제를 확인했습니다.')
  const [internalDraft, setInternalDraft] = useState('인증팀 확인 필요')
  const draft = mode === 'PUBLIC' ? publicDraft : internalDraft
  return (
    <SeedComposer
      canSubmit={draft.trim().length > 0}
      draft={draft}
      messageLabel={
        mode === 'PUBLIC' ? '고객에게 보낼 답변' : '직원만 볼 내부 메모'
      }
      mode={mode}
      onDraftChange={mode === 'PUBLIC' ? setPublicDraft : setInternalDraft}
      onModeChange={setMode}
      onSubmit={() => undefined}
      placeholder={
        mode === 'PUBLIC'
          ? '고객에게 보낼 답변을 작성하세요.'
          : '팀에 공유할 확인 사항을 작성하세요.'
      }
      status="브라우저에 안전하게 보관된 초안입니다."
      submitLabel={mode === 'PUBLIC' ? '공개 답변 저장' : '내부 메모 저장'}
    />
  )
}

function TicketWorkspaceAnatomyPattern() {
  const [filter, setFilter] = useState<'ALL' | 'PUBLIC' | 'INTERNAL'>('ALL')
  const [contextOpen, setContextOpen] = useState(false)
  return (
    <SeedTicketWorkspaceShell
      contextOpen={contextOpen}
      onContextOpen={() => setContextOpen(true)}
      onContextClose={() => setContextOpen(false)}
      context={
        <div className="seed-context-stack">
          <SeedContextCard title="고객">
            <strong>Jennifer Ward</strong>
            <p>jennifer.ward@example.com</p>
          </SeedContextCard>
          <SeedContextCard title="관련 티켓">
            <p>#41207 로그인 실패</p>
          </SeedContextCard>
        </div>
      }
      conversation={
        <div className="seed-workspace-column">
          <div className="seed-workspace-column__scroll">
            <SeedConversationTimeline
              activeFilter={filter}
              filters={[
                { id: 'ALL', label: '대화', count: 2 },
                { id: 'PUBLIC', label: 'PUBLIC', count: 1 },
                { id: 'INTERNAL', label: 'INTERNAL', count: 1 },
              ]}
              onFilterChange={setFilter}
              sortLabel="오래된 순"
            >
              <SeedConversationItem
                actorLabel="Jennifer Ward"
                actorRole="고객"
                dateTime="2026-08-15T09:00:00Z"
                initials="JW"
                sourceLabel="이메일"
                timestamp="오전 9:00"
                visibility="PUBLIC"
              >
                <p>비밀번호를 재설정한 뒤 로그인할 수 없습니다.</p>
              </SeedConversationItem>
              <SeedConversationItem
                actorLabel="Alex Rivera"
                actorRole="상담사"
                dateTime="2026-08-15T09:20:00Z"
                initials="AR"
                sourceLabel="상담사 웹"
                timestamp="오전 9:20"
                visibility="INTERNAL"
              >
                <p>인증 동기화 상태를 확인합니다.</p>
              </SeedConversationItem>
            </SeedConversationTimeline>
          </div>
          <div className="seed-workspace-column__composer">
            <SeedConflictBar
              actions={<SeedButton>비교</SeedButton>}
              description="다른 탭에서 상태가 변경되었습니다."
              title="저장 충돌"
            />
          </div>
        </div>
      }
      header={
        <SeedWorkspaceHeader
          requester={{
            label: 'Jennifer Ward',
            email: 'jennifer.ward@example.com',
          }}
          onOpenContext={() => setContextOpen(true)}
          status={<SeedStatusBadge tone="positive">처리 중</SeedStatusBadge>}
          ticketLabel="#48219"
          title="비밀번호 재설정 후 로그인할 수 없습니다"
        />
      }
      properties={
        <SeedPropertyStack
          title="티켓 속성"
          details={{
            summary: '요청 정보',
            content: (
              <SeedPropertyRow label="요청자">Jennifer Ward</SeedPropertyRow>
            ),
          }}
        >
          <SeedPropertyRow label="상태">처리 중</SeedPropertyRow>
          <SeedPropertyRow label="우선순위">높음</SeedPropertyRow>
          <SeedPropertyRow label="요청자">Jennifer Ward</SeedPropertyRow>
        </SeedPropertyStack>
      }
    />
  )
}

const meta = {
  title: '04 Patterns/Seed Workspace',
  component: QueuePattern,
  parameters: {
    layout: 'fullscreen',
    docs: {
      description: {
        component: `
Canonical staff workspace patterns. Use the icon rail for global navigation; every item retains its label as an accessible name and native tooltip. SeedBrandLockup compact renders the existing Deskseed mark.

SeedWorkspaceHeader: title and ticketLabel identify the ticket; status is the current server state. Optional requester: { label: string; email?: string } adds the requester row. onOpenContext opens the existing context drawer through the labelled customer-info action. contextButtonRef is an optional return-focus anchor. copiedMessage/onCopyTicketLabel and onRefresh preserve copy/refresh actions. Existing optional assignee, priority and sla remain compatible, but avoid duplicating editable properties in the focused screen.

SeedPropertyStack: title, children and optional action compose the property panel. Optional details: { summary: string; content: ReactNode } puts secondary read-only metadata in a native keyboard-accessible disclosure. Never place status, priority, group or assignee in this disclosure.

SeedTicketWorkspaceShell: header, properties, conversation and context are ReactNode slots. contextOpen defaults to false at every desktop width. onContextOpen optionally adds the right-side context rail action; onContextClose closes the drawer. contextReturnFocusRef is optional: without it the drawer restores focus to whichever action opened it. Keep contextOpen in layout state and preserve PUBLIC/INTERNAL drafts in the editor model.
`,
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof QueuePattern>

export default meta
type Story = StoryObj<typeof meta>

export const QueueShell: Story = {}
export const TicketWorkspaceAnatomy: Story = {
  render: () => <TicketWorkspaceAnatomyPattern />,
}
export const Composer: Story = {
  render: () => (
    <div style={{ maxWidth: 760, padding: 24 }}>
      <ComposerPattern />
    </div>
  ),
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }),
    )
    await expect(canvas.getByLabelText('직원만 볼 내부 메모')).toHaveValue(
      '인증팀 확인 필요',
    )
    await userEvent.click(
      canvas.getByRole('tab', { name: '공개 답변 작성 모드로 전환' }),
    )
    await expect(canvas.getByLabelText('고객에게 보낼 답변')).toHaveValue(
      '로그인 문제를 확인했습니다.',
    )
  },
}
