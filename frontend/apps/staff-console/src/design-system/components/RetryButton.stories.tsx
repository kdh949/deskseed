import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn } from 'storybook/test'
import { RetryButton } from './Feedback'

const meta = {
  title: '03 Components/RetryButton',
  component: RetryButton,
  parameters: {
    docs: {
      description: {
        component:
          '실패한 read나 안전하게 반복 가능한 action을 사용자가 명시적으로 다시 시도할 때 사용한다. 자동 retry나 중복 mutation 보장을 대신하지 않는다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof RetryButton>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  args: { onClick: fn() },
  play: async ({ args, canvas, userEvent }) => {
    await userEvent.click(canvas.getByRole('button', { name: '다시 시도' }))
    await expect(args.onClick).toHaveBeenCalledOnce()
  },
}
