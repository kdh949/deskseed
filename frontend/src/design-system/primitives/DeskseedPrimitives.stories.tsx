import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { DsIconButton } from './DeskseedPrimitives'

const meta = {
  component: DsIconButton,
  tags: ['ai-generated'],
} satisfies Meta<typeof DsIconButton>

export default meta
type Story = StoryObj<typeof meta>

export const Search: Story = {
  args: {
    icon: 'search',
    label: '티켓 검색',
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('button')).toHaveAttribute(
      'aria-label',
      '티켓 검색',
    )
  },
}

export const MoreOptions: Story = {
  args: {
    icon: 'overflow',
    label: '추가 옵션',
  },
}
