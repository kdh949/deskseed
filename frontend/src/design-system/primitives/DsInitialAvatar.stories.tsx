import type { Meta, StoryObj } from '@storybook/react-vite'
import { DsInitialAvatar } from './DeskseedPrimitives'

const meta = {
  title: '02 Primitives/DsInitialAvatar',
  component: DsInitialAvatar,
  argTypes: {
    size: { control: 'select', options: ['sm', 'md', 'lg', 'xl'] },
  },
  parameters: {
    docs: {
      description: {
        component:
          'profile image가 없는 사람을 initials로 식별할 때 사용한다. label은 screen reader가 initials의 대상을 이해하도록 제공한다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof DsInitialAvatar>

export default meta
type Story = StoryObj<typeof meta>

export const Customer: Story = {
  args: { initials: '김지', label: '김지연' },
}

export const ExtraLarge: Story = {
  args: { initials: '김지', label: '김지연', size: 'xl' },
}
