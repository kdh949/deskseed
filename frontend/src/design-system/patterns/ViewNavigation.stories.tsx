import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn } from 'storybook/test'
import { ViewNavigation } from './ViewNavigation'

const meta = {
  title: '04 Patterns/ViewNavigation',
  component: ViewNavigation,
  parameters: {
    docs: {
      description: {
        component:
          'Agent Queue에서 shared/personal view를 범주화해 탐색할 때 사용한다. 현재 route는 NavLink의 선택 상태로 표현하고, 편집 action은 항목 label을 포함한 accessible name을 제공한다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof ViewNavigation>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  args: {
    label: '상담사 보기',
    onEditItem: fn(),
    sections: [
      {
        id: 'standard',
        items: [
          {
            count: 12,
            editable: true,
            icon: 'inbox',
            key: 'my-open',
            label: '내 담당 티켓',
            to: '/agent/views/my-open',
          },
          {
            count: 4,
            icon: 'clock',
            key: 'pending',
            label: '고객 답변 대기',
            to: '/agent/views/pending',
          },
        ],
        label: '기본 보기',
      },
    ],
    title: '보기',
  },
  play: async ({ args, canvas, userEvent }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '내 담당 티켓 편집' }),
    )
    await expect(args.onEditItem).toHaveBeenCalled()
  },
}

export const EmptySection: Story = {
  args: {
    label: '상담사 보기',
    sections: [{ id: 'custom', items: [], label: '맞춤 보기' }],
    title: '보기',
  },
}

export const PersonalViews: Story = {
  args: {
    label: '상담사 보기',
    sections: [
      {
        id: 'personal',
        items: [
          {
            count: 7,
            editable: true,
            icon: 'bookmark',
            key: 'created-by-me',
            label: '내가 생성한 티켓',
            to: '/agent/views/created-by-me',
          },
        ],
        label: '개인 보기',
      },
    ],
    title: '보기',
  },
}

export const LongLabels: Story = {
  args: {
    label: '상담사 보기',
    sections: [
      {
        id: 'long',
        items: [
          {
            count: 128,
            icon: 'history',
            key: 'long-view',
            label:
              '지난 30일 동안 결제와 환불 문의가 함께 업데이트된 긴 이름의 공유 보기',
            to: '/agent/views/long-view',
          },
        ],
        label: '긴 label 검증',
      },
    ],
    title: '보기',
  },
}
