import { useState } from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn, userEvent, within } from 'storybook/test'
import {
  SeedCollaborationThread,
  SeedMacroMenu,
  SeedNotificationMenu,
  SeedSplitButton,
  type SeedAsyncState,
} from './SeedWorkspaceControls'

const collaborationNotes = [
  {
    id: 'note-1',
    author: 'Sam Lee',
    initials: 'SL',
    body: '@Alex 최근 결제 오류와 같은 현상인지 확인해 주세요.',
    timestamp: '오전 10:02',
    mentionLabels: ['@Alex Rivera'],
  },
  {
    id: 'note-2',
    author: 'Priya Nair',
    initials: 'PN',
    body: '비슷한 문의가 추가되는지 모니터링하겠습니다.',
    timestamp: '오전 10:05',
  },
  {
    id: 'note-3',
    author: 'Alex Rivera',
    initials: 'AR',
    body: 'PG 승인 기록을 확인했습니다.',
    timestamp: '오전 10:08',
  },
]

function CollaborationDemo({ state = 'idle' }: { state?: SeedAsyncState }) {
  const [notes, setNotes] = useState(collaborationNotes)
  return (
    <div style={{ maxWidth: 320 }}>
      <SeedCollaborationThread
        canWrite
        notes={notes}
        onRetry={() => undefined}
        onSubmit={async (body, mentionedIds) => {
          setNotes((current) => [
            {
              id: `note-${current.length + 1}`,
              author: 'Alex Rivera',
              initials: 'AR',
              body,
              timestamp: '방금',
              mentionLabels: mentionedIds.map(() => '@Sam Lee'),
            },
            ...current,
          ])
          return true
        }}
        people={[
          { id: 'staff-sam', label: 'Sam Lee', initials: 'SL' },
          { id: 'staff-priya', label: 'Priya Nair', initials: 'PN' },
        ]}
        state={state}
      />
    </div>
  )
}

function ControlCatalog() {
  const [message, setMessage] = useState('아직 실행하지 않았습니다.')
  return (
    <div
      style={{
        alignItems: 'flex-start',
        display: 'flex',
        flexWrap: 'wrap',
        gap: 24,
      }}
    >
      <section>
        <p>{message}</p>
        <SeedSplitButton
          actions={[
            {
              id: 'PENDING',
              label: '답변 후 고객 대기',
              description: '댓글과 상태 변경을 한 command로 저장합니다.',
            },
            {
              id: 'SOLVED',
              label: '답변 후 해결',
              description: '답변과 해결 상태를 함께 저장합니다.',
            },
          ]}
          label="답변 보내기"
          onAction={(id) => setMessage(id)}
          onPrimary={() => setMessage('PUBLIC')}
        />
      </section>
      <SeedMacroMenu
        items={[
          {
            id: 'macro-1',
            label: '결제 승인 확인',
            description: '공유 매크로',
          },
          {
            id: 'macro-2',
            label: '브라우저 초기화 안내',
            description: '개인 매크로',
          },
        ]}
        onSelect={(id) => setMessage(id)}
        state="idle"
      />
      <SeedNotificationMenu
        items={[
          {
            id: 'notification-1',
            title: '#1042 협업 멘션',
            description: 'Sam Lee님이 회원님을 멘션했습니다.',
            timestamp: '2분 전',
            unread: true,
          },
        ]}
        onSelect={(id) => setMessage(id)}
        state="idle"
        unreadCount={1}
      />
    </div>
  )
}

const meta = {
  title: '03 Components/Seed Workspace Controls',
  component: ControlCatalog,
  parameters: { layout: 'padded' },
  tags: ['autodocs'],
} satisfies Meta<typeof ControlCatalog>

export default meta
type Story = StoryObj<typeof meta>

export const ComposerActions: Story = {
  play: async ({ canvas }) => {
    const toggle = canvas.getByRole('button', { name: '답변 보내기 추가 작업' })
    await userEvent.click(toggle)
    await expect(
      canvas.getByRole('menuitem', { name: /답변 후 고객 대기/ }),
    ).toHaveFocus()
    await userEvent.keyboard('{Escape}')
    await expect(toggle).toHaveFocus()
  },
}

export const Collaboration: Story = {
  render: () => <CollaborationDemo />,
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '협업 메모 작성' }),
    )
    const composer = canvas.getByRole('textbox', { name: '협업 메모' })
    await expect(composer).toHaveFocus()
    await userEvent.type(composer, '결제팀 확인을 부탁드립니다.')
    await userEvent.click(canvas.getByRole('combobox', { name: '@ 멘션 추가' }))
    await userEvent.keyboard('{ArrowDown}{Enter}')
    await expect(
      canvas.getByRole('button', { name: 'Sam Lee 멘션 제거' }),
    ).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '메모 추가' }))
    await expect(canvas.getByText('결제팀 확인을 부탁드립니다.')).toBeVisible()
    await userEvent.click(
      canvas.getByRole('button', { name: '모든 메모 보기' }),
    )
    const drawer = await canvas.findByRole('dialog', { name: '모든 협업 메모' })
    await expect(
      within(drawer).getAllByRole('listitem').length,
    ).toBeGreaterThan(2)
  },
}

export const CollaborationEmpty: Story = {
  render: () => <CollaborationDemo state="empty" />,
}
export const CollaborationLoading: Story = {
  render: () => <CollaborationDemo state="loading" />,
}
export const CollaborationError: Story = {
  render: () => <CollaborationDemo state="error" />,
}
export const CollaborationDenied: Story = {
  render: () => <CollaborationDemo state="denied" />,
}

export const MacroStates: Story = {
  render: () => (
    <div style={{ alignItems: 'flex-start', display: 'flex', gap: 16 }}>
      {(['loading', 'empty', 'error', 'denied'] as SeedAsyncState[]).map(
        (state) => (
          <SeedMacroMenu
            items={[]}
            key={state}
            onRetry={fn()}
            onSelect={fn()}
            state={state}
          />
        ),
      )}
    </div>
  ),
}

export const NotificationStates: Story = {
  render: () => (
    <div style={{ alignItems: 'flex-start', display: 'flex', gap: 24 }}>
      {(['loading', 'empty', 'error', 'denied'] as SeedAsyncState[]).map(
        (state) => (
          <SeedNotificationMenu
            items={[]}
            key={state}
            onRetry={fn()}
            onSelect={fn()}
            state={state}
            unreadCount={0}
          />
        ),
      )}
    </div>
  ),
}
