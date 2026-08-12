import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { ScreenState } from './Feedback'

const meta = {
  component: ScreenState,
  tags: ['ai-generated'],
} satisfies Meta<typeof ScreenState>

export default meta
type Story = StoryObj<typeof meta>

export const Empty: Story = {
  args: {
    description: '현재 조건에 맞는 티켓이 없습니다.',
    kind: 'empty',
    title: '표시할 티켓이 없습니다',
  },
}

export const Error: Story = {
  args: {
    description: '잠시 후 다시 시도해 주세요.',
    kind: 'error',
    requestId: 'req_storybook_101',
    title: '티켓을 불러오지 못했습니다',
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('alert')).toHaveAttribute(
      'aria-label',
      '티켓을 불러오지 못했습니다',
    )
  },
}

export const Conflict: Story = {
  args: {
    description: '다른 상담사의 변경 사항을 확인한 뒤 다시 저장해 주세요.',
    kind: 'conflict',
    title: '최신 변경 사항이 있습니다',
  },
}
