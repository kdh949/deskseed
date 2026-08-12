import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn } from 'storybook/test'
import { ViewNavigation } from './ViewNavigation'

const meta = {
  component: ViewNavigation,
  tags: ['ai-generated'],
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
