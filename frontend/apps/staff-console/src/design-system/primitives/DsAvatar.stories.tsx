import type { Meta, StoryObj } from '@storybook/react-vite'
import agentAvatar from '../../assets/deskseed/agent-mina-park-v1.png'
import { DsAvatar } from './DeskseedPrimitives'

const meta = {
  title: '02 Primitives/DsAvatar',
  component: DsAvatar,
  argTypes: {
    size: { control: 'select', options: ['sm', 'md', 'lg', 'xl'] },
  },
  parameters: {
    docs: {
      description: {
        component:
          '상담사나 고객의 제공된 profile image를 표시한다. name은 image의 accessible alt text에 포함되며 단순 decoration에는 사용하지 않는다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof DsAvatar>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  args: { name: 'Mina Park', src: agentAvatar },
}

export const Large: Story = {
  args: { name: 'Mina Park', size: 'lg', src: agentAvatar },
}
