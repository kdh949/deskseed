import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { StatusBadge } from './StatusBadge'

const meta = {
  title: '03 Components/StatusBadge',
  component: StatusBadge,
  argTypes: {
    status: {
      control: 'select',
      options: ['NEW', 'OPEN', 'PENDING', 'ON_HOLD', 'SOLVED', 'CLOSED'],
    },
  },
  parameters: {
    docs: {
      description: {
        component:
          'canonical ticket status를 한국어 label, icon, tone으로 함께 표시한다. 상태를 색상만으로 전달하지 않는다.',
      },
    },
  },
  tags: ['autodocs'],
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

export const Open: Story = {
  args: { status: 'OPEN' },
}

export const OnHold: Story = {
  args: { status: 'ON_HOLD' },
}

export const Solved: Story = {
  args: { status: 'SOLVED' },
}

export const Closed: Story = {
  args: { status: 'CLOSED' },
}
