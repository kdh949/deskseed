import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { CustomerRequestConversation } from './CustomerRequestConversation'

const request = {
  ticketNumber: 1042,
  subject: '결제 승인 내역 확인 요청',
  status: 'OPEN' as const,
  createdAt: '2026-08-15T00:00:00Z',
  updatedAt: '2026-08-15T01:00:00Z',
  comments: [
    {
      id: 'comment-customer-1',
      authorDisplayName: '김민아',
      body: '결제 승인 내역을 확인해 주세요.',
      createdAt: '2026-08-15T00:00:00Z',
    },
    {
      id: 'comment-agent-1',
      authorDisplayName: 'Deskseed 지원팀',
      body: '확인 후 공개 대화로 안내드리겠습니다.',
      createdAt: '2026-08-15T00:30:00Z',
    },
  ],
}

const meta = {
  title: '06 Customer/Customer Request Conversation',
  component: CustomerRequestConversation,
  args: {
    onSubmitFollowUp: async () => undefined,
    request,
  },
  parameters: {
    docs: {
      description: {
        component:
          '고객에게 허용된 `CustomerRequestDetail` projection만 표시하는 문의 상세·PUBLIC 대화 컴포넌트입니다. INTERNAL comment, child relation, staff assignment, audit metadata는 props에 없으며 DOM을 구성하지 않습니다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerRequestConversation>

export default meta
type Story = StoryObj<typeof meta>

export const OpenWithPublicConversation: Story = {
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('heading', { name: '#1042 결제 승인 내역 확인 요청' }),
    ).toBeVisible()
    await expect(canvas.getByText('처리 중')).toBeVisible()
    await expect(
      canvas.getByRole('textbox', { name: '추가 답변' }),
    ).toBeVisible()
  },
}

export const EmptyConversation: Story = {
  args: {
    onSubmitFollowUp: async () => undefined,
    request: { ...request, comments: [] },
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('heading', { name: '공개 대화가 비어 있습니다.' }),
    ).toBeVisible()
  },
}
