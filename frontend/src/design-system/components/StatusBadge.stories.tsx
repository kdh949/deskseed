import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { StatusBadge } from './StatusBadge'

const meta = {
  component: StatusBadge,
  tags: ['ai-generated'],
} satisfies Meta<typeof StatusBadge>

export default meta
type Story = StoryObj<typeof meta>

export const New: Story = {
  args: { status: 'NEW' },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('신규')).toHaveClass(
      'ds-status-indicator--new',
    )
  },
}

export const Pending: Story = {
  args: { status: 'PENDING' },
}

export const Solved: Story = {
  args: { status: 'SOLVED' },
}
