import type { Meta, StoryObj } from '@storybook/react-vite'
import { DeskseedBrandMark } from './DeskseedPrimitives'

const meta = {
  title: '02 Primitives/DeskseedBrandMark',
  component: DeskseedBrandMark,
  argTypes: {
    size: { control: 'select', options: ['sm', 'md', 'lg'] },
  },
  parameters: {
    docs: {
      description: {
        component:
          'Deskseed-owned product mark다. Agent chrome처럼 brand context가 필요한 위치에서 사용하며 Zendesk mark나 copied asset으로 대체하지 않는다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof DeskseedBrandMark>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = { args: {} }

export const TransparentOnChrome: Story = {
  args: { transparent: true },
  decorators: [
    (Story) => (
      <div
        style={{
          background: 'var(--ds-background-chrome)',
          padding: 'var(--ds-ref-space-4)',
        }}
      >
        <Story />
      </div>
    ),
  ],
}
