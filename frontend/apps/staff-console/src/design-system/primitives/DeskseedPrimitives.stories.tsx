import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { DsIconButton } from './DeskseedPrimitives'

const meta = {
  title: '02 Primitives/DsIconButton',
  component: DsIconButton,
  parameters: {
    docs: {
      description: {
        component:
          '공간이 제한된 toolbar action에 사용한다. 보이는 텍스트가 없으므로 label은 행동과 대상을 함께 설명하는 필수 accessible name이다.',
      },
    },
  },
  tags: ['autodocs'],
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

export const Disabled: Story = {
  args: {
    disabled: true,
    icon: 'paperclip',
    label: '파일 첨부',
  },
}
