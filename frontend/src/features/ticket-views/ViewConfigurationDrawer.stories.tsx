import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn } from 'storybook/test'
import { ViewConfigurationDrawer } from './ViewConfigurationDrawer'

const meta = {
  title: '06 Domain & Workspace/ViewConfigurationDrawer',
  component: ViewConfigurationDrawer,
  parameters: {
    docs: {
      description: {
        component:
          '개인 view의 이름, icon, sidebar 순서를 현재 workspace 안에서 구성하는 drawer다. server-backed saved-view builder가 아니며 현재 browser-only 범위를 설명한다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof ViewConfigurationDrawer>

export default meta
type Story = StoryObj<typeof meta>

export const Create: Story = {
  args: {
    editor: { mode: 'create' },
    onClose: fn(),
    onSave: fn(),
  },
  play: async ({ args, canvas, userEvent }) => {
    await userEvent.click(canvas.getByRole('button', { name: '보기 만들기' }))
    await expect(canvas.getByRole('alert')).toHaveTextContent(
      '보기 이름을 입력하세요.',
    )
    await userEvent.type(canvas.getByLabelText('보기 이름'), '결제 문의')
    await userEvent.click(
      canvas.getByRole('button', { name: '북마크 아이콘 선택' }),
    )
    await userEvent.click(canvas.getByRole('button', { name: '보기 만들기' }))
    await expect(args.onSave).toHaveBeenCalledWith({
      icon: 'bookmark',
      label: '결제 문의',
    })
  },
}

export const Edit: Story = {
  args: {
    editor: {
      mode: 'edit',
      view: { icon: 'inbox', key: 'my-open', label: '내 담당 티켓' },
    },
    onClose: fn(),
    onMove: fn(),
    onSave: fn(),
    position: { index: 1, total: 3 },
  },
}

export const Closed: Story = {
  args: {
    editor: null,
    onClose: fn(),
    onSave: fn(),
  },
}
