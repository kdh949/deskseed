import type { Meta, StoryObj } from '@storybook/react-vite'
import { ConversationTimeline } from './ConversationTimeline'
import { ticketFixtures } from './ticketWorkspaceFixture'

const meta = {
  title: '06 Domain & Workspace/ConversationTimeline',
  component: ConversationTimeline,
  parameters: {
    docs: {
      description: {
        component:
          '티켓의 chronological staff conversation을 PUBLIC, INTERNAL, system entry로 구분한다. visibility는 icon과 text로 함께 전달하며 customer projection에 사용하는 component가 아니다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof ConversationTimeline>

export default meta
type Story = StoryObj<typeof meta>

export const PublicConversation: Story = {
  args: {
    entries: ticketFixtures[0]!.conversation.filter(
      (entry) => entry.kind === 'message' && entry.visibility === 'public',
    ),
  },
}

export const InternalNote: Story = {
  args: {
    entries: [
      {
        author: 'agent',
        body: ['PG 승인 로그와 gateway response code를 확인해야 합니다.'],
        kind: 'message',
        name: 'Mina Park',
        timestamp: 'Aug 11, 2026 9:35 AM',
        visibility: 'internal',
      },
    ],
  },
}

export const SystemEvent: Story = {
  args: {
    entries: [
      {
        body: 'Mina Park님이 우선순위를 Normal에서 High로 변경했습니다.',
        kind: 'system',
        timestamp: 'Aug 11, 2026 9:29 AM',
      },
    ],
  },
}

export const MixedWithAttachment: Story = {
  args: { entries: ticketFixtures[0]!.conversation },
}
