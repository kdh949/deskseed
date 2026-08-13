import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { DsButton } from './DeskseedControls'

const meta = {
  title: '02 Primitives/DsButton',
  component: DsButton,
  argTypes: {
    tone: { control: 'select', options: ['primary', 'secondary', 'ghost'] },
  },
  parameters: {
    docs: {
      description: {
        component:
          '현재 view의 행동을 실행하는 기본 button이다. primary는 화면의 한 가지 주 행동에만 사용하고, 보조 행동은 secondary 또는 ghost를 선택한다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof DsButton>

export default meta
type Story = StoryObj<typeof meta>

export const Primary: Story = {
  args: {
    children: '변경 사항 저장',
    tone: 'primary',
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('button', { name: '변경 사항 저장' }),
    ).toHaveAttribute('type', 'button')
  },
}

export const Secondary: Story = {
  args: {
    children: '취소',
    tone: 'secondary',
  },
}

export const Disabled: Story = {
  args: {
    children: '저장 중',
    disabled: true,
    tone: 'primary',
  },
}

export const Ghost: Story = {
  args: {
    children: '새로고침',
    tone: 'ghost',
  },
}

export const LongLabel: Story = {
  args: {
    children: '변경 사항을 저장하고 티켓으로 돌아가기',
    tone: 'primary',
  },
}

export const CssCheck: Story = {
  args: {
    children: '스타일 확인',
    tone: 'primary',
  },
  play: async ({ canvas }) => {
    const button = canvas.getByRole('button', { name: '스타일 확인' })
    await expect(getComputedStyle(button).backgroundColor).toBe(
      'rgb(14, 113, 117)',
    )
  },
}
