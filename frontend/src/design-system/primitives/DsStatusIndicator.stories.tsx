import type { Meta, StoryObj } from '@storybook/react-vite'
import { DsStatusIndicator } from './DeskseedPrimitives'

const meta = {
  title: '02 Primitives/DsStatusIndicator',
  component: DsStatusIndicator,
  argTypes: {
    tone: {
      control: 'select',
      options: ['new', 'open', 'pending', 'onHold', 'solved', 'high'],
    },
  },
  parameters: {
    docs: {
      description: {
        component:
          'icon, text, semantic tone을 결합해 compact status를 표시한다. canonical ticket status label이 필요하면 StatusBadge를 우선 사용한다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof DsStatusIndicator>

export default meta
type Story = StoryObj<typeof meta>

export const New: Story = { args: { children: '신규', tone: 'new' } }
export const Open: Story = { args: { children: '처리 중', tone: 'open' } }
export const Pending: Story = {
  args: { children: '고객 답변 대기', tone: 'pending' },
}
export const OnHold: Story = { args: { children: '보류', tone: 'onHold' } }
export const Solved: Story = { args: { children: '해결', tone: 'solved' } }
export const HighPriority: Story = {
  args: { children: '높음', tone: 'high' },
}
