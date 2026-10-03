import { useState } from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, userEvent } from 'storybook/test'
import { SeedPanelResizeHandle } from './SeedPanelResizeHandle'

const meta = {
  title: '03 Components/Seed Panel Resize Handle',
  component: SeedPanelResizeHandle,
  parameters: {
    docs: {
      description: {
        component:
          '패널 너비를 제어하는 separator다. value/min/max/onChange는 픽셀 정수이며 direction은 패널이 경계의 왼쪽인지 오른쪽인지 뜻한다. relative 부모의 가장자리에 배치한다. 포인터·방향키(10px, Shift 1px)·Home/End로 조절하고 범위를 벗어나지 않는다. 영속화는 소비자 소유다.',
      },
    },
  },
} satisfies Meta<typeof SeedPanelResizeHandle>
export default meta
type Story = StoryObj<typeof meta>

export const KeyboardBounds: Story = {
  args: {
    label: '속성 너비',
    value: 320,
    min: 240,
    max: 420,
    onChange: () => undefined,
  },
  render: function ResizeExample(args) {
    const [width, setWidth] = useState(args.value)
    return (
      <div style={{ position: 'relative', width, minHeight: 160 }}>
        <p>속성 패널 {width}px</p>
        <SeedPanelResizeHandle {...args} value={width} onChange={setWidth} />
      </div>
    )
  },
  play: async ({ canvas }) => {
    const handle = canvas.getByRole('separator', { name: '속성 너비' })
    handle.focus()
    await userEvent.keyboard('{ArrowRight}')
    await expect(handle).toHaveAttribute('aria-valuenow', '330')
    await userEvent.keyboard('{End}{ArrowRight}')
    await expect(handle).toHaveAttribute('aria-valuenow', '420')
    await userEvent.keyboard('{Home}{ArrowLeft}')
    await expect(handle).toHaveAttribute('aria-valuenow', '240')
    await expect(handle).toHaveFocus()
  },
}
