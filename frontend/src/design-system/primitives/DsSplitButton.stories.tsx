import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn } from 'storybook/test'
import { DsSplitButton } from './DeskseedControls'

const meta = {
  title: '02 Primitives/DsSplitButton',
  component: DsSplitButton,
  parameters: {
    docs: {
      description: {
        component:
          '기본 submit과 추가 option menu를 한 묶음으로 제공할 때 사용한다. 왼쪽 action의 결과와 오른쪽 menu trigger의 accessible name을 분리한다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof DsSplitButton>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  args: {
    actionLabel: '공개 답변 보내기',
    children: '공개 답변 보내기',
    onAction: fn(),
    onMore: fn(),
  },
  play: async ({ args, canvas, userEvent }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '공개 답변 보내기' }),
    )
    await expect(args.onAction).toHaveBeenCalledOnce()
    await userEvent.click(
      canvas.getByRole('button', { name: '공개 답변 보내기 추가 옵션' }),
    )
    await expect(args.onMore).toHaveBeenCalledOnce()
  },
}

export const Disabled: Story = {
  args: {
    actionLabel: '내부 메모 추가',
    children: '내부 메모 추가',
    disabled: true,
    onAction: fn(),
  },
}
