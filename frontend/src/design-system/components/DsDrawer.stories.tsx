import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn } from 'storybook/test'
import { DsDrawer } from './DsDrawer'

const meta = {
  title: '03 Components/DsDrawer',
  component: DsDrawer,
  parameters: {
    docs: {
      description: {
        component:
          '현재 화면을 떠나지 않고 보조 설정이나 detail을 처리할 때 사용한다. 열릴 때 내부로 focus를 이동하고 Escape 또는 닫기로 종료한 뒤 원래 trigger에 focus를 복원한다.',
      },
    },
  },
  tags: ['autodocs'],
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
  play: async ({ args, canvas, userEvent }) => {
    const dialog = canvas.getByRole('dialog')
    await expect(dialog).toHaveAttribute('aria-modal', 'true')
    await expect(
      canvas.getByRole('button', { name: '보기 편집 닫기' }),
    ).toHaveFocus()
    await userEvent.keyboard('{Escape}')
    await expect(args.onClose).toHaveBeenCalledOnce()
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
