import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, userEvent } from 'storybook/test'
import {
  ComposerPresenceStatus,
  TicketPresenceContext,
} from './DraftsPresenceContribution'
import type { CollaborationSocket } from './collaborationRealtime'

type Scenario =
  'connecting' | 'empty' | 'member' | 'unavailable' | 'denied' | 'unsupported'
function installSocket(scenario: Scenario) {
  const original = window.WebSocket
  let connections = 0
  class StorySocket implements CollaborationSocket {
    readyState = 0
    onclose: CollaborationSocket['onclose'] = null
    onerror: CollaborationSocket['onerror'] = null
    onmessage: CollaborationSocket['onmessage'] = null
    onopen: CollaborationSocket['onopen'] = null
    private readonly first = ++connections === 1
    constructor() {
      if (scenario === 'connecting') return
      queueMicrotask(() => {
        this.readyState = 1
        this.onopen?.({} as Event)
      })
    }
    close() {
      this.readyState = 3
      this.onclose?.({} as CloseEvent)
    }
    send(raw: string) {
      const message = JSON.parse(raw)
      if (message.type !== 'subscribe') return
      queueMicrotask(() => {
        if (this.readyState !== 1) return
        if (scenario === 'denied' && this.first) {
          this.onmessage?.({
            data: JSON.stringify({
              version: 1,
              type: 'error',
              code: 'FORBIDDEN',
              retryable: false,
            }),
          } as MessageEvent<string>)
          return
        }
        this.onmessage?.({
          data: JSON.stringify({
            version: 1,
            type: 'presence.snapshot',
            ticketNumber: 94140,
            members:
              scenario === 'member' ||
              (scenario === 'unavailable' && this.first)
                ? [
                    {
                      staffId: 'presence-story-staff',
                      displayName: '결제 담당자',
                      state: 'EDITING_INTERNAL',
                      lastSeenAt: '2026-10-03T00:00:00Z',
                    },
                  ]
                : [],
          }),
        } as MessageEvent<string>)
        if (scenario === 'unavailable' && this.first) this.close()
      })
    }
  }
  Object.defineProperty(window, 'WebSocket', {
    configurable: true,
    writable: true,
    value: scenario === 'unsupported' ? undefined : StorySocket,
  })
  return () => {
    window.WebSocket = original
  }
}

const meta = {
  title: '07 Screens/Ticket Presence',
  component: TicketPresenceContext,
  args: { ticketNumber: 94140 },
  beforeEach: () => installSocket('empty'),
  decorators: [
    (Story) => (
      <div style={{ maxWidth: 420 }}>
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof TicketPresenceContext>
export default meta
type Story = StoryObj<typeof meta>

export const Connecting: Story = {
  beforeEach: () => installSocket('connecting'),
  play: async ({ canvas }) => {
    await expect(
      canvas.getByText('실시간 작업 상태에 연결하는 중입니다.'),
    ).toBeVisible()
    await expect(
      canvas.queryByText(/다른 상담사가 없습니다/),
    ).not.toBeInTheDocument()
  },
}
export const Empty: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText(/다른 상담사가 없습니다/),
    ).toBeVisible()
  },
}
export const WorkingTogether: Story = {
  beforeEach: () => installSocket('member'),
  play: async ({ canvas }) => {
    await expect(await canvas.findByText('결제 담당자')).toBeVisible()
    await expect(canvas.getByText('내부 메모 작성 중')).toBeVisible()
    await expect(canvas.getByText(/마지막 상태 확인/)).toBeVisible()
  },
}
export const Reconnect: Story = {
  beforeEach: () => installSocket('unavailable'),
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText(/이전 상담사 목록은 표시하지 않습니다/),
    ).toBeVisible()
    await expect(canvas.queryByText('결제 담당자')).not.toBeInTheDocument()
    await expect(canvas.getByText(/다음 시도/)).toBeVisible()
    const retry = canvas.getByRole('button', { name: '연결 다시 확인' })
    retry.focus()
    await userEvent.keyboard('{Enter}')
    await expect(
      await canvas.findByText(/다른 상담사가 없습니다/),
    ).toBeVisible()
  },
}
export const Denied: Story = {
  beforeEach: () => installSocket('denied'),
  play: async ({ canvas }) => {
    await expect(await canvas.findByText('권한 없음')).toBeVisible()
    await expect(canvas.queryByText(/다음 시도/)).not.toBeInTheDocument()
    await userEvent.click(
      canvas.getByRole('button', { name: '연결 다시 확인' }),
    )
    await expect(
      await canvas.findByText(/다른 상담사가 없습니다/),
    ).toBeVisible()
  },
}
export const Unsupported: Story = {
  beforeEach: () => installSocket('unsupported'),
  play: async ({ canvas }) => {
    await expect(await canvas.findByText('지원 안 됨')).toBeVisible()
    await expect(
      canvas.queryByRole('button', { name: '연결 다시 확인' }),
    ).not.toBeInTheDocument()
  },
}
export const ComposerUnavailable: Story = {
  beforeEach: () => installSocket('denied'),
  render: () => (
    <ComposerPresenceStatus composerMode="internal" ticketNumber={94140} />
  ),
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText(/초안 보관 상태를 별도로 확인/),
    ).toBeVisible()
    await expect(canvas.queryByText(/계속 저장됩니다/)).not.toBeInTheDocument()
  },
}
