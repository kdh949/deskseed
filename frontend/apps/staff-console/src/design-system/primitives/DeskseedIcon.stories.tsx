import type { Meta, StoryObj } from '@storybook/react-vite'
import { DeskseedIcon } from './DeskseedIcon'

const meta = {
  title: '02 Primitives/DeskseedIcon',
  component: DeskseedIcon,
  argTypes: {
    size: { control: 'select', options: ['sm', 'md', 'lg'] },
  },
  parameters: {
    docs: {
      description: {
        component:
          'Deskseed design-system의 decorative icon renderer다. 전체 IconName은 01 Foundations/Icons에서 검색하고, icon-only action에는 accessible label을 제공하는 DsIconButton을 사용한다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof DeskseedIcon>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = { args: { name: 'inbox' } }
export const Small: Story = { args: { name: 'clock', size: 'sm' } }
export const Large: Story = { args: { name: 'alertWarning', size: 'lg' } }
