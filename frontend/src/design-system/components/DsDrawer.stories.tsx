import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn } from 'storybook/test'
import { DsDrawer } from './DsDrawer'

const meta = {
  component: DsDrawer,
  tags: ['ai-generated'],
} satisfies Meta<typeof DsDrawer>

export default meta
type Story = StoryObj<typeof meta>

export const Open: Story = {
  args: {
    children: <p>보기의 이름, 조건, 정렬 방식을 변경할 수 있습니다.</p>,
    description: '변경 사항은 나에게만 적용됩니다.',
    onClose: fn(),
    open: true,
    title: '보기 편집',
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('dialog')).toHaveAttribute(
      'aria-modal',
      'true',
    )
  },
}

export const Closed: Story = {
  args: {
    children: <p>닫힌 서랍은 렌더링하지 않습니다.</p>,
    onClose: fn(),
    open: false,
    title: '보기 편집',
  },
}
