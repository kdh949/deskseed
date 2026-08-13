import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { TableSkeleton } from './Feedback'

const meta = {
  title: '03 Components/TableSkeleton',
  component: TableSkeleton,
  parameters: {
    docs: {
      description: {
        component:
          'ticket table의 예상 구조를 유지하며 초기 데이터를 준비할 때 사용한다. background revalidation에는 기존 content를 지우지 않고 별도 subtle indicator를 사용한다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof TableSkeleton>

export default meta
type Story = StoryObj<typeof meta>

export const LoadingTickets: Story = {
  args: { label: '내 티켓 불러오는 중' },
  play: async ({ canvas }) => {
    await expect(canvas.getByLabelText('내 티켓 불러오는 중')).toHaveAttribute(
      'aria-busy',
      'true',
    )
  },
}
